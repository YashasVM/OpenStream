#pragma once

#include <cstddef>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <queue>
#include <thread>

// Serial executor for camera-control I/O. Commands are deliberately serialized:
// phones expose a small single-client HTTP server and OBS UI callbacks must never
// wait for its network timeouts.
class AsyncControlClient {
 public:
  AsyncControlClient();
  ~AsyncControlClient();
  AsyncControlClient(const AsyncControlClient &) = delete;
  AsyncControlClient &operator=(const AsyncControlClient &) = delete;

  bool post(std::function<void()> command);
  // Reservation releases are lifecycle-critical. They are queued separately,
  // retried on transient failure, and drained before the executor stops.
  bool post_urgent(std::function<bool()> command);
  void stop();

 private:
  // Camera controls are transient. Rejecting new work when this small queue
  // is full prevents a disconnected phone from turning UI clicks into stale
  // network requests.
  static constexpr std::size_t kQueueCapacity = 16;
  static constexpr int kUrgentRetryAttempts = 3;

  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::queue<std::function<void()>> commands_;
  std::queue<std::function<bool()>> urgent_commands_;
  bool stopping_ = false;
  bool stopped_ = false;
  std::thread worker_;
};
