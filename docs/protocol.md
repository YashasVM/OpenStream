# OpenStream Beta Protocol Specification

## Overview

OpenStream Beta separates the camera media path from an authenticated control
plane. The Android phone is the camera head; the OBS plugin is the control-room
administrator. Both surfaces may operate the camera in `collaborative` mode,
while OBS can select `obs_lock` to make its settings authoritative.

The product uses three channels:

1. **Media** - SRT/MPEG-TS from Android to OBS.
2. **Discovery** - UDP advertisements on the local network.
3. **Control** - authenticated HTTP/JSON between OBS and Android.

The media and discovery transports remain compatible with V1. Professional
camera control is versioned under `/v2`; a V2 command is never silently
downgraded to an unauthenticated V1 request.

## Media stream

Android uses MediaCodec to encode HEVC/H.264 video and AAC audio, muxes the
access units into MPEG-TS, and connects as an SRT caller. OBS listens through
FFmpeg/libsrt and exposes decoded video and audio as one source.

```text
Android: srt://<obs-ip>:9000?mode=caller&latency=120
OBS:     srt://0.0.0.0:9000?mode=listener&latency=120
```

The normal latency range is 80-200 ms. The default profile is 1920x1080, with
HEVC preferred, AVC fallback, AAC mono at 48 kHz, and a one-second keyframe
interval. Resolution, codec, and FPS changes are disruptive: OBS must present
them as **Apply & Reconnect**, not as live-safe camera commands.

## Discovery and pairing

V1 discovery remains available during the compatibility window. OBS listener
advertisements use `OPENSTREAM/1`; phone advertisements use
`OPENSTREAM_PHONE/1`. Both use UDP port `51515`, multicast group
`239.255.42.99`, subnet broadcasts, and a limited global-broadcast fallback.

An OBS advertisement contains the stable source identity, friendly camera
name, SRT listener details, pairing URL, and busy state:

```text
OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"name":"Camera 1","instanceId":"...","sourceInstanceId":"...","slotId":"...","slotLabel":"Camera 1","pairingUrl":"openstream://connect?...","host":"<obs-ip>","listenerPort":9000,"latencyMs":120,"bitrateMbps":50,"busy":false}
```

A phone advertisement includes its stable instance identity, control address,
active media profile, reservation owner, and whether V2 control is supported:

```text
OPENSTREAM_PHONE/1 {"type":"dev.openstream.phone","version":1,"name":"Pixel 8 Pro","instanceId":"...","host":"<phone-ip>","listenerPort":9000,"controlPort":9001,"codec":"video/hevc","width":1920,"height":1080,"fps":60,"bitrateMbps":50,"busy":false,"reservedBy":"","controlVersion":2}
```

The first V2 connection pairs an OBS source with the phone:

```http
POST /v2/pair
Content-Type: application/json

{
  "sourceInstanceId": "openstream-...",
  "sourceName": "Stage left",
  "pairingCode": "123456"
}
```

A successful response returns a bearer token. Android displays a six-digit
one-time pairing code and rotates it after successful use. Bearer tokens contain
32 bytes of cryptographic randomness and are stored only in the Android app and
the OBS source configuration.

```json
{"ok":true,"token":"<opaque-token>"}
```

Every other `/v2` request requires:

```http
Authorization: Bearer <opaque-token>
```

Missing, malformed, or unknown credentials are rejected using a constant-time
comparison. A pairing code is not a long-term control credential. V2 bearer
authentication prevents accidental or unauthorized control, but plain HTTP on
a trusted LAN is not transport confidentiality; production networks must be
isolated until authenticated encryption is added to the control channel.

When automatic discovery is unavailable, the same pairing target may be
carried by the existing deep link or a QR code:

```text
openstream://connect?sourceInstanceId=<source-id>&host=<obs-ip>&port=<srt-port>&latency=<ms>&name=...
```

## V2 camera model

### Capabilities

```http
GET /v2/capabilities
Authorization: Bearer <opaque-token>
```

`CameraCapabilities` is the source of truth for which controls OBS displays.
It reports the available cameras/lenses, supported AF/AWB/AE and stabilization
modes, ISO and exposure-time ranges, compensation range, focus-distance range,
zoom-ratio range, torch support, and supported stream profiles. A client must
not infer support from a device model name or display an enabled control for an
absent capability.

### State and revisions

```http
GET /v2/state
Authorization: Bearer <opaque-token>
```

The response contains one authoritative `CameraState`:

```json
{
  "revision": 42,
  "authority": "collaborative",
  "settings": {
    "exposureMode": "manual",
    "iso": 400,
    "shutterNs": 10000000,
    "exposureCompensation": 0,
    "whiteBalanceMode": "manual",
    "whiteBalanceKelvin": 5600,
    "whiteBalanceTint": 0,
    "whiteBalanceLock": false,
    "focusMode": "continuous",
    "focusDistanceDiopters": 0.0,
    "zoomRatio": 1.0,
    "torch": false,
    "stabilizationMode": "standard",
    "fps": 50
  },
  "telemetry": {
    "batteryPercent": 87,
    "temperatureCelsius": 39.2,
    "wifiRssi": -48,
    "encoderState": "streaming"
  },
  "tally": {"program":false,"preview":true}
}
```

