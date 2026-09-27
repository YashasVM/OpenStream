#include "openstream-control-api.hpp"

#include <obs-frontend-api.h>

#include <QByteArray>
#include <QAbstractButton>
#include <QButtonGroup>
#include <QComboBox>
#include <QDoubleSpinBox>
#include <QFormLayout>
#include <QFrame>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPushButton>
#include <QSignalBlocker>
#include <QSlider>
#include <QScrollArea>
#include <QToolButton>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

#include <vector>
#include <algorithm>
#include <cmath>

namespace {
constexpr int kSourceRefreshMs = 1500;

class OpenStreamDock final : public QWidget {
 public:
  OpenStreamDock() {
    setObjectName("OpenStreamCameraControl");

    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(8, 8, 8, 8);
    layout->setSpacing(6);
    auto *version = new QLabel(QStringLiteral("OpenStream OBS v") +
                               QStringLiteral(OPENSTREAM_VERSION), this);
    version->setObjectName("openstreamPluginVersion");
    layout->addWidget(version);
    auto *connection_group = new QGroupBox("Connection", this);
    auto *connection_form = new QFormLayout(connection_group);
    connection_form->setContentsMargins(8, 8, 8, 8);
    connection_form->setVerticalSpacing(5);
    source_selector_ = new QComboBox(this);
    source_selector_->setObjectName("openstreamDeviceSelector");
    source_selector_->setAccessibleName("OpenStream source");
    connection_form->addRow("Source", source_selector_);
    phone_selector_ = new QComboBox(this);
    phone_selector_->setObjectName("openstreamPhoneSelector");
    phone_selector_->setAccessibleName("OpenStream phone");
    connection_form->addRow("Phone", phone_selector_);

    status_ = new QLabel("No OpenStream source", this);
    status_->setObjectName("openstreamStatus");
    connection_form->addRow("Status", status_);

    auto *name_row = new QHBoxLayout();
    name_row->addWidget(new QLabel("Name", connection_group));
    name_ = new QLineEdit(this);
    name_->setObjectName("openstreamSourceName");
    name_->setAccessibleName("OpenStream source name");
    name_->setMaxLength(128);
    name_->setPlaceholderText("Name this source");
    name_row->addWidget(name_);
    connection_form->addRow(name_row);
    connect(name_, &QLineEdit::editingFinished, this,
            [this] { renameSelectedSource(); });

    auto *connection = new QHBoxLayout();
    test_connect_ = new QPushButton("Test / connect", this);
    test_connect_->setObjectName("openstreamTestConnect");
    disconnect_ = new QPushButton("Disconnect / release", this);
    disconnect_->setObjectName("openstreamDisconnect");
    connection->addWidget(test_connect_);
    connection->addWidget(disconnect_);
    connection_form->addRow(connection);
    layout->addWidget(connection_group);

    auto *lens_group = new QGroupBox("Lens selection", this);
    auto *lens_layout = new QVBoxLayout(lens_group);
    lens_layout->setContentsMargins(8, 8, 8, 8);
    lens_layout->setSpacing(5);
    lens_scroll_ = new QScrollArea(this);
    lens_scroll_->setObjectName("openstreamLensOptions");
    lens_scroll_->setWidgetResizable(true);
    lens_scroll_->setFrameShape(QFrame::NoFrame);
    lens_scroll_->setHorizontalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    lens_scroll_->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    lens_buttons_widget_ = new QWidget(lens_scroll_);
    lens_buttons_layout_ = new QHBoxLayout(lens_buttons_widget_);
    lens_buttons_layout_->setContentsMargins(0, 0, 0, 0);
    lens_buttons_layout_->setSpacing(4);
    lens_scroll_->setWidget(lens_buttons_widget_);
    lens_layout->addWidget(lens_scroll_);

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
    zoom_value_ = new QLabel("1.0×", this);
    zoom_value_->setObjectName("openstreamZoomValue");
    zoom_ = new QSlider(Qt::Horizontal, this);
    zoom_->setObjectName("openstreamZoom");
    zoom_->setAccessibleName("Camera zoom");
    zoom_->setRange(10, 100);
    zoom_->setSingleStep(1);
    zoom_->setPageStep(5);
    zoom_->setTracking(true);
    zoom_send_timer_ = new QTimer(this);
    zoom_send_timer_->setInterval(75);
    zoom_row->addWidget(new QLabel("Zoom", lens_group));
    zoom_row->addWidget(zoom_, 1);
    zoom_row->addWidget(zoom_value_);
    lens_layout->addLayout(zoom_row);
    connect(zoom_, &QSlider::valueChanged, this, [this](int value) {
      zoom_value_->setText(QString::number(value / 10.0, 'f', 1) + QChar(0x00d7));
      if (!zoom_send_timer_->isActive()) zoom_send_timer_->start();
    });
    connect(zoom_send_timer_, &QTimer::timeout, this, [this] {
      sendCurrentZoom();
      if (!zoom_->isSliderDown()) zoom_send_timer_->stop();
    });
    connect(zoom_, &QSlider::sliderReleased, this, [this] {
      zoom_send_timer_->stop();
      sendCurrentZoom();
    });

    layout->addWidget(lens_group);

    auto *controls_group = new QGroupBox("Camera controls", this);
    auto *controls_layout = new QVBoxLayout(controls_group);
    controls_layout->setContentsMargins(8, 8, 8, 8);
    controls_layout->setSpacing(5);

    auto *torch_row = new QHBoxLayout();
    torch_on_ = new QPushButton("Torch on", this);
    torch_on_->setObjectName("openstreamTorchOn");
    torch_off_ = new QPushButton("Torch off", this);
    torch_off_->setObjectName("openstreamTorchOff");
    identify_ = new QPushButton("Identify", this);
    identify_->setObjectName("openstreamIdentify");
    torch_status_ = new QLabel("Torch status unavailable", this);
    torch_status_->setObjectName("openstreamTorchStatus");
    torch_row->addWidget(torch_on_);
    torch_row->addWidget(torch_off_);
    torch_row->addWidget(torch_status_);
    torch_row->addWidget(identify_);
    controls_layout->addLayout(torch_row);
    connect(torch_on_, &QPushButton::clicked, this, [this] {
      send("/torch", R"({"enabled":true})");
    });
    connect(torch_off_, &QPushButton::clicked, this, [this] {
      send("/torch", R"({"enabled":false})");
    });
    connect(identify_, &QPushButton::clicked, this, [this] {
      send("/identify", R"({"label":"OBS"})");
    });

    layout->addWidget(controls_group);

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

  void sendCurrentZoom() {
    const QByteArray body = QByteArray("{\"value\":") +
    QByteArray::number(zoom_->value() / 10.0, 'f', 1) + "}";
    send("/zoom", body.constData());
    if (!zoom_->isSliderDown()) {
      if (obs_source_t *source = currentSource()) {
        openstream_request_camera_status(source);
      }
    }
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
    if (queued) openstream_request_camera_status(source);
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
    zoom_value_->setEnabled(available);
    torch_on_->setEnabled(available);
    torch_off_->setEnabled(available);
    identify_->setEnabled(available);
    lens_scroll_->setEnabled(false);

    if (!source) {
      phone_selector_->clear();
      refreshLensSelector(nullptr);
      if (!name_->hasFocus()) name_->clear();
      if (sources_.size() > 1) {
        status_->setText("Select an OpenStream source.");
      } else {
        showNoSource();
      }
      return;
    }

    refreshPhones(source);
    openstream_request_camera_status(source);
    refreshLensSelector(source);
    refreshTorchState(source);

    if (replace_name || !name_->hasFocus()) {
      QSignalBlocker blocker(name_);
      name_->setText(QString::fromUtf8(obs_source_get_name(source)));
    }
    status_->setText(QString::fromUtf8(openstream_source_status(source)));
  }

  void refreshLensSelector(obs_source_t *source) {
    const size_t count = source ? openstream_camera_lens_count(source) : 0;
    const QString selected = source
                                 ? QString::fromUtf8(openstream_camera_current_lens(source))
                                 : QString();
    double zoom = 1.0;
    double zoom_min = 1.0;
    double zoom_max = 10.0;
    if (source) openstream_camera_zoom_state(source, &zoom, &zoom_min, &zoom_max);

    QStringList labels;
    for (size_t index = 0; index < count; ++index) {
      labels.append(QString::fromUtf8(openstream_camera_lens_label(source, index)));
    }

    if (labels != lens_labels_) {
      lens_labels_ = labels;
      while (QLayoutItem *item = lens_buttons_layout_->takeAt(0)) {
        delete item->widget();
        delete item;
      }
      delete lens_button_group_;
      lens_button_group_ = new QButtonGroup(this);
      lens_button_group_->setExclusive(true);
      for (const QString &label : lens_labels_) {
        auto *button = new QToolButton(lens_buttons_widget_);
        button->setText(label);
        button->setCheckable(true);
        button->setToolButtonStyle(Qt::ToolButtonTextOnly);
        button->setAccessibleName("Select lens " + label);
        lens_button_group_->addButton(button);
        lens_buttons_layout_->addWidget(button);
        connect(button, &QToolButton::clicked, this, [this, label] {
          QJsonObject body;
          body.insert("lens", label);
          const QByteArray json = QJsonDocument(body).toJson(QJsonDocument::Compact);
          send("/lens", json.constData());
          if (obs_source_t *source = currentSource()) {
            openstream_request_camera_status(source);
          }
        });
      }
      if (lens_labels_.isEmpty()) {
        auto *waiting = new QLabel("Waiting for connected phone", lens_buttons_widget_);
        lens_buttons_layout_->addWidget(waiting);
      }
      lens_buttons_layout_->addStretch();
    }
    {
      QSignalBlocker blocker(lens_button_group_);
      for (QAbstractButton *button : lens_button_group_->buttons()) {
        button->setChecked(button->text() == selected);
      }
    }
    lens_scroll_->setEnabled(!lens_labels_.isEmpty());

    const int min_value = std::max(1, static_cast<int>(std::lround(zoom_min * 10.0)));
    const int max_value = std::max(min_value, static_cast<int>(std::lround(zoom_max * 10.0)));
    const int step = std::clamp((max_value - min_value) / 90, 1, 10);
    QSignalBlocker zoom_blocker(zoom_);
    zoom_->setRange(min_value, max_value);
    zoom_->setSingleStep(step);
    zoom_->setPageStep(step * 5);
    if (!zoom_->isSliderDown() && !zoom_send_timer_->isActive()) {
      zoom_->setValue(std::clamp(static_cast<int>(std::lround(zoom * 10.0)),
                                 min_value,
                                 max_value));
      zoom_value_->setText(QString::number(zoom, 'f', 1) + QChar(0x00d7));
    }
  }

  void refreshTorchState(obs_source_t *source) {
    bool available = false;
    bool enabled = false;
    if (source) openstream_camera_torch_state(source, &available, &enabled);
    const bool has_source = source != nullptr;
    torch_on_->setEnabled(has_source && available);
    torch_off_->setEnabled(has_source);
    torch_status_->setText(!has_source ? "No source"
                            : !available ? "Unavailable for this lens"
                            : enabled ? "On" : "Off");
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
  QSlider *zoom_ = nullptr;
  QLabel *zoom_value_ = nullptr;
  QTimer *zoom_send_timer_ = nullptr;
  QScrollArea *lens_scroll_ = nullptr;
  QWidget *lens_buttons_widget_ = nullptr;
  QHBoxLayout *lens_buttons_layout_ = nullptr;
  QButtonGroup *lens_button_group_ = nullptr;
  QStringList lens_labels_ = {QStringLiteral("__loading__")};
  QPushButton *torch_on_ = nullptr;
  QPushButton *torch_off_ = nullptr;
  QLabel *torch_status_ = nullptr;
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
