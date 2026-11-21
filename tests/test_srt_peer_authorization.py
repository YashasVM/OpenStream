"""Exercise the Android sender's real accept loop against localhost libsrt peers."""
import shlex
import shutil
import socket
import subprocess

import pytest

from _helpers import read_text


def test_listener_rejects_unreserved_peer_and_accepts_controller(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    pkg_config = shutil.which("pkg-config")
    if not compiler or not pkg_config:
        pytest.skip("requires C++ compiler and desktop libsrt")
    flags = subprocess.run([pkg_config, "--cflags", "--libs", "srt"], capture_output=True, text=True)
    if flags.returncode:
        pytest.skip("requires desktop libsrt development package")
    source = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    production = source[source.index("#include <algorithm>"):source.index("struct StreamState")]
    call = 'sender.listen(url, 1, [&](const std::string &peer) { return reserved.load() && peer == "127.0.0.2"; })'
    harness = r"""
#include <chrono>
#include <future>
#include <cstdio>
#define OPENSTREAM_HAVE_LIBSRT 1
#define ANDROID_LOG_INFO 0
#define ANDROID_LOG_ERROR 1
#define ANDROID_LOG_WARN 2
int __android_log_print(int, const char*, const char*, ...) { return 0; }
""" + production + r"""
} // namespace
int main(int argc, char **argv) {
  using namespace std::chrono_literals;
  NativeSender sender;
  sender.initializeRuntime();
  sender.advanceLifecycleGeneration(1);
  const int port = std::stoi(argv[1]);
  const std::string url = "srt://127.0.0.1:" + std::to_string(port) + "?mode=listener";
  std::atomic<bool> reserved{false};
  auto listening = std::async(std::launch::async, [&] { return LISTEN_CALL; });
  auto connectPeer = [&](const char *ip) {
    for (int attempt = 0; attempt < 20; ++attempt) {
      SRTSOCKET client = srt_create_socket();
      int timeout = 200, receiver = 0;
      srt_setsockopt(client, 0, SRTO_CONNTIMEO, &timeout, sizeof timeout);
      srt_setsockopt(client, 0, SRTO_SENDER, &receiver, sizeof receiver);
      sockaddr_in local{}; local.sin_family = AF_INET;
      inet_pton(AF_INET, ip, &local.sin_addr);
      if (srt_bind(client, reinterpret_cast<sockaddr*>(&local), sizeof local) == SRT_ERROR) std::abort();
      sockaddr_in remote{}; remote.sin_family = AF_INET; remote.sin_port = htons(port);
      inet_pton(AF_INET, "127.0.0.1", &remote.sin_addr);
      if (srt_connect(client, reinterpret_cast<sockaddr*>(&remote), sizeof remote) != SRT_ERROR) return client;
      srt_close(client);
      std::this_thread::sleep_for(20ms);
    }
    std::abort();
  };
  auto unreserved = connectPeer("127.0.0.2");
  if (listening.wait_for(300ms) != std::future_status::timeout) std::abort();
  srt_close(unreserved);
  reserved = true;
  auto stranger = connectPeer("127.0.0.1");
  if (listening.wait_for(300ms) != std::future_status::timeout) {
    fprintf(stderr, "Unauthorised peer was accepted as the media receiver\n");
    std::abort();
  }
  srt_close(stranger);
  auto controller = connectPeer("127.0.0.2");
  if (listening.wait_for(2s) != std::future_status::ready || !listening.get()) std::abort();
  if (!sender.send(std::vector<uint8_t>(188, 0x47))) std::abort();
  sender.disconnect(); srt_close(controller);
}
""".replace("LISTEN_CALL", call)
    cpp = tmp_path / "peer_test.cpp"
    executable = tmp_path / "peer_test"
    cpp.write_text(harness)
    subprocess.run([compiler, "-std=c++20", "-pthread", str(cpp), "-o", str(executable), *shlex.split(flags.stdout)], check=True)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    subprocess.run([str(executable), str(port)], check=True, timeout=15)
