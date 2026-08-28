#include "../src/async-control-client.hpp"
#include "../src/media-clock.hpp"

#include <cstdlib>
#include <future>

namespace {
void check(bool condition) {
  if (!condition) std::abort();
}
}  // namespace

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
    check(urgent_runs == 2);
    check(!client.post([] {}));
  }

  {
    AsyncControlClient client;
    int attempts = 0;
    check(client.post_urgent([&] {
      ++attempts;
      return attempts >= 3;
    }));
    client.stop();
    check(attempts == 3);
  }
}
