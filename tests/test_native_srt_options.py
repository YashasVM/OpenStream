"""Compile the production SRT parser/options without the Android runtime."""
import shutil
import subprocess

import pytest

from _helpers import read_text


LEGACY_PARSER = r"""std::optional<SrtUrl> legacyParseSrtUrl(const std::string &url) {
  constexpr const char *scheme = "srt://";
  if (url.rfind(scheme, 0) != 0) {
    return std::nullopt;
  }

  const size_t authorityStart = std::strlen(scheme);
  const size_t queryStart = url.find('?', authorityStart);
  const std::string authority = url.substr(authorityStart, queryStart - authorityStart);
  const size_t colon = authority.rfind(':');
  if (colon == std::string::npos || colon == 0 || colon + 1 >= authority.size()) {
    return std::nullopt;
  }

  SrtUrl parsed;
  parsed.host = authority.substr(0, colon);
  parsed.port = authority.substr(colon + 1);
  if (parsed.host.size() >= 2 && parsed.host.front() == '[' && parsed.host.back() == ']') {
    parsed.host = parsed.host.substr(1, parsed.host.size() - 2);
  }
  unsigned int portNumber = 0;
  const auto portResult = std::from_chars(
      parsed.port.data(), parsed.port.data() + parsed.port.size(), portNumber);
  if (portResult.ec != std::errc{} ||
      portResult.ptr != parsed.port.data() + parsed.port.size() ||
      portNumber == 0 || portNumber > 65535) {
    return std::nullopt;
  }
  parsed.portNumber = static_cast<uint16_t>(portNumber);

  if (queryStart != std::string::npos) {
    const std::string query = url.substr(queryStart + 1);
    size_t offset = 0;
    while (offset < query.size()) {
      const size_t amp = query.find('&', offset);
      const std::string part = query.substr(offset, amp == std::string::npos ? std::string::npos : amp - offset);
      constexpr const char *latencyKey = "latency=";
      if (part.rfind(latencyKey, 0) == 0) {
        const std::string value = part.substr(std::strlen(latencyKey));
        int latency = 0;
        const auto latencyResult =
            std::from_chars(value.data(), value.data() + value.size(), latency);
        if (latencyResult.ec == std::errc{} &&
            latencyResult.ptr == value.data() + value.size()) {
          parsed.latencyMs = std::clamp(latency, kMinSrtLatencyMs, kMaxSrtLatencyMs);
        }
      }
      if (amp == std::string::npos) {
        break;
      }
      offset = amp + 1;
    }
  }

  return parsed;
}

"""


