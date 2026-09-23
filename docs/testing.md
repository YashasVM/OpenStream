# OpenStream Test Plan

## Release baseline: published v1.0.1

The v1.0.1 release assets were downloaded from GitHub Releases and inspected on
2026-09-23. The complete asset sizes and SHA-256 hashes are saved in
[`evidence/v1.0.1-artifact-metadata.json`](evidence/v1.0.1-artifact-metadata.json).
Reproduce the release download and hash check from the repository root with:

```sh
python tools/record_release_baseline.py \
  --tag v1.0.1 \
  --output-dir /tmp/openstream-v1.0.1 \
  --manifest /tmp/openstream-v1.0.1.json
```

Binary inspection commands used for the Android APK were:

```sh
aapt dump badging /tmp/openstream-v1.0.1/openstream-android.apk
apksigner verify --verbose --print-certs /tmp/openstream-v1.0.1/openstream-android.apk
```

Observed APK metadata: package `dev.openstream.app`, version name `1.0.1`,
version code `1785874048`, and signer certificate SHA-256
`92eb303b80d1fe4ffdbf7bd430f0914463cdafb642088c719cab804dbfecd9b9`. These
values describe the downloaded APK only. They do not prove that it installs or
updates on a particular phone.

The published Windows manual ZIP contains `openstream-obs.dll`. The DLL's
strings include `openstream_phone_v7_source` and `openstream_phone_v8_source`;
this confirms those identifiers occur in the binary, not that either saved
scene loads in OBS. The release installer was hash-checked but not executed.
No candidate APK or plugin package was present in this worktree's build output,
so candidate artifact hashes are not recorded.

| Baseline check | Result | Evidence / limit |
|---|---|---|
| Published APK package, version, code, signer | VERIFIED | APK metadata and certificate inspection above |
| Published release asset hashes | VERIFIED | GitHub API sizes/digests match downloaded bytes in the saved manifest |
| V7/V8 source identifier strings in published Windows DLL | VERIFIED | Extracted DLL contains both strings; runtime compatibility is not established |
| Candidate install over published APK | INCONCLUSIVE | No physical Android device was available |
| Published old scene opens and retains settings in OBS | INCONCLUSIVE | No OBS host or real prior scene collection was available |
| Published plugin receives live media in OBS | INCONCLUSIVE | No OBS host or phone was available |

Treat these as baseline observations, not release acceptance. A string in a
binary is not proof of a working OBS source or scene migration.

## Automated merge and publish gates

The commands below describe repository CI/release gates. Their listing is not
evidence that they passed for a particular commit or artifact.

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
- Android discovers a `shin` OBS source without manual IP entry.
- Tapping a discovered OBS device starts the stream directly.
- Stopping the OBS listener removes the device from Android discovery within about 5 seconds.
- SRT reconnect completes within 2 seconds after a short Wi-Fi interruption.
- OBS receives video through the selected `shin` source.
- OBS receives mono AAC audio at 48 kHz on the source's mixer channel.
- The OBS source shows only the phone camera feed, never the Android screen.
- Starting OBS Virtual Camera while the source is live does not create an
  ever-growing delay; video remains within the configured low-latency window.
- A clap or tone remains aligned between the OBS video and mono audio paths
  after Virtual Camera starts and after a reconnect.
- Telemetry updates at least once per second.

## Android session lifecycle contract

`PhoneSessionService` owns the camera, encoders, SRT connection, discovery, control server, and
reservation state. `MainActivity` observes that owner and attaches the preview surface.

| Event | Expected behavior | Result |
|---|---|---|
| Home, screen lock, Settings, or task removal | The foreground service keeps the session owner and reservation. The Activity detaches its preview. During a live stream, the camera capture session targets the encoder without the preview surface. | INCONCLUSIVE until checked on a physical phone |
| Return to OpenStream | The Activity attaches its new preview surface to the existing owner and shows the current session state. The owner does not create another encoder. | INCONCLUSIVE until checked on a physical phone |
| Notification Stop or in-app Stop | The owner stops media, closes the listener and control services, clears the reservation, and persists Stopped. Returning to the Activity does not restart it. The user must tap Start. | JVM and instrumentation test code added; emulator run unavailable |
| Camera permission revoked | The owner stops media and clears the reservation. After permission is restored, the user must tap Start. | Instrumentation test code added; emulator run unavailable |
| Start while camera permission is denied | The service does not enter the foreground or clear a persisted Stop. The owner remains stopped and requests camera permission. | Instrumentation test code added; emulator run unavailable |
| Process recreation | The foreground service does not restart itself. The next app launch creates an available owner with no reservation. A previously explicit Stop remains stopped. | State transition tested; process-death device check INCONCLUSIVE |

Screen-off camera access depends on Android version and device policy. The foreground service requests
the camera service type before the Activity leaves the foreground, but this code does not prove that a
phone keeps delivering camera frames while locked. Record the phone model, Android version, APK hash,
OBS version, and stream result for each physical run.

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

## Performance measurement record

No before-and-after runtime performance measurements are established by this
test plan. The previously listed changes in frame rate, bitrate, buffer size,
send-path capacity, and stale-frame threshold were configuration comparisons,
not measurements of phone temperature, latency, A/V offset, reconnect time, or
drops. Do not use them as evidence that the candidate performs better.

For each future paired device run, record the exact APK and plugin SHA-256,
phone model and Android version, OBS version and host OS, Wi-Fi conditions,
profile, Virtual Camera state, duration, temperature delta, measured
end-to-end latency, maximum audio/video offset, reconnect time, and dropped
frame count for both builds.
