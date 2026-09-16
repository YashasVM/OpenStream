#pragma once

// POSIX SIGPIPE hardening for the camera-control TCP channel.
//
// Background: send_control_command() writes a short HTTP request to the
// phone with send(..., 0). When the phone has already closed the connection
// (crashed app, Wi-Fi drop, stale reservation), a write to a stream socket
// whose peer has disconnected raises SIGPIPE. OBS runs with the default
// SIGPIPE disposition, so the signal terminates the whole OBS process —
// taking every other camera down with it (AGENTS.md rule 8).
//
// Fix (two layers, both production paths use them):
// - Per-send: openstream_socket_send_flags() returns MSG_NOSIGNAL where the
//   platform defines it (Linux). The flag suppresses SIGPIPE for that send()
//   call only; the call instead fails with -1/EPIPE, which the existing
//   send loop already treats as "command failed" and logs.
// - Per-socket: openstream_suppress_send_sigpipe() sets SO_NOSIGPIPE where
//   the platform defines it (macOS/*BSD, which lack MSG_NOSIGNAL). On Linux
//   and Windows it is a no-op.
//
// This header is intentionally dependency-free (no libobs, no Qt) so the
// POSIX behaviour can be covered by obs-plugin/tests/test_sigpipe.cpp
// without linking the plugin.

#ifdef _WIN32
inline int openstream_socket_send_flags() {
  return 0;
}

template <typename SocketHandleT>
inline void openstream_suppress_send_sigpipe(SocketHandleT) {}
#else
#include <sys/socket.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

inline int openstream_socket_send_flags() {
#ifdef MSG_NOSIGNAL
  return MSG_NOSIGNAL;
#else
  return 0;
#endif
}

template <typename SocketHandleT>
inline void openstream_suppress_send_sigpipe(SocketHandleT socket) {
#ifdef SO_NOSIGPIPE
  int opt = 1;
  setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &opt, sizeof(opt));
#else
  (void)socket;
#endif
}
#endif
