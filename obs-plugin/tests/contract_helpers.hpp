#pragma once

// Shared failure helpers for obs-plugin behavioural tests (AGENTS.md: every
// media change must include behavioural tests). One definition; previously
// each test file duplicated its own check_at/check/require.
#include <cstdio>
#include <cstdlib>

namespace openstream_test {
inline void check_at(bool condition, int line) {
  if (!condition) {
    std::fprintf(stderr, "OpenStream test failed at line %d\n", line);
    std::fflush(stderr);
    std::abort();
  }
}

inline void require(bool ok, const char *message) {
  if (!ok) {
    std::fprintf(stderr, "%s\n", message);
    std::fflush(stderr);
    std::exit(1);
  }
}

inline void require(bool ok) {
  require(ok, "OpenStream test requirement failed");
}
}  // namespace openstream_test

#define check(condition) ::openstream_test::check_at((condition), __LINE__)
