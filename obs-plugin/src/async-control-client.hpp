#pragma once

#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

// Serial executor for camera-control I/O.  Commands are deliberately serialized:
// phones expose a small single-client HTTP server and OBS UI callbacks must never
// wait for its network timeouts.
class AsyncControlClient {
 public:
  AsyncControlClient();
  ~AsyncControlClient();
  AsyncControlClient(const AsyncControlClient &) = delete;
  AsyncControlClient &operator=(const AsyncControlClient &) = delete;

  void post(std::function<void()> command);
  void postLatest(std::string key, std::function<void()> command);
  void stop();

 private:
  struct PendingCommand {
    std::string key;
    std::function<void()> command;
  };

  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::deque<PendingCommand> commands_;
  bool stopping_ = false;
  std::thread worker_;
};