def test_native_irl_srt_options_and_legacy_obs_parser(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        pytest.skip("SRT option tests require a C++ compiler")
    source = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    parser = source[source.index("struct SrtUrl") : source.index("class NativeSender")]
    include = "android/app/src/main/cpp/third_party/srt/include"
    harness = r"""
#include <algorithm>
#include <cassert>
#include <charconv>
#include <cstdint>
#include <cstring>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>
#include <srt/srt.h>
#define OPENSTREAM_HAVE_LIBSRT 1
constexpr int kMinSrtLatencyMs = 80;
constexpr int kMaxSrtLatencyMs = 200;
std::map<SRT_SOCKOPT, std::vector<char>> applied;
SRT_SOCKOPT failing = SRTO_MSS;
extern "C" int srt_setsockopt(SRTSOCKET, int, SRT_SOCKOPT option, const void* data, int size) {
  if (option == failing) return SRT_ERROR;
  const auto* bytes = static_cast<const char*>(data);
  applied[option] = std::vector<char>(bytes, bytes + size);
  return 0;
}
""" + parser + LEGACY_PARSER + r"""
int integerOption(SRT_SOCKOPT option) {
  assert(applied.at(option).size() == sizeof(int));
  int value;
  std::memcpy(&value, applied.at(option).data(), sizeof value);
  return value;
}
std::string stringOption(SRT_SOCKOPT option) {
  return std::string(applied.at(option).begin(), applied.at(option).end());
}
void sameAsLegacy(const std::string& url) {
  auto actual = parseSrtUrl(url), expected = legacyParseSrtUrl(url);
  assert(actual.has_value() == expected.has_value());
  if (!actual) return;
  assert(actual->host == expected->host && actual->port == expected->port);
  assert(actual->portNumber == expected->portNumber && actual->latencyMs == expected->latencyMs);
  assert(!actual->irl && !actual->streamId && !actual->passphrase);
}
int main() {
  const std::string base = "srt://relay.example:9000?";
  const std::string query = "mode=caller&streamid=%23%21%3A%3Ar%3Dlive%2Fabc%26m%3Dpublish%2Ba%20b" 
      "&passphrase=secret%2B%26%3D%2520%20word&pbkeylen=32&latency=%32%30%30%30&irl=1";
  auto url = parseSrtUrl(base + query);
  assert(url && url->irl && url->latencyMs == 2000 && url->pbkeylen == 32);
  assert(*url->streamId == "#!::r=live/abc&m=publish+a b");
  assert(*url->passphrase == "secret+&=%20 word");
  assert(*percentDecode("a+b%2Bc%20d") == "a+b+c d");
  assert(*percentDecode("%e2%82%ac") == "\xe2\x82\xac");
  assert(!percentDecode("%") && !percentDecode("%0") && !percentDecode("%GG"));
  assert(!percentDecode("%00"));
  for (const auto& latency : std::vector<std::pair<std::string,int>>{
      {"-1",500},{"0",500},{"80",500},{"500",500},{"2000",2000},{"5000",5000},{"5001",5000}}) {
    assert(parseSrtUrl(base + "latency=" + latency.first + "&irl=1")->latencyMs == latency.second);
    assert(parseSrtUrl(base + "irl=1&latency=" + latency.first)->latencyMs == latency.second);
  }
  assert(parseSrtUrl(base + "irl=1")->latencyMs == 2000);
  assert(parseSrtUrl(base + "irl=%31")->irl);
  for (const auto& invalid : std::vector<std::string>{
      "mode=listener", "mode=rendezvous", "latency=", "latency=abc", "latency=12x", "latency=2147483648",
      "pbkeylen=0", "pbkeylen=17", "pbkeylen=abc", "passphrase=short", "passphrase=",
      "streamid=%00", "streamid=truncated%", "passphrase=123456789%00", "streamid=a&streamid=b",
      "latency=500&latency=600", "unknown=1", "irl=1", "irl=0"}) {
    assert(!parseSrtUrl(base + "irl=1&" + invalid));
  }
  assert(!parseSrtUrl(base + "irl=1&passphrase=" + std::string(80, 'a')));
  assert(!parseSrtUrl(base + "irl=1&streamid=" + std::string(513, 'a')));
  assert(parseSrtUrl(base + "irl=1&streamid=" + std::string(512, 'a')));
  for (int length : {10,79}) assert(parseSrtUrl(base + "irl=1&passphrase=" + std::string(length,'a')));
  for (int length : {16,24,32}) assert(parseSrtUrl(base + "irl=1&pbkeylen=" + std::to_string(length)));
  assert(parseSrtUrl("srt://[::1]:9000?irl=1")->host == "::1");
  for (const auto& invalid : {"srt://host", "srt://:9000?irl=1", "srt://host:0?irl=1", "srt://host:65536?irl=1"})
    assert(!parseSrtUrl(invalid));

  // Compare against the original production parser, including ignored credentials,
  // malformed options and repeated latency values. No IRL opt-in means OBS behavior.
  for (const std::string host : {"relay.example", "[::1]", "192.0.2.1"}) {
    for (const std::string port : {"1", "9999", "65535", "0", "65536", "bad"}) {
      for (const std::string query : {"", "?mode=listener", "?latency=0", "?latency=5000", "?latency=500&latency=bad",
          "?latency=bad&latency=100", "?latency=%32%30%30%30", "?streamid=%00&passphrase=short",
          "?irl=0&latency=5000", "?unknown=%GG&latency=95", "?latency=120&&latency=200"}) {
        sameAsLegacy("srt://" + host + ":" + port + query);
      }
    }
  }
  for (int latency = -100; latency <= 6000; ++latency)
    sameAsLegacy(base + "latency=" + std::to_string(latency));

  std::string error;
  assert(configureIrlSocket(1, *url, error));
  assert(error.empty());
  assert(integerOption(SRTO_LATENCY) == 2000 && integerOption(SRTO_PEERLATENCY) == 2000);
  assert(integerOption(SRTO_PBKEYLEN) == 32);
  assert(stringOption(SRTO_STREAMID) == *url->streamId);
  assert(stringOption(SRTO_PASSPHRASE) == *url->passphrase);
  assert(applied.size() == 12);
  const auto options = applied;
  for (const auto& [option, bytes] : options) {
    applied.clear();
    failing = option;
    assert(!configureIrlSocket(1, *url, error));
    assert(error.find("CONFIG:") == 0);
    if (option == SRTO_PBKEYLEN || option == SRTO_PASSPHRASE)
      assert(error == "CONFIG: SRT passphrase encryption is not supported by this build");
    assert(error.find(*url->streamId) == std::string::npos);
    assert(error.find(*url->passphrase) == std::string::npos);
  }
  failing = SRTO_MSS;
  applied.clear();
  assert(configureIrlSocket(1, *parseSrtUrl(base + "irl=1"), error));
  assert(!applied.count(SRTO_STREAMID) && !applied.count(SRTO_PASSPHRASE));
  for (int reason : std::vector<int>{SRT_REJ_BADSECRET, SRT_REJ_UNSECURE, SRT_REJ_PEER, 1401, 1403, 2001})
    assert(irlConnectFailure(reason).find("AUTH:") == 0);
  for (int reason : std::vector<int>{SRT_REJ_VERSION, SRT_REJ_MESSAGEAPI, SRT_REJ_CONGESTION, 1001, 1400, 1404, 1405})
    assert(irlConnectFailure(reason).find("CONFIG:") == 0);
  for (int reason : std::vector<int>{SRT_REJ_UNKNOWN, SRT_REJ_BACKLOG, SRT_REJ_TIMEOUT, 1402, 1500, 1503})
    assert(irlConnectFailure(reason).find("TRANSIENT:") == 0);
}
"""
    cpp = tmp_path / "srt_options_test.cpp"
    executable = tmp_path / "srt_options_test"
    cpp.write_text(harness)
    subprocess.run([compiler, "-std=c++20", "-O2", "-I", include, str(cpp), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=20)


def test_irl_clients_share_process_wide_native_generations():
    # SrtLiveTransport creates a fresh SrtStreamClient per (re)connect attempt,
    # while native rejects generations older than its current session.
    source = read_text("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")
    companion = source[source.index("companion object") :]
    assert "private val sessionGenerations = AtomicLong()" in companion
    assert "sessionGeneration.incrementAndGet()" not in source
    disconnect = source[source.index("fun disconnect()") : source.index("private fun connectToResolvedAddress")]
    guard = "if (nativeSessionOwner.compareAndSet(previous, generation))"
    assert guard in disconnect
    assert disconnect.index(guard) < disconnect.index("SrtNativeBridge.disconnect(generation)")
    native = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    irl_log = native[native.index("parsed && parsed->irl") :]
    assert "%s" not in irl_log[: irl_log.index("return JNI_TRUE")]
