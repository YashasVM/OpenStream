#pragma once

#include <functional>
#include <string>
#include <vector>

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
};

enum class OpenStreamUiCommand {
  Start,
  Stop,
  Refresh,
  Identify,
  TorchOn,
  TorchOff,
  RearCamera,
  FrontCamera,
  ZoomIn,
  ZoomOut,
};

using OpenStreamCommandResult = std::function<void(bool, std::string)>;

std::vector<OpenStreamCameraSnapshot> openstream_camera_snapshots();
void openstream_run_command_async(const std::string &instance_id,
                                  OpenStreamUiCommand command,
                                  OpenStreamCommandResult completion);
void openstream_wait_for_commands();

void openstream_dock_create();
void openstream_dock_destroy();
