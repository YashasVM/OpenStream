#include "openstream-control-api.hpp"

#include <obs-frontend-api.h>

#include <QAction>
#include <QComboBox>
#include <QByteArray>
#include <QDesktopServices>
#include <QDoubleSpinBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPair>
#include <QPushButton>
#include <QTimer>
#include <QStringList>
#include <QVBoxLayout>
#include <QVersionNumber>
#include <QWidget>
#include <QUrl>

#include <cmath>
#include <vector>

namespace {
int compareSemver(const QString &left, const QString &right) {
  const auto split = [](const QString &version) {
    const int dash = version.indexOf(QChar('-'));
    return qMakePair(QVersionNumber::fromString(
                         dash < 0 ? version : version.left(dash)),
                     dash < 0 ? QString() : version.mid(dash + 1));
  };
  const auto lhs = split(left);
  const auto rhs = split(right);
  const int core = QVersionNumber::compare(lhs.first, rhs.first);
  if (core != 0) return core;
  if (lhs.second.isEmpty() != rhs.second.isEmpty())
    return lhs.second.isEmpty() ? 1 : -1;
  if (lhs.second == rhs.second) return 0;
  const QStringList leftIds = lhs.second.split(QChar('.'));
  const QStringList rightIds = rhs.second.split(QChar('.'));
  const int count = std::min(leftIds.size(), rightIds.size());
  for (int i = 0; i < count; ++i) {
    bool leftNumeric = false;
    bool rightNumeric = false;
    const int leftNumber = leftIds[i].toInt(&leftNumeric);
    const int rightNumber = rightIds[i].toInt(&rightNumeric);
    if (leftNumeric && rightNumeric && leftNumber != rightNumber)
      return leftNumber < rightNumber ? -1 : 1;
    if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
    const int text = QString::compare(leftIds[i], rightIds[i], Qt::CaseSensitive);
    if (text != 0) return text < 0 ? -1 : 1;
  }
  if (leftIds.size() == rightIds.size()) return 0;
  return leftIds.size() < rightIds.size() ? -1 : 1;
}

class OpenStreamDock final : public QWidget {
 public:
  OpenStreamDock() {
    setObjectName("OpenStreamCameraControl");
    auto *layout = new QVBoxLayout(this);
    source_ = new QComboBox(this);
    status_ = new QLabel("No OpenStream camera source", this);
    status_->setWordWrap(true);
    layout->addWidget(new QLabel("Camera source", this));
    layout->addWidget(source_);
    layout->addWidget(status_);

    auto *connection = new QHBoxLayout();
    auto *retry = new QPushButton("Connect / retry", this);
    auto *stop = new QPushButton("Stop", this);
    connect(retry, &QPushButton::clicked, this, [this] {
      const bool started = openstream_start_camera_source(currentSource());
      status_->setText(started ? "Connection started" : "Choose an OpenStream source first");
    });
    connect(stop, &QPushButton::clicked, this, [this] {
      const bool stopped = openstream_stop_camera_source(currentSource());
      status_->setText(stopped ? "Camera slot stopped" : "Choose an OpenStream source first");
    });
    connection->addWidget(retry);
    connection->addWidget(stop);
    layout->addLayout(connection);

    auto *lens = new QHBoxLayout();
    addButton(lens, "Rear", "/lens", R"({"lens":"1\u00d7"})");
    addButton(lens, "Front", "/lens", R"({"lens":"Front"})");
    layout->addLayout(lens);

    auto *torch = new QHBoxLayout();
    addButton(torch, "Torch on", "/torch", R"({"enabled":true})");
    addButton(torch, "Torch off", "/torch", R"({"enabled":false})");
    addButton(torch, "Identify", "/identify", R"({"label":"OBS"})");
    layout->addLayout(torch);

    auto *zoomRow = new QHBoxLayout();
    zoom_ = new QDoubleSpinBox(this);
    zoom_->setRange(1.0, 10.0);
    zoom_->setSingleStep(0.1);
    zoom_->setSuffix("x");
    auto *applyZoom = new QPushButton("Set zoom", this);
    connect(applyZoom, &QPushButton::clicked, this, [this] {
      const QByteArray body = QByteArray("{\"value\":") +
                              QByteArray::number(zoom_->value(), 'f', 1) + "}";
      send("/zoom", body.constData());
    });
    zoomRow->addWidget(zoom_);
    zoomRow->addWidget(applyZoom);
    layout->addLayout(zoomRow);

    update_ = new QLabel(this);
    update_->setWordWrap(true);
    updateButton_ = new QPushButton("View update", this);
    updateButton_->hide();
    connect(updateButton_, &QPushButton::clicked, this, [this] {
      QDesktopServices::openUrl(updateUrl_);
    });
    layout->addWidget(update_);
    layout->addWidget(updateButton_);
    layout->addStretch();

    refresh_ = new QTimer(this);
    refresh_->setInterval(1500);
    connect(refresh_, &QTimer::timeout, this, [this] { refreshSources(); });
    refresh_->start();
    refreshSources();
    checkForUpdates();
  }

