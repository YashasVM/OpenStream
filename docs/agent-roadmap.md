# OpenStream agent roadmap

This roadmap turns the [product readiness review](product-readiness-review.md) into work that a coordinator can assign to subagents. It targets one Android phone supplying one native OBS camera source over a local network on Windows and native Linux. It does not assume that every issue in the review is a confirmed runtime defect. Measure uncertain behavior before changing it.

The roadmap is complete when the **same release** of the Android app and both OBS plugins can be installed, updated, connected, controlled, disconnected, and reconnected on supported hardware; the exact artifacts pass the automated and physical gates below; and the user-facing names, docs, and downloads agree. A passing unit-test suite alone does not meet that definition.

## How the coordinator runs the work

1. Create one issue and one PR for each numbered package. Give each subagent its own branch or worktree. A subagent owns its files until its PR is reviewed; no two agents edit `MainActivity.kt`, `openstream-source.cpp`, or release workflows at the same time.
2. Start each package from the latest accepted predecessor. Record the old behavior, make the smallest change, run its checks, and attach the evidence to the PR. Keep Android, OBS, and `docs/protocol.md` in one PR when a wire behavior changes.
3. Require a reviewer to inspect the actual diff and test output. Use **VERIFIED**, **NOT VERIFIED**, or **INCONCLUSIVE** for each acceptance check. An unrun physical check is INCONCLUSIVE, even when CI passes.
4. Keep `applicationId=dev.openstream.app` and the published Android signing identity. Preserve source timestamps, bounded queues, hardware-encode policy, and old scene/protocol identities that real prior artifacts used. Do not introduce a second phone encoder, silent software video fallback, or network work on the UI thread.
5. Do not publish a release from an open PR. The coordinator collects the exact APK and plugin hashes, checks the four Android/plugin version combinations, and obtains the physical acceptance record before tagging and publishing.

Each subagent's handoff must name its package ID, PR, changed files, Android-to-OBS workflow step affected, old/new compatibility result, commands and device tests actually run, measured before/after results for performance work, and remaining INCONCLUSIVE checks. The coordinator updates [docs/testing.md](testing.md) only with observed results.

## Work order and parallel lanes

| Stage | Coordinator's gate | Work that can run in parallel after the gate |
| --- | --- | --- |
| 0. Establish the baseline | B1 records the published artifacts; B2 then records candidate device behavior | After B1, V1, O1, and A1 can use separate worktrees while B2 runs. |
| 1. Make builds and identity truthful | V1 and V2 prove matching artifact versions; O1 proves old scene behavior | A2 follows A1. V3 follows V2. |
| 2. Make the session reliable | A3 follows A2; the session owner and Stop behavior pass device checks | D1 can proceed separately. P1 follows A3 and O1. |
| 3. Finish both ends of the connection | P1 and P2 pass protocol and discovery checks; O2 provides host control parity | Run P1, P2, O2, then M1 in sequence where they share source files. U1 and U2 follow the state and control changes. |
| 4. Prove a releasable product | G1 checks exact artifacts, compatibility, device matrix, and new-user trials | Optional performance or transport work begins only from measured demand. |

The coordinator can change this order when baseline evidence shows a more urgent defect. Write the reason and the replacement acceptance check in the relevant PR. Keep protocol changes together across Android and OBS, even when subagents investigate the two sides separately.

## Stage 0: establish what works now

### B1. Record the published release and candidate baseline

- **Agent owns:** `docs/testing.md`, a reproducible evidence script or checklist under `tools/`, and saved artifact metadata. Download the published v1.0.1 APK and Windows plugin. Read the APK's real package ID, version code, and signing fingerprint. Save the asset hashes and OBS scene/source IDs from an actual old scene collection. Record the current candidate's APK/plugin hashes separately.
- **Prove:** Install the candidate APK over the published APK on a phone with the same signing identity. Open an old scene in real OBS and note whether the source loads, retains settings, and receives media. Record the result as VERIFIED or INCONCLUSIVE, not as assumed compatibility.
- **Blocker:** This needs a phone, OBS, the published artifacts, and release signing access. Other code packages may proceed, but G1 cannot pass without it.

### B2. Build the repeatable end-to-end check

