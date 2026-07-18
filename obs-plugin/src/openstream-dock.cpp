#include "openstream-ui-api.hpp"

#include <obs-frontend-api.h>
#include <obs-module.h>

#include <QApplication>
#include <QCheckBox>
#include <QComboBox>
#include <QDoubleSpinBox>
#include <QFormLayout>
#include <QFrame>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QMouseEvent>
#include <QPainter>
#include <QPointer>
#include <QPushButton>
#include <QScrollArea>
#include <QSignalBlocker>
#include <QSpinBox>
#include <QStackedWidget>
#include <QStyle>
#include <QTimer>
#include <QVBoxLayout>
#include <QWidget>

#include <algorithm>
#include <cmath>
#include <functional>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {
constexpr const char *kDockId = "openstream-control-room";
constexpr int kRemoteRefreshMs = 2500;
constexpr int kZoomUpdateIntervalMs = 50;

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

class FocusTarget final : public QWidget {
 public:
  std::function<void(double, double)> focus_requested;

  explicit FocusTarget(QWidget *parent = nullptr) : QWidget(parent) {
    setMinimumSize(240, 135);
    setCursor(Qt::CrossCursor);
    setToolTip("Click the same normalized position where the subject appears in the OBS canvas.");
  }

 protected:
  void paintEvent(QPaintEvent *) override {
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.fillRect(rect(), QColor(12, 17, 23));
    painter.setPen(QPen(QColor(65, 78, 92), 1));
    painter.drawRect(rect().adjusted(0, 0, -1, -1));
    painter.drawLine(width() / 3, 0, width() / 3, height());
    painter.drawLine(width() * 2 / 3, 0, width() * 2 / 3, height());
    painter.drawLine(0, height() / 3, width(), height() / 3);
    painter.drawLine(0, height() * 2 / 3, width(), height() * 2 / 3);
    painter.setPen(QColor(153, 166, 180));
    painter.drawText(rect(), Qt::AlignCenter, "FOCUS TARGET\nClick a position in frame");
  }

  void mousePressEvent(QMouseEvent *event) override {
    if (event->button() != Qt::LeftButton || !isEnabled()) return;
    const double x = std::clamp(event->position().x() / std::max(1, width()), 0.0, 1.0);
    const double y = std::clamp(event->position().y() / std::max(1, height()), 0.0, 1.0);
    if (focus_requested) focus_requested(x, y);
  }
};

class OpenStreamDock final : public QWidget {
 public:
  OpenStreamDock() {
    setObjectName("openstreamControlRoom");
    setMinimumWidth(360);
    buildUi();
    connectUi();
    subscription_id_ = openstream_subscribe_camera_changes([guard = QPointer<OpenStreamDock>(this)](const std::string &) {
      if (!guard || !qApp) return;
      QMetaObject::invokeMethod(qApp, [guard] {
        if (guard) guard->refreshSnapshots();
      }, Qt::QueuedConnection);
    });
    refresh_timer_.setInterval(kRemoteRefreshMs);
    connect(&refresh_timer_, &QTimer::timeout, this, [this] { requestRemoteRefresh(); });
    zoom_update_timer_.setInterval(kZoomUpdateIntervalMs);
    zoom_update_timer_.setSingleShot(true);
    connect(&zoom_update_timer_, &QTimer::timeout, this, [this] { flushZoomUpdate(); });
    refresh_timer_.start();
    refreshSnapshots();
  }

  ~OpenStreamDock() override {
    refresh_timer_.stop();
    zoom_update_timer_.stop();
    if (subscription_id_ != 0) openstream_unsubscribe_camera_changes(subscription_id_);
  }

