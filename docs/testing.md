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

## Device acceptance runs

Use the [device acceptance record template](../tools/device-acceptance-record.md)
for every phone and OBS host pair. Run the same checks on at least three
distinct Android devices and both supported OBS hosts before G1. Record exact
APK and plugin SHA-256 values for each run. A device or host you did not run is
unverified for that release.

Track each phone and host pair in this matrix. Replace each status only after
you complete the run and link its record. An unrun pair stays `INCONCLUSIVE`.

| Android device | Android version | Windows OBS | Linux OBS | Run records |
| --- | --- | --- | --- | --- |
| Device 1 | | INCONCLUSIVE | INCONCLUSIVE | |
| Device 2 | | INCONCLUSIVE | INCONCLUSIVE | |
| Device 3 | | INCONCLUSIVE | INCONCLUSIVE | |

The partial local development checks on an API 35 emulator and Linux OBS are
recorded in [the 2026-09-24 evidence note](evidence/2026-09-24-local-emulator-and-obs.md).
They do not count as physical-device or old-scene acceptance, so the matrix
above remains `INCONCLUSIVE`.

The template starts every result as `INCONCLUSIVE`. Change a result to
`VERIFIED` only after you run the check and save evidence. Use `NOT VERIFIED`
when a run fails or does not meet its expected result. Do not infer physical
behavior from CI, source inspection, or a binary string.

The run checks install and update, clean OBS startup and plugin load, phone
discovery and reservation, first video and AAC audio, camera controls, Stop,
short and long network loss, OBS restart, source removal, Virtual Camera, a
30-minute stream, A/V sync, thermal readings, OBS shutdown with the phone
unreachable, and isolation of unrelated OBS sources during an OpenStream
failure. The record captures latency, dropped media, recovery time, battery,
temperature, and logs.

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

For each paired device run, record the exact APK and plugin SHA-256, phone
model and Android version, OBS version and host OS, Wi-Fi conditions, profile,
Virtual Camera state, duration, temperature delta, measured end-to-end latency,
maximum audio/video offset, reconnect time, and dropped frame count for both
builds. The template separates recorded values from expected behavior so an
unmeasured target stays `INCONCLUSIVE`.