- **Agent owns:** `docs/testing.md` and a small runner or recording template under `tools/`. Specify device model/Android version, OBS version/host OS, APK/plugin hashes, Wi-Fi setup, latency setting, result, and log location.
- **Prove:** Exercise install, first discovery, reserve, first video and AAC audio, lens/torch/zoom, Stop, reconnect after short and long loss, OBS restart, source removal, Virtual Camera, and a 30-minute run. Include a clap or tone for A/V sync. Record latency, dropped media, recovery time, battery, and temperature.
- **Gate:** At least three distinct Android devices and both supported OBS hosts run the same script before G1. A device or host not run is explicitly unsupported or unverified for that release.
- **Depends on:** B1, because both packages update `docs/testing.md`. On each host, start OBS from a clean installation, confirm one plugin loads and its source and controls appear, then test OBS shutdown with the phone unreachable. Keep an unrelated OBS source active while OpenStream fails to check isolation.

## Stage 1: one version and honest OBS identity

### V1. Move the candidate version to one source

- **Agent owns:** new `release/version.properties` with `productVersion` and `androidVersionCode`; `android/app/build.gradle.kts`; `obs-plugin/CMakeLists.txt`; `build_plugin.bat`; `build_plugin_linux.sh`; `tools/installer/openstream-obs-plugin.iss`; the Windows and Linux CI workflows; and version tests.
- **Change:** Parse the root values in Gradle and CMake. Make build scripts pass the same product version to CMake and Inno. Remove independent `1.0.1` defaults and the literal `1.0.1` assertions in `tests/test_repo_contract.py`. Give private `website/package.json` a tooling version, not a public product claim.
- **Prove:** A deliberate mismatch fails a pull-request build. A normal build reports one candidate product version on Android, Windows, and Linux. Keep `SHIN/1`, saved OBS source IDs, OBS 32.2.1/FFmpeg ABI, and vendored SRT versions separate. Do not lower the current Android version code or change signing.
- **Depends on:** B1's actual published APK metadata. The next release code must exceed every published APK code for the same application ID, including prereleases, and the current candidate code.

### V2. Check the binaries, installer, and release tag

- **Agent owns:** a version checker under `tools/`, `.github/workflows/release.yml`, plugin version metadata in `obs-plugin/src/openstream-source.cpp`, package scripts, and a generated release manifest.
- **Change:** Embed an inspectable product version in the plugin. Have CI compare the tag, APK package/name/code/signing certificate, both plugin versions, Windows installer metadata, artifact hashes, and manifest. Check the previous **published APK's** code and certificate; do not infer them only from historical tag timestamps. Fail closed when fetching the previous release fails. Reject a stale binary in Linux `--package-only` mode. Split candidate build from publication: current `release.yml` builds and calls `gh release create` automatically on a tag push, before any physical acceptance gate.
- **Prove:** CI rejects a wrong tag, wrong APK version code, wrong signing certificate, stale plugin binary, wrong installer version, and missing artifact. It accepts a matched candidate. The checker runs on PRs where possible and again on the exact release artifacts. Candidate workflow uploads signed artifacts without publishing; a separate manual publish step consumes those exact bytes and their hashes.
- **Depends on:** V1. The release manifest names protocol and supported OBS ABI separately from product version.

### V3. Separate development version from published download version

- **Agent owns:** `website/src/main.jsx`, `website/index.html`, `website/package.json` and lockfile, `README.md`, `docs/release.md`, and `docs/release-notes-template.md`.
- **Change:** Remove hard-coded current-version claims from pages that link to `releases/latest`. Show “Latest release” until the deployment has the published release manifest, then show that manifest's version and matched assets. Keep historical notes fixed. Generate new release notes for each release instead of overwriting v1.0.1 history.
- **Prove:** A candidate bump cannot make the public site advertise an unpublished APK. A published release changes the site only after matching assets exist. Website build and link checks pass.
- **Depends on:** V2's manifest. This package does not change the Android application ID or release signing.

### O1. Prove and repair OBS source identity

