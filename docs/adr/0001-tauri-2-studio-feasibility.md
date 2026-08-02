# ADR 0001: Keep the V4 Studio framework decision open after the Tauri 2 spike

- Status: Proposed; decision gate remains open
- Date: 2026-08-02
- Scope: V4-03 Studio framework evidence update

## Context

The V4 execution plan provisionally prefers C++/WinRT with WinUI 3 and requires
equivalent WinUI 3 and Qt 6 shells to present four shared D3D11 textures before
selecting a framework. This spike adds Tauri 2 + React/shadcn as a third
candidate at the request of the lead. It does not alter the central execution
ledger or replace the original comparison.

V4 requires a disposable `OpenStreamStudio.exe`: views and commands only,
versioned named-pipe control/state, and shared D3D11 handles from an independent
C++ engine. Studio must never own media lifetime or put network/disk work on the
UI thread.

## Decision

Do not select Tauri, WinUI 3, or Qt 6 from this spike. Retain the provisional
WinUI 3 direction and keep V4-03 open until the original equivalent-candidate
matrix and the native shared-texture presentation gate have measured results.

Tauri remains a viable control-shell candidate because this spike proves:

- the Rust backend client can connect to a typed fake pipe in behavioral tests,
  and the standalone service creates a user-SID-scoped protected pipe;
- the pipe hello is versioned, length-bounded, nonce checked, request matched,
  time bounded, and executed on Tokio rather than React's UI thread;
- React receives typed preview descriptors without owning sessions or seeing
  native handle values;
- the compact shell has keyboard, semantic, light/dark, reduced-motion, and
  browser scale-test infrastructure.

Tauri is not yet acceptable for product Studio because this spike does not
present keyed/shared D3D11 textures. A WebView placeholder says nothing about
the native compositor path, keyed-mutex scheduling, adapter identity, occlusion,
device removal, resize synchronization, or zero CPU frame copies. A native
overlay/child-window presenter may erase much of Tauri's simplicity and must be
prototyped before selection.

## Evidence matrix

| Gate | Tauri 2 | WinUI 3 | Qt 6 |
|---|---|---|---|
| Four typed simulated previews | PASS | NOT_RUN | NOT_RUN |
| Separate user-scoped pipe service | PASS standalone; GUI consumption FAIL 0/3 | NOT_RUN | NOT_RUN |
| UI-thread-independent pipe delay/timeout | PASS (async contract and timeout) | NOT_RUN | NOT_RUN |
| Real shared D3D11 texture presentation | NOT_RUN | NOT_RUN | NOT_RUN |
| Installed bytes / start / private bytes / idle CPU | See raw spike evidence | NOT_RUN | NOT_RUN |
| Process-attributed idle GPU | NOT_RUN | NOT_RUN | NOT_RUN |
| Resize measurement | See raw spike evidence | NOT_RUN | NOT_RUN |
| 100/150/200% browser rendering | PASS | NOT_RUN | NOT_RUN |
| Actual Windows 100/150/200% DPI | NOT_RUN | NOT_RUN | NOT_RUN |
| Keyboard and automated accessibility scan | PASS | NOT_RUN | NOT_RUN |
| Narrator human verification | NOT_RUN | NOT_RUN | NOT_RUN |
| Light/dark and reduced motion | PASS | NOT_RUN | NOT_RUN |

`NOT_RUN` is intentional. API availability, browser emulation, and simulated
descriptors are not substituted for native evidence.

## Deployment and IPC consequences

Tauri adds Rust and npm supply chains plus the system WebView2 runtime. The
measured bundle uses a current-user NSIS installer and embedded Evergreen
bootstrapper. Offline/fixed runtime choices materially change installer size
and servicing; Evergreen improves security servicing but requires forward-
compatibility testing and an application restart to take a newly installed
runtime. Product artifacts require signing of every executable and installer,
trusted timestamping, pinned dependencies, licenses, and SBOM/provenance.

Tauri commands serialize JSON through the core process. Capabilities limit
frontend API exposure but do not make incorrect Rust safe. Product IPC would
retain the engine's independent named-pipe protocol, strict maximum frames,
timeouts/cancellation, snapshot/reconnect, a bounded/coalescing event channel,
and user-only ACL. Same-user pipe squatting remains in the threat model; the
test nonce is not a production credential.

## Consequences and rollback

- Keep this prototype quarantined under `spikes/v4-tauri-studio`; it is not
  referenced by root CMake or product builds.
- Do not move Rust, WebView2, npm, or Tauri dependencies into the C++ engine.
- Before reconsidering Tauri, build the smallest native D3D11 presenter and
  collect the original WinUI/Qt/Tauri comparison on the same host and method.
- Rollback is deletion of the quarantined spike, raw evidence, and this ADR;
  no legacy, engine, Android, OBS, or product Studio path changes.
