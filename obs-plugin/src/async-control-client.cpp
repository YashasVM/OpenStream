#include "async-control-client.hpp"

#include <chrono>

AsyncControlClient::AsyncControlClient() : worker_(&AsyncControlClient::run, this) {}

AsyncControlClient::~AsyncControlClient() { stop(); }

bool AsyncControlClient::post(std::function<void()> command) {
  if (!command) return false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || commands_.size() >= kQueueCapacity) return false;
    commands_.push(std::move(command));
  }
  wake_.notify_one();
  return true;
}

void AsyncControlClient::stop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return;
    stopping_ = true;
    // Transient controls can be discarded at teardown. Keep only the newest
    // pending reservation release: older tokens are superseded, and draining
    // several unreachable-phone releases would stall OBS source destruction.
    std::queue<std::function<void()>> empty;
    commands_.swap(empty);
    while (urgent_commands_.size() > 1) {
      urgent_commands_.pop();
    }
  }
  wake_.notify_one();
  if (worker_.joinable()) worker_.join();
}

bool AsyncControlClient::post_urgent(std::function<bool()> command) {
  if (!command) return false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || stopped_) return false;
    urgent_commands_.push(std::move(command));
  }
  wake_.notify_one();
  return true;
}

void AsyncControlClient::run() {
  for (;;) {
    std::function<void()> command;
    std::function<bool()> urgent_command;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      wake_.wait(lock, [this] {
        return stopping_ || !urgent_commands_.empty() || !commands_.empty();
      });
      if (!urgent_commands_.empty()) {
        urgent_command = std::move(urgent_commands_.front());
        urgent_commands_.pop();
      } else if (stopping_) {
        stopped_ = true;
        return;
      } else {
        command = std::move(commands_.front());
        commands_.pop();
      }
    }

    if (urgent_command) {
      for (int attempt = 0; attempt < kUrgentRetryAttempts; ++attempt) {
        if (urgent_command()) break;
        if (attempt + 1 < kUrgentRetryAttempts) {
          std::unique_lock<std::mutex> lock(mutex_);
          if (wake_.wait_for(lock,
                             std::chrono::milliseconds(150),
                             [this] { return stopping_; })) {
            break;
          }
        }
      }
      continue;
    }
    command();
  }
}