 private:
  void buildUi() {
    auto *root = new QVBoxLayout(this);
    root->setContentsMargins(14, 14, 14, 14);
    root->setSpacing(10);

    auto *title = new QLabel("OpenStream Beta Control Room");
    title->setObjectName("osTitle");
    auto *subtitle = new QLabel("Select a camera, pair once, then shade and focus it without leaving OBS.");
    subtitle->setObjectName("osMuted");
    subtitle->setWordWrap(true);
    root->addWidget(title);
    root->addWidget(subtitle);

    camera_selector_ = new QComboBox;
    camera_selector_->setAccessibleName("Camera source");
    root->addWidget(camera_selector_);

    auto *identity_row = new QHBoxLayout;
    identity_ = new QLabel("No camera selected");
    identity_->setObjectName("osIdentity");
    status_ = new QLabel("OFFLINE");
    status_->setObjectName("osStatus");
    identity_row->addWidget(identity_, 1);
    identity_row->addWidget(status_);
    root->addLayout(identity_row);

    pages_ = new QStackedWidget;
    root->addWidget(pages_, 1);

    empty_page_ = new QWidget;
    auto *empty_layout = new QVBoxLayout(empty_page_);
    auto *empty_title = new QLabel("Add an OpenStream Beta Camera source");
    empty_title->setObjectName("osSectionTitle");
    auto *empty_help = new QLabel("In Sources, choose + → OpenStream Beta Camera. Normal connection and all live controls then stay here in the Control Room.");
    empty_help->setWordWrap(true);
    empty_help->setObjectName("osMuted");
    empty_layout->addStretch();
    empty_layout->addWidget(empty_title);
    empty_layout->addWidget(empty_help);
    empty_layout->addStretch();
    pages_->addWidget(empty_page_);

    pair_page_ = new QWidget;
    auto *pair_layout = new QVBoxLayout(pair_page_);
    auto *pair_title = new QLabel("Pair this camera");
    pair_title->setObjectName("osSectionTitle");
    pair_help_ = new QLabel("Enter the six-digit code shown at the top of the Android camera screen.");
    pair_help_->setWordWrap(true);
    pair_help_->setObjectName("osMuted");
    pairing_code_ = new QLineEdit;
    pairing_code_->setPlaceholderText("6-digit pairing code");
    pairing_code_->setMaxLength(6);
    pairing_code_->setInputMask("000000");
    pair_button_ = new QPushButton("Pair camera");
    pair_button_->setProperty("primary", true);
    pair_layout->addStretch();
    pair_layout->addWidget(pair_title);
    pair_layout->addWidget(pair_help_);
    pair_layout->addWidget(pairing_code_);
    pair_layout->addWidget(pair_button_);
    pair_layout->addStretch();
    pages_->addWidget(pair_page_);

    auto *scroll = new QScrollArea;
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    control_page_ = new QWidget;
    auto *controls = new QVBoxLayout(control_page_);
    controls->setContentsMargins(0, 0, 0, 0);
    controls->setSpacing(10);

    auto *session = new QGroupBox("Session");
    auto *session_layout = new QVBoxLayout(session);
    auto *session_actions = new QHBoxLayout;
    primary_button_ = new QPushButton("Connect");
    primary_button_->setProperty("primary", true);
    refresh_button_ = new QPushButton("Sync");
    identify_button_ = new QPushButton("Identify");
    session_actions->addWidget(primary_button_, 1);
    session_actions->addWidget(refresh_button_);
    session_actions->addWidget(identify_button_);
    session_layout->addLayout(session_actions);
    auto *authority_row = new QFormLayout;
    authority_ = new QComboBox;
    authority_->addItem("Collaborative", "collaborative");
    authority_->addItem("Lock controls to OBS", "obs_lock");
    authority_row->addRow("Control authority", authority_);
    session_layout->addLayout(authority_row);
    auto *tally_row = new QHBoxLayout;
    preview_tally_ = new QPushButton("Preview");
    preview_tally_->setCheckable(true);
    program_tally_ = new QPushButton("Program");
    program_tally_->setCheckable(true);
    tally_row->addWidget(preview_tally_);
    tally_row->addWidget(program_tally_);
    session_layout->addLayout(tally_row);
    controls->addWidget(session);

    auto *exposure = new QGroupBox("Exposure");
    auto *exposure_form = new QFormLayout(exposure);
    exposure_mode_ = new QComboBox;
    exposure_mode_->addItem("Auto", "auto");
    exposure_mode_->addItem("Manual", "manual");
    iso_ = new QSpinBox;
    iso_->setRange(1, 102400);
    shutter_denominator_ = new QSpinBox;
    shutter_denominator_->setRange(1, 32000);
    shutter_denominator_->setPrefix("1/");
    shutter_denominator_->setSuffix(" s");
    frame_rate_ = new QComboBox;
    exposure_compensation_ = new QDoubleSpinBox;
    exposure_compensation_->setDecimals(0);
    exposure_compensation_->setPrefix("EV ");
    exposure_form->addRow("Mode", exposure_mode_);
    exposure_form->addRow("ISO", iso_);
    exposure_form->addRow("Shutter", shutter_denominator_);
    exposure_form->addRow("Frame rate", frame_rate_);
    exposure_form->addRow("Compensation", exposure_compensation_);
    controls->addWidget(exposure);

    auto *focus = new QGroupBox("Focus");
    auto *focus_layout = new QVBoxLayout(focus);
    auto *focus_form = new QFormLayout;
    focus_mode_ = new QComboBox;
    focus_mode_->addItem("Continuous AF", "continuous");
    focus_mode_->addItem("Single AF", "single");
    focus_mode_->addItem("Manual", "manual");
    focus_distance_ = new QDoubleSpinBox;
    focus_distance_->setDecimals(2);
    focus_distance_->setSuffix(" D");
    focus_form->addRow("Mode", focus_mode_);
    focus_form->addRow("Distance", focus_distance_);
    focus_layout->addLayout(focus_form);
    focus_target_ = new FocusTarget;
    focus_layout->addWidget(focus_target_);
    controls->addWidget(focus);

    auto *color = new QGroupBox("Color");
    auto *color_form = new QFormLayout(color);
    white_balance_mode_ = new QComboBox;
    white_balance_mode_->addItem("Auto", "auto");
    white_balance_mode_->addItem("Daylight", "daylight");
    white_balance_mode_->addItem("Cloudy", "cloudy");
    white_balance_mode_->addItem("Tungsten", "incandescent");
    white_balance_mode_->addItem("Fluorescent", "fluorescent");
    white_balance_mode_->addItem("Manual", "manual");
    kelvin_ = new QSpinBox;
    kelvin_->setRange(2000, 12000);
    kelvin_->setSingleStep(50);
    kelvin_->setSuffix(" K");
    tint_ = new QSpinBox;
    tint_->setRange(-100, 100);
    white_balance_lock_ = new QCheckBox("Hold current white balance");
    color_form->addRow("White balance", white_balance_mode_);
    color_form->addRow("Temperature", kelvin_);
    color_form->addRow("Tint", tint_);
    color_form->addRow("", white_balance_lock_);
    controls->addWidget(color);

    auto *lens = new QGroupBox("Lens & image");
    auto *lens_form = new QFormLayout(lens);
    zoom_ = new QDoubleSpinBox;
    zoom_->setDecimals(2);
    zoom_->setSingleStep(0.1);
    zoom_->setSuffix("×");
    stabilization_ = new QComboBox;
    stabilization_->addItem("Off", "off");
    stabilization_->addItem("Electronic", "video");
    stabilization_->addItem("Optical", "optical");
    torch_ = new QCheckBox("Torch");
    lens_form->addRow("Zoom", zoom_);
    lens_form->addRow("Stabilization", stabilization_);
    lens_form->addRow("", torch_);
    controls->addWidget(lens);

    auto *health = new QGroupBox("Camera health");
    auto *health_layout = new QVBoxLayout(health);
    health_ = new QLabel("Waiting for camera telemetry");
    health_->setWordWrap(true);
    health_layout->addWidget(health_);
    controls->addWidget(health);

    feedback_ = new QLabel;
    feedback_->setObjectName("osFeedback");
    feedback_->setWordWrap(true);
    controls->addWidget(feedback_);
    controls->addStretch();
    scroll->setWidget(control_page_);
    pages_->addWidget(scroll);

    setStyleSheet(R"(
      #openstreamControlRoom { background: palette(window); }
      #osTitle { font-size: 18px; font-weight: 700; }
      #osIdentity, #osSectionTitle { font-size: 14px; font-weight: 650; }
      #osMuted { color: palette(mid); }
      #osStatus { font-weight: 700; }
      #osStatus[tone="program"] { color: #ff5c63; }
      #osStatus[tone="preview"] { color: #65d68d; }
      #osStatus[tone="live"] { color: #65d6c8; }
      #osStatus[tone="warning"] { color: #e5a33b; }
      #osStatus[tone="offline"] { color: palette(mid); }
      #osFeedback { color: #e5a33b; }
      QGroupBox { border: 1px solid palette(midlight); border-radius: 8px; margin-top: 10px; padding: 12px 8px 8px 8px; font-weight: 650; }
      QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
      QPushButton { min-height: 30px; padding: 3px 10px; }
      QPushButton[primary="true"] { background: #65d6a6; color: #07130f; font-weight: 700; }
      QPushButton:checked { background: #e24b4b; color: white; }
      QComboBox, QSpinBox, QDoubleSpinBox, QLineEdit { min-height: 28px; }
    )");
  }

  void connectUi() {
    connect(camera_selector_, &QComboBox::currentIndexChanged, this, [this](int index) {
      zoom_update_pending_ = false;
      zoom_pending_instance_.clear();
      current_instance_ = index >= 0 ? camera_selector_->itemData(index).toString().toStdString() : "";
      renderSelected();
      requestRemoteRefresh();
    });
    connect(pair_button_, &QPushButton::clicked, this, [this] {
      OpenStreamCommand command;
      command.type = OpenStreamCommandType::Pair;
      command.pairing_code = pairing_code_->text().trimmed().toStdString();
      runCommand(std::move(command), "Pairing securely…");
    });
    connect(pairing_code_, &QLineEdit::textChanged, this, [this](const QString &value) {
      const auto camera = selected();
      pair_button_->setEnabled(camera && camera->phone_available && !camera->request_pending && value.size() == 6);
    });
    connect(primary_button_, &QPushButton::clicked, this, [this] {
      const auto camera = selected();
      if (!camera) return;
      OpenStreamCommand command;
      command.type = camera->live || camera->status == "Reserved" || camera->status == "Connecting"
                         ? OpenStreamCommandType::Stop : OpenStreamCommandType::Start;
      const QString message = command.type == OpenStreamCommandType::Stop
                                  ? "Stopping camera…" : "Listening for camera…";
      runCommand(std::move(command), message);
    });
    connect(refresh_button_, &QPushButton::clicked, this, [this] { requestRemoteRefresh(true); });
    connect(identify_button_, &QPushButton::clicked, this, [this] {
      OpenStreamCommand command;
      command.type = OpenStreamCommandType::Identify;
      runCommand(std::move(command), "Identifying camera…");
    });
    connect(authority_, &QComboBox::currentIndexChanged, this, [this](int) {
      OpenStreamCommand command;
      command.type = OpenStreamCommandType::SetAuthority;
      command.authority = authority_->currentData().toString().toStdString();
      runCommand(std::move(command), "Updating control authority…");
    });
    connect(program_tally_, &QPushButton::clicked, this, [this] { sendTally(); });
    connect(preview_tally_, &QPushButton::clicked, this, [this] { sendTally(); });

    connect(exposure_mode_, &QComboBox::currentIndexChanged, this, [this](int) {
      OpenStreamSettingsPatch patch;
      patch.exposure_mode = exposure_mode_->currentData().toString().toStdString();
      sendSettings(std::move(patch), "Updating exposure mode…");
    });
    connect(iso_, &QSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.iso = iso_->value(); sendSettings(std::move(patch), "Setting ISO…");
    });
    connect(shutter_denominator_, &QSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.shutter_us = 1000000.0 / shutter_denominator_->value(); sendSettings(std::move(patch), "Setting shutter…");
    });
    connect(frame_rate_, &QComboBox::currentIndexChanged, this, [this](int) {
      if (frame_rate_->currentData().isNull()) return;
      OpenStreamSettingsPatch patch; patch.frame_rate = frame_rate_->currentData().toDouble(); sendSettings(std::move(patch), "Setting frame rate…");
    });
    connect(exposure_compensation_, &QDoubleSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.exposure_compensation = exposure_compensation_->value(); sendSettings(std::move(patch), "Setting exposure compensation…");
    });
    connect(focus_mode_, &QComboBox::currentIndexChanged, this, [this](int) {
      OpenStreamSettingsPatch patch; patch.focus_mode = focus_mode_->currentData().toString().toStdString(); sendSettings(std::move(patch), "Updating focus mode…");
    });
    connect(focus_distance_, &QDoubleSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.focus_distance = focus_distance_->value(); sendSettings(std::move(patch), "Setting focus distance…");
    });
    focus_target_->focus_requested = [this](double x, double y) {
      OpenStreamCommand command;
      command.type = OpenStreamCommandType::FocusAt;
      command.focus_x = x;
      command.focus_y = y;
      runCommand(std::move(command), "Focusing selected point…");
    };
    connect(white_balance_mode_, &QComboBox::currentIndexChanged, this, [this](int) {
      OpenStreamSettingsPatch patch; patch.white_balance_mode = white_balance_mode_->currentData().toString().toStdString(); sendSettings(std::move(patch), "Updating white balance…");
    });
    connect(kelvin_, &QSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.white_balance_kelvin = kelvin_->value(); sendSettings(std::move(patch), "Setting color temperature…");
    });
    connect(tint_, &QSpinBox::editingFinished, this, [this] {
      OpenStreamSettingsPatch patch; patch.white_balance_tint = tint_->value(); sendSettings(std::move(patch), "Setting tint…");
    });
    connect(white_balance_lock_, &QCheckBox::clicked, this, [this](bool enabled) {
      OpenStreamSettingsPatch patch; patch.white_balance_lock = enabled; sendSettings(std::move(patch), enabled ? "Locking white balance…" : "Unlocking white balance…");
    });
    connect(zoom_, &QDoubleSpinBox::valueChanged, this, [this](double value) {
      queueZoomUpdate(value);
    });
    connect(stabilization_, &QComboBox::currentIndexChanged, this, [this](int) {
      OpenStreamSettingsPatch patch; patch.stabilization_mode = stabilization_->currentData().toString().toStdString(); sendSettings(std::move(patch), "Setting stabilization…");
    });
    connect(torch_, &QCheckBox::clicked, this, [this](bool enabled) {
      OpenStreamSettingsPatch patch; patch.torch = enabled; sendSettings(std::move(patch), enabled ? "Turning torch on…" : "Turning torch off…");
    });
  }

  std::optional<OpenStreamCameraSnapshot> selected() const {
    const auto it = std::find_if(snapshots_.begin(), snapshots_.end(), [this](const auto &camera) {
      return camera.instance_id == current_instance_;
    });
    if (it == snapshots_.end()) return std::nullopt;
    return *it;
  }

  void refreshSnapshots() {
    const std::string previous = current_instance_;
    snapshots_ = openstream_camera_snapshots();
    bool selector_changed = camera_selector_->count() != static_cast<int>(snapshots_.size());
    if (!selector_changed) {
      for (int index = 0; index < camera_selector_->count(); ++index) {
        if (camera_selector_->itemData(index).toString().toStdString() !=
            snapshots_[static_cast<size_t>(index)].instance_id) {
          selector_changed = true;
          break;
        }
      }
    }
    if (!selector_changed) {
      renderSelected();
      return;
    }
    QSignalBlocker blocker(camera_selector_);
    camera_selector_->clear();
    for (const auto &camera : snapshots_) {
      const QString label = QString::fromStdString(camera.slot_label + " · " + camera.source_name);
      camera_selector_->addItem(label, QString::fromStdString(camera.instance_id));
    }
    int selected_index = -1;
    for (int index = 0; index < camera_selector_->count(); ++index) {
      if (camera_selector_->itemData(index).toString().toStdString() == previous) {
        selected_index = index;
        break;
      }
    }
    if (selected_index < 0 && camera_selector_->count() > 0) selected_index = 0;
    camera_selector_->setCurrentIndex(selected_index);
    current_instance_ = selected_index >= 0 ? camera_selector_->itemData(selected_index).toString().toStdString() : "";
    renderSelected();
  }

  void renderSelected() {
    const auto camera = selected();
    if (!camera) {
      identity_->setText("No camera selected");
      status_->setText("OFFLINE");
      setStatusTone("offline");
      pages_->setCurrentWidget(empty_page_);
      return;
    }

    identity_->setText(QString::fromStdString(camera->slot_label + " · " + camera->production_label));
    status_->setText(QString::fromStdString(camera->status).toUpper());
    if (camera->state.program_tally) {
      setStatusTone("program");
    } else if (camera->state.preview_tally) {
      setStatusTone("preview");
    } else if (camera->live) {
      setStatusTone("live");
    } else if (camera->status == "Reconnecting" || camera->status == "Connecting") {
      setStatusTone("warning");
    } else {
      setStatusTone("offline");
    }
    if (!camera->paired) {
      pages_->setCurrentWidget(pair_page_);
      const bool ready = camera->phone_available && !camera->request_pending;
      pair_help_->setText(camera->phone_available
          ? "Enter the six-digit code shown at the top of the Android camera screen."
          : "Waiting for this camera on the local network. Open the Android app and arm remote operation.");
      pairing_code_->setEnabled(ready);
      pair_button_->setEnabled(ready && pairing_code_->text().size() == 6);
      return;
    }

    pages_->setCurrentIndex(2);
    const bool can_stop = camera->live || camera->status == "Reserved" || camera->status == "Connecting";
    const bool usable = camera->phone_available && !camera->request_pending;
    primary_button_->setText(can_stop ? "Stop" : "Connect");
    primary_button_->setEnabled(can_stop || !camera->request_pending);
    refresh_button_->setEnabled(usable);
    identify_button_->setEnabled(usable);
    feedback_->setText(!camera->last_control_error.empty()
        ? QString::fromStdString(camera->last_control_error) : feedback_text_);
    health_->setText(camera->phone_available
        ? QString("Control channel ready · %1 · %2").arg(QString::fromStdString(camera->phone_label), camera->live ? "media live" : "media standing by")
        : "Camera is offline. Controls remain visible but no values are fabricated.");

    const auto &caps = camera->capabilities;
    const auto &state = camera->state;
    const bool controls_ready = usable && caps.loaded && state.valid;
    const QSignalBlocker authority_block(authority_);
    const QSignalBlocker preview_block(preview_tally_);
    const QSignalBlocker program_block(program_tally_);
    selectWireValue(authority_, state.authority);
    authority_->setEnabled(controls_ready);
    preview_tally_->setChecked(state.preview_tally);
    program_tally_->setChecked(state.program_tally);
    preview_tally_->setEnabled(controls_ready);
    program_tally_->setEnabled(controls_ready);

    renderExposure(*camera, controls_ready);
    renderFocus(*camera, controls_ready);
    renderColor(*camera, controls_ready);
    renderLens(*camera, controls_ready);
  }

  void renderExposure(const OpenStreamCameraSnapshot &camera, bool ready) {
    const auto &caps = camera.capabilities;
    const auto &state = camera.state;
    const QSignalBlocker mode_block(exposure_mode_);
    const QSignalBlocker iso_block(iso_);
    const QSignalBlocker shutter_block(shutter_denominator_);
    const QSignalBlocker fps_block(frame_rate_);
    const QSignalBlocker ev_block(exposure_compensation_);
    selectWireValue(exposure_mode_, state.exposure_mode);
    setItemSupported(exposure_mode_, "auto", caps.auto_exposure);
    setItemSupported(exposure_mode_, "manual", caps.manual_exposure);
    exposure_mode_->setEnabled(ready);
    const bool manual = ready && caps.manual_exposure && state.exposure_mode == "manual";
    if (caps.iso.available) iso_->setRange(static_cast<int>(caps.iso.minimum), static_cast<int>(caps.iso.maximum));
    iso_->setValue(static_cast<int>(std::max(1.0, state.iso)));
    iso_->setEnabled(manual && caps.iso.available);
    if (caps.shutter_us.available) {
      const int slow = std::max(1, static_cast<int>(std::round(1000000.0 / caps.shutter_us.maximum)));
      const int fast = std::max(slow, static_cast<int>(std::round(1000000.0 / caps.shutter_us.minimum)));
      shutter_denominator_->setRange(slow, fast);
    }
    if (state.shutter_us > 0.0) shutter_denominator_->setValue(std::max(1, static_cast<int>(std::round(1000000.0 / state.shutter_us))));
    shutter_denominator_->setEnabled(manual && caps.shutter_us.available);
    frame_rate_->clear();
    for (double rate : caps.frame_rates) frame_rate_->addItem(QString::number(rate, 'f', 0) + " fps", rate);
    const int frame_index = frame_rate_->findData(state.frame_rate);
    if (frame_index >= 0) frame_rate_->setCurrentIndex(frame_index);
    frame_rate_->setEnabled(ready && !caps.frame_rates.empty());
    if (caps.exposure_compensation.available) exposure_compensation_->setRange(caps.exposure_compensation.minimum, caps.exposure_compensation.maximum);
    exposure_compensation_->setValue(state.exposure_compensation);
    exposure_compensation_->setEnabled(ready && caps.exposure_compensation.available && state.exposure_mode == "auto");
  }

  void renderFocus(const OpenStreamCameraSnapshot &camera, bool ready) {
    const auto &caps = camera.capabilities;
    const auto &state = camera.state;
    const QSignalBlocker mode_block(focus_mode_);
    const QSignalBlocker distance_block(focus_distance_);
    selectWireValue(focus_mode_, state.focus_mode);
    applySupportedModes(focus_mode_, caps.focus_modes);
    focus_mode_->setEnabled(ready && (caps.autofocus || caps.manual_focus));
    if (caps.focus_distance.available) focus_distance_->setRange(caps.focus_distance.minimum, caps.focus_distance.maximum);
    focus_distance_->setValue(state.focus_distance);
    focus_distance_->setEnabled(ready && caps.manual_focus && state.focus_mode == "manual");
    focus_target_->setEnabled(ready && caps.tap_to_focus);
  }

  void renderColor(const OpenStreamCameraSnapshot &camera, bool ready) {
    const auto &caps = camera.capabilities;
    const auto &state = camera.state;
    const QSignalBlocker mode_block(white_balance_mode_);
    const QSignalBlocker kelvin_block(kelvin_);
    const QSignalBlocker tint_block(tint_);
    const QSignalBlocker lock_block(white_balance_lock_);
    selectWireValue(white_balance_mode_, state.white_balance_mode);
    applySupportedModes(white_balance_mode_, caps.white_balance_modes);
    white_balance_mode_->setEnabled(ready && (caps.auto_white_balance || caps.manual_white_balance));
    kelvin_->setValue(static_cast<int>(state.white_balance_kelvin > 0 ? state.white_balance_kelvin : 5600));
    tint_->setValue(static_cast<int>(state.white_balance_tint));
    const bool manual = ready && caps.manual_white_balance && state.white_balance_mode == "manual";
    kelvin_->setEnabled(manual);
    tint_->setEnabled(manual);
    white_balance_lock_->setChecked(state.white_balance_lock);
    white_balance_lock_->setEnabled(ready && caps.auto_white_balance);
  }

  void renderLens(const OpenStreamCameraSnapshot &camera, bool ready) {
    const auto &caps = camera.capabilities;
    const auto &state = camera.state;
    const QSignalBlocker zoom_block(zoom_);
    const QSignalBlocker stabilization_block(stabilization_);
    const QSignalBlocker torch_block(torch_);
    const bool local_zoom_update = zoom_update_in_flight_ && zoom_active_instance_ == camera.instance_id;
    const bool pending_zoom_update = zoom_update_pending_ && zoom_pending_instance_ == camera.instance_id;
    if (caps.zoom_ratio.available) zoom_->setRange(caps.zoom_ratio.minimum, caps.zoom_ratio.maximum);
    if (!local_zoom_update && !pending_zoom_update) zoom_->setValue(state.zoom_ratio);
    zoom_->setEnabled((ready || local_zoom_update) && caps.zoom);
    selectWireValue(stabilization_, state.stabilization_mode);
    stabilization_->setEnabled(ready && caps.stabilization);
    applySupportedModes(stabilization_, caps.stabilization_modes);
    torch_->setChecked(state.torch);
    torch_->setEnabled(ready && caps.torch);
  }

  static void selectWireValue(QComboBox *combo, const std::string &value) {
    const int index = combo->findData(QString::fromStdString(value));
    combo->setPlaceholderText(index >= 0 ? QString() : QString("Unsupported: %1").arg(QString::fromStdString(value)));
    combo->setCurrentIndex(index);
  }

  void setStatusTone(const char *tone) {
    if (status_->property("tone").toString() == QString::fromUtf8(tone)) return;
    status_->setProperty("tone", tone);
    status_->style()->unpolish(status_);
    status_->style()->polish(status_);
  }

  static void setItemSupported(QComboBox *combo, const char *wire_value, bool supported) {
    const int index = combo->findData(QString::fromUtf8(wire_value));
    if (index >= 0) combo->setItemData(index, supported ? QVariant() : QVariant(0), Qt::UserRole - 1);
  }

  static void applySupportedModes(QComboBox *combo, const std::vector<std::string> &supported) {
    for (int index = 0; index < combo->count(); ++index) {
      const std::string value = combo->itemData(index).toString().toStdString();
      const bool available = std::find(supported.begin(), supported.end(), value) != supported.end();
      combo->setItemData(index, available ? QVariant() : QVariant(0), Qt::UserRole - 1);
    }
  }

  void sendSettings(OpenStreamSettingsPatch patch, const QString &message) {
    OpenStreamCommand command;
    command.type = OpenStreamCommandType::ApplySettings;
    command.settings = std::move(patch);
    runCommand(std::move(command), message);
  }

  void queueZoomUpdate(double value) {
    if (current_instance_.empty()) return;
    zoom_pending_value_ = value;
    zoom_pending_instance_ = current_instance_;
    zoom_update_pending_ = true;
    if (!zoom_update_in_flight_ && !zoom_update_timer_.isActive()) zoom_update_timer_.start();
  }

  void flushZoomUpdate() {
    if (zoom_update_in_flight_ || !zoom_update_pending_) return;
    if (zoom_pending_instance_ != current_instance_) {
      zoom_update_pending_ = false;
      zoom_pending_instance_.clear();
      renderSelected();
      return;
    }
    const auto camera = selected();
    if (!camera || camera->request_pending) {
      zoom_update_timer_.start();
      return;
    }

    zoom_update_pending_ = false;
    zoom_update_in_flight_ = true;
    zoom_active_instance_ = camera->instance_id;
    OpenStreamSettingsPatch patch;
    patch.zoom_ratio = zoom_pending_value_;
    OpenStreamCommand command;
    command.type = OpenStreamCommandType::ApplySettings;
    command.settings = std::move(patch);
    runCommand(std::move(command), "Setting zoom…", [this] {
      zoom_update_in_flight_ = false;
      zoom_active_instance_.clear();
      if (zoom_update_pending_) {
        zoom_update_timer_.start();
      } else {
        renderSelected();
      }
    });
  }

  void sendTally() {
    OpenStreamCommand command;
    command.type = OpenStreamCommandType::SetTally;
    command.preview_tally = preview_tally_->isChecked();
    command.program_tally = program_tally_->isChecked();
    runCommand(std::move(command), "Updating tally…");
  }

  void requestRemoteRefresh(bool user_requested = false) {
    if (!user_requested && !isVisible()) return;
    const auto camera = selected();
    if (!camera || !camera->phone_available || !camera->paired || camera->request_pending) return;
    OpenStreamCommand command;
    command.type = OpenStreamCommandType::RefreshRemoteState;
    runCommand(std::move(command), user_requested ? "Synchronizing camera state…" : QString());
  }

  void runCommand(OpenStreamCommand command, const QString &working,
                  std::function<void()> completed = {}) {
    const auto camera = selected();
    if (!camera || (camera->request_pending && command.type != OpenStreamCommandType::Stop)) {
      if (completed) completed();
      return;
    }
    if (command.expected_revision == 0) command.expected_revision = camera->state.revision;
    const std::string instance = camera->instance_id;
    if (!working.isEmpty()) feedback_text_ = working;
    renderSelected();
    QPointer<OpenStreamDock> guard(this);
    openstream_run_command_async(instance, std::move(command),
                                 [guard, completed = std::move(completed)](OpenStreamCommandResponse response) mutable {
      if (!guard || !qApp) return;
      QMetaObject::invokeMethod(qApp, [guard, response = std::move(response), completed = std::move(completed)]() mutable {
        if (!guard) return;
        guard->feedback_text_ = QString::fromStdString(response.message);
        guard->refreshSnapshots();
        if (completed) completed();
      }, Qt::QueuedConnection);
    });
  }

  QComboBox *camera_selector_ = nullptr;
  QLabel *identity_ = nullptr;
  QLabel *status_ = nullptr;
  QStackedWidget *pages_ = nullptr;
  QWidget *empty_page_ = nullptr;
  QWidget *pair_page_ = nullptr;
  QWidget *control_page_ = nullptr;
  QLabel *pair_help_ = nullptr;
  QLineEdit *pairing_code_ = nullptr;
  QPushButton *pair_button_ = nullptr;
  QPushButton *primary_button_ = nullptr;
  QPushButton *refresh_button_ = nullptr;
  QPushButton *identify_button_ = nullptr;
  QComboBox *authority_ = nullptr;
  QPushButton *preview_tally_ = nullptr;
  QPushButton *program_tally_ = nullptr;
  QComboBox *exposure_mode_ = nullptr;
  QSpinBox *iso_ = nullptr;
  QSpinBox *shutter_denominator_ = nullptr;
  QComboBox *frame_rate_ = nullptr;
  QDoubleSpinBox *exposure_compensation_ = nullptr;
  QComboBox *focus_mode_ = nullptr;
  QDoubleSpinBox *focus_distance_ = nullptr;
  FocusTarget *focus_target_ = nullptr;
  QComboBox *white_balance_mode_ = nullptr;
  QSpinBox *kelvin_ = nullptr;
  QSpinBox *tint_ = nullptr;
  QCheckBox *white_balance_lock_ = nullptr;
  QDoubleSpinBox *zoom_ = nullptr;
  QComboBox *stabilization_ = nullptr;
  QCheckBox *torch_ = nullptr;
  QLabel *health_ = nullptr;
  QLabel *feedback_ = nullptr;
  QString feedback_text_;
  std::vector<OpenStreamCameraSnapshot> snapshots_;
  std::string current_instance_;
  uint64_t subscription_id_ = 0;
  QTimer refresh_timer_;
  QTimer zoom_update_timer_;
  double zoom_pending_value_ = 1.0;
  bool zoom_update_pending_ = false;
  bool zoom_update_in_flight_ = false;
  std::string zoom_pending_instance_;
  std::string zoom_active_instance_;
};

FrontendApi g_frontend;
QPointer<OpenStreamDock> g_dock;

void register_dock() {
  if (g_dock || !g_frontend.add_dock || !qApp) return;
  auto *dock = new OpenStreamDock;
  if (!g_frontend.add_dock(kDockId, "OpenStream Beta Control Room", dock)) {
    delete dock;
    blog(LOG_WARNING, "[OpenStream Beta] OBS declined the Control Room dock registration");
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
    blog(LOG_WARNING, "[OpenStream Beta] OBS frontend dock API unavailable; source setup remains available");
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
