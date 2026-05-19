# OpenStream Architecture

## V1 Pipeline

```text
Android Camera2
  -> MediaCodec hardware HEVC/H.264 video encode
  -> MediaCodec AAC audio encode
  -> MPEG-TS muxer (video + audio)
  -> SRT caller over local Wi-Fi
  -> Native OBS source plugin FFmpeg/libsrt listener
  -> OBS video output (I420/NV12/BGRA) + audio output (separate mixer channel)
  -> OBS final stream/record encode
```

The V1 prototype uses hardware video and audio encoding on the phone. This is
the best path for stable, high-quality wireless streaming over commodity Wi-Fi.

## Discovery & Connection

OBS starts the media listener and advertises it on the LAN with OpenStream UDP
multicast discovery. Android shows discovered OBS listeners as tappable devices,
then connects directly over SRT.

The discovery flow:

1. OBS plugin broadcasts UDP beacon every 1 second on port `51515` (multicast `239.255.42.99` + subnet broadcast).
2. Android app listens for beacons and displays discovered OBS instances.
3. User taps an OBS device → Android constructs `srt://<obs-ip>:<port>?mode=caller&latency=<ms>`.
4. Android MPEG-TS stream begins.
5. OBS plugin uses FFmpeg to open the SRT input, decode video and audio, and output frames.

Fallback: If UDP discovery is blocked, the OBS source exposes an
`openstream://connect?host=<obs-ip>&port=<port>&latency=<ms>&name=...`
URL for manual/QR pairing.

## Remote Camera Controls

The Android app runs a lightweight HTTP control server on port `9001`. The OBS
plugin sends POST requests to control the phone camera in real-time:

| Endpoint | Body | Effect |
|----------|------|--------|
| `POST /zoom` | `{"value": 2.5}` | Set digital zoom level |
| `POST /torch` | `{"enabled": true}` | Toggle flashlight |
| `POST /lens` | `{"lens": "1×"}` | Switch camera lens |

Control commands are separate from the media stream and use standard HTTP for
simplicity and debuggability.

## Why not raw YUV first?

Raw 1080p YUV420 is hundreds of megabits per second before overhead. Lossless
zstd compression is content-dependent and cannot be assumed to reach streaming
bitrates. The V1 system keeps a clean transport interface so a later raw/lossless
transport can be tested without blocking the reliable product path.

## Transport Defaults

- Android is the SRT caller.
- Windows is the SRT listener.
- OBS advertises active listeners with UDP multicast on port `51515`.
- Default SRT port: `9000`.
- Default control port: `9001`.
- Default latency: `120 ms`.
- Valid latency tuning range: `80–200 ms`.

## Encoding Defaults

- Preferred video codec: HEVC/H.265.
- Fallback video codec: AVC/H.264.
- Audio codec: AAC.
- Default resolution: `1920×1080`.
- Fallback resolution: `1280×720`.
- Default frame rate: `60 fps`.
- Bitrate presets: `8`, `12`, `20`, `35 Mbps`.
- Keyframe interval: `1 second`.

## Video Output Path

The OBS plugin handles three pixel format paths to minimize unnecessary
conversions:

1. **I420 (YUV420P)** — Zero-copy pass-through with proper color matrix.
2. **NV12** — Zero-copy pass-through with proper color matrix.
3. **BGRA fallback** — swscale conversion for non-standard decoder output.

Color metadata (BT.601/BT.709/BT.2020, full/limited range, HDR TRC) is
extracted from FFmpeg frame properties and forwarded to OBS.

## Sync Model

V1 does not promise PTP or genlock. Each Android frame/audio buffer is
timestamped with a monotonic clock. The Windows receiver and OBS source use
those timestamps to estimate drift and preserve A/V alignment.

## Telemetry

The Android app publishes:

- Device name
- Codec
- Resolution and frame rate
- Current bitrate
- Dropped frame estimate
- Battery percentage
- Temperature
- Wi-Fi RSSI
- Encoder state
- SRT target URL

Telemetry is intentionally separate from the media path so future dashboards
and OBS property panels can evolve independently.

## Research Track

The following are explicitly not required for the first prototype:

- Raw `YUV_420_888` over network
- zstd lossless frame transport
- PTP-grade synchronization
- GPU zero-copy receive path
- ML Kit auto-framing
- Multi-network bonding
- Predictive auto-switching
