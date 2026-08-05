#pragma once

#include <cstdint>
#include <optional>

// Maps the phone's shared MPEG-TS clock into OBS's monotonic clock while
// retaining the original audio/video offset. One instance belongs to one
// receiver session, so reconnects cannot leak timing state across cameras.
class MediaClock {
 public:
  std::optional<uint64_t> map(int64_t source_ns, uint64_t obs_now_ns) {
    if (source_ns < 0) return std::nullopt;
    if (!source_origin_ns_) {
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
    }

    const int64_t delta = source_ns - *source_origin_ns_;
    if (delta < 0 && static_cast<uint64_t>(-delta) > obs_origin_ns_) {
      return std::nullopt;
    }
    return delta < 0 ? obs_origin_ns_ - static_cast<uint64_t>(-delta)
                     : obs_origin_ns_ + static_cast<uint64_t>(delta);
  }

 private:
  std::optional<int64_t> source_origin_ns_;
  uint64_t obs_origin_ns_ = 0;
};