- **Agent owns:** `obs-plugin/src/openstream-source.cpp`, native source tests, old scene fixtures, and setup docs.
- **Current evidence:** The plugin registers only `shin_phone_source` and displays `shin`. Current docs say users should add `OpenStream V8` and claim V7 saved-scene support. The source does not establish that claim.
- **Change:** Inspect source IDs in published plugins and saved scenes. Keep every confirmed old ID loadable through an adapter or explicit migration. Change only the new source's visible picker name to `OpenStream Camera`; retain its stable internal ID. Correct docs to match what OBS actually shows.
- **Prove:** A published-release scene opens in Windows and Linux OBS without a missing-source dialog, keeps settings, and receives media. A new scene displays `OpenStream Camera`. If an old ID never existed in an artifact, document that rather than inventing an adapter.
- **Depends on:** B1. Do this before changing UI labels or promising scene compatibility.

## Stage 2: make the Android session reliable

### A1. Define the session states and Stop behavior

- **Agent owns:** `android/app/src/main/java/dev/openstream/app/ReservationState.kt`, a new pure session-state module, `MainActivity.kt` call sites, and JVM behavior tests.
- **Change:** Model Available, Selected, Reserved, Connecting, Live, Reconnecting, Error, and Stopped with explicit transition events and a generation for stale work. Decide and implement distinct actions: **Stop** ends media and does not auto-restart; **Start** makes the phone available again; **Disconnect** releases the OBS reservation while the phone remains available. Today the Stop button sets `pendingListenerStart`, so its label and behavior disagree.
- **Prove:** Unit tests cover duplicate reserves, rapid phone switching, timeout, stale callback, reconnect, release, Stop, and Start. On a device, Stop remains stopped across background/foreground, and a visible Start action returns to reserve/live without restarting the app. The phone and OBS show the same transition. Avoid spreading state flags across additional UI callbacks.

### A2. Remove blocking teardown from Android UI paths

- **Agent owns:** `MainActivity.kt`, `camera/Camera2Controller.kt`, `stream/SrtStreamClient.kt`, encoders, and native SRT adapter as needed.
- **Change:** Move camera/codec/socket start and stop to one serialized, bounded session worker. Keep view changes on the main thread. `surfaceDestroyed()` currently calls camera and server teardown from the UI callback; other UI paths can reach native disconnect and codec stop. Ensure each operation checks session generation so late work cannot revive a stopped stream.
- **Prove:** Android behavior tests cover rapid start/stop, lens change, Settings, preview destruction, and stale callbacks. StrictMode or trace evidence on real phones shows no network/disk work or long codec teardown on the UI thread. Each queue or worker declares its capacity and overflow behavior.
- **Depends on:** A1. Do not parallel-edit `MainActivity.kt` with A3.

### A3. Give live media a lifecycle owner

- **Agent owns:** a foreground camera session module or equivalent, `MainActivity.kt`, `AndroidManifest.xml`, preview binding, notification actions, and Android tests.
- **Change:** The session owner holds camera, encoders, SRT, discovery, control, and reservation state. The activity observes state and supplies a preview surface. Define behavior for Home, screen lock, Settings, permission revoke, task removal, and process recreation under supported Android versions. Keep one video encoder and make notification Stop final.
- **Prove:** A device run shows the documented background behavior without a stale reservation or second encoder. A fast return to the app restores preview and current state. Behavioral tests exercise owner transitions; instrumentation covers lifecycle and permission edges where an emulator can reproduce them.
- **Depends on:** A2. Android background-camera restrictions require device checks before promising uninterrupted screen-off streaming.

## Stage 3: secure and finish the connection

### P1. Make pairing and control authorization one protocol change

- **Agent owns:** Android `control/CameraControlServer.kt`, discovery and reservation code, OBS `openstream-source.cpp` control client, `docs/protocol.md`, and cross-product tests. One integration PR owns both ends and the wire documentation.
- **Current evidence:** V1 control is not authenticated. `/reserve` can receive an optional token; camera commands rely on the reserved peer address. The docs claim a pairing URL carries a control token, but the current URL builder does not add one.
- **Change:** Specify capability/version negotiation, phone approval, a session secret, expiry and rotation, and authorization for every mutating command. Define a safe response for previous Android/new OBS and new Android/previous OBS. Do not silently accept an incompatible pairing or expose secrets in discovery, logs, or a support bundle.
- **Prove:** Another LAN host cannot reserve or control the camera; stale and released secrets fail. Test reserve, control, release, reconnect, and both previous/candidate combinations on actual Android and OBS. Keep source timestamps and SRT media unchanged unless a separate measured media change is needed.
- **Depends on:** A3 and O1. The coordinator owns this shared protocol PR; separate subagents may investigate each side without concurrent edits to the same branch.

