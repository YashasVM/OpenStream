#!/usr/bin/env python3
"""Host-only packetizer test with independently decoded H.264/AAC segments.

Requires g++, ffmpeg and ffprobe. Does not access Android devices.
"""
import json
import re
import struct
import subprocess
import tempfile
from pathlib import Path


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="irl-mux-") as tmp:
    tmp = Path(tmp)
    video = run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
                "-t", "3", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-x264-params", "aud=1:keyint=30:min-keyint=30:scenecut=0", "-f", "h264", "pipe:1")
    audio = run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "3", "-ac", "1", "-c:a", "aac", "-f", "adts", "pipe:1")
    nals = list(re.finditer(b"\x00\x00(?:\x00)?\x01", video))
    aud = [m.start() for m in nals if video[m.end()] & 31 == 9]
    config = b"".join(video[m.start():nals[i + 1].start() if i + 1 < len(nals) else len(video)]
                      for i, m in enumerate(nals) if video[m.end()] & 31 in (7, 8))
    assert config
    frames = []
    for i, start in enumerate(aud):
        data = video[start:aud[i + 1] if i + 1 < len(aud) else len(video)]
        key = i % 30 == 0
        frames.append((i * 1_000_000 // 30, 0, int(key), data))
    offset = 0
    count = 0
    while offset < len(audio):
        frame_length = ((audio[offset + 3] & 3) << 11) | (audio[offset + 4] << 3) | (audio[offset + 5] >> 5)
        assert frame_length >= 7
        # Raw AAC tests the muxer's ADTS framing with the actual encoder config, LC 48 kHz mono.
        frames.append((count * 1024 * 1_000_000 // 48000, 1, 0, audio[offset + 7:offset + frame_length]))
        offset += frame_length
        count += 1
    frames.sort(key=lambda x: (x[0], x[1]))
    fixture = tmp / "input.bin"
    with fixture.open("wb") as f:
        for pts, is_audio, key, data in frames:
            f.write(struct.pack("<QIII", pts, is_audio, key, len(data)))
            f.write(data)
    (tmp / "config.bin").write_bytes(config)
    harness = tmp / "check.cpp"
    harness.write_text(r'''
#include "mpeg_ts_snapshot.h"
#include <cassert>
#include <fstream>
#include <iterator>
#include <string>
int main(int argc, char **argv) {
  assert(argc == 4);
  irl::MpegTsMuxer mux(irl::VideoCodec::Avc);
  std::ifstream input(argv[1], std::ios::binary), csd(argv[2], std::ios::binary);
  std::vector<uint8_t> config((std::istreambuf_iterator<char>(csd)), {});
  std::vector<uint8_t> audioConfig{0x11, 0x88};
  std::ofstream output;
  int segments = 0;
  while (true) {
    uint64_t pts;
    uint32_t audio, key, size;
    if (!input.read(reinterpret_cast<char *>(&pts), 8)) break;
    input.read(reinterpret_cast<char *>(&audio), 4);
    input.read(reinterpret_cast<char *>(&key), 4);
    input.read(reinterpret_cast<char *>(&size), 4);
    std::vector<uint8_t> data(size);
    input.read(reinterpret_cast<char *>(data.data()), size);
    if (!audio && key) {
      output.close();
      output.open(std::string(argv[3]) + "/" + std::to_string(segments++) + ".ts", std::ios::binary);
      data.insert(data.begin(), config.begin(), config.end());
    }
    assert(output.is_open());
    auto ts = audio ? mux.muxAudioAccessUnit(data, audioConfig, pts) : mux.muxAccessUnit(data, pts, key);
    assert(ts.size() % 188 == 0);
    for (size_t i = 0; i < ts.size(); i += 188) assert(ts[i] == 0x47);
    output.write(reinterpret_cast<const char *>(ts.data()), ts.size());
  }
  assert(segments == 3);
}
''')
    binary = tmp / "check"
    run("g++", "-std=c++20", "-I", str(root / "main/cpp"), str(harness), "-o", str(binary))
    run(str(binary), str(fixture), str(tmp / "config.bin"), str(tmp))
    for segment in sorted(tmp.glob("*.ts")):
        data = segment.read_bytes()
        assert data[0] == 0x47 and data[1] & 31 == 0 and data[2] == 0, "Segment must begin with PAT"
        probe = json.loads(run("ffprobe", "-v", "error", "-show_streams", "-of", "json", str(segment)))
        assert {s["codec_name"] for s in probe["streams"]} == {"h264", "aac"}
        decoded = subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(segment), "-f", "null", "-"], capture_output=True)
        assert decoded.returncode == 0 and not decoded.stderr, decoded.stderr.decode()
        print(f"PASS: {segment.name} independently decodes H.264 and AAC, starts with PAT, all packets 188 bytes")
