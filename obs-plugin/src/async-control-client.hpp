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
//
// Queue bounds (AGENTS.md: never unbounded; every queue declares capacity+overflow):
// - Normal queue: capacity kQueueCapacity (16). Overflow policy is drop-newest:
//   post() returns false when full or stopping; the caller (queue_control_command)
//   emits blog(LOG_WARNING, "... control queue is full or stopping").
// - Urgent queue: capacity kUrgentCapacity (4). Overflow policy is drop-newest:
//   post_urgent() returns false when full or stopping; the caller
//   (queue_release_phone) emits blog(LOG_WARNING, "... could not be queued").
//   Teardown additionally collapses pending urgent releases to the newest token
//   (see stop()), because older reservation tokens are superseded.
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
  static constexpr std::size_t kUrgentCapacity = 4;
  static constexpr int kUrgentRetryAttempts = 3;

  void run();
  std::mutex mutex_;
  std::condition_variable wake_;
  std::queue<std::function<void()>> commands_;
  std::queue<std::function<bool()>> urgent_commands_;
  bool stopping_ = false;
  std::thread worker_;
};
