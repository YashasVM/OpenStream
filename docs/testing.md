# OpenStream Test Plan

## Automated merge and publish gates

Every pull request and push to `main` must pass:

- `python -m pytest -q`
- Android debug unit tests
- Android lint
- The native-SRT Android debug build
- The Windows OBS plugin and installer build

The Android update publish job depends on the test/lint/build job and must not
run when that gate fails. Full releases also require a signed Android release
build and matching Android/OBS artifacts before the release is created.

The Python repository-contract tests are fast guardrails, not substitutes for
executing Kotlin or C++ production code. New parsers and protocol state
machines should receive native unit tests rather than Python copies of their
implementation.

## Device acceptance tests

- One Android phone streams 1080p30 for 30 minutes without receiver crash.
- Android discovers an OBS `OpenStream Phone Link` listener without manual IP entry.
- The discovery screen distinguishes available, busy, and selected camera slots.
- Tapping an available discovered slot reserves it and starts the connection flow.
- The Android status distinguishes discovery, reserved, connecting, live, reconnecting, stopped, and actionable error states.
- Permission rationale, denial, and permanently-denied system-settings recovery are actionable.
- Stopping the OBS listener removes the device from Android discovery within about 5 seconds.
- SRT reconnect completes within 2 seconds after a short Wi-Fi interruption.
- Reconnecting and errors are announced in the Android UI without leaving stale live controls enabled.
- OBS receives video as one source.
- OBS receives mono AAC audio at 48 kHz on the source's mixer channel.
- The OBS source shows only the phone camera feed, never the Android screen.
- 1080p60 works on devices that advertise hardware support.
- Telemetry updates at least once per second.

## UI and accessibility acceptance tests

Test Android on a compact Android 10 device and a current large-screen device,
then test the OBS dock at narrow and wide widths with Windows scaling at 100%,
150%, and 200%.

- Camera controls and the primary action have at least 48 dp touch targets.
- TalkBack announces controls, selected slots, connection-state changes, and errors in a logical focus order.
- Large font does not clip status, slot labels, settings validation, or recovery actions.
- Camera preview, controls, and transient messages stay clear of system bars and gesture insets.
- Pinch zoom has an accessible slider alternative; unsupported torch/lens actions are disabled with a visible explanation.
- Settings preserve saved values, validate host/port/latency inline, and keep manual networking collapsed until explicitly enabled.
- The OBS dock lists every OpenStream source and updates on add, remove, rename, scene-collection change, reconnect, and unload.
- OBS slot cards expose one state-appropriate primary action and never enable remote controls unless a compatible phone is live.
- Progress, success, warning, and failure are understandable without relying on color alone.
- OBS light/dark themes and narrow dock widths do not hide status or the primary recovery action.

## Android/OBS compatibility matrix

Before changing discovery fields, pairing URLs, authentication, reservations,
or media framing, test all four combinations:

| Android | OBS plugin | Expected result |
|---|---|---|
| Previous | Previous | Existing workflow remains operational |
| Previous | Candidate | Candidate remains backward-compatible or provides a clear upgrade path |
| Candidate | Previous | Candidate remains backward-compatible or is withheld from Android auto-update |
| Candidate | Candidate | New behavior works end to end |

A candidate Android build that introduces a required control token must not be published
while the generally available OBS plugin still advertises tokenless beacons.
Protocol-breaking changes ship as one full release with both artifacts.

## Network impairment tests

Use network tooling or router controls to test:

- 1% packet loss
- 3% packet loss
- 5% packet loss
- 50 ms jitter
- temporary disconnect under 5 seconds

Expected behavior:

- The stream may degrade, but the app should not crash.
- Reconnect attempts should continue automatically.
- Telemetry should report degraded state.
- SRT latency can be increased from 120 ms to 200 ms for lossy networks.

## Developer receiver

`tools/openstream_receiver.py` remains available for FFmpeg/SRT smoke tests
without OBS. It is not part of the normal user workflow.

## Thermal tests

Run 1080p30 for 30 minutes and log:

- Battery level
- Temperature
- Encoder state
- Frame drops
- Bitrate

If temperature exceeds the warning threshold, the app should recommend lowering bitrate or switching to 720p.
