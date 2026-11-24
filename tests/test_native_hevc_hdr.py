"""Decode actual Main10 HDR access units through the production TS muxer."""
import json
import shutil
import subprocess

import pytest

from _helpers import read_text


@pytest.mark.parametrize("transfer", ["arib-std-b67", "smpte2084"])
def test_main10_hdr_mux_preserves_pixels_and_metadata(tmp_path, transfer):
    for tool in ("ffmpeg", "ffprobe", "g++"):
        if not shutil.which(tool):
            pytest.skip(f"requires {tool}")
    source = tmp_path / "source.hevc"
    subprocess.run([
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=160x96:rate=30",
        "-frames:v", "4", "-vf", "format=yuv420p10le", "-c:v", "libx265",
        "-x265-params", f"pools=none:frame-threads=1:bframes=0:keyint=2:repeat-headers=1:log-level=error:colorprim=9:transfer={18 if transfer == 'arib-std-b67' else 16}:colormatrix=9:range=limited",
        "-color_primaries", "bt2020", "-color_trc", transfer, "-colorspace", "bt2020nc",
        "-color_range", "tv", "-f", "hevc", str(source),
    ], check=True, timeout=30)
    packets = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_packets", "-of", "json", str(source)
    ]))["packets"]
    media = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    media = media[media.index("enum class VideoCodec"):media.index("struct SrtUrl")]
    cpp = tmp_path / "mux.cpp"
    cpp.write_text(r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>
constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannelCount = 1;
''' + media + r'''
int main(int argc, char **argv) {
  std::ifstream input(argv[1], std::ios::binary);
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
  std::ofstream output(argv[2], std::ios::binary);
  MpegTsMuxer mux(VideoCodec::Hevc);
  int frame = 0;
  for (int i = 3; i + 1 < argc; i += 2, ++frame) {
    const auto pos = std::stoul(argv[i]), size = std::stoul(argv[i + 1]);
    assert(pos + size <= bytes.size());
    std::vector<uint8_t> au(bytes.begin() + pos, bytes.begin() + pos + size);
    auto ts = mux.muxAccessUnit(au, frame * 33333, frame % 2 == 0);
    assert(ts.size() % 188 == 0);
    output.write(reinterpret_cast<char *>(ts.data()), ts.size());
  }
}
''')
    exe, ts = tmp_path / "mux", tmp_path / "output.ts"
    subprocess.run(["g++", "-std=c++20", "-O2", str(cpp), "-o", str(exe)], check=True)
    args = [str(exe), str(source), str(ts)]
    for packet in packets:
        args += [packet["pos"], packet["size"]]
    subprocess.run(args, check=True)
    streams = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_streams", "-of", "json", str(ts)
    ]))["streams"]
    video = next(stream for stream in streams if stream["codec_type"] == "video")
    assert video["codec_name"] == "hevc"
    assert video["profile"] == "Main 10"
    assert video["pix_fmt"] == "yuv420p10le"
    assert (video["width"], video["height"]) == (160, 96)
    assert video["color_primaries"] == "bt2020"
    assert video["color_transfer"] == transfer
    assert video["color_space"] == "bt2020nc"
    assert video["color_range"] == "tv"
    def pixels(path):
        result = subprocess.check_output([
            "ffmpeg", "-v", "error", "-i", str(path), "-map", "0:v:0",
            "-f", "framemd5", "-",], timeout=20).decode()
        return [line.split(",")[-1].strip() for line in result.splitlines() if not line.startswith("#")]
    assert pixels(source) == pixels(ts)
    assert len(pixels(ts)) == 4
