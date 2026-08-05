#pragma once

#include <cstddef>
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
  static constexpr size_t kQueueCapacity = 16;

  AsyncControlClient();
  ~AsyncControlClient();
  AsyncControlClient(const AsyncControlClient &) = delete;
  AsyncControlClient &operator=(const AsyncControlClient &) = delete;

  bool post(std::function<void()> command);
  void stop();

 private:
  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::queue<std::function<void()>> commands_;
  bool stopping_ = false;
  std::thread worker_;
};
