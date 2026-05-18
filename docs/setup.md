# OpenStream Setup

## OBS receiver

Install FFmpeg with SRT enabled. Verify:

```powershell
ffmpeg -protocols
```

The output should include `srt`.

Normal use does not require the standalone Python receiver. OBS listens directly
through the `OpenStream Phone` source.

## Android app

Open `android/` in Android Studio.

Required permissions:

- Camera
- Network
- Wi-Fi multicast/broadcast discovery
- Foreground service

Open the app on the same Wi-Fi network as OBS. Camera preview starts as soon as
camera permission is granted. Available `OpenStream Phone`
listeners appear as tappable devices. Tap the OBS device to connect the selected
phone camera directly; manual IP/port entry is only a fallback for networks that
block UDP discovery.

The native Android sender packetizes `MediaCodec` H.264/H.265 access units
as MPEG-TS before sending them to SRT, which lets FFmpeg/ffplay/OBS read the
phone stream as a normal SRT transport stream. Normal APK builds require
Android ABI-compatible libsrt headers and libraries:

```powershell
./gradlew :app:assembleDebug `
  -Popenstream.libsrtIncludeDir=C:/path/to/libsrt/include `
  -Popenstream.libsrtLibrary=C:/path/to/libsrt/android/arm64-v8a/libsrt.so
```

Use `-Popenstream.nonStreamingCiBuild=true` only for CI/source compile checks
that intentionally build the non-streaming native bridge.

Recommended first stream settings:

- `1920x1080`
- `30 fps`
- `12 Mbps`
- HEVC if available, H.264 fallback
- SRT latency `120 ms`

## OBS plugin

The plugin is in `obs-plugin/`. It registers an `OpenStream Phone` source,
listens for the Android SRT caller through FFmpeg, advertises itself over LAN
UDP discovery on port `51515`, decodes the video stream, converts frames to BGRA,
and submits them to OBS.

Configure with paths to your OBS and FFmpeg development installations:

```powershell
cmake -S obs-plugin -B build/obs-plugin `
  -DOBS_ROOT="C:/Program Files/obs-studio" `
  -DFFMPEG_ROOT="C:/path/to/ffmpeg-dev"
```

The FFmpeg build used by the plugin must include SRT protocol support. Verify
your runtime FFmpeg with `ffmpeg -protocols`; the output should include `srt`.

Expected V1 user flow:

1. Add `OpenStream Phone` as an OBS source.
2. Keep `Start listener` enabled, then click `OK`; the source waits blank.
3. Keep the default listener port `9000` and latency `120 ms`, or change them if needed.
4. Open the Android app on the same Wi-Fi network.
5. Tap the available OBS device.
6. Direct camera video appears in OBS.

The source also exposes an `openstream://connect?...` fallback URL that can be
encoded as a QR code if UDP discovery is blocked on the network.

## Developer receiver

For receiver smoke tests without OBS, keep using the Python tool:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

This is a developer/debug path only; it is not part of the normal user workflow.

## Network recommendations

- Use a dedicated 5 GHz or Wi-Fi 6 network.
- Keep the phone close to the access point during first tests.
- Disable VPNs and client isolation.
- Start with one phone before testing multiple devices.
- Prefer fixed bitrate before enabling adaptive behavior.
