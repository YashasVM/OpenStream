#include "media-clock.hpp"

#include <cassert>

int main() {
  MediaClock clock;
  assert(!clock.map(-1, 1'000'000'000).has_value());
  assert(clock.map(10'000'000, 1'000'000'000) == 1'000'000'000);
  assert(clock.map(30'000'000, 9'000'000'000) == 1'020'000'000);
  assert(clock.map(15'000'000, 20'000'000'000) == 1'005'000'000);
}
