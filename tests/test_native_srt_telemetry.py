"""Exercise the production sender with real local SRT sockets, when libsrt is installed."""
import shutil
import subprocess

import pytest

from _helpers import read_text


def test_native_sender_reports_real_telemetry_and_verifies_bound_address(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    pkg_config = shutil.which("pkg-config")
    if not compiler or not pkg_config:
        pytest.skip("native transport check requires a compiler and pkg-config")
    flags = subprocess.run([pkg_config, "--cflags", "--libs", "srt"], capture_output=True, text=True)
    if flags.returncode:
        pytest.skip("native transport check requires host libsrt development files")
    production = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    sender = production[production.index("struct SrtUrl") : production.index("struct StreamState")]
    harness = r'''
#include <srt/srt.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <net/if.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <charconv>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>
#define OPENSTREAM_HAVE_LIBSRT 1
using jlong = int64_t;
#define ANDROID_LOG_INFO 1
#define ANDROID_LOG_WARN 2
#define ANDROID_LOG_ERROR 3
int __android_log_print(int, const char*, const char*, ...) { return 0; }
void logInfo(const char*) {}
void logError(const char*) {}
constexpr const char* kTag = "test";
constexpr int kMinSrtLatencyMs = 80, kMaxSrtLatencyMs = 200, kListenerAcceptPollMs = 100;
''' + sender + r'''
int main() {
  NativeSender sender;
  sender.initializeRuntime();
  const auto listener = srt_create_socket();
  assert(listener != SRT_INVALID_SOCK);
  sockaddr_in address{};
  address.sin_family = AF_INET;
  assert(inet_pton(AF_INET, "127.0.0.1", &address.sin_addr) == 1);
  assert(srt_bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0);
  int length = sizeof(address);
  assert(srt_getsockname(listener, reinterpret_cast<sockaddr*>(&address), &length) == 0);
  assert(srt_listen(listener, 1) == 0);
  sender.advanceLifecycleGeneration(1);
  const auto url = "srt://127.0.0.1:" + std::to_string(ntohs(address.sin_port));
  assert(sender.connect(url + "?localaddr=127.0.0.1&localif=lo&peeraddr=127.0.0.1", 1));
  assert(sender.verifyLocalAddress("127.0.0.1"));
  assert(!sender.verifyLocalAddress("127.0.0.2"));
  sockaddr_storage peer{};
  int peerLength = sizeof(peer);
  const auto receiver = srt_accept(listener, reinterpret_cast<sockaddr*>(&peer), &peerLength);
  assert(receiver != SRT_INVALID_SOCK);
  int timeout = 2000;
  assert(srt_setsockopt(receiver, 0, SRTO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
  const std::vector<uint8_t> payload(1316, 0x47);
  assert(sender.send(payload));
  std::array<char, 1500> received{};
  assert(srt_recvmsg(receiver, received.data(), received.size()) == 1316);
  assert(std::all_of(received.begin(), received.begin() + 1316,
                     [](char byte) { return byte == 0x47; }));
  const auto stats = sender.telemetry();
  assert(stats && (*stats)[0] >= 1 && (*stats)[1] >= 0 && (*stats)[2] >= 0);
  assert((*stats)[3] >= 0 && (*stats)[4] >= 0 && (*stats)[5] >= 0);
  sender.disconnect();
  assert(!sender.telemetry());
  srt_close(receiver);
  // The same live loopback receiver must be unreachable when output is pinned
  // to another NIC; a source-address-only bind would silently reach it.
  struct if_nameindex *interfaces = if_nameindex();
  assert(interfaces);
  for (auto *entry = interfaces; entry->if_index; ++entry) {
    if (std::string(entry->if_name) != "lo") {
      sender.advanceLifecycleGeneration(2);
      assert(!sender.connect(url + "?localaddr=127.0.0.1&localif=" + entry->if_name +
                             "&peeraddr=127.0.0.1", 2));
      assert(!sender.telemetry());
      break;
    }
  }
  if_freenameindex(interfaces);
  srt_close(listener);
  // A local address not assigned to this machine must fail instead of using another route.
  sender.advanceLifecycleGeneration(2);
  assert(!sender.connect(url + "?localaddr=203.0.113.55", 2));
  assert(!sender.connect(url + "?localaddr=127.0.0.1&localif=not-a-device&peeraddr=127.0.0.1", 2));
  assert(!sender.telemetry());
  assert(!sender.connect(url + "?localaddr=127.0.0.1&localif=lo&peeraddr=127.0.0.2", 2));
  assert(!sender.telemetry());
  assert(!sender.connect(url + "?localaddr=127.0.0.1&peeraddr=127.0.0.1", 2));
  assert(!sender.connect(url + "?localaddr=127.0.0.1&localif=lo", 2));
}
'''
    source = tmp_path / "sender.cpp"
    binary = tmp_path / "sender"
    source.write_text(harness)
    subprocess.run([compiler, "-std=c++20", "-pthread", str(source), "-o", str(binary),
                    *flags.stdout.split()], check=True, capture_output=True)
    subprocess.run([str(binary)], check=True, capture_output=True, timeout=15)
