#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include <optional>

// Maps the phone's shared media clock into OBS's monotonic clock. The mapping
// preserves the audio/video offset carried by MPEG-TS instead of replacing it
// with the time at which a frame happened to arrive on the receiver thread.
// One instance belongs to one receiver session, so reconnects start cleanly.
//
// Discontinuity policy (AGENTS.md: every recording gap must be surfaced and
// logged; never replace source timestamps with arrival time):
// - If the mapped timestamp jumps more than kMaxJumpNs (2 s) ahead of the OBS
//   arrival clock, or a stream rewinds by that amount, the origin is
//   re-anchored to (source_ns, obs_now_ns). Forward source gaps are surfaced
//   but retain their source-derived mapping. The caller logs every event.
// - Re-anchoring preserves subsequent source offsets instead of freezing a
//   stale origin forever. Stale/backlog drops remain the caller's job.
// - This header stays OBS-free so obs-plugin/tests/test_contracts.cpp can
//   build without libobs; logging lives with the caller. gap_count() and
//   last_gap_ns() expose the event for tests and telemetry.
// - One shared origin preserves the A/V offset carried by MPEG-TS. Per-stream
//   observations detect gaps without replacing that common epoch.
class MediaClock {
 public:
  static constexpr int64_t kMaxJumpNs = 2'000'000'000LL;
  enum class Stream : std::size_t { Generic = 0, Video = 1, Audio = 2 };

  std::optional<uint64_t> map(int64_t source_ns, uint64_t obs_now_ns,
                              bool *discontinuity_out = nullptr,
                              Stream stream = Stream::Generic) {
    if (discontinuity_out) *discontinuity_out = false;
    if (source_ns < 0) return std::nullopt;
    const std::size_t stream_index = static_cast<std::size_t>(stream);
    if (!source_origin_ns_) {
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
      last_source_ns_[stream_index] = source_ns;
      return obs_now_ns;
    }

    const int64_t delta = source_ns - *source_origin_ns_;
    std::optional<uint64_t> mapped;
    if (delta < 0) {
      const uint64_t magnitude =
          static_cast<uint64_t>(-(delta + 1)) + 1u;
      if (magnitude > obs_origin_ns_) return std::nullopt;
      mapped = obs_origin_ns_ - magnitude;
    } else {
      const uint64_t offset = static_cast<uint64_t>(delta);
      if (offset > (std::numeric_limits<uint64_t>::max)() - obs_origin_ns_) {
        return std::nullopt;
      }
      mapped = obs_origin_ns_ + offset;
    }

    // Discontinuity detector: compare the mapped timestamp against the OBS
    // arrival clock. A large divergence means the phone clock restarted,
    // the encoder re-based timestamps, or a multi-second gap occurred.
    // Stale (mapped far behind now) is left to the caller's backlog drop;
    // only re-anchor when the jump would otherwise freeze or teleport the
    // timeline by more than kMaxJumpNs.
    const uint64_t mapped_ns = *mapped;
    // Re-anchor on large forward jumps (mapped far ahead of arrival) and on
    // large source-clock rewinds (delta < 0 with magnitude > threshold).
    // Backward mapped-behind-now is the normal stale/backlog case and is not
    // re-anchored here so the caller's stale-frame drop still surfaces it.
    bool source_rewound = false;
    const bool mapped_ahead = mapped_ns > obs_now_ns &&
                              mapped_ns - obs_now_ns >
                                  static_cast<uint64_t>(kMaxJumpNs);
    bool source_gap = false;
    if (last_source_ns_[stream_index].has_value()) {
      const int64_t last = *last_source_ns_[stream_index];
      source_gap = source_ns > last &&
                   static_cast<uint64_t>(source_ns) -
                           static_cast<uint64_t>(last) >
                       static_cast<uint64_t>(kMaxJumpNs);
      source_rewound = source_ns < last &&
                       static_cast<uint64_t>(last) -
                               static_cast<uint64_t>(source_ns) >
                           static_cast<uint64_t>(kMaxJumpNs);
    }
    if (source_rewound || mapped_ahead || source_gap) {
      ++gap_count_;
      if (source_gap && last_source_ns_[stream_index].has_value()) {
        last_gap_ns_ = source_ns - *last_source_ns_[stream_index];
      } else if (mapped_ns >= obs_now_ns) {
        const uint64_t difference = mapped_ns - obs_now_ns;
        last_gap_ns_ = difference > static_cast<uint64_t>((std::numeric_limits<int64_t>::max)())
                           ? (std::numeric_limits<int64_t>::max)()
                           : static_cast<int64_t>(difference);
      } else {
        const uint64_t difference = obs_now_ns - mapped_ns;
        last_gap_ns_ = difference > static_cast<uint64_t>((std::numeric_limits<int64_t>::max)())
                           ? (std::numeric_limits<int64_t>::min)()
                           : -static_cast<int64_t>(difference);
      }
      if (discontinuity_out) *discontinuity_out = true;

      // A rewind or timestamp that would land far in the future indicates an
      // epoch reset. A real-time forward gap is already correctly represented
      // by the source timestamp, so surface it without replacing the epoch.
      if (source_rewound || mapped_ahead) {
        source_origin_ns_ = source_ns;
        obs_origin_ns_ = obs_now_ns;
        last_source_ns_.fill(std::nullopt);
        last_source_ns_[stream_index] = source_ns;
        return obs_now_ns;
      }
    }

    last_source_ns_[stream_index] = source_ns;
    return mapped;
  }

  uint64_t gap_count() const { return gap_count_; }
  int64_t last_gap_ns() const { return last_gap_ns_; }

 private:
  std::optional<int64_t> source_origin_ns_;
  uint64_t obs_origin_ns_ = 0;
  std::array<std::optional<int64_t>, 3> last_source_ns_{};
  uint64_t gap_count_ = 0;
  int64_t last_gap_ns_ = 0;
};
