#include "openstream-control-api.hpp"

#include <obs-frontend-api.h>

#include <QByteArray>
#include <QComboBox>
#include <QDoubleSpinBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSignalBlocker>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

#include <vector>

namespace {
constexpr int kSourceRefreshMs = 1500;

class OpenStreamDock final : public QWidget {
 public:
  OpenStreamDock() {
    setObjectName("OpenStreamCameraControl");

    auto *layout = new QVBoxLayout(this);
    source_selector_ = new QComboBox(this);
    source_selector_->setObjectName("openstreamDeviceSelector");
    source_selector_->setAccessibleName("OpenStream source");
    layout->addWidget(new QLabel("Source", this));
    layout->addWidget(source_selector_);
    phone_selector_ = new QComboBox(this);
    phone_selector_->setObjectName("openstreamPhoneSelector");
    phone_selector_->setAccessibleName("OpenStream phone");
    layout->addWidget(new QLabel("Phone", this));
    layout->addWidget(phone_selector_);

    status_ = new QLabel("No OpenStream source", this);
    status_->setObjectName("openstreamStatus");
    layout->addWidget(status_);

    auto *name_row = new QHBoxLayout();
    name_row->addWidget(new QLabel("Name", this));
    name_ = new QLineEdit(this);
    name_->setObjectName("openstreamSourceName");
    name_->setAccessibleName("OpenStream source name");
    name_->setMaxLength(128);
    name_->setPlaceholderText("Name this source");
    name_row->addWidget(name_);
    layout->addLayout(name_row);
    connect(name_, &QLineEdit::editingFinished, this,
            [this] { renameSelectedSource(); });

    auto *connection = new QHBoxLayout();
    test_connect_ = new QPushButton("Test / connect", this);
    test_connect_->setObjectName("openstreamTestConnect");
    disconnect_ = new QPushButton("Disconnect / release", this);
    disconnect_->setObjectName("openstreamDisconnect");
    connection->addWidget(test_connect_);
    connection->addWidget(disconnect_);
    layout->addLayout(connection);

    // These calls only publish into the source's bounded, coalescing slots.
    // Network work and worker joins stay outside the OBS UI thread.
    connect(test_connect_, &QPushButton::clicked, this, [this] {
      obs_source_t *source = currentSource();
      if (!source) {
        showNoSource();
        return;
      }
      const bool queued = openstream_start_camera_source(source);
      status_->setText(queued ? "Connection test queued in background..."
                              : "Could not queue the connection test.");
    });
    connect(disconnect_, &QPushButton::clicked, this, [this] {
      obs_source_t *source = currentSource();
      if (!source) {
        showNoSource();
        return;
      }
      const bool queued = openstream_stop_camera_source(source);
      status_->setText(queued ? "Disconnect queued; phone release is in progress..."
                              : "Could not queue the disconnect.");
    });

    auto *zoom_row = new QHBoxLayout();
    zoom_ = new QDoubleSpinBox(this);
    zoom_->setObjectName("openstreamZoom");
    zoom_->setAccessibleName("Camera zoom");
    zoom_->setRange(1.0, 10.0);
    zoom_->setSingleStep(0.1);
    zoom_->setDecimals(1);
    zoom_->setSuffix("x");
    apply_zoom_ = new QPushButton("Apply zoom", this);
    apply_zoom_->setObjectName("openstreamApplyZoom");
    zoom_row->addWidget(new QLabel("Zoom", this));
    zoom_row->addWidget(zoom_);
    zoom_row->addWidget(apply_zoom_);
    layout->addLayout(zoom_row);
    connect(apply_zoom_, &QPushButton::clicked, this, [this] {
      const QByteArray body = QByteArray("{\"value\":") +
                              QByteArray::number(zoom_->value(), 'f', 1) + "}";
      send("/zoom", body.constData());
    });

    // Peer-bound camera controls. Each call only publishes into the source's
    // bounded, coalescing control slot; the network round-trip stays off the
    // OBS UI thread and the phone rejects non-reserving peers with 401.
    auto *lens_row = new QHBoxLayout();
    lens_rear_ = new QPushButton("Rear", this);
    lens_rear_->setObjectName("openstreamLensRear");
    lens_front_ = new QPushButton("Front", this);
    lens_front_->setObjectName("openstreamLensFront");
    lens_row->addWidget(new QLabel("Lens", this));
    lens_row->addWidget(lens_rear_);
    lens_row->addWidget(lens_front_);
    layout->addLayout(lens_row);
    connect(lens_rear_, &QPushButton::clicked, this, [this] {
      send("/lens", R"({"lens":"1×"})");
    });
    connect(lens_front_, &QPushButton::clicked, this, [this] {
      send("/lens", R"({"lens":"Front"})");
    });

    auto *torch_row = new QHBoxLayout();
    torch_on_ = new QPushButton("Torch on", this);
    torch_on_->setObjectName("openstreamTorchOn");
    torch_off_ = new QPushButton("Torch off", this);
    torch_off_->setObjectName("openstreamTorchOff");
    identify_ = new QPushButton("Identify", this);
    identify_->setObjectName("openstreamIdentify");
    torch_row->addWidget(torch_on_);
    torch_row->addWidget(torch_off_);
    torch_row->addWidget(identify_);
    layout->addLayout(torch_row);
    connect(torch_on_, &QPushButton::clicked, this, [this] {
      send("/torch", R"({"enabled":true})");
    });
    connect(torch_off_, &QPushButton::clicked, this, [this] {
      send("/torch", R"({"enabled":false})");
    });
    connect(identify_, &QPushButton::clicked, this, [this] {
      send("/identify", R"({"label":"OBS"})");
    });

    layout->addStretch();

    connect(source_selector_, &QComboBox::currentIndexChanged, this,
            [this](int) { updateSelectedSource(true); });
    connect(phone_selector_, &QComboBox::currentIndexChanged, this,
            [this](int index) {
              obs_source_t *source = currentSource();
              if (!source || index < 0) return;
              const QByteArray id = phone_selector_->itemData(index).toString().toUtf8();
              if (openstream_select_camera_phone(source, id.constData())) {
                status_->setText("Phone selected; press Connect to bind it.");
              }
            });
    refresh_ = new QTimer(this);
    refresh_->setInterval(kSourceRefreshMs);
    connect(refresh_, &QTimer::timeout, this,
            [this] { refreshSources(); });
    refresh_->start();
    refreshSources();
  }

