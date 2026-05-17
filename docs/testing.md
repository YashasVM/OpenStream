# OpenStream Test Plan

## Prototype acceptance tests

- One Android phone streams 1080p30 for 30 minutes without receiver crash.
- SRT reconnect completes within 2 seconds after a short Wi-Fi interruption.
- OBS receives video as one source.
- The OBS source shows only the phone camera feed, never the Android screen.
- 1080p60 works on devices that advertise hardware support.
- Telemetry updates at least once per second.

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

## Thermal tests

Run 1080p30 for 30 minutes and log:

- Battery level
- Temperature
- Encoder state
- Frame drops
- Bitrate

If temperature exceeds the warning threshold, the app should recommend lowering bitrate or switching to 720p.
