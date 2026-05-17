# OpenStream Setup

## Windows receiver

Install FFmpeg with SRT enabled. Verify:

```powershell
ffmpeg -protocols
```

The output should include `srt`.

Start the feasibility receiver:

```powershell
python tools/openstream_receiver.py --port 9000 --latency-ms 120 --ffplay
```

## Android app

Open `android/` in Android Studio.

Required permissions:

- Camera
- Network
- Foreground service

Set the OBS PC IP and listener port in the app. The app builds the SRT caller URL for you and captures only the selected phone camera, not the phone screen.

Recommended first stream settings:

- `1920x1080`
- `30 fps`
- `12 Mbps`
- HEVC if available, H.264 fallback
- SRT latency `120 ms`

## OBS plugin

The plugin is in `obs-plugin/`. It registers an `OpenStream Phone` source,
listens for the Android SRT caller through FFmpeg, decodes the video stream,
converts frames to BGRA, and submits them to OBS.

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
2. Keep the default listener port `9000` and latency `120 ms`, or change them if needed.
3. Start the listener in the source properties.
4. Enter the OBS PC IP, port, and latency in the Android app.
5. Tap `Start camera feed`.

## Network recommendations

- Use a dedicated 5 GHz or Wi-Fi 6 network.
- Keep the phone close to the access point during first tests.
- Disable VPNs and client isolation.
- Start with one phone before testing multiple devices.
- Prefer fixed bitrate before enabling adaptive behavior.
