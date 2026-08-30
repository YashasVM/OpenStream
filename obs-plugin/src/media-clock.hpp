#pragma once

#include <cstdint>
#include <limits>
#include <optional>

// Maps the phone's shared media clock into OBS's monotonic clock. The mapping
// preserves the audio/video offset carried by MPEG-TS instead of replacing it
// with the time at which a frame happened to arrive on the receiver thread.
// One instance belongs to one receiver session, so reconnects start cleanly.
class MediaClock {
 public:
  std::optional<uint64_t> map(int64_t source_ns, uint64_t obs_now_ns) {
    if (source_ns < 0) return std::nullopt;
    if (!source_origin_ns_) {
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
    }

    const int64_t delta = source_ns - *source_origin_ns_;
    if (delta < 0) {
      const uint64_t magnitude =
          static_cast<uint64_t>(-(delta + 1)) + 1u;
      if (magnitude > obs_origin_ns_) return std::nullopt;
      return obs_origin_ns_ - magnitude;
    }

    const uint64_t offset = static_cast<uint64_t>(delta);
    if (offset > (std::numeric_limits<uint64_t>::max)() - obs_origin_ns_) {
      return std::nullopt;
    }
    return obs_origin_ns_ + offset;
  }

 private:
  std::optional<int64_t> source_origin_ns_;
  uint64_t obs_origin_ns_ = 0;
};
