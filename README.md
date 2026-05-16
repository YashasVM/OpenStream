# OpenStream

OpenStream is an open-source prototype for turning Android phones into low-latency wireless OBS camera sources.

This repository implements the practical V1 direction:

- Android captures with Camera2.
- Android encodes with hardware HEVC/H.265 by default, H.264 fallback.
- Android sends the contribution feed over SRT.
- Windows receives the feed through FFmpeg/libsrt.
- OBS integration starts as a native source plugin scaffold.
- Raw YUV/zstd lossless transport is kept as a later research track.

## Repository layout

```text
android/                 Android Camera2 + MediaCodec app scaffold
obs-plugin/              Native OBS source plugin scaffold
tools/openstream_receiver.py
                         Windows feasibility receiver using FFmpeg SRT input
tests/test_repo_contract.py
                         Contract tests for the prototype architecture
docs/                    Architecture, setup, and test notes
```

## Prototype target

The first working milestone is one Android phone streaming 1080p30 to a Windows receiver or OBS source for 30 minutes without freezing.

Default video settings:

- 1080p30
- HEVC/H.265 if hardware encoder exists
- H.264 fallback
- 12 Mbps default bitrate
- 1 second keyframe interval
- No B-frame dependency in the target encoder profile
- SRT latency range: 80-200 ms

## Local receiver smoke test

Run this on Windows to validate FFmpeg/SRT support and listen for one phone:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

The Android app should call:

```text
srt://<windows-ip>:9000?mode=caller&latency=120
```

## Build notes

The Android project requires Android Studio or a local Gradle installation with the Android Gradle plugin available.

The current native Android SRT bridge is a deliberate integration placeholder. It exposes the JNI boundary and logs encoded access units; the next implementation step is linking libsrt and packaging the `MediaCodec` access units into an FFmpeg-readable stream.

The OBS plugin scaffold requires:

- OBS Studio development headers/libraries
- CMake
- FFmpeg development libraries
- libsrt development libraries

See [docs/setup.md](docs/setup.md) for setup details.

## License

OpenStream is intended for MIT or Apache-2.0 licensing. Add the final license file before publishing packages or releases.
