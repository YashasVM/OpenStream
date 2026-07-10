#include "openstream-ui-api.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>

#include <QApplication>
#include <QFrame>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QPointer>
#include <QPushButton>
#include <QScrollArea>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

#include <map>

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {
constexpr const char *kDockId = "openstream-cameras";

using AddDockFn = bool (*)(const char *, const char *, void *);
using RemoveDockFn = void (*)(const char *);
using AddEventFn = void (*)(obs_frontend_event_cb, void *);
using RemoveEventFn = void (*)(obs_frontend_event_cb, void *);

struct FrontendApi {
  AddDockFn add_dock = nullptr;
  RemoveDockFn remove_dock = nullptr;
  AddEventFn add_event = nullptr;
  RemoveEventFn remove_event = nullptr;
};

template<typename T> T frontend_symbol(const char *name) {
#ifdef _WIN32
  HMODULE module = GetModuleHandleW(L"obs-frontend-api.dll");
  if (!module) module = GetModuleHandleW(nullptr);
  return module ? reinterpret_cast<T>(GetProcAddress(module, name)) : nullptr;
#else
  return reinterpret_cast<T>(dlsym(RTLD_DEFAULT, name));
#endif
}

FrontendApi load_frontend_api() {
  return {
      frontend_symbol<AddDockFn>("obs_frontend_add_dock_by_id"),
      frontend_symbol<RemoveDockFn>("obs_frontend_remove_dock"),
      frontend_symbol<AddEventFn>("obs_frontend_add_event_callback"),
      frontend_symbol<RemoveEventFn>("obs_frontend_remove_event_callback"),
  };
}

QString status_tone(const std::string &status) {
  if (status == "Live") return "live";
  if (status == "Reserved" || status == "Connecting") return "progress";
  if (status == "Waiting") return "waiting";
  if (status == "Reconnecting") return "warning";
  return "offline";
}

QString primary_label(const OpenStreamCameraSnapshot &camera) {
  if (camera.live || camera.status == "Reserved" || camera.status == "Connecting" ||
      camera.status == "Reconnecting") return "Stop";
  if (camera.status == "Offline") return "Retry";
  return "Connect";
}

class OpenStreamDock final : public QWidget {
 public:
  OpenStreamDock() {
    setObjectName("openstreamCamerasDock");
    setMinimumWidth(300);
    auto *root = new QVBoxLayout(this);
    root->setContentsMargins(12, 12, 12, 12);
    root->setSpacing(10);

    auto *heading = new QLabel("OpenStream Cameras");
    heading->setObjectName("openstreamHeading");
    auto *description = new QLabel("Connect and control every phone camera from one place.");
    description->setWordWrap(true);
    description->setObjectName("openstreamMuted");
    root->addWidget(heading);
    root->addWidget(description);

    scroll_ = new QScrollArea;
    scroll_->setWidgetResizable(true);
    scroll_->setFrameShape(QFrame::NoFrame);
    content_ = new QWidget;
    cards_ = new QVBoxLayout(content_);
    cards_->setContentsMargins(0, 0, 0, 0);
    cards_->setSpacing(10);
    scroll_->setWidget(content_);
    root->addWidget(scroll_, 1);

    setStyleSheet(R"(
      #openstreamHeading { font-size: 16px; font-weight: 600; }
      #openstreamMuted { color: palette(mid); }
      QFrame#cameraCard { border: 1px solid palette(midlight); border-radius: 8px; background: palette(base); }
      QLabel[role="slot"] { font-size: 14px; font-weight: 600; }
      QLabel[tone="live"] { color: #35b56a; font-weight: 600; }
      QLabel[tone="progress"] { color: #4b9cff; font-weight: 600; }
      QLabel[tone="waiting"] { color: palette(text); font-weight: 600; }
      QLabel[tone="warning"] { color: #e5a33b; font-weight: 600; }
      QLabel[tone="offline"] { color: #d66a6a; font-weight: 600; }
      QPushButton { min-height: 28px; padding: 2px 9px; }
      QPushButton[primary="true"] { font-weight: 600; }
    )");

    refresh();
    timer_.setInterval(1000);
    connect(&timer_, &QTimer::timeout, this, [this] { refresh(); });
    timer_.start();
  }

 private:
  void clear_cards() {
    while (QLayoutItem *item = cards_->takeAt(0)) {
      delete item->widget();
      delete item;
    }
  }

  QPushButton *action_button(const QString &text,
                             const std::string &id,
                             OpenStreamUiCommand command,
                             bool enabled = true) {
    auto *button = new QPushButton(text);
    button->setEnabled(enabled);
    connect(button, &QPushButton::clicked, this, [this, id, command, button] {
      button->setEnabled(false);
      feedback_[id] = "Working...";
      QPointer<OpenStreamDock> guard(this);
      openstream_run_command_async(id, command, [guard, id](bool ok, std::string message) {
        if (!guard) return;
        QMetaObject::invokeMethod(guard.data(), [guard, id, ok, message = std::move(message)] {
          if (!guard) return;
          guard->feedback_[id] = (ok ? "Done: " : "Could not complete: ") + message;
          guard->refresh();
        }, Qt::QueuedConnection);
      });
    });
    return button;
  }

  void refresh() {
    const auto cameras = openstream_camera_snapshots();
    clear_cards();
    if (cameras.empty()) {
      auto *empty = new QLabel("No OpenStream sources yet.\n\nAdd an OpenStream Phone source to a scene, then choose its camera slot in the Android app.");
      empty->setWordWrap(true);
      empty->setAlignment(Qt::AlignCenter);
      empty->setObjectName("openstreamMuted");
      cards_->addWidget(empty, 1);
      return;
    }

    for (const auto &camera : cameras) {
      auto *card = new QFrame;
      card->setObjectName("cameraCard");
      auto *layout = new QVBoxLayout(card);
      layout->setContentsMargins(12, 10, 12, 10);
      layout->setSpacing(7);

      auto *top = new QHBoxLayout;
      auto *name = new QLabel(QString::fromStdString(camera.slot_label));
      name->setProperty("role", "slot");
      name->setToolTip(QString::fromStdString(camera.source_name));
      auto *status = new QLabel(QString::fromStdString(camera.status));
      status->setProperty("tone", status_tone(camera.status));
      top->addWidget(name);
      top->addStretch();
      top->addWidget(status);
      layout->addLayout(top);

      auto *production = new QLabel(QString::fromStdString(camera.production_label));
      production->setObjectName("openstreamMuted");
      layout->addWidget(production);
      auto *phone = new QLabel(QString::fromStdString(camera.phone_label));
      phone->setWordWrap(true);
      layout->addWidget(phone);

      auto *primary_row = new QHBoxLayout;
      const bool should_stop = primary_label(camera) == "Stop";
      auto *primary = action_button(primary_label(camera), camera.instance_id,
                                    should_stop ? OpenStreamUiCommand::Stop : OpenStreamUiCommand::Start);
      primary->setProperty("primary", true);
      primary_row->addWidget(primary, 1);
      primary_row->addWidget(action_button("Refresh", camera.instance_id, OpenStreamUiCommand::Refresh));
      primary_row->addWidget(action_button("Identify", camera.instance_id, OpenStreamUiCommand::Identify,
                                            camera.phone_available));
      layout->addLayout(primary_row);

      auto *controls = new QGridLayout;
      controls->addWidget(action_button("Zoom -", camera.instance_id, OpenStreamUiCommand::ZoomOut,
                                         camera.phone_available), 0, 0);
      controls->addWidget(action_button("Zoom +", camera.instance_id, OpenStreamUiCommand::ZoomIn,
                                         camera.phone_available), 0, 1);
      controls->addWidget(action_button("Torch off", camera.instance_id, OpenStreamUiCommand::TorchOff,
                                         camera.phone_available), 1, 0);
      controls->addWidget(action_button("Torch on", camera.instance_id, OpenStreamUiCommand::TorchOn,
                                         camera.phone_available), 1, 1);
      controls->addWidget(action_button("Rear", camera.instance_id, OpenStreamUiCommand::RearCamera,
                                         camera.phone_available), 2, 0);
      controls->addWidget(action_button("Front", camera.instance_id, OpenStreamUiCommand::FrontCamera,
                                         camera.phone_available), 2, 1);
      layout->addLayout(controls);

      if (auto it = feedback_.find(camera.instance_id); it != feedback_.end()) {
        auto *feedback = new QLabel(QString::fromStdString(it->second));
        feedback->setWordWrap(true);
        feedback->setObjectName("openstreamMuted");
        layout->addWidget(feedback);
      }
      cards_->addWidget(card);
    }
    cards_->addStretch();
  }

  QScrollArea *scroll_ = nullptr;
  QWidget *content_ = nullptr;
  QVBoxLayout *cards_ = nullptr;
  QTimer timer_;
  std::map<std::string, std::string> feedback_;
};

FrontendApi g_frontend;
QPointer<OpenStreamDock> g_dock;

void register_dock() {
  if (g_dock || !g_frontend.add_dock || !qApp) return;
  auto *dock = new OpenStreamDock;
  if (!g_frontend.add_dock(kDockId, "OpenStream Cameras", dock)) {
    delete dock;
    blog(LOG_WARNING, "[OpenStream] OBS declined the camera dock registration");
    return;
  }
  g_dock = dock;
}

void frontend_event(obs_frontend_event event, void *) {
  if (event == OBS_FRONTEND_EVENT_FINISHED_LOADING) register_dock();
}
}  // namespace

void openstream_dock_create() {
  g_frontend = load_frontend_api();
  if (!g_frontend.add_dock || !g_frontend.remove_dock) {
    blog(LOG_WARNING, "[OpenStream] OBS frontend dock API unavailable; source properties remain available");
    return;
  }
  if (g_frontend.add_event) g_frontend.add_event(frontend_event, nullptr);
  register_dock();
}

void openstream_dock_destroy() {
  if (g_frontend.remove_event) g_frontend.remove_event(frontend_event, nullptr);
  if (g_dock && g_frontend.remove_dock) g_frontend.remove_dock(kDockId);
  if (g_dock) delete g_dock.data();
  g_dock.clear();
  g_frontend = {};
}
