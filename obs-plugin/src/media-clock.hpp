#pragma once

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
// - If the mapped timestamp jumps more than kMaxJumpNs (2 s) away from the
//   OBS arrival clock, the origin is re-anchored to (source_ns, obs_now_ns)
//   and map() returns obs_now_ns. The caller must blog(LOG_WARNING) the gap
//   (see decode path: "MediaClock discontinuity ... media gap surfaced").
// - Re-anchoring preserves subsequent source offsets instead of freezing a
//   stale origin forever. Stale/backlog drops remain the caller's job.
// - This header stays OBS-free so obs-plugin/tests/test_contracts.cpp can
//   build without libobs; logging lives with the caller. gap_count() and
//   last_gap_ns() expose the event for tests and telemetry.
// - Prefer one MediaClock per stream (video_clock/audio_clock). Sharing a
//   single clock across audio+video would let one stream's jump re-anchor the
//   other's timeline.
// TODO(per-stream-split): if a shared clock ever returns, route video and
// audio through separate instances; see decode_packets video_clock/audio_clock.
class MediaClock {
 public:
  static constexpr int64_t kMaxJumpNs = 2'000'000'000LL;

  std::optional<uint64_t> map(int64_t source_ns, uint64_t obs_now_ns,
                              bool *reanchored_out = nullptr) {
    if (reanchored_out) *reanchored_out = false;
    if (source_ns < 0) return std::nullopt;
    if (!source_origin_ns_) {
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
      last_source_ns_ = source_ns;
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
    const int64_t gap_vs_now = (mapped_ns >= obs_now_ns)
                                   ? static_cast<int64_t>(mapped_ns - obs_now_ns)
                                   : -static_cast<int64_t>(obs_now_ns - mapped_ns);
    const uint64_t abs_gap = gap_vs_now >= 0
                                 ? static_cast<uint64_t>(gap_vs_now)
                                 : static_cast<uint64_t>(-(gap_vs_now + 1)) + 1u;
    // Re-anchor on large forward jumps (mapped far ahead of arrival) and on
    // large source-clock rewinds (delta < 0 with magnitude > threshold).
    // Backward mapped-behind-now is the normal stale/backlog case and is not
    // re-anchored here so the caller's stale-frame drop still surfaces it.
    const bool source_rewound = (delta < 0) &&
                                (static_cast<uint64_t>(-(delta + 1)) + 1u >
                                 static_cast<uint64_t>(kMaxJumpNs));
    const bool mapped_ahead = (gap_vs_now > kMaxJumpNs);
    if (source_rewound || mapped_ahead) {
      ++gap_count_;
      last_gap_ns_ = gap_vs_now;
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
      last_source_ns_ = source_ns;
      if (reanchored_out) *reanchored_out = true;
      return obs_now_ns;
    }

    last_source_ns_ = source_ns;
    return mapped;
  }

  uint64_t gap_count() const { return gap_count_; }
  int64_t last_gap_ns() const { return last_gap_ns_; }

 private:
  std::optional<int64_t> source_origin_ns_;
  uint64_t obs_origin_ns_ = 0;
  int64_t last_source_ns_ = 0;
  uint64_t gap_count_ = 0;
  int64_t last_gap_ns_ = 0;
};
