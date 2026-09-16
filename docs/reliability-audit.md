# Solo-camera reliability audit

This is a reviewed first hardening pass, not production certification. Muse Spark 1.3 through opencode performed the audit and Linux implementation. Its Android lifecycle rewrite failed review and a new behavioural test; that rewrite and its test were removed together. Existing Android media behaviour is retained.

## Confirmed defects fixed

- Linux control socket writes could raise SIGPIPE on disconnect. Per-send suppression now fails the command without terminating OBS; a forked test reproduces the failure and verifies the fix.
- Linux CMake could not find distro OBS headers, required Windows-style configuration and had no install/CI path. Native Linux now builds, packages and installs against system dependencies.
- The OBS JSON scanner could read digits from another field, misread negatives and mishandle escaped names. Typed Qt JSON parsing rejects malformed values; production parser tests cover these cases.
- Android manual connect used SRT caller mode, but OBS always called the phone. An explicit advanced manual-receive mode now opens an OBS listener. The production settings adapter is tested.
- Refresh Phones did not repopulate its properties list. It now refreshes from current discovery data while retaining an unavailable saved selection.
- Multi-camera creation was still exposed. A process-wide ownership gate permits one active source; conflicting sources get an explanation. Concurrent acquisition and production start rejection are tested. Reuse Add Existing across scenes. Legacy scene/protocol identities still load.

## Checks

- Existing Python repository checks: 92 passed.
- Linux CMake build: passed with libobs 32.2.2, avformat 63, Qt 6.11.2.
- Five native CTest suites passed: lifecycle/control contracts, SIGPIPE, concurrent solo ownership, typed JSON, production source settings.
- Linux tarball extraction and isolated/repeated installer execution passed.
- Website production build passed.
- Android unit tests, lint and native APK build: passed locally with the real bundled SRT libraries (all four Android ABIs).
- Windows build is configured in existing CI; not executed locally.

## Release blockers / remaining work

- Android still owns sessions in MainActivity, can block during lifecycle shutdown, and stops streaming when backgrounded. Moving this into a tested session owner remains required; the rejected rewrite is not shipped.
- No physical Android-to-OBS acceptance run was performed. Verify 30-minute streaming, screen/display behaviour, camera changes, microphone failures, A/V sync, packet loss and reconnects on both Windows and Linux before release.
- OBS hardware decode is not wired: current receiver explicitly warns and uses software decoding. Phone encoding remains hardware based.
- Native distro Linux only; Flatpak/Snap and IPv6-only networks are not validated. No new release was published.
- Vendor-specific codec config handling and timestamp recovery need device evidence. Audit hypotheses were not treated as confirmed defects or patched speculatively.
- This audit is not an exhaustive proof that no other bugs exist. No performance improvement is claimed or benchmarked.
