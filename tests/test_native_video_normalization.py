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