### P2. Repair discovery and deep-link fallback

- **Agent owns:** Android `discovery/*.kt`, `stream/ConnectionTarget.kt`, `MainActivity.kt`, `AndroidManifest.xml`, OBS pairing URL code, protocol docs, and parsing tests.
- **Current evidence:** The manifest advertises `openstream://connect`, but the manual `ConnectionTarget.fromPairingUri()` path accepts only `shin://connect`. Confirm behavior for published links before modifying it.
- **Change:** Parse confirmed legacy and current schemes, validate host/port/latency and input sizes, and show a specific reason when multicast, Wi-Fi, VPN, firewall, or port binding prevents discovery. Keep manual connection as a reachable fallback.
- **Prove:** Old and new links connect on a device; malformed links are rejected without a crash. Discovery and manual pairing work on the same network and on a multicast-blocked network. Review pairing behavior against P1's version rules.
- **Depends on:** P1's protocol specification. If the parser fix is independent, it may be a smaller earlier PR after B1.

### O2. Give Windows and Linux equivalent live controls

- **Agent owns:** `obs-plugin/CMakeLists.txt`, `src/openstream-dock.cpp`, `src/openstream-source.cpp`, OBS tests, and setup docs.
- **Current evidence:** The dock builds only on Linux, while Windows docs tell users to open it. Linux source properties and the dock also overlap on some controls.
- **Change:** Make a dock or equally clear live control area available on both supported hosts. Keep setup settings in source properties; put live connect/release, selection, status, zoom, lens, torch, and identify actions in one place per host. Remove a duplicate control only after its replacement works on Windows and Linux.
- **Prove:** Real OBS on both hosts shows each action, the action reaches the selected phone, and an unreachable phone cannot freeze OBS. The old saved scene still opens.
- **Depends on:** O1, V1, and P1. Apply its protocol changes before editing the same OBS source file for live controls.

### M1. Make media failures observable and test the receiver behavior

- **Agent owns:** Android `encoder/MediaCodecAudioEncoder.kt`, video encoder, `stream/SrtStreamClient.kt`, `cpp/openstream_srt.cpp`; OBS `media-clock.hpp`, `async-control-client.*`, `openstream-source.cpp`; and behavioral media tests.
- **Change:** Surface video-only, audio muted/failed, encoder unavailable, software AAC fallback, SRT queue saturation, and reconnect reasons in the app and OBS. Keep phone software video encode disabled unless the user explicitly opts into it. Preserve source presentation timestamps and log recording gaps. Add executable tests for packet timestamps, loss, bounded queues, stop/release, and reconnect rather than relying only on Python source-text checks.
- **Prove:** A failed microphone does not silently look healthy; loss and overflow produce a visible state and bounded recovery. A/V offset stays measured after reconnect and Virtual Camera starts. Every performance adjustment includes same-device before/after CPU, latency, drops, battery, and heat.
- **Depends on:** A3, P1, and O2. Treat hardware OBS decode as an experiment after the baseline: the current probe always falls back to software. Ship a hardware path only if measured benefit justifies it and the fallback remains explicit.

### U1. Make setup and labels match the real workflow

- **Agent owns:** Android `strings.xml`, layouts, `MainActivity.kt`, `SettingsActivity.kt`; OBS visible labels and docs; new-user acceptance script.
- **Change:** Show OpenStream consistently while keeping confirmed legacy IDs and stored preference keys. Guide permission grant, OBS discovery, selection, pairing, first frame, and audio check. Put raw IP, ports, and latency in Advanced. Show version mismatch and offer a matched download. Make Stop and Disconnect distinct and discoverable.
- **Prove:** A person who has never built the repo reaches live video and audio without reading protocol docs, can identify the connected phone, and can stop or recover deliberately. Check labels and accessibility on a real phone and both OBS hosts.
- **Depends on:** A1, A3, O1, O2, P1, and P2. Do not use a broad visual redesign to substitute for connection clarity.

### U2. Add actionable diagnostics and a feedback path

