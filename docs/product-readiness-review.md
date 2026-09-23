# OpenStream product readiness review

Reviewed on 2026-09-23 against the current `fix/openstream-prod-reliability` candidate. This is a source and build review, not a device acceptance result. The published v1.0.1 downloads predate the candidate changes.

## Assessment

**Confidence that this candidate is ready for broad, professional use: 5/10.** This is a judgment about release evidence and user experience, not a measured failure rate. The core Android-to-OBS design is credible and the product reportedly works, but a new user still has several ways to get stuck without a clear recovery path. The most important candidate workflow has no recorded physical phone and OBS sign-off.

| Area | Score | Evidence |
| --- | ---: | --- |
| Core media and protocol engineering | 7/10 | Hardware phone encode, bounded SRT send queue, source timestamp handling, reservation logic, reconnect status, Linux and Windows native builds. See `docs/architecture.md`, `docs/protocol.md`, and `docs/reliability-audit.md`. |
| Reliability proof | 4/10 | CI runs repository, Android, and plugin checks, but `docs/reliability-audit.md` records no candidate device run. `docs/testing.md` lists the missing 30-minute, loss, sync, and old/new compatibility checks. |
| First-use and daily operation | 4/10 | Discovery, manual connection, source controls, and setup docs exist. Android still labels itself `shin`; OBS uses legacy V7/V8 and `shin` names. Connection recovery depends on status text and setup instructions. |
| Installation and release discipline | 6/10 | Android signing and version-code gates, Windows installer tests, Linux packaging, checksums, and CI are present. The published release differs from the current candidate and Linux support is still a source-built candidate. |
| Trust and user feedback | 3/10 | No top-level project license, contribution guide, security contact, privacy statement, issue templates, or recorded user acceptance results. The absence of open issues is not evidence of user satisfaction. |

The score is weighted toward the core workflow and reliability proof. It should rise only after candidate builds pass repeatable device tests and people outside the development environment complete setup and recovery without help.

## What already works well

- The product has a coherent local path: Camera2, hardware AVC and AAC, MPEG-TS over SRT, and a native OBS source. It avoids a cloud account and a second phone encoder in the standard mode.
- The app and plugin have explicit ownership and reconnect behavior. The phone transport has a byte-bounded send queue; the receiver preserves source timestamps and logs late media gaps.
- CI covers Android build and lint, C++ tests, Windows packaging, Linux packaging, and release signing checks. Legacy OBS scene identifiers remain compatible.
- The repo has a setup guide and protocol documentation, which give future maintainers a base for field support.

## Highest-impact gaps

1. **Prove the complete candidate workflow on devices.** `docs/reliability-audit.md` explicitly lacks a physical Android-to-OBS run. Test install-over-release, discovery, pairing, 30-minute video and audio, screen lock/background behavior, lens and torch changes, short and long Wi-Fi loss, OBS restart, Virtual Camera, and source removal on Windows and native Linux. Record device, OS, OBS version, network, measured latency, A/V offset, dropped frames, heat, and recovery time. Test previous and candidate Android/plugin combinations before a protocol release.
2. **Give the media session an Android owner independent of the activity.** `MainActivity.kt` creates the camera, encoders, transport, discovery, and control server, then stops them in `onStop()`. A foreground camera service or equivalent session owner should manage the live state and explicit stop action; the activity should observe it. First reproduce background and screen-lock behavior on supported Android versions, then implement the lifecycle change with behavioral tests.
3. **Make pairing authorization real.** `docs/protocol.md` says protocol V1 does not authenticate the control channel. `CameraControlServer.kt` accepts an optional reservation token and authorizes camera commands by reserved peer address. Add an explicit pairing confirmation and session secret, validate every control command, and show the paired OBS identity on the phone. Roll out with a versioned Android/plugin compatibility plan so old clients fail with an understandable upgrade message.
4. **Make one connection model visible everywhere.** The Android launcher name is `shin` (`strings.xml`); the plugin still includes `shin` prompts and OBS V7/V8 labels. Keep legacy identifiers internally for saved scenes, but present `OpenStream Camera` consistently in the phone, OBS source, dock, installer, and docs. Use the same human-readable states: Available, Pairing, Connected, Live, Reconnecting, and Action needed.
5. **Make failure recovery a product feature.** Show whether discovery, control, and SRT media are reachable separately. Give a specific next action for denied permissions, wrong network, busy phone, blocked port, failed hardware encoder, missing audio, and stale reservation. Add a redacted support bundle with app/plugin versions, device/OBS/OS details, recent state changes, and error codes. Never include pairing secrets or raw video.
6. **Finish the public trust basics.** Choose and add a top-level license after checking ownership and bundled dependencies. Add a security reporting path, privacy and local-network data statement, supported-platform table, contribution guide, issue templates, and a changelog tied to actual artifacts. Do not call the repository open-source until the license is published.

