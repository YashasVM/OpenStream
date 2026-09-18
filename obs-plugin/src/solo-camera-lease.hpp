#pragma once

#include <atomic>

// One active camera per OBS process. This is an ownership gate, not a queue:
// capacity 1, conflicting acquisitions are rejected and surfaced by the caller.
// Opaque owners are compared only; the lease never dereferences an OBS object.
class SoloCameraLease {
 public:
  bool acquire(const void *owner) {
    if (!owner) return false;
    const void *expected = nullptr;
    return owner_.compare_exchange_strong(expected, owner) || expected == owner;
  }

  void release(const void *owner) {
    if (!owner) return;
    owner_.compare_exchange_strong(owner, nullptr);
  }

 private:
  std::atomic<const void *> owner_{nullptr};
};
