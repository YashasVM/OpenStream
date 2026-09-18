// Behavioural coverage for the POSIX SIGPIPE hardening in
// obs-plugin/src/socket-send.hpp, used by send_control_command() in
// obs-plugin/src/openstream-source.cpp.
//
// Production failure: the phone closes its control connection (app kill,
// Wi-Fi drop, stale reservation) and OBS then send()s the next camera
// command. With flags 0 and the default SIGPIPE disposition, the kernel
// delivers SIGPIPE and the whole OBS process dies — taking every other
// camera down with it (AGENTS.md rule 8).
//
// The dangerous sends run in forked children so a regression kills only the
// child (observed via waitpid) instead of the test runner. The parent
// asserts:
//   1. (premise) send(..., 0) to a disconnected peer with SIG_DFL really
//      terminates the child with SIGPIPE — i.e. this test reproduces the
//      production crash it guards against;
//   2. (fix) send(..., openstream_socket_send_flags()) under the same
//      conditions survives and fails with EPIPE, which the existing send
//      loop in send_control_command() already turns into "command failed".
// POSIX-only; on Windows the flags helper is defined as 0 and main() only
// checks that contract.

#include "../src/socket-send.hpp"

#include <cstdio>
#include <cstdlib>

namespace {
void check_at(bool condition, int line) {
  if (!condition) {
    std::fprintf(stderr, "OpenStream SIGPIPE test failed at line %d\n", line);
    std::fflush(stderr);
    std::abort();
  }
}
}  // namespace

#define check(condition) check_at((condition), __LINE__)

#ifdef _WIN32
int main() {
  check(openstream_socket_send_flags() == 0);
  std::fprintf(stderr, "OpenStream SIGPIPE test: Windows flags contract ok\n");
  return 0;
}
#else

#include <cerrno>
#include <cstring>
#include <signal.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {
// A disconnected stream peer with a small send buffer: the first write may
// be buffered, but the buffer fills after a few writes and the next write
// triggers EPIPE/SIGPIPE deterministically.
int make_disconnected_sender() {
  int pair[2] = {-1, -1};
  check(::socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
  // Close the "phone" end with unread data pending so the next writes fail
  // instead of blocking forever.
  check(::close(pair[1]) == 0);
  const int small_buffer = 4096;
  check(::setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &small_buffer,
                     sizeof(small_buffer)) == 0);
  // Prove the per-socket helper is safe to call on a live stream socket
  // (SO_NOSIGPIPE where defined, no-op on Linux).
  openstream_suppress_send_sigpipe(pair[0]);
  return pair[0];
}

constexpr int kPayloadBytes = 4096;
constexpr int kMaxWrites = 100000;

void child_send_with_flags_zero() {
  signal(SIGPIPE, SIG_DFL);
  const int sender = make_disconnected_sender();
  char payload[kPayloadBytes];
  std::memset(payload, 'x', sizeof(payload));
  for (int i = 0; i < kMaxWrites; ++i) {
    const ssize_t sent =
        ::send(sender, payload, sizeof(payload), 0);
    if (sent <= 0) {
      // Survived without SIGPIPE: premise broken, exit loudly.
      _exit(42);
    }
  }
  _exit(43);
}

void child_send_with_production_flags() {
  signal(SIGPIPE, SIG_DFL);
  const int sender = make_disconnected_sender();
  const int flags = openstream_socket_send_flags();
  check(flags == MSG_NOSIGNAL);
  char payload[kPayloadBytes];
  std::memset(payload, 'x', sizeof(payload));
  for (int i = 0; i < kMaxWrites; ++i) {
    const ssize_t sent = ::send(sender, payload, sizeof(payload), flags);
    if (sent <= 0) {
      check(sent == -1);
      check(errno == EPIPE);
      _exit(0);
    }
  }
  // Never saw EPIPE: the suppression path did not behave like production.
  _exit(44);
}

int wait_for_child(pid_t child) {
  int status = 0;
  check(::waitpid(child, &status, 0) == child);
  return status;
}
}  // namespace

int main() {
  // Do not let a stray SIGPIPE in the parent mask a broken child setup.
  signal(SIGPIPE, SIG_DFL);

  {
    pid_t child = ::fork();
    check(child >= 0);
    if (child == 0) child_send_with_flags_zero();
    const int status = wait_for_child(child);
    check(WIFSIGNALED(status) && WTERMSIG(status) == SIGPIPE);
    std::fprintf(stderr,
                 "OpenStream SIGPIPE test: flags-0 send to disconnected peer "
                 "kills with SIGPIPE (production premise reproduced)\n");
  }

  {
    pid_t child = ::fork();
    check(child >= 0);
    if (child == 0) child_send_with_production_flags();
    const int status = wait_for_child(child);
    check(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    std::fprintf(stderr,
                 "OpenStream SIGPIPE test: production send flags survive with "
                 "EPIPE (no process death)\n");
  }

  return 0;
}
#endif
