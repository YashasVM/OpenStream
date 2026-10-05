"""Run the production C++ normalizer and muxer without an Android runtime."""
import shutil
import subprocess

import pytest

from _helpers import read_text


def test_native_video_normalization_preserves_payload_and_storage(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        pytest.skip("native video test requires a C++ compiler")
    source = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    media = source[source.index("enum class VideoCodec") : source.index("struct SrtUrl")]
    harness = r"""
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <map>
#include <optional>
#include <random>
#include <string>
#include <utility>
#include <vector>
constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannelCount = 1;
""" + media + r"""
void check(std::vector<uint8_t> input, const std::vector<uint8_t>& expected) {
  const auto* storage = input.data();
  auto result = normalizeAnnexB(std::move(input));
  assert(result == expected);
  assert(result.empty() || result.data() == storage);
  MpegTsMuxer actual(VideoCodec::Avc), reference(VideoCodec::Avc);
  for (int frame = 0; frame < 35; ++frame) {
    assert(actual.muxAccessUnit(result, frame * 33333, frame == 0) ==
           reference.muxAccessUnit(expected, frame * 33333, frame == 0));
  }
}
int main() {
  check({}, {});
  check({0,0,1,0x65,9}, {0,0,1,0x65,9});
  check({0,0,0,1,0x65,9}, {0,0,0,1,0x65,9});
  check({0,0,0,2,0x65,9,0,0,0,2,0x41,8},
        {0,0,0,1,0x65,9,0,0,0,1,0x41,8});
  // A malformed second NAL must not leave the first NAL partly converted.
  for (auto invalid : std::vector<std::vector<uint8_t>>{
      {0,0}, {0,0,0,0}, {0xff,0xff,0xff,0xff,9},
      {0,0,0,2,0x65,9,0,0,0,4,8}, {0,0,0,2,0x65,9,0}}) {
    check(invalid, invalid);
  }
  // Preserve the existing start-code precedence for ambiguous length prefixes.
  std::vector<uint8_t> ambiguous(260, 0x65);
  ambiguous[0] = 0; ambiguous[1] = 0; ambiguous[2] = 1; ambiguous[3] = 0;
  check(ambiguous, ambiguous);
  std::mt19937 random(42);
  for (int sample = 0; sample < 200; ++sample) {
    std::vector<uint8_t> input, expected;
    for (unsigned nal = 0, count = 1 + random() % 20; nal < count; ++nal) {
      const uint32_t size = 2 + random() % 250;
      input.insert(input.end(), {0, 0, static_cast<uint8_t>(size >> 8),
                                static_cast<uint8_t>(size)});
      expected.insert(expected.end(), {0, 0, 0, 1});
      for (uint32_t byte = 0; byte < size; ++byte) {
        const uint8_t value = static_cast<uint8_t>(random());
        input.push_back(value);
        expected.push_back(value);
      }
    }
    check(input, expected);
    input.push_back(0); // Incomplete final length: preserve the entire access unit.
    check(input, input);
  }
  std::vector<uint8_t> large(1024 * 1024 + 4, 0x65);
  large[0] = 0; large[1] = 0x10; large[2] = 0; large[3] = 0;
  auto expected = large;
  expected[1] = 0; expected[3] = 1;
  check(std::move(large), expected);
}
"""
    cpp = tmp_path / "video_test.cpp"
    executable = tmp_path / "video_test"
    cpp.write_text(harness)
    subprocess.run([compiler, "-std=c++20", "-O2", str(cpp), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=20)
