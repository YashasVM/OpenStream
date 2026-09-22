#pragma once

#include <cstddef>
#include <mutex>
#include <string>
#include <unordered_map>

// Process-local optimistic ownership for discovered phones.  The Android
// /reserve endpoint remains the authoritative arbiter; this bounded map only
// prevents two local OBS sources from racing the same phone and makes release
// owner-specific.  Overflow rejects a new phone rather than growing without
// bound.
class PhoneOwnershipRegistry {
 public:
  static constexpr std::size_t kCapacity = 64;

  bool acquire(const std::string &phone_id, const std::string &source_id) {
    if (phone_id.empty() || source_id.empty()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = owners_.find(phone_id);
    if (found != owners_.end()) return found->second == source_id;
    if (owners_.size() >= kCapacity) return false;
    owners_.emplace(phone_id, source_id);
    return true;
  }

  void release(const std::string &phone_id, const std::string &source_id) {
    if (phone_id.empty() || source_id.empty()) return;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = owners_.find(phone_id);
    if (found != owners_.end() && found->second == source_id) owners_.erase(found);
  }

 private:
  std::mutex mutex_;
  std::unordered_map<std::string, std::string> owners_;
};
