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
  AsyncControlClient();
  ~AsyncControlClient();
  AsyncControlClient(const AsyncControlClient &) = delete;
  AsyncControlClient &operator=(const AsyncControlClient &) = delete;

  bool post(std::function<void()> command);
  // Reservation release is lifecycle-critical. Keep one separate command so
  // teardown can still release a phone when the transient control queue is full.
  bool post_urgent(std::function<void()> command);
  void stop();

 private:
  // Camera controls are transient. Rejecting new work when this small queue
  // is full prevents a disconnected phone from turning UI clicks into stale
  // network requests.
  static constexpr std::size_t kQueueCapacity = 16;

  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::queue<std::function<void()>> commands_;
  std::function<void()> urgent_command_;
  bool stopping_ = false;
  std::thread worker_;
};
