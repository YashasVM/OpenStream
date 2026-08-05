#include "async-control-client.hpp"

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
    // Commands contain network targets, never source pointers. Dropping pending
    // work makes source destruction bounded by at most the in-flight timeout.
    std::queue<std::function<void()>> empty;
    commands_.swap(empty);
  }
  wake_.notify_one();
  if (worker_.joinable()) worker_.join();
}

bool AsyncControlClient::post_urgent(std::function<void()> command) {
  if (!command) return false;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || urgent_command_) return false;
    urgent_command_ = std::move(command);
  }
  wake_.notify_one();
  return true;
}

void AsyncControlClient::run() {
  for (;;) {
    std::function<void()> command;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      wake_.wait(lock, [this] {
        return stopping_ || urgent_command_ || !commands_.empty();
      });
      if (urgent_command_) {
        command = std::move(urgent_command_);
      } else if (stopping_) {
        return;
      } else {
        command = std::move(commands_.front());
        commands_.pop();
      }
    }
    command();
  }
}
