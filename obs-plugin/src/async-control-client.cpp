#include "async-control-client.hpp"

AsyncControlClient::AsyncControlClient() : worker_(&AsyncControlClient::run, this) {}

AsyncControlClient::~AsyncControlClient() { stop(); }

void AsyncControlClient::post(std::function<void()> command) {
  if (!command) return;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return;
    commands_.push_back({{}, std::move(command)});
  }
  wake_.notify_one();
}

void AsyncControlClient::postLatest(std::string key,
                                    std::function<void()> command) {
  if (key.empty() || !command) return;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return;
    for (auto pending = commands_.begin(); pending != commands_.end(); ++pending) {
      if (pending->key == key) {
        commands_.erase(pending);
        break;
      }
    }
    commands_.push_back({std::move(key), std::move(command)});
  }
  wake_.notify_one();
}

void AsyncControlClient::stop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return;
    stopping_ = true;
    // Commands contain network targets, never source pointers. Dropping pending
    // work makes source destruction bounded by at most the in-flight timeout.
    commands_.clear();
  }
  wake_.notify_one();
  if (worker_.joinable()) worker_.join();
}

void AsyncControlClient::run() {
  for (;;) {
    std::function<void()> command;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      wake_.wait(lock, [this] { return stopping_ || !commands_.empty(); });
      if (stopping_) return;
      command = std::move(commands_.front().command);
      commands_.pop_front();
    }
    command();
  }
}
