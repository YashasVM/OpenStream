# Professional Camera Redesign Status

Last audited: 2026-07-11

This ledger tracks the professional camera-head and OBS control-room redesign.
It records implemented behavior separately from work that still requires code,
native UI validation, or physical-device testing.

## Implemented

- Authoritative Android camera state, capability and telemetry models with
  monotonic revisions.
- Complete repeating Camera2 request construction so one setting change does
  not reset unrelated exposure, focus, color, zoom, torch or stabilization
  state.
- Normalized tap-focus coordinate mapping with rotation, mirroring and crop
  coverage.
- Pairing-code bootstrap, persisted OBS administrator identity and bearer-authenticated
  V2 commands.
- Post-pair authentication for compatibility control routes.
- Paired-secret SRT encryption on Android and OBS without logging the secret in
  the media URL.
- Foreground-service arming foundation with wake lock, high-performance Wi-Fi
  lock, headless preview surface and explicit non-sticky restart behavior.
- Landscape Android camera HUD with focused exposure, focus, white-balance and
  lens palettes, tally, authority feedback and emergency local stop.
- Android thirds/safe-area guides, 95% luminance zebras and live battery,
  thermal and network HUD telemetry.
- Setup-only OBS source properties and a custom Control Room dock with pairing,
  selected-camera controls, exposure, focus, color, lens, health and tally
  surfaces.
- Serialized OBS control commands, mutation-response state adoption, emergency
  Stop availability during remote requests and capability-driven mode controls.
- Event-driven dock model updates with stable camera selection and hidden-dock
  polling suppression.

## Next Engineering Slices

1. Replace the painted OBS focus target with one OBS-rendered selected-camera
   preview and add keyboard focus-reticle control.
2. Derive Program and Preview tally from OBS scene state rather than manual dock
   buttons.
3. Move encoder, camera, control-server and SRT lifetime ownership fully into the
   armed foreground service so unattended streaming survives Activity removal.
4. Replace authenticated HTTP polling with the planned persistent WebSocket
   event channel while retaining explicit revision/conflict semantics.
5. Add live-safe preset save/recall, multi-camera diff/apply and guarded
   Apply-and-Reconnect for disruptive profile changes.
6. Add focus/exposure lock, rack-focus points, histogram, focus peaking, false
   color and audio meters.
7. Model rational broadcast frame rates (23.976, 29.97 and 59.94) end to end and
   cross-check Camera2 ranges against MediaCodec support.

## Verification Still Requiring Equipment

- Pixel 8 Pro and Galaxy S24 Ultra profiles must not be called certified until
  the full control and stream matrix passes on physical hardware.
- FULL, LIMITED, fixed-focus, 30-only, logical multi-camera and separately
  exposed-lens devices need capability and failure-path coverage.
- Two-hour screen-off runs must record battery, thermal, Wi-Fi, A/V sync and
  dropped frames.
- Tap-focus accuracy needs physical verification across rotation, mirroring,
  zoom and letterboxing.
- The Qt Control Room needs a native OBS build and rendered theme/DPI/accessibility
  pass on a machine with the Qt 6 development package.

## Current Automated Gates

- `python -m pytest -q`
- `android/gradlew.bat test lintDebug assembleDebug`
- `git diff --check`

Android builds should use JDK 17 or the Android Studio bundled runtime. The
current Gradle/Kotlin toolchain does not support running under JDK 26.
