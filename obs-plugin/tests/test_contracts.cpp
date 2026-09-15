#include "../src/async-control-client.hpp"
#include "../src/media-clock.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <future>

namespace {
void check_at(bool condition, int line) {
  if (!condition) {
    std::fprintf(stderr, "OpenStream contract failed at line %d\n", line);
    std::fflush(stderr);
    std::abort();
  }
}
}  // namespace

#define check(condition) check_at((condition), __LINE__)

int main() {
  {
    MediaClock clock;
    const uint64_t origin = 10'000'000'000ULL;
    check(clock.map(1'000'000, origin).value() == origin);
    check(clock.map(1'033'333, origin + 33'333).value() == origin + 33'333);
    check(!clock.map(-1, origin).has_value());
  }

  {
    AsyncControlClient client;
    std::promise<void> started;
    std::promise<void> release;
    const auto release_signal = release.get_future().share();
    check(client.post([&] {
      started.set_value();
      release_signal.wait();
    }));
    started.get_future().wait();

    for (int i = 0; i < 16; ++i) {
      check(client.post([] {}));
    }
    check(!client.post([] {}));
    release.set_value();

    int urgent_runs = 0;
    check(client.post_urgent([&] {
      ++urgent_runs;
      return true;
    }));
    check(client.post_urgent([&] {
      ++urgent_runs;
      return true;
    }));
    client.stop();
    // stop() retains the newest pending urgent release. If the worker already
    // dequeued the first release before stop acquired the lock, that in-flight
    // command also completes; pending work must never exceed those two calls.
    check(urgent_runs >= 1 && urgent_runs <= 2);
    check(!client.post([] {}));
  }

  {
    // Prove the normal three-attempt policy without racing stop() against the
    // worker. Teardown has separate semantics and is tested below.
    AsyncControlClient client;
    std::atomic<int> attempts{0};
    std::promise<void> completed;
    check(client.post_urgent([&] {
      const int current = ++attempts;
      if (current == 3) {
        completed.set_value();
        return true;
      }
      return false;
    }));
    completed.get_future().wait();
    client.stop();
    check(attempts.load() == 3);
  }

  {
    // Once destruction starts, retry backoff must wake immediately instead of
    // draining the normal three-attempt lifecycle-critical release policy.
    AsyncControlClient client;
    std::atomic<int> attempts{0};
    std::promise<void> first_attempt;
    check(client.post_urgent([&] {
      const int current = ++attempts;
      if (current == 1) first_attempt.set_value();
      return false;
    }));
    first_attempt.get_future().wait();
    client.stop();
    check(attempts.load() == 1);
  }

  {
    // Urgent queue is bounded (kUrgentCapacity=4, drop-newest). Fill it while
    // the worker is blocked on a normal command; the 5th urgent must be
    // rejected so a disconnected phone cannot build an unbounded backlog.
    AsyncControlClient client;
    std::promise<void> started;
    std::promise<void> release;
    const auto release_signal = release.get_future().share();
    check(client.post([&] {
      started.set_value();
      release_signal.wait();
    }));
    started.get_future().wait();

    std::atomic<int> urgent_runs{0};
    std::promise<void> last_urgent_done;
    for (int i = 0; i < 4; ++i) {
      const bool is_last = (i == 3);
      check(client.post_urgent([&, is_last] {
        ++urgent_runs;
        if (is_last) last_urgent_done.set_value();
        return true;
      }));
    }
    check(!client.post_urgent([&] {
      ++urgent_runs;
      return true;
    }));
    release.set_value();
    last_urgent_done.get_future().wait();
    client.stop();
    check(urgent_runs.load() == 4);
  }

  {
    // Queue overflow policy: both queues are bounded drop-newest (reject the
    // newcomer, caller logs the warning). Normal capacity is 16, urgent is 4.
    AsyncControlClient client;
    std::promise<void> started;
    std::promise<void> release;
    const auto release_signal = release.get_future().share();
    check(client.post([&] {
      started.set_value();
      release_signal.wait();
    }));
    started.get_future().wait();
    for (int i = 0; i < 16; ++i) {
      check(client.post([] {}));
    }
    check(!client.post([] {}));
    for (int i = 0; i < 4; ++i) {
      check(client.post_urgent([] { return true; }));
    }
    check(!client.post_urgent([] { return true; }));
    release.set_value();
    client.stop();
    check(!client.post([] {}));
    check(!client.post_urgent([] { return true; }));
  }

  {
    // MediaClock discontinuity: a >2 s jump re-anchors the origin and counts
    // a surfaced gap instead of teleporting OBS timestamps forever.
    MediaClock clock;
    const uint64_t origin = 10'000'000'000ULL;
    check(clock.map(1'000'000, origin).value() == origin);
    check(clock.map(1'033'333, origin + 33'333).value() == origin + 33'333);
    check(clock.gap_count() == 0);

    bool reanchored = false;
    const auto jumped =
        clock.map(1'000'000 + 5'000'000'000LL, origin + 66'666, &reanchored);
    check(reanchored);
    check(jumped.has_value() && jumped.value() == origin + 66'666);
    check(clock.gap_count() == 1);

    bool steady = false;
    check(clock.map(1'000'000 + 5'000'000'000LL + 33'333, origin + 99'999, &steady).value() ==
          origin + 99'999);
    check(!steady);
    check(clock.gap_count() == 1);

    bool rewound = false;
    const auto back = clock.map(1'000'000, origin + 200'000, &rewound);
    check(rewound);
    check(back.has_value() && back.value() == origin + 200'000);
    check(clock.gap_count() == 2);
  }

  {
    // Audio and video retain their source-clock offset even when their first
    // decoded frames arrive at different times.
    MediaClock clock;
    const uint64_t obs_origin = 10'000'000'000ULL;
    check(clock.map(1'000'000'000LL, obs_origin, nullptr,
                    MediaClock::Stream::Video).value() == obs_origin);
    check(clock.map(980'000'000LL, obs_origin + 10'000'000ULL, nullptr,
                    MediaClock::Stream::Audio).value() ==
          obs_origin - 20'000'000ULL);
  }

  {
    // A real-time multi-second source gap must be surfaced even when the OBS
    // arrival clock advances by the same amount. Its mapped timestamp remains
    // source-derived rather than being replaced with arrival time.
    MediaClock clock;
    const uint64_t obs_origin = 20'000'000'000ULL;
    check(clock.map(1'000'000'000LL, obs_origin, nullptr,
                    MediaClock::Stream::Video).value() == obs_origin);
    bool discontinuity = false;
    const auto after_gap = clock.map(6'000'000'000LL,
                                     obs_origin + 5'000'000'000ULL,
                                     &discontinuity,
                                     MediaClock::Stream::Video);
    check(discontinuity);
    check(after_gap.value() == obs_origin + 5'000'000'000ULL);
    check(clock.gap_count() == 1);
  }
}
