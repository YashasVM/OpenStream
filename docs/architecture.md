# OpenStream Beta Architecture

## Product model

OpenStream Beta follows the professional camera-head and control-room model:

- **Android camera head:** captures and encodes media, owns Camera2 hardware
  state, and offers a complete local operator HUD when a cameraman is present.
- **OBS control room:** owns pairing, tally, source orchestration, and remote
  technical control. It can run the phone unattended with `obs_lock`.
- **SRT media plane:** continues independently from UI and control updates so a
  slow control request cannot stall video or audio.

The Android and OBS interfaces are two views over the same capability/state/
revision model. Neither surface maintains a competing camera truth.

## End-to-end pipeline

```text
Android Camera2
  -> authoritative CameraState + complete repeating CaptureRequest
  -> MediaCodec hardware HEVC/H.264 video + AAC audio
  -> MPEG-TS muxer
  -> SRT caller over local Wi-Fi
  -> OBS FFmpeg/libsrt listener
  -> OBS source video + independent mixer audio
  -> OBS stream/record output

OBS Control Room <-> authenticated V2 HTTP/JSON <-> Android CameraStateStore
UDP discovery + one-time pairing ----------------^
```

Media uses monotonic timestamps for A/V alignment. V2 does not claim PTP,
genlock, or frame-accurate multi-phone synchronization.

## Android camera engine

`CameraCapabilities` describes exactly what the active camera can do.
`CameraSettings` represents requested/applied controls, `CameraTelemetry`
contains operational health, and `CameraState` combines them with authority,
tally, and a monotonic revision. `CameraStateStore` serializes local and remote
mutations and rejects stale revisions.

`Camera2Controller` builds every repeating `CaptureRequest` from the complete
current state. This is a hard invariant: a torch request must not accidentally
restore automatic exposure, and a focus request must not reset white balance or
zoom. Capture results feed actual ISO, exposure duration, focus state, and
hardware-clamped values back into `CameraState`.

Professional controls are capability-driven:

- Manual ISO/shutter require `MANUAL_SENSOR`; shutter duration respects the
  active FPS frame duration.
- Tap/click focus uses one normalized transmitted-frame coordinate mapper that
  accounts for crop, rotation, mirroring, and zoom.
- `CONTROL_ZOOM_RATIO` is used for a logical multi-camera when the OEM exposes
  one. Otherwise a physical camera change is explicit; OpenStream Beta does not
  pretend a hard lens reconfiguration is seamless optical zoom.
- Fixed-focus cameras, missing torch hardware, unsupported stabilization, and
  unavailable Kelvin/tint controls remain visible only as explained capability
  limitations, never as inert fake controls.

## Android operator and unattended operation

The cameraman HUD is landscape-first and exposes status, exposure, focus,
white balance, zoom/lens, stabilization, monitoring assists, audio meters,
tally, and transport health through focused palettes. Tapping the image and
clicking the OBS preview use the same focus command and state feedback.

For unattended operation, the user explicitly chooses **Arm for Remote
Operation** while OpenStream Beta is visible. `OpenStreamCameraService` then owns the
camera, encoder, control server, wake lock, and Wi-Fi lock as a camera/
microphone foreground service. **Screen Off** turns off the presentation while
capture and remote control continue; it does not stop the service.

Android platform constraints are part of the product contract. OBS cannot
cold-start camera access after force-stop, permission revocation, reboot, or an
unarmed process death. The phone must be opened and armed again. The permanent
notification and local emergency **Stop Camera** remain available even under
`obs_lock`.

## OBS plugin architecture

The native source retains responsibility for discovery, pairing, reservation,
SRT receive/decode, decoded media output, reconnect, and persistence of stable
source IDs. Existing V7/V8/legacy source IDs continue to load so scene
collections do not break.

OBS **Source Properties** is setup-only: bind or change the phone, name the
camera, connect/retry/stop the source, or open collapsed diagnostics and manual
network settings. Camera image controls are never duplicated there.

Day-to-day camera operation lives in one custom Qt **OpenStream Beta Control Room**
dock. The dock has a camera roster and selected-camera preview, followed by
Exposure, Focus, Color, Lens, and Health groups. It exposes tally,
collaborative/OBS Lock authority, presets, Identify, Retry, and a guarded Stop
action. Unsupported controls are disabled with a reason derived from
`CameraCapabilities`.

The source publishes immutable UI snapshots. State-change notifications queue
updates onto the Qt UI thread; widgets are updated in place rather than being
destroyed and rebuilt on a one-second timer. Continuous controls coalesce
network commands to a bounded rate and send one final exact value on release.
Only the selected camera owns an interactive preview, avoiding one OBS display
per roster card.

## Control, authority, and failure handling

Pairing creates an OBS administrator credential. All V2 state/control requests
after pairing require its bearer token. Commands include the expected state
revision, and responses return applied state. In `collaborative` mode either
surface may act; in `obs_lock`, local controls are read-only except safety and
request-control actions. OBS alone derives Program/Preview tally from frontend
state.

Live-safe settings update Camera2 without interrupting SRT. Codec, resolution,
FPS profiles that require session reconstruction, and non-logical physical
camera changes use **Apply & Reconnect**, warn when on Program, stop at a clean
boundary, rebuild capture/encoder, then resume on a fresh keyframe.

On a short network interruption, the OBS reservation is held for 45 seconds.
Control and media reconnect independently and converge from a fresh state
snapshot; queued stale slider commands are discarded. Authentication failure
never triggers an unauthenticated retry.

## Compatibility boundary

Legacy discovery, reservation, source IDs, and SRT media remain readable.
Legacy phones are labeled as such and receive only controls their V1 status can
support. Professional V2 features require both a V2 Android app and V2 OBS
plugin. Required protocol or security changes ship as one Android/OBS release,
with no enforcing Android auto-update ahead of the matching plugin.

## Explicit non-goals

This redesign does not promise raw YUV transport, PTP/genlock, GPU zero-copy
receive, multi-network bonding, or identical Camera2 behavior across OEMs.
Those remain research tracks and must not block a reliable professional
Camera2/MediaCodec/SRT path.
