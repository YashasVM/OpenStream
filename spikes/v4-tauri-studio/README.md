# Tauri 2 Studio feasibility spike

This quarantined V4-03 candidate evaluates a Tauri 2 host with React and
shadcn-style primitives. It is not product Studio code and is not part of the
root CMake build.

The Rust Tauri host is a disposable control client. A second executable,
`openstream-tauri-pipe-service`, is only a fake engine-style service. It owns
the user-SID-scoped named-pipe server and remains a separate process. Neither
binary contains media, codec, OBS, recording, or C++ engine code.

The flow under test is: launch the independent test service -> launch Studio ->
the async Tauri command performs a nonce-authenticated, versioned pipe hello ->
React receives four typed `PreviewDescriptor` values and renders placeholders.
No D3D11 handle is exposed to JavaScript. These descriptors do not prove shared
texture presentation or a zero-copy path.

## Pinned toolchain

- Rust 1.88.0 (`rust-toolchain.toml`)
- Node from the environment; the measured version is captured in JSONL
- Exact npm dependency versions in `package-lock.json`
- Exact Cargo resolution in `src-tauri/Cargo.lock`

On Windows, install the Rust MSVC toolchain and Visual Studio 2022 Build Tools
with MSVC v143 and a Windows 11 SDK. Then run:

```powershell
npm ci
npm run build:web
npm test
npm run test:a11y
cargo test --manifest-path src-tauri/Cargo.toml --all-targets
npm run build
powershell -File scripts/measure-release.ps1 -Launches 5 -IdleSeconds 10
```

The Browser plugin was attempted first for rendered QA. Its local runtime was
unavailable in this worktree (`failed to write kernel assets`), so the checked-in
Playwright suite is the recorded fallback.

## Security and bounds

- Pipe name includes the current user SID; the server applies a protected DACL
  granting that SID access and uses first-instance creation.
- A 32--128 character per-run nonce and protocol/request IDs reject accidental
  or stale same-user connections. This is test hardening, not production
  authentication.
- Frames are length-prefixed and capped at 64 KiB. The request has a two-second
  timeout. There is no event queue in this single-snapshot spike.
- Pipe work is awaited by Tokio inside an async Tauri command, never by React
  or the WebView UI thread.
- The capability contains only Tauri core defaults for the `main` window; the
  app loads bundled local content under a restrictive CSP and grants no remote
  URL access.

## Windows distribution implications

The spike fixes NSIS to current-user install and uses Tauri's embedded
WebView2 bootstrapper mode. That adds roughly 1.8 MB to the installer and still
requires network access if Evergreen WebView2 is missing. Offline and fixed
runtime modes add roughly 127 MB and 180 MB respectively, so installed-size
claims must always state the mode. Windows 11 normally carries Evergreen
WebView2, but the installer must still handle missing/managed runtimes.

Product release would sign the Studio executable, independent engine/test
sidecar, and NSIS installer using SHA-256 plus trusted timestamping (or Tauri's
custom `signCommand` backed by organizational key custody). This spike has no
certificate and does not produce signed release evidence.

Primary references:

- <https://v2.tauri.app/concept/inter-process-communication/>
- <https://v2.tauri.app/security/capabilities/>
- <https://v2.tauri.app/distribute/windows-installer/>
- <https://v2.tauri.app/distribute/sign/windows/>
- <https://learn.microsoft.com/microsoft-edge/webview2/concepts/distribution>
- <https://learn.microsoft.com/windows/apps/design/accessibility/accessibility-testing>
