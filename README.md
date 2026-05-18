# OpenStream

> [!IMPORTANT]
> # STILL IN PROGRESS
> OpenStream is actively being built. The current codebase is a prototype scaffold, not a finished OBS-ready wireless camera system yet.

OpenStream is an open-source prototype for turning Android phones into low-latency wireless OBS camera sources over local Wi-Fi.

This repository implements the practical V1 direction:

- Android captures with Camera2.
- Android exposes only the selected phone camera feed, not the whole phone screen.
- Android encodes with hardware HEVC/H.265 by default, H.264 fallback.
- Android sends the contribution feed over SRT.
- OBS receives the feed through a native FFmpeg/libsrt source plugin.
- OBS advertises active listeners on the LAN so Android can tap to connect.
- Raw YUV/zstd lossless transport is kept as a later research track.

## Repository layout

```text
android/                 Android Camera2 + MediaCodec app scaffold
obs-plugin/              Native OBS source plugin with FFmpeg receive path
tools/openstream_receiver.py
                         Windows feasibility receiver using FFmpeg SRT input
tests/test_repo_contract.py
                         Contract tests for the prototype architecture
docs/                    Architecture, setup, and test notes
```

## Prototype target

The first working milestone is one Android phone streaming 1080p30 to a Windows receiver or OBS source for 30 minutes without freezing.

Current progress:

- Android camera capture, hardware encoder, and tap-to-connect discovery scaffolding are in place.
- The OBS source includes an FFmpeg-backed listener/decoder path and UDP discovery beacon.
- The Android native SRT bridge packetizes MediaCodec output as MPEG-TS and links to libsrt when provided.

Default video settings:

- 1080p30
- HEVC/H.265 if hardware encoder exists
- H.264 fallback
- 12 Mbps default bitrate
- 1 second keyframe interval
- No B-frame dependency in the target encoder profile
- SRT latency range: 80-200 ms

## Normal user workflow

1. Add `OpenStream Phone` as an OBS source.
2. Leave `Start listener` enabled and click `OK`; the source waits blank.
3. Open the Android app on the same Wi-Fi network; camera preview appears immediately.
4. Tap the available OBS source.
5. Direct camera video appears in OBS.

## Developer receiver smoke test

Run this on Windows to validate FFmpeg/SRT support without OBS:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

The Python receiver is only a developer/debug path. Normal users should use the
OBS source directly.

## Build notes

The Android project requires Android Studio or a local Gradle installation with the Android Gradle plugin available.

Normal Android APK builds require Android ABI-compatible libsrt binaries for real
network sending. Use `-Popenstream.nonStreamingCiBuild=true` only for source
compile checks that intentionally skip streaming support.

The OBS plugin requires:

- OBS Studio development headers/libraries
- CMake
- FFmpeg development libraries
- an FFmpeg build with SRT protocol support

See [docs/setup.md](docs/setup.md) for setup details.

## License

OpenStream is intended for MIT or Apache-2.0 licensing. Add the final license file before publishing packages or releases.
