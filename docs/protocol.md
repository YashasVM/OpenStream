# OpenStream Protocol Specification

## Overview

OpenStream uses three communication channels between the Android phone and OBS:

1. **Media Stream** - SRT/MPEG-TS for video + audio (phone -> OBS)
2. **Discovery** - UDP multicast/broadcast beacons (bidirectional)
3. **Control** - HTTP POST for remote camera commands (OBS -> phone)

---

## Media Stream

The default camera-slot flow uses SRT from the OBS source (caller) to the
Android phone (listener). This lets OBS choose a reserved phone and reconnect
to the same camera slot. Manual pairing may also use the legacy Android-caller
flow when a pairing URL provides an OBS listener.

### SRT URLs

Default OBS caller:

```
srt://<phone-ip>:9000?mode=caller&latency=120
```

Default Android listener:

```
srt://0.0.0.0:9000?mode=listener&latency=120
```

Legacy manual pairing remains supported:

```text
Android caller: srt://<obs-pc-ip>:9000?mode=caller&latency=120
OBS listener:   srt://0.0.0.0:9000?mode=listener&latency=120
```

### Container Format

The Android app muxes encoded video and audio into **MPEG-TS** before sending
over SRT. This ensures FFmpeg/OBS can read the phone stream as a standard
transport stream.

### Video Payload

- Codec: `video/hevc` (preferred) or `video/avc` (fallback)
- Source: MediaCodec hardware encoder
- Resolution: 1920x1080 default
- Frame rate: 60 fps default
- Bitrate: 50 Mbps default
- Keyframe interval: 1 second
- No B-frame dependency in target encoder profile

### Audio Payload

- Codec: AAC
- Source: MediaCodec audio encoder from device microphone
- Sample rate: Device default (typically 44100 Hz)
- Channels: Mono
- Output: Separate OBS mixer channel for independent volume control

---

## Discovery Protocol

### OBS -> Phone (Listener Advertisement)

When the OBS source listener starts, it broadcasts a UDP beacon every 1 second
on port `51515`:

**Broadcast destinations:**
- Subnet broadcast addresses (computed from local interfaces)
- Multicast group `239.255.42.99`
- Fallback `255.255.255.255`

**Beacon format:**

```
OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"name":"OpenStream","instanceId":"...","sourceInstanceId":"...","slotId":"...","slotLabel":"CAM A","pairingUrl":"openstream://connect?...","host":"<obs-ip>","listenerPort":9000,"latencyMs":120,"bitrateMbps":50,"busy":false}
```

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | Always `dev.openstream.listener` |
| `version` | int | Protocol version (currently `1`) |
| `name` | string | OBS source display name |
| `instanceId` | string | Unique source instance identifier |
| `sourceInstanceId` | string | Stable OBS source instance used for reservations |
| `slotId` | string | OBS camera slot identifier |
| `slotLabel` | string | Human-readable camera slot label, such as `CAM A` |
| `pairingUrl` | string | Deep-link URL for QR/manual pairing |
| `host` | string | OBS machine IP address |
| `listenerPort` | int | SRT listener port |
| `latencyMs` | int | Configured SRT latency |
| `bitrateMbps` | int | Expected stream bitrate |
| `busy` | bool | Whether the source is already receiving a stream |

### Phone -> OBS (Phone Advertisement)

The Android app advertises itself on the same multicast group:

**Beacon format:**

```
OPENSTREAM_PHONE/1 {"type":"dev.openstream.phone","version":1,"name":"<device-name>","instanceId":"...","host":"<phone-ip>","listenerPort":9000,"controlPort":9001,"latencyMs":120,"codec":"video/hevc","width":1920,"height":1080,"fps":60,"bitrateMbps":50,"busy":false,"reservedBy":""}
```

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | Always `dev.openstream.phone` |
| `version` | int | Protocol version (currently `1`) |
| `name` | string | Device model name |
| `instanceId` | string | Unique phone advertisement identifier |
| `host` | string | Phone IP address |
| `listenerPort` | int | Phone's SRT port |
| `controlPort` | int | HTTP control server port |
| `latencyMs` | int | Configured SRT latency |
| `width` | int | Stream width in pixels |
| `height` | int | Stream height in pixels |
| `fps` | int | Stream frame rate |
| `bitrateMbps` | int | Stream bitrate |
| `busy` | bool | Whether the phone is already reserved or streaming |
| `reservedBy` | string | OBS source instance that currently owns the reservation, or empty |

OBS keeps a registry of discovered phones keyed by `instanceId`. Each
OpenStream source has a `selected_phone_id` setting. `auto` selects the first
non-busy phone; any other value binds that source to one specific phone.

### Fallback Pairing

If discovery is blocked, the OBS source exposes a deep-link URL:

```
openstream://connect?slotId=<slot-id>&slotLabel=<slot-label>&sourceInstanceId=<source-id>&controlToken=<64-hex-token>&host=<obs-ip>&port=<port>&latency=<ms>&name=...
```

This can be encoded as a QR code or entered manually in the Android app.

---

## Control Protocol

The Android app runs an authenticated HTTP server on port `9001` for remote
camera control from OBS. OBS creates a 256-bit token per source and includes
it in that source's pairing URL. After the phone opens the link (or selects
the advertised slot), the app retains the token in memory for the current
session. Every request, including `GET /status`, must include it:

```text
X-OpenStream-Token: <controlToken>
```

The token is a local-network capability, not a replacement for transport
encryption: pairing and discovery must be performed only on a trusted LAN.
Requests without a valid token receive `401 Unauthorized`; request headers
and bodies are size-limited, and the server does not provide browser CORS
access.

### Reserve Phone

```
POST /reserve
Content-Type: application/json

{"sourceInstanceId":"openstream-..."}
```

Marks the phone as reserved before OBS opens the SRT stream. The phone rejects
reservations from other source instances while reserved or streaming.

### Release Phone

```
POST /release
Content-Type: application/json

{"sourceInstanceId":"openstream-..."}
```

Clears the reservation after disconnect.

### Endpoints

#### Set Zoom

```
POST /zoom
Content-Type: application/json

{"value": 2.5}
```

Sets the digital zoom level. Value range depends on the active camera lens.

#### Toggle Torch

```
POST /torch
Content-Type: application/json

{"enabled": true}
```

Turns the flashlight on (`true`) or off (`false`).

#### Switch Lens

```
POST /lens
Content-Type: application/json

{"lens": "1×"}
```

Switches to the specified camera lens. Known values:

| Value | Camera |
|-------|--------|
| `"0.5×"` | Ultra-wide |
| `"1×"` | Wide (default back camera) |
| `"2×"` | Telephoto |
| `"Front"` | Front-facing camera |

Available lenses depend on the device hardware.

### Response

All control endpoints return HTTP `200 OK` on success.

---

## Telemetry

Telemetry is separate from media. The Android app samples:

```json
{
  "deviceName": "Google Pixel",
  "codec": "video/hevc",
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "bitrate": 20000000,
  "batteryPercent": 87,
  "wifiRssi": -48,
  "temperatureCelsius": null,
  "encoderState": "streaming"
}
```

V1 telemetry is used internally by the app. Future versions may expose it
over the control HTTP channel or a WebSocket for OBS-side dashboards.