Every successful state mutation increments `revision`. A mutating request must
include `expectedRevision`; a stale revision is rejected with the current
state instead of overwriting a newer operator change. Responses report the
applied values because Camera2 may clamp a request to hardware limits.

### Apply camera settings

```http
POST /v2/settings
Authorization: Bearer <opaque-token>
Content-Type: application/json

{
  "expectedRevision": 42,
  "settings": {
    "exposureMode": "manual",
    "iso": 800,
    "shutterNs": 20000000,
    "whiteBalanceMode": "manual",
    "whiteBalanceKelvin": 4300,
    "whiteBalanceTint": -2,
    "focusMode": "manual",
    "focusDistanceDiopters": 1.25,
    "zoomRatio": 2.0,
    "torch": false,
    "stabilizationMode": "standard",
    "fps": 50
  }
}
```

The patch is atomic. Omitted settings retain their current values. The Android
camera engine rebuilds the repeating request from the complete resulting state
so changing torch, zoom, focus, or white balance cannot reset another control.
Manual ISO and shutter require Camera2 `MANUAL_SENSOR`; shutter duration is
clamped against the active frame duration. Manual Kelvin/tint is offered only
when the device reports the required post-processing support.

### Focus and metering point

```http
POST /v2/focus
Authorization: Bearer <opaque-token>
Content-Type: application/json

{"expectedRevision":43,"x":0.35,"y":0.62,"mode":"auto"}
```

`x` and `y` are normalized coordinates in the transmitted frame, each in the
inclusive range `0.0..1.0`; they are not Android view pixels or sensor pixels.
Android maps the point through letterboxing/crop, device rotation, front-camera
mirroring, active sensor crop, and zoom before creating Camera2 AF/AE regions.
The same mapping is used for a tap on the Android preview and a click on the OBS
preview. The focus action is `auto` (meter and focus) or `lock` (meter, focus,
then hold). The persistent focus mode remains capability-driven
(`continuous`, `single`, `manual`, or fixed focus). The response distinguishes
pending, locked, and failed focus rather than treating command receipt as focus
success.

### Authority

```http
POST /v2/authority
Authorization: Bearer <opaque-token>
Content-Type: application/json

{"expectedRevision":44,"mode":"obs_lock"}
```

- `collaborative`: Android and the paired OBS administrator may change camera
  settings. Mutations still serialize through the revision contract.
- `obs_lock`: authenticated OBS commands remain enabled; Android camera
  controls become read-only. Android always retains emergency **Stop Camera**,
  restore-display, and request-control actions.

OBS is the only tally authority. A phone cannot independently promote itself
to Program or Preview.

### Tally

```http
POST /v2/tally
Authorization: Bearer <opaque-token>
Content-Type: application/json

{"program":true,"preview":false}
```

Program red and Preview green are carried as separate booleans. If both are
requested, Program wins and Android reports Preview false so the phone never
shows conflicting tally.

### Responses and errors

Successful mutations return `ok` and the complete applied state. Errors use an
HTTP status and stable code:

| Status | Code | Meaning |
|---|---|---|
| 400 | `invalid_request` | Malformed JSON, coordinate, mode, or range |
| 401 | `unauthorized` | Missing or invalid bearer token |
| 423 | `obs_locked` | The actor cannot mutate in the current mode |
| 409 | `revision_conflict` | `expectedRevision` is stale |
| 422 | `unsupported` | The active camera cannot apply the requested setting |
| 503 | `camera_not_ready` | Camera capabilities are not available yet |

## Media encryption

After V2 pairing, the 256-bit pairing token also becomes the SRT passphrase.
Android configures `SRTO_PASSPHRASE` with a 32-byte key before accepting the
caller, and OBS supplies the same secret to FFmpeg without placing it in the
logged SRT URL. Pairing restarts the waiting Android listener so the first
post-pair media session is encrypted. Pre-pair V1 discovery can still bootstrap
an upgrade, but paired cameras require the bearer token even on legacy control
routes.

## Legacy V1 compatibility

Existing source IDs, scenes, SRT URLs, discovery fields, reservations, and
`openstream://connect` links remain readable. During the compatibility window,
V1 phones may still expose `/reserve`, `/release`, `/zoom`, `/torch`, `/lens`,
`/identify`, and `/status` on trusted LANs.

V1 controls are explicitly compatibility-only: they are unauthenticated, have
no revision conflict protection, and cannot claim `obs_lock`. OBS must identify
such a phone as **Legacy control** and avoid showing professional controls that
cannot be verified through capabilities. A paired V2 phone never falls back to
V1 because authentication or authorization failed.

Android and OBS are released atomically when a required protocol field or
security rule changes. The previous/candidate compatibility matrix in
`docs/testing.md` is a release gate.
