# OpenStream Architecture

## V1 pipeline

```text
Android Camera2
  -> MediaCodec hardware HEVC/H.264 encode
  -> SRT caller over local Wi-Fi
  -> Windows FFmpeg/libsrt receiver
  -> Native OBS source plugin
  -> OBS final stream/record encode
```

The V1 prototype intentionally uses hardware video encoding on the phone. This is the best path for stable, high-quality wireless streaming over commodity Wi-Fi.

## Why not raw YUV first?

Raw 1080p YUV420 is hundreds of megabits per second before overhead. Lossless zstd compression is content-dependent and cannot be assumed to reach streaming bitrates. The V1 system keeps a clean transport interface so a later raw/lossless transport can be tested without blocking the reliable product path.

## Transport defaults

- Android is the SRT caller.
- Windows is the SRT listener.
- One port per device.
- Default port: `9000`.
- Default latency: `120 ms`.
- Valid latency tuning range: `80-200 ms`.

## Encoding defaults

- Preferred codec: HEVC/H.265.
- Fallback codec: AVC/H.264.
- Default resolution: `1920x1080`.
- Fallback resolution: `1280x720`.
- Default frame rate: `30 fps`.
- V1 high-performance mode: `60 fps`.
- Bitrate presets: `8`, `12`, `20`, `35 Mbps`.
- Keyframe interval: `1 second`.

## Sync model

V1 does not promise PTP or genlock. Each Android frame/audio buffer is timestamped with a monotonic clock. The Windows receiver and OBS source use those timestamps to estimate drift and preserve A/V alignment.

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

Telemetry is intentionally separate from the media path so future dashboards and OBS property panels can evolve independently.

## Research track

The following are explicitly not required for the first prototype:

- Raw `YUV_420_888` over network
- zstd lossless frame transport
- PTP-grade synchronization
- GPU zero-copy receive path
- ML Kit auto-framing
- Multi-network bonding
- Predictive auto-switching
