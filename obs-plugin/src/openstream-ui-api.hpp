#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

struct OpenStreamNumberRange {
  double minimum = 0.0;
  double maximum = 0.0;
  double step = 0.0;
  bool available = false;
};

struct OpenStreamCameraCapabilities {
  bool loaded = false;
  bool autofocus = false;
  bool tap_to_focus = false;
  bool manual_focus = false;
  bool auto_exposure = false;
  bool manual_exposure = false;
  bool auto_white_balance = false;
  bool manual_white_balance = false;
  bool zoom = false;
  bool torch = false;
  bool lens_selection = false;
  bool stabilization = false;
  OpenStreamNumberRange iso;
  OpenStreamNumberRange shutter_us;
  OpenStreamNumberRange focus_distance;
  OpenStreamNumberRange white_balance_kelvin;
  OpenStreamNumberRange white_balance_tint;
  OpenStreamNumberRange zoom_ratio;
  std::vector<std::string> lenses;
  std::vector<std::string> stabilization_modes;
  std::vector<double> frame_rates;
};

struct OpenStreamCameraState {
  uint64_t revision = 0;
  std::string authority = "collaborative";
  std::string exposure_mode = "auto";
  std::string focus_mode = "continuous";
  std::string white_balance_mode = "auto";
  std::string lens = "wide";
  std::string focus_status = "idle";
  double iso = 0.0;
  double shutter_us = 0.0;
  double exposure_compensation = 0.0;
  double frame_rate = 0.0;
  double focus_distance = 0.0;
  double white_balance_kelvin = 0.0;
  double white_balance_tint = 0.0;
  double zoom_ratio = 1.0;
  double battery_percent = -1.0;
  double device_temperature_c = 0.0;
  double network_mbps = 0.0;
  double dropped_frames_percent = 0.0;
  bool torch = false;
  bool white_balance_lock = false;
  std::string stabilization_mode = "off";
  bool program_tally = false;
  bool preview_tally = false;
  bool screen_sleeping = false;
  bool valid = false;
};

struct OpenStreamCameraSnapshot {
  std::string instance_id;
  std::string source_name;
  std::string slot_label;
  std::string production_label;
  std::string status;
  std::string phone_label;
  bool listener_enabled = false;
  bool phone_available = false;
  bool live = false;
  bool paired = false;
  bool request_pending = false;
  std::string last_control_error;
  OpenStreamCameraCapabilities capabilities;
  OpenStreamCameraState state;
};

enum class OpenStreamCommandType {
  Start,
  Stop,
  RefreshDiscovery,
  Pair,
  RefreshRemoteState,
  ApplySettings,
  FocusAt,
  SetAuthority,
  SetTally,
  Identify,
};

struct OpenStreamSettingsPatch {
  std::optional<std::string> exposure_mode;
  std::optional<double> iso;
  std::optional<double> shutter_us;
  std::optional<double> exposure_compensation;
  std::optional<double> frame_rate;
  std::optional<std::string> focus_mode;
  std::optional<double> focus_distance;
  std::optional<std::string> white_balance_mode;
  std::optional<double> white_balance_kelvin;
  std::optional<double> white_balance_tint;
  std::optional<bool> white_balance_lock;
  std::optional<std::string> lens;
  std::optional<double> zoom_ratio;
  std::optional<bool> torch;
  std::optional<std::string> stabilization_mode;
};

struct OpenStreamCommand {
  OpenStreamCommandType type = OpenStreamCommandType::RefreshRemoteState;
  uint64_t expected_revision = 0;
  OpenStreamSettingsPatch settings;
  double focus_x = 0.5;
  double focus_y = 0.5;
  std::string focus_mode = "auto";
  std::string authority = "collaborative";
  bool program_tally = false;
  bool preview_tally = false;
  std::string pairing_code;
};

struct OpenStreamCommandResponse {
  bool ok = false;
  std::string message;
  uint64_t revision = 0;
};

using OpenStreamCommandResult = std::function<void(OpenStreamCommandResponse)>;
using OpenStreamCameraChangedCallback = std::function<void(const std::string &)>;

// Transitional adapter for the legacy dock. Removed when the Control Room lands.
enum class OpenStreamUiCommand {
  Start, Stop, Refresh, Identify, TorchOn, TorchOff, RearCamera, FrontCamera, ZoomIn, ZoomOut,
};

std::vector<OpenStreamCameraSnapshot> openstream_camera_snapshots();
void openstream_run_command_async(const std::string &instance_id,
                                  OpenStreamCommand command,
                                  OpenStreamCommandResult completion);
void openstream_run_command_async(const std::string &instance_id,
                                  OpenStreamUiCommand command,
                                  std::function<void(bool, std::string)> completion);
uint64_t openstream_subscribe_camera_changes(OpenStreamCameraChangedCallback callback);
void openstream_unsubscribe_camera_changes(uint64_t subscription_id);
void openstream_wait_for_commands();

void openstream_dock_create();
void openstream_dock_destroy();
