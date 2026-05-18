# OpenStream Prototype Protocol

## Media stream

V1 media transport uses SRT from Android caller to Windows listener.

Default caller URL:

```text
srt://<windows-ip>:9000?mode=caller&latency=120
```

The Android app sends only the Camera2 camera feed. It does not capture the Android screen, notifications, navigation UI, or microphone for the V1 camera-source milestone.

The simplified pairing model is:

```text
OBS source listens on srt://0.0.0.0:<port>?mode=listener&latency=<latency_ms>
Android app calls srt://<obs-pc-ip>:<port>?mode=caller&latency=<latency_ms>
```

Normal setup uses LAN discovery. When the OBS source listener starts, it
broadcasts a UDP beacon once per second on port `51515`:

```text
OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"name":"OpenStream Phone","instanceId":"...","listenerPort":9000,"latencyMs":120,"bitrateMbps":12,"busy":false}
```

The Android app uses the packet source IP plus the advertised port and latency
to generate the SRT caller URL. Users should not need to hand-edit SRT query
strings during normal setup. If discovery is blocked, the OBS source exposes an
`openstream://connect?host=<obs-ip>&port=<port>&latency=<latency_ms>&name=...`
fallback URL for QR/manual pairing.

The Android app sends encoded video access units produced by `MediaCodec`.

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

The OBS source exposes:

- `listener_enabled`
- `device_name`
- `listener_port`
- `srt_url`
- `phone_target_hint`
- `pairing_url`
- `latency_ms`
- `bitrate_mbps`
- start/stop listener controls for advanced/debug use

The OBS plugin connects those settings to an FFmpeg/libsrt decode worker and
submits decoded video frames through OBS video output APIs.
