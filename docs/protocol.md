# OpenStream Prototype Protocol

## Media stream

V1 media transport uses SRT from Android caller to Windows listener.

Default caller URL:

```text
srt://<windows-ip>:9000?mode=caller&latency=120
```

The Android app sends encoded access units produced by `MediaCodec`.

Required metadata per video access unit:

- codec: `video/hevc` or `video/avc`
- presentation timestamp in microseconds
- keyframe/config flags from `MediaCodec.BufferInfo`
- payload bytes

The first native SRT implementation should packetize access units in MPEG-TS or another FFmpeg-readable container before network send. Raw access-unit writes are only acceptable for a private test receiver.

## Telemetry

Telemetry is separate from media. V1 can send it over a local HTTP/WebSocket channel later; the Android app already samples the required fields.

Minimum telemetry payload:

```json
{
  "deviceName": "Google Pixel",
  "codec": "video/hevc",
  "width": 1920,
  "height": 1080,
  "fps": 30,
  "bitrate": 12000000,
  "batteryPercent": 87,
  "wifiRssi": -48,
  "temperatureCelsius": null,
  "encoderState": "streaming"
}
```

## OBS source settings

The initial OBS source exposes:

- `device_name`
- `srt_url`
- `latency_ms`
- `bitrate_mbps`
- connect/disconnect controls

The next plugin milestone should connect those settings to an FFmpeg/libsrt decode worker and submit frames through OBS video/audio output APIs.
