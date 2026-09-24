# Local Android emulator and OBS checks

This is a partial development check, not a physical-device acceptance run. It
does not change the phone/host matrix in `docs/testing.md` from `INCONCLUSIVE`.

## Android emulator

| Field | Value |
| --- | --- |
| Date | 2026-09-24, Asia/Kolkata |
| Device | Pixel 7 AVD, x86_64 emulator |
| Android | Android 15, API 35 |
| Candidate source commit | `2f2b22f33c534a5798c01a30feedfe747484b9f5` |
| Debug APK SHA-256 | `7dc355a80d4859751446f20242c6f91bba3035da7e770c8c79545a9562d86957` |
| OBS host used in this run | None; lifecycle instrumentation only |

Commands run from `android/`:

```sh
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The emulator instrumentation suite completed with **4 tests passed**. It covered
Activity recreation with a service-owned runtime, returning from background,
explicit Stop and relaunch, camera-permission loss handling, and Start while the
permission result is denied. The permission-edge tests supplied a denied result
to the service handler; they did not revoke Android's actual runtime permission
because API 35 terminates the target process when its permission is revoked.

The lifecycle test log showed the native SRT listener starting on ports 9000 and
9100. This verifies the JNI bridge and listener startup on the emulator. It does
not verify media delivery to OBS.

Repository checks on this source tree:

```sh
python3 -m pytest -q
```

Result: **118 passed**. Android debug unit tests and APK assembly also passed.

## Linux OBS module load

| Field | Value |
| --- | --- |
| Host | Linux x86_64 |
| OBS | 32.2.2 |
| Source commit | `33280463e42d4a0d6a4c052a1b524e62a28ecf6b` (O1 branch) |
| Module SHA-256 | `2029002dd8adf50e9c2ce4f2e766110bdd7800399f69ea53e4aa7ffd15880a87` |
| Result | Module loaded in an isolated OBS profile |

This checks module loading only. No published old scene collection with known
provenance was available, and no Windows OBS host was available. Old-scene
settings retention, source media, and Windows UI behavior remain
`INCONCLUSIVE`.

## Still required

- Physical Android checks for camera behavior under Home, screen lock, task
  removal, and process recreation.
- Actual Android runtime-permission revoke and restore on a device.
- A published legacy scene opened in real OBS on Windows and Linux, with its
  settings retained and media received.
- End-to-end Android-to-OBS video/audio, controls, reconnect, Virtual Camera,
  A/V sync, and thermal runs from the device acceptance template.
