# OpenStream Test Plan

## Automated merge and publish gates

Every pull request and push to `main` must pass:

- `python -m pytest -q`
- Android debug unit tests
- Android lint
- The native-SRT Android debug build
- The Windows OBS plugin and installer build

Full releases also require a signed Android release build and matching
Android/OBS artifacts before the release is created.

The Python repository-contract tests are fast guardrails, not substitutes for
executing Kotlin or C++ production code. New parsers and protocol state
machines should receive native unit tests rather than Python copies of their
implementation.

## Device acceptance tests

- One Android phone streams 1080p30 for 30 minutes without receiver crash.
- Android discovers an OBS `OpenStream Phone Link` listener without manual IP entry.
- Tapping a discovered OBS device starts the stream directly.
- Stopping the OBS listener removes the device from Android discovery within about 5 seconds.
- SRT reconnect completes within 2 seconds after a short Wi-Fi interruption.
- OBS receives video as one source.
- OBS receives mono AAC audio at 48 kHz on the source's mixer channel.
- The OBS source shows only the phone camera feed, never the Android screen.
- 1080p60 works on devices that advertise hardware support.
- Telemetry updates at least once per second.

## Android/OBS compatibility matrix

Before changing discovery fields, pairing URLs, authentication, reservations,
or media framing, test all four combinations:

| Android | OBS plugin | Expected result |
|---|---|---|
| Previous | Previous | Existing workflow remains operational |
| Previous | Candidate | Candidate remains backward-compatible or provides a clear upgrade path |
| Candidate | Previous | Candidate remains backward-compatible |
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