## Feature decisions

| Priority | Include | Why and acceptance check |
| --- | --- | --- |
| Next release | A guided first connection that checks permissions, matching app/plugin versions, phone and OBS discovery, reservation, then first video and audio | A new user completes setup without reading protocol docs; each failure names one corrective action. |
| Next release | Persistent live session, explicit Stop, reconnect reason and progress, and visible audio status | Locking the screen or opening another app follows a documented behavior; a brief network loss returns to the same OBS source. |
| Next release | Diagnostic screen and support bundle | A tester can report a failure with device and error details without sending video or secrets. |
| Next release | Device and OBS compatibility matrix in CI/manual sign-off | At least a small, named set of Android vendors and current OBS versions passes the same scripted scenarios before publishing. |
| After reliability gate | Simple quality presets such as Stable 720p and Balanced 1080p, with actual bitrate, frame rate, and health shown | Profiles are validated on hardware and tell users when thermal or network conditions force a change. Measure latency and heat before shipping adaptive behavior. |
| After reliability gate | USB transport only if user tests show local Wi-Fi is a frequent blocker | Compare setup time, stability, and maintenance cost against the existing SRT path before adding a second transport. |
| Later, only with demand | Multiple simultaneous phones, macOS, remote internet transport, recording, effects, or cloud accounts | These expand the protocol, support matrix, or market position. Prioritize from user requests and evidence, not a feature checklist. |

## One version system for the whole release

The current release number is copied into `android/gradle.properties`, `obs-plugin/CMakeLists.txt`, both plugin build scripts, the Inno installer, the Windows CI workflow, the website source and metadata, and release prose. The repository test even asserts several literal `1.0.1` strings. The release workflow checks the Android value against the tag, but a local or pull-request build can still produce mismatched artifacts. Android's version code is a separate monotonic install counter, while the `V7`/`V8` OBS names and protocol `1` are compatibility identifiers. These four concepts need distinct names.

Use one root file, for example `release/version.properties`, as the source for **candidate product version** and **Android version code**:

```properties
productVersion=1.0.1
androidVersionCode=1789925364
```

The next public release must use a larger Android version code than the maximum previously published value. Keep `applicationId=dev.openstream.app` and the existing release signing certificate. Never derive the version code from SemVer or reset the current large code to `10200`; Android would reject that as a downgrade. Do not put the signing key in the version file.

Implement the migration as one focused PR:

1. Make Gradle read both properties from the root file. Make CMake and the Windows/Linux build scripts read `productVersion` or require the same value as an explicit build argument; remove their `1.0.1` fallbacks. Pass that value into the Inno installer. The website build should receive the version only when showing a candidate; its public download label should come from the **latest published release**, not the current development checkout.
2. Add `tools/check_release_version.py` as a platform-neutral CI gate. It should validate SemVer, a positive Android version code, the release tag, embedded APK metadata, the plugin's built version, the installer version, and the version in each artifact manifest. It should reject a mismatch and check the previous published APK's code and signing certificate. Replace tests that assert literal `1.0.1` with tests for that behavior.
3. Publish one matched release manifest alongside the APK and Windows/Linux packages. Include product version, Android code, protocol version, plugin build identifiers, supported OBS ABI, artifact hashes, and signing-certificate fingerprint. The download page can show the manifest's release version and flag app/plugin mismatches during pairing.
4. Keep wire protocol version, legacy OBS source IDs, and OBS/FFmpeg ABI baseline separate. Increase them only when their own compatibility rules require it. Keep fixed version numbers in historical release notes; do not generate old release history from the current candidate version.

For example, a future candidate could be `productVersion=1.1.0` with `androidVersionCode=1789925365`, if that code exceeds every published APK for the same application ID. The wire protocol could remain `1`, and an old saved scene could still have its V7 source ID. The release tag would be `v1.1.0`, and CI would prove that the APK, both plugin packages, installer, manifest, and release page all describe `1.1.0` before publication.

