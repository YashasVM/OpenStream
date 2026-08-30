# OpenStream Protocol Specification

## Overview

OpenStream V1.0.1 currently uses version 1 of its LAN wire protocol. Product
version numbers and protocol versions are independent: `OPENSTREAM/1` and
`OPENSTREAM_PHONE/1` below identify the protocol, not the app release.

OpenStream uses three communication channels between the Android phone and OBS:

1. **Media Stream** - SRT/MPEG-TS for video + audio (phone -> OBS)
2. **Discovery** - UDP multicast/broadcast beacons (bidirectional)
3. **Control** - HTTP POST for remote camera commands (OBS -> phone)

---

## Media Stream

V1 media transport uses SRT from Android caller to Windows listener.

### SRT URLs

Android caller:

```text
srt://<obs-pc-ip>:9000?mode=caller&latency=120
```

OBS listener:

```text
srt://0.0.0.0:9000?mode=listener&latency=120
```

The supported latency range is `80-200 ms`; both pairing links and the OBS
source clamp values to that range.

### Container Format

The Android app muxes encoded video and audio into **MPEG-TS** before sending
over SRT. This ensures FFmpeg/OBS can read the phone stream as a standard
transport stream.

### Video Payload

- Codec: `video/avc` by default; `video/hevc` only for an explicit hardware profile
- Source: MediaCodec hardware encoder
- Resolution: 1920x1080 default
- Frame rate: 30 fps default
- Bitrate: 12 Mbps default (bounded to 8-50 Mbps)
- Keyframe interval: 1 second
- No B-frame dependency in target encoder profile

### Audio Payload

- Codec: AAC
- Source: MediaCodec audio encoder from device microphone
- Sample rate: 48 kHz
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

```text
OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"name":"OpenStream","instanceId":"...","sourceInstanceId":"...","slotId":"...","slotLabel":"CAM A","pairingUrl":"openstream://connect?...","host":"<obs-ip>","listenerPort":9000,"latencyMs":120,"bitrateMbps":12,"busy":false}
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
| `pairingUrl` | string | Deep-link URL for QR/manual pairing, including the source control token |
| `host` | string | OBS machine IP address |
| `listenerPort` | int | SRT listener port |
| `latencyMs` | int | Configured SRT latency |
| `bitrateMbps` | int | Expected stream bitrate |
| `busy` | bool | Whether the source is already receiving a stream |

### Phone -> OBS (Phone Advertisement)

The Android app advertises itself on the same multicast group:

**Beacon format:**

```text
OPENSTREAM_PHONE/1 {"type":"dev.openstream.phone","version":1,"name":"<device-name>","instanceId":"...","host":"<phone-ip>","listenerPort":9000,"controlPort":9001,"latencyMs":120,"codec":"video/avc","width":1920,"height":1080,"fps":30,"bitrateMbps":12,"busy":false,"reservedBy":""}
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

```text
openstream://connect?slotId=<slot-id>&slotLabel=<slot-label>&sourceInstanceId=<source-id>&host=<obs-ip>&port=<port>&latency=<ms>&name=...
```

This can be encoded as a QR code or entered manually in the Android app.

---

## Control Protocol

The Android app runs a lightweight HTTP server on port `9001` for remote camera
control from OBS. Requests, headers, and bodies are size-limited and parsed as
UTF-8 bytes. Protocol V1 does not authenticate this channel, so discovery and
camera control must be used only on a trusted LAN. Authentication must be added
as a versioned, atomic Android-and-OBS protocol upgrade rather than enabled in
only one component.

### Reserve Phone

```http
POST /reserve
Content-Type: application/json

{
  "sourceInstanceId": "openstream-...",
  "slotId": "slot-a",
  "slotLabel": "CAM A",
  "bitrateMbps": 12
}
```

Marks the phone as reserved before OBS opens the SRT stream. The phone rejects
reservations from other source instances while reserved or streaming.
`sourceInstanceId` is required. `slotId`, `slotLabel`, and `bitrateMbps` provide
slot metadata and the requested streaming bitrate.

### Release Phone

```http
POST /release
Content-Type: application/json

{"sourceInstanceId":"openstream-..."}
```

Clears the reservation after disconnect.

### Endpoints

#### Set Zoom

```http
POST /zoom
Content-Type: application/json

{"value": 2.5}
```

Sets the digital zoom level. Value range depends on the active camera lens.

#### Toggle Torch

```http
POST /torch
Content-Type: application/json

{"enabled": true}
```

Turns the flashlight on (`true`) or off (`false`).

#### Switch Lens

```http
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

#### Identify Phone

```http
POST /identify
Content-Type: application/json

{"label":"CAM A","subtitle":"Wide shot"}
```

Temporarily shows the OBS camera-slot label on the phone.

#### Read Status

```http
GET /status
```

Returns the current zoom range, selected lens, available lenses, and
reservation owner.

### Response

All control endpoints return HTTP `200 OK` on success. Successful `POST`
responses contain `"ok": true` (and may include endpoint-specific fields), for
example:

```json
{"ok":true}
```

## Compatibility and Release Ordering

Android and the OBS plugin are two halves of one protocol. Adding a required
field, token, endpoint, or validation rule is a compatibility change even if
the `OPENSTREAM/1` prefix remains unchanged. Such changes must be tested as an
old/new client matrix and released atomically as one full release containing
both artifacts.

Keep required protocol changes in one release containing both the Android APK
and OBS plugin. Additive optional fields may be rolled out independently only
when both older components demonstrably ignore them safely.

---

## Telemetry

Telemetry is separate from media. The Android app samples:

```json
{
  "deviceName": "Google Pixel",
  "codec": "video/avc",
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

V1 telemetry is used internally by the app.