  ~OpenStreamDock() override { releaseSources(); }

 private:
  void addButton(QHBoxLayout *row, const char *label, const char *path,
                 const char *body) {
    auto *button = new QPushButton(label, this);
    connect(button, &QPushButton::clicked, this,
            [this, path = QByteArray(path), body = QByteArray(body)] {
              send(path.constData(), body.constData());
            });
    row->addWidget(button);
  }

  void send(const char *path, const char *body) {
    obs_source_t *source = currentSource();
    if (!source) return;
    const bool queued = openstream_post_camera_command(source, path, body);
    status_->setText(queued ? "Command queued" : "Camera is not connected");
  }

  obs_source_t *currentSource() const {
    const int index = source_->currentIndex();
    return index >= 0 && static_cast<size_t>(index) < sources_.size()
               ? sources_[static_cast<size_t>(index)]
               : nullptr;
  }

  void releaseSources() {
    for (obs_source_t *source : sources_) obs_source_release(source);
    sources_.clear();
  }

  void refreshSources() {
    const QString selected = source_->currentText();
    releaseSources();
    source_->clear();
    obs_enum_sources(
        [](void *opaque, obs_source_t *source) {
          auto *self = static_cast<OpenStreamDock *>(opaque);
          if (!openstream_is_camera_source(source)) return true;
          self->sources_.push_back(obs_source_get_ref(source));
          self->source_->addItem(QString::fromUtf8(obs_source_get_name(source)));
          return true;
        },
        this);
    const int old = source_->findText(selected);
    if (old >= 0) source_->setCurrentIndex(old);
    if (obs_source_t *source = currentSource()) {
      status_->setText(QString::fromUtf8(openstream_source_status(source)));
    } else {
      status_->setText("Add an OpenStream V8 source to control your phone here.");
    }
  }

  void checkForUpdates() {
    updates_ = new QNetworkAccessManager(this);
    QNetworkRequest request(QUrl(
        "https://github.com/YashasVM/OpenStream/releases/latest/download/openstream-obs-update.json"));
    request.setTransferTimeout(5000);
    QNetworkReply *reply = updates_->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
      const QByteArray payload = reply->readAll();
      const bool ok = reply->error() == QNetworkReply::NoError;
      reply->deleteLater();
      if (!ok || payload.size() > 64 * 1024) return;
      const QJsonObject manifest = QJsonDocument::fromJson(payload).object();
      if (manifest.value("schemaVersion").toInt() != 1) return;
      const QString available = manifest.value("version").toString();
      QString current = QString::fromUtf8(OPENSTREAM_VERSION);
      if (current.startsWith(QChar('v'))) current.remove(0, 1);
      const QUrl release(manifest.value("releaseUrl").toString());
      if (available.isEmpty() || compareSemver(available, current) <= 0 ||
          !release.isValid() || release.scheme() != "https" ||
          release.host() != "github.com" ||
          !release.path().startsWith("/YashasVM/OpenStream/releases/tag/")) return;
      updateUrl_ = release;
      update_->setText("OpenStream " + available + " is available. Close OBS before running the installer.");
      updateButton_->show();
    });
  }

  QComboBox *source_ = nullptr;
  QLabel *status_ = nullptr;
  QDoubleSpinBox *zoom_ = nullptr;
  QTimer *refresh_ = nullptr;
  QLabel *update_ = nullptr;
  QPushButton *updateButton_ = nullptr;
  QNetworkAccessManager *updates_ = nullptr;
  QUrl updateUrl_;
  std::vector<obs_source_t *> sources_;
};

OpenStreamDock *g_dock = nullptr;
QAction *g_tools_action = nullptr;
}  // namespace

void openstream_show_dock() {
  if (!g_dock) return;
  QWidget *container = g_dock->parentWidget() ? g_dock->parentWidget() : g_dock;
  container->show();
  container->raise();
  container->activateWindow();
}

void openstream_register_dock() {
  if (g_dock) return;
  g_dock = new OpenStreamDock();
  // OBS 30+ owns the dock wrapper; the plugin retains/deletes the widget on unload.
  if (!obs_frontend_add_dock_by_id("openstream-camera-control",
                                   "OpenStream Camera Control", g_dock)) {
    delete g_dock;
    g_dock = nullptr;
    return;
  }
  g_tools_action = static_cast<QAction *>(
      obs_frontend_add_tools_menu_qaction("OpenStream Camera Control"));
  if (g_tools_action) {
    QObject::connect(g_tools_action, &QAction::triggered, [] { openstream_show_dock(); });
  }
  QTimer::singleShot(0, [] { openstream_show_dock(); });
}

void openstream_unregister_dock() {
  if (!g_dock) return;
  if (g_tools_action) {
    delete g_tools_action;
    g_tools_action = nullptr;
  }
  obs_frontend_remove_dock("openstream-camera-control");
  delete g_dock;
  g_dock = nullptr;
}