- **Agent owns:** Android and OBS status presentation, bounded recent-event logs, a redacted support export, `docs/set-up.md`, and issue templates.
- **Change:** Report discovery, control, reservation, SRT media, audio, and encoder health separately. Give a next action for denied permission, wrong network, phone busy, firewall/port issue, codec failure, and stale reservation. Export exact app/plugin/OBS/OS versions, state transitions, and error codes only with user action; remove secrets, IP addresses, and raw media.
- **Prove:** Each injected failure maps to one clear message on the phone and OBS, and a tester can submit a useful report without a developer extracting Logcat. Validate redaction with tests and inspect an actual export.
- **Depends on:** P1, M1, and U1.

### D1. Add the public trust and support basics

- **Agent owns:** root license/provenance inventory, `SECURITY.md`, `CONTRIBUTING.md`, privacy/support docs, issue templates, and release notes process.
- **Change:** Identify copyright holders and bundled dependency obligations. The project owner chooses the top-level license after reviewing that inventory; then add the approved license. Publish supported host/device limits, a security contact, update instructions, and a feedback form. Do not call the repository open-source before the license exists.
- **Prove:** A new contributor can build and report an issue; a user can find the supported platforms and privacy behavior from the download page. After candidate staging in G1, run 10 to 20 matched-artifact beta installs with people outside the developer workflow. Record setup completion, time to first frame, failed reconnects, and common support questions.
- **Depends on:** B1 for true release claims. It can run in parallel with technical packages except where it edits their docs.

## Stage 4: release gate

### G1. Publish only a matched, exercised release

- **Agent owns:** release workflow, `docs/release.md`, final evidence record, and release manifest. The coordinator is the single writer of the final release decision.
- **Automated gate:** Repository tests, Android JVM tests/lint/native-SRT APK, Android instrumentation where practical, Windows and Linux plugin builds/native tests, installer/package smoke tests, website build, version checker, signature/code continuity, and hashes must be VERIFIED. CI's Python contracts supplement native tests; they do not replace them.
- **Candidate staging:** Build one signed APK and both host packages from a fixed commit in a workflow that uploads candidate artifacts without publishing. Pin the commit and record each artifact hash in the manifest. The current tag workflow auto-publishes, so remove that behavior before this gate is used.
- **Physical gate:** Download and install those exact bytes on at least three Android devices and both supported OBS hosts. Start real OBS and check clean module load, one source picker entry, and the expected controls before media testing. Complete B2's 30-minute, network-loss, A/V sync, Virtual Camera, controls, screen lock, old scene, OBS shutdown, unrelated-source isolation, and reinstall/update checks. Test previous/previous, previous/candidate, candidate/previous, and candidate/candidate Android/plugin combinations. Record actual results and release blockers. A missing device result stays INCONCLUSIVE and blocks claims of that device's support.
- **Publication:** After sign-off, a manual publish workflow verifies the staged hashes, tags the tested commit, and attaches the **same files** and release notes. It does not rebuild. The published SHA-256 hashes must equal the tested manifest. A changed artifact requires repeat verification. Then update the website's published version. The release notes state tested hosts and known limits. Do not treat an automatic CI green result as the entire product sign-off.

## Add after the core release is proven

- Add a simple Stable 720p and Balanced 1080p profile selector when device measurements show both are sustainable. Show the actual resolution, frame rate, bitrate, latency target, and thermal warning.
- Add USB only if beta reports show Wi-Fi setup or stability is a frequent blocker and a prototype shows better results without making installation harder.
- Add an optional, privacy-safe feedback channel and a public compatibility matrix from completed device runs.
- Consider hardware decode, more simultaneous phones, macOS, remote contribution, and advanced camera controls only with a measured use case and a test plan for both ends.

## Remove, hide, or stop claiming

- Remove hard-coded current product versions from build defaults, tests, and public download copy. Keep historical release notes and independent protocol/ABI numbers.
- Remove `shin` from visible new-user labels after confirming and preserving the internal IDs that old artifacts require.
- Remove duplicate OBS live controls only after Windows and Linux have the same working replacement. Hide manual network fields in Advanced, not in protocol or developer docs.
- Stop calling unmeasured reconnect behavior “reliable” or promising a latency number without device evidence. Stop claiming V7/V8 scene support until old scene fixtures load in real OBS.
- Keep the Python receiver as a developer tool. Do not add cloud accounts, built-in recording, filters, automatic microphone mixing, or a second phone encoder to the supported one-phone path.
