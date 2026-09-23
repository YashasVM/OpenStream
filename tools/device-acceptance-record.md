# OpenStream device acceptance record

Copy this file for each Android device and OBS host pair. Run the checks in
order. Keep raw logs, photos, and measurements with the record. Do not change an
unrun result from `INCONCLUSIVE`.

## Run identity

| Field | Value |
| --- | --- |
| Record ID | |
| Date and time, including time zone | |
| Tester | |
| Git commit | |
| Android device model and hardware revision | |
| Android version and security patch | |
| OBS host OS, version, and architecture | |
| OBS version and build | |
| Phone install type: clean install or update | |
| App version name and version code | |
| APK path | |
| APK SHA-256 | |
| APK signer certificate SHA-256 | |
| Plugin package path and format | |
| Plugin package SHA-256 | |
| Loaded plugin module path and SHA-256 | |
| Scene collection used | |
| Result folder for logs and captures | |

Compute artifact hashes before installing. On Linux or macOS, run:

```sh
sha256sum /path/to/openstream-android.apk /path/to/plugin-package
```

On Windows PowerShell, run:

```powershell
Get-FileHash -Algorithm SHA256 .\openstream-android.apk
Get-FileHash -Algorithm SHA256 .\plugin-package.zip
```

Inspect Android package metadata and signer before installation:

```sh
aapt dump badging /path/to/openstream-android.apk
apksigner verify --verbose --print-certs /path/to/openstream-android.apk
```

Install with `adb install /path/to/openstream-android.apk` for a clean app
install. For an update run, use `adb install -r /path/to/openstream-android.apk`
and record the result. Do not uninstall the published app before an update
check.

Record the package hash and the hash of the module OBS actually loads. If more
than one OpenStream module copy exists, stop and record `NOT VERIFIED` for
plugin startup until the duplicate is removed and the run is repeated.

## Network and stream settings

| Field | Value |
| --- | --- |
| Router or access point model and firmware | |
| Wi-Fi band and channel | |
| Phone SSID and subnet | |
| OBS host interface, SSID or wired, and subnet | |
| VPN enabled on phone or host | |
| Guest network or client isolation enabled | |
| Signal strength at phone | |
| Video profile, resolution, and frame rate | |
| Bitrate | |
| Configured SRT latency | |
| Virtual Camera enabled during run | |
| Other network traffic or impairment | |

## Check results

Use `VERIFIED`, `NOT VERIFIED`, or `INCONCLUSIVE`. For every result, record the
time, observed behavior, and evidence path. Keep `INCONCLUSIVE` if a required
device, host, permission, artifact, or measurement is unavailable.

| # | Check | Result | Observation and evidence path |
| --- | --- | --- | --- |
| 1 | Install the exact APK. For an update run, install over the previous published APK signed with the same identity. Confirm the package ID, version code, and signer match the candidate manifest. | INCONCLUSIVE | |
| 2 | Install OBS and the plugin on a clean host setup. Confirm the intended plugin module loads once, record the module path, and record the source name and available controls. | INCONCLUSIVE | |
| 3 | Add one OpenStream source. Start the phone app and confirm first discovery without manual IP entry. Record discovery time. | INCONCLUSIVE | |
| 4 | Select the phone and confirm reservation. Record the phone state and OBS state before media arrives. | INCONCLUSIVE | |
| 5 | Confirm live video from the selected camera and mono AAC audio in OBS. Confirm the source does not show the phone screen. | INCONCLUSIVE | |
| 6 | Try front/rear lens selection, torch when supported, and zoom. Record the selected camera and observed control response. | INCONCLUSIVE | |
| 7 | Use Stop in the expected user flow. Confirm media stops, reservation releases, and the UI reports a stopped or disconnected state. Record whether another Start works. | INCONCLUSIVE | |
| 8 | Restore the stream, interrupt Wi-Fi for less than 5 seconds, and record whether media recovers without restarting either app. Measure recovery time. | INCONCLUSIVE | |
| 9 | Restore the stream, make the phone unreachable for at least 60 seconds, then restore network access. Record recovery time and whether the user must act. | INCONCLUSIVE | |
| 10 | Keep the phone streaming, restart OBS, reopen the same scene, and record whether the plugin and source return and whether the phone reconnects. | INCONCLUSIVE | |
| 11 | Remove the OpenStream source while streaming. Confirm the phone reservation releases and record any OBS or app error. | INCONCLUSIVE | |
| 12 | Start the OBS Virtual Camera while the source is live. Record startup result, delay behavior, and any media loss. | INCONCLUSIVE | |
| 13 | With Virtual Camera active, clap once in view of the phone camera and microphone. Record video and audio, then measure the A/V offset. Repeat after reconnect. | INCONCLUSIVE | |
| 14 | Run video and audio continuously for 30 minutes. Record start/end times, dropped frames, audio interruptions, and any app or OBS failure. | INCONCLUSIVE | |
| 15 | During the 30-minute run, record battery level and phone temperature at start, every 5 minutes, and at end. Record encoder state and bitrate when available. | INCONCLUSIVE | |
| 16 | Add and keep an unrelated OBS source active. Make the phone unreachable during an OpenStream failure. Confirm the unrelated source stays active and OBS exits without a hang when you stop it. | INCONCLUSIVE | |
| 17 | Restore the network and repeat discovery, reservation, media, controls, Stop, and Start after the failures above. | INCONCLUSIVE | |

## Measurements

| Measurement | Value and method |
| --- | --- |
| Time from app launch to discovery | |
| Time from selection to reservation | |
| Time from reservation to first video | |
| Time from reservation to first audio | |
| End-to-end video latency and method | |
| Maximum A/V offset before reconnect | |
| Maximum A/V offset after reconnect | |
| Short-loss recovery time | |
| Long-loss recovery time | |
| Dropped video frames during 30 minutes | |
| Audio gaps or dropouts | |
| Battery level at start and end | |
| Phone temperature at start and end, including units | |
| Thermal warning or throttling observed | |
| Other media gaps or failure reasons | |

## Logs and evidence

Save the Android and OBS logs from before app launch through the final reconnect
check. Record commands and file paths. For Android, an example capture command
is:

```sh
adb logcat -c
adb logcat -v threadtime > android-logcat.txt
```

Stop the capture with Ctrl+C after the run. Save the OBS log from the OBS Help
menu or the host's OBS log directory. Include screenshots or short clips for
discovery, reservation, controls, sync, and any failure. Redact Wi-Fi secrets,
tokens, and personal data before sharing the record.

| Evidence | Path or description |
| --- | --- |
| Android log | |
| OBS log | |
| OBS scene collection | |
| Screen capture or photos | |
| Measurements or analysis files | |
| Failure details and reproduction steps | |

## Run conclusion

| Field | Value |
| --- | --- |
| Overall result | INCONCLUSIVE |
| Checks not run | |
| Failed checks | |
| Follow-up issue or PR | |
| Tester notes | |

Keep the overall result `INCONCLUSIVE` while any required check remains
inconclusive. A failed required check makes the run `NOT VERIFIED`. Mark the run
`VERIFIED` only when every required check passes and the evidence is saved.
