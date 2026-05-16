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
- Microphone
- Network
- Foreground service

Set the Windows machine IP and port in the app. The initial prototype supports manual connection first; mDNS discovery should be added after one-phone streaming is stable.

Recommended first stream settings:

- `1920x1080`
- `30 fps`
- `12 Mbps`
- HEVC if available, H.264 fallback
- SRT latency `120 ms`

## OBS plugin

The plugin scaffold is in `obs-plugin/`.

Configure with paths to your OBS, FFmpeg, and SRT development installations:

```powershell
cmake -S obs-plugin -B build/obs-plugin `
  -DOBS_ROOT="C:/Program Files/obs-studio" `
  -DFFMPEG_ROOT="C:/path/to/ffmpeg-dev" `
  -DSRT_ROOT="C:/path/to/srt-dev"
```

The scaffold registers the `openstream_source` source type and exposes the initial settings expected by V1.

## Network recommendations

- Use a dedicated 5 GHz or Wi-Fi 6 network.
- Keep the phone close to the access point during first tests.
- Disable VPNs and client isolation.
- Start with one phone before testing multiple devices.
- Prefer fixed bitrate before enabling adaptive behavior.