  ~OpenStreamDock() override { releaseSources(); }

 private:
  void showNoSource() {
    status_->setText("Add an OpenStream source in OBS first.");
  }

  void renameSelectedSource() {
    obs_source_t *source = currentSource();
    if (!source) {
      showNoSource();
      return;
    }

    const QString name = name_->text().trimmed();
    if (name.isEmpty()) {
      name_->setText(QString::fromUtf8(obs_source_get_name(source)));
      status_->setText("Source name cannot be empty.");
      return;
    }

    const QByteArray utf8_name = name.toUtf8();
    obs_source_set_name(source, utf8_name.constData());
    openstream_set_camera_label(source, utf8_name.constData());
    const int index = source_selector_->currentIndex();
    if (index >= 0) {
      QSignalBlocker blocker(source_selector_);
      source_selector_->setItemText(index, name);
    }
    status_->setText("Source name saved.");
  }

  void send(const char *path, const char *body) {
    obs_source_t *source = currentSource();
    if (!source) {
      showNoSource();
      return;
    }
    const bool queued = openstream_post_camera_command(source, path, body);
    status_->setText(queued ? "Command queued in background."
                            : "Camera is not connected.");
  }

  obs_source_t *currentSource() const {
    const int index = source_selector_->currentIndex();
    if (index < 0 || index >= static_cast<int>(sources_.size())) return nullptr;
    return sources_[static_cast<size_t>(index)];
  }

  void releaseSources() {
    for (obs_source_t *source : sources_) obs_source_release(source);
    sources_.clear();
  }