## Trim or consolidate

- Keep V7/V8 and `shin` protocol IDs as compatibility adapters, but remove them from new user-facing labels. Do not delete saved-scene support.
- Put setup options in OBS source properties and live controls/status in one dock. Remove duplicate controls once the dock covers every live action. Keep the Android screen focused on phone selection, preview, camera controls, and a clear Stop action.
- Hide raw host, port, and latency fields behind an Advanced path. Offer manual pairing only when discovery fails or the user chooses it.
- Stop promoting unvalidated claims such as “reliable reconnects” or specific low latency in public copy until device measurements support them. Keep the existing technical target values in protocol docs.
- Keep the Python receiver as a developer tool. It should not become another supported end-user path.
- Avoid effects, built-in recording, and a cloud account in the next release. OBS already owns production and recording; OpenStream's job is to supply a dependable phone camera source.

## A practical release sequence

1. **First evidence pass.** Run the current candidate with at least three distinct Android devices and both supported OBS hosts. Measure setup completion, 30-minute stability, reconnect time, audio/video offset, and support questions. Fix any crash, stuck reservation, or silent media failure first.
2. **Lifecycle and pairing pass.** Move session ownership out of the activity, add observable connection states, then version and secure the pairing/control protocol. Test candidate/previous combinations and preserve legacy scene adapters.
3. **Professional onboarding pass.** Align branding, simplify the OBS setup and live dock, add actionable diagnostics, and test the entire flow with people who have never built the repo.
4. **Release operations pass.** Publish a license and support policies, automate compatibility gates where possible, record manual acceptance evidence for the exact release artifacts, and release the Android APK and matching OBS plugin together.

Before claiming better performance, capture before-and-after latency, dropped frames, CPU, memory, battery, and heat on the same devices and network. Before claiming broad support, track the percentage of first-time users who reach live video, time to first frame, and successful reconnect rate.

For a small public beta, recruit 10 to 20 users who did not build the project, including at least two Android vendors and both OBS host platforms. Give each tester the matched APK and plugin packages, a short task script, and one feedback form. Ask them to install, reach live video, switch a lens, recover from a network drop, and update to the next build. Record where they stop, the error they see, and the time it takes to recover. Review reports weekly, publish known issues with workarounds, and use the measured failures to choose the next change. Make logs opt-in and remove IP addresses and pairing data before upload.

## Position against other products

| Product | Strong fit | Trade-off for OpenStream users |
| --- | --- | --- |
| OpenStream | A local Android-to-native-OBS source with camera controls and no account | One phone, LAN-only, limited release/device proof, and a narrower platform matrix. |
| [DroidCam OBS](https://droidcam.app/obs/) | Established OBS phone-camera plugin with Wi-Fi and USB and support for multiple devices | OpenStream cannot win on transport or device breadth yet; it can compete on a focused open, local, diagnosable workflow if licensing and reliability evidence catch up. |
| [Camo Studio](https://camo.com/studio) | Polished camera setup, USB/wireless connection, video adjustments, and broad desktop app use | Its wider creative scope is a poor immediate target for OpenStream. Match the clarity of setup and controls before adding effects. |
| [VDO.Ninja](https://docs.vdo.ninja/getting-started/mobile-phone-camera-into-webcam) | Browser-based phone camera into an OBS Browser Source, useful for remote guests and quick joining | OpenStream's native local source can be simpler for repeated LAN use if pairing and recovery become predictable; remote contribution is a separate product problem. |

OpenStream's useful position is a dependable, privacy-conscious Android camera for OBS on the same network. The strongest near-term advantage would be predictable reconnection and clear diagnosis, backed by a published device matrix. It does not need to match Camo's editing tools or VDO.Ninja's remote guest workflow.

## Checks performed for this review

- `python -m pytest -q`: 115 passed.
- Five previously built native test executables under `obs-plugin/build/`: passed when run directly. This does not prove that those binaries match the current source commit.
- `npm run build` in `website`: passed.
- Android unit tests and lint: attempted, blocked because this workstation has no configured Android SDK.
- `ctest`: unavailable on this workstation; CI defines native CTest jobs.
- No physical Android device or OBS session was used. No runtime latency, heat, stability, or first-use success rate was measured.
