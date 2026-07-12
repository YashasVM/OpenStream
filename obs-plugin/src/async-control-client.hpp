#pragma once

#include <condition_variable>
#include <functional>
#include <mutex>
#include <queue>
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
  void stop();

 private:
  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::queue<std::function<void()>> commands_;
  bool stopping_ = false;
  std::thread worker_;
};