  void updateSelectedSource(bool replace_name) {
    obs_source_t *source = currentSource();
    const bool available = source != nullptr;
    source_selector_->setEnabled(!sources_.empty());
    name_->setEnabled(available);
    test_connect_->setEnabled(available);
    disconnect_->setEnabled(available);
    zoom_->setEnabled(available);
    apply_zoom_->setEnabled(available);
    lens_rear_->setEnabled(available);
    lens_front_->setEnabled(available);
    torch_on_->setEnabled(available);
    torch_off_->setEnabled(available);
    identify_->setEnabled(available);

    if (!source) {
      phone_selector_->clear();
      if (!name_->hasFocus()) name_->clear();
      if (sources_.size() > 1) {
        status_->setText("Select an OpenStream source.");
      } else {
        showNoSource();
      }
      return;
    }

    refreshPhones(source);

    if (replace_name || !name_->hasFocus()) {
      QSignalBlocker blocker(name_);
      name_->setText(QString::fromUtf8(obs_source_get_name(source)));
    }
    status_->setText(QString::fromUtf8(openstream_source_status(source)));
  }

  void refreshSources() {
    QString selected_uuid;
    if (source_selector_->currentIndex() >= 0) {
      selected_uuid = source_selector_->currentData().toString();
    }

    releaseSources();
    {
      QSignalBlocker blocker(source_selector_);
      source_selector_->clear();
      obs_enum_sources(
          [](void *opaque, obs_source_t *source) {
            auto *self = static_cast<OpenStreamDock *>(opaque);
            if (!openstream_is_camera_source(source)) return true;
            self->sources_.push_back(obs_source_get_ref(source));
            return true;
          },
          this);

      for (obs_source_t *source : sources_) {
        const char *raw_name = obs_source_get_name(source);
        const char *raw_uuid = obs_source_get_uuid(source);
        const QString name = raw_name && raw_name[0]
                                 ? QString::fromUtf8(raw_name)
                                 : QStringLiteral("OpenStream source");
        const QString uuid = raw_uuid ? QString::fromUtf8(raw_uuid) : QString();
        source_selector_->addItem(name, uuid);
      }

      int selected_index = source_selector_->findData(selected_uuid);
      if (selected_index < 0 && source_selector_->count() > 0) {
        selected_index = 0;
      }
      source_selector_->setCurrentIndex(selected_index);
    }
    updateSelectedSource(false);
  }

  void refreshPhones(obs_source_t *source) {
    if (!source) {
      phone_selector_->clear();
      phone_selector_->setEnabled(false);
      return;
    }
    const QString selected = phone_selector_->currentData().toString();
    QSignalBlocker blocker(phone_selector_);
    phone_selector_->clear();
    phone_selector_->addItem("Choose a discovered phone", QString());
    const size_t count = openstream_camera_phone_count(source);
    for (size_t index = 0; index < count; ++index) {
      const QString id = QString::fromUtf8(openstream_camera_phone_id(source, index));
      const QString label = QString::fromUtf8(openstream_camera_phone_name(source, index));
      phone_selector_->addItem(label, id);
    }
    int selected_index = phone_selector_->findData(selected);
    if (selected_index < 0) selected_index = 0;
    phone_selector_->setCurrentIndex(selected_index);
    phone_selector_->setEnabled(phone_selector_->count() > 0);
  }

  QComboBox *source_selector_ = nullptr;
  QComboBox *phone_selector_ = nullptr;
  QLabel *status_ = nullptr;
  QLineEdit *name_ = nullptr;
  QPushButton *test_connect_ = nullptr;
  QPushButton *disconnect_ = nullptr;
  QDoubleSpinBox *zoom_ = nullptr;
  QPushButton *apply_zoom_ = nullptr;
  QPushButton *lens_rear_ = nullptr;
  QPushButton *lens_front_ = nullptr;
  QPushButton *torch_on_ = nullptr;
  QPushButton *torch_off_ = nullptr;
  QPushButton *identify_ = nullptr;
  QTimer *refresh_ = nullptr;
  std::vector<obs_source_t *> sources_;
};

OpenStreamDock *g_dock = nullptr;
}  // namespace

void openstream_register_dock() {
  if (g_dock) return;
  g_dock = new OpenStreamDock();
  // OBS 30+ owns the dock wrapper and the widget after registration.
  if (!obs_frontend_add_dock_by_id("openstream-camera-control",
                                   "OpenStream Camera Control", g_dock)) {
    delete g_dock;
    g_dock = nullptr;
  }
}

void openstream_unregister_dock() {
  if (!g_dock) return;
  // OBS owns the QDockWidget wrapper and destroys the child widget when the
  // dock is removed. Clear our non-owning pointer before triggering teardown.
  g_dock = nullptr;
  obs_frontend_remove_dock("openstream-camera-control");
}
