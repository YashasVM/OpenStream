# V4-03 Tauri 2 Studio feasibility evidence

Date: 2026-08-02. Candidate: Tauri 2.11.5, React 19.2.8, shadcn-style
primitives, WebView2 150.0.4078.105. This updates evidence only; it does not
complete the WinUI 3 versus Qt 6 framework decision.

## Result

The candidate is useful but **not selected**. The typed Rust pipe client passes
its behavioral test against a versioned named-pipe snapshot and the standalone
test service creates a protected current-user-SID pipe. The release GUI launched
and resized reliably, but its three measurement launches did not consume the
standalone service snapshot (`pipeServiceExitCode: null` in every raw row).
Therefore an end-to-end GUI-to-service connection is a **failed cell**, not a
pass. Real D3D11 shared-texture presentation, OS DPI switching, Narrator, and
process-attributed GPU are also `NOT_RUN`.

## Environment

- HP Victus 15-fa1xxx, i5-13420H (12 logical processors), 16 GB RAM.
- NVIDIA RTX 4050 Laptop GPU, driver 32.0.15.9282; Intel UHD 32.0.101.5542.
- Windows 11 Home 10.0.26200; Node 25.6.1; npm 11.9.0; Rust 1.88.0.
- Tauri 2.11.5 / tauri-build 2.6.3 exact; WebView2 150.0.4078.105.
- Release Studio executable: 3,320,832 bytes. Studio plus test-service
  executables: 3,641,856 bytes. No installer was installed, so true expanded
  installed bytes are `NOT_RUN`.
- Bundle policy is current-user NSIS with embedded Evergreen bootstrapper.

## Raw measurements

Source: `spikes/v4-tauri-studio/results/2026-08-02-target/measurements.jsonl`.
Three launches used a three-second settle and a two-second CPU sample. The first
launch is only `cold-unprimed`, not a post-reboot cold launch.

| Measurement | Result |
|---|---:|
| Launch to responsive window, first | 1122.53 ms |
| Warm launch to responsive window, mean (2) | 753.55 ms |
| Process-tree private bytes, mean | 196,227,072 bytes |
| Process-tree working set, range | 444,575,744–452,501,504 bytes |
| Idle CPU, mean | 0.369% of total logical CPU |
| Three-step resize call sequence, mean | 23.38 ms |
| Process count | 8 |
| Standalone pipe service consumed by GUI | FAIL, 0/3 |

The resize value measures synchronous window resize calls, not rendered-frame
jank or latency. CPU and memory include the Studio/WebView2 descendant process
tree discovered from parent PIDs. GPU is not inferred from API availability.

## Automated gates

| Gate | Result |
|---|---|
| `npm run build:web` | PASS |
| `npm test` | PASS, 2 tests |
| `npm run test:a11y` | PASS, 6 tests |
| `npm audit --audit-level=high` | PASS, 0 vulnerabilities |
| `cargo fmt --check` | PASS |
| `cargo test --workspace --all-targets` | PASS, 2 tests |
| User-SID pipe/DACL standalone service smoke | PASS |
| GUI process launch/resize samples | PASS, 3 rows |

The rendered flow was tested through Playwright after the in-app Browser runtime
failed to initialize with `failed to write kernel assets`. At browser device
scale factors 1.0, 1.5, and 2.0 the four-preview shell rendered, keyboard focus
reached and activated Theme, axe reported no violations, light/dark changed,
and reduced-motion CSS reduced transition duration. Browser scale factors are
not claimed as Windows OS DPI tests.

Visual inspection of the 1180×760 screenshot checked: all four previews are
visible; no clipping or overlap; warning is text and colour; focus ring is
visible; status and descriptor text are legible; footer safety statements fit.

## Exact `NOT_RUN` and failed cells

- **Installed size:** `NOT_RUN`; no NSIS installation was performed under the
  lead timebox. Executable bytes and WebView2 policy are reported separately.
- **Post-reboot cold start:** `NOT_RUN`; rebooting the host was outside the
  isolated spike session. The first sample is labeled cold-unprimed only.
- **Idle GPU:** `NOT_RUN`; the harness did not obtain reliable per-process GPU
  Engine counters for the complete WebView2 process tree.
- **Real 100/150/200% OS DPI:** `NOT_RUN`; Windows scale changes require an
  interactive sign-out/reconfiguration. Browser device-scale automation is
  retained only as layout evidence.
- **Narrator:** `NOT_RUN`; screen-reader quality requires an interactive human
  judgment pass. Axe and semantic DOM evidence do not substitute for it.
- **Real shared D3D11 textures:** `NOT_RUN`; descriptors are simulated and no
  native texture presenter exists.
- **GUI-to-standalone-service snapshot:** **FAIL** in the release measurement,
  0/3 services exited after Studio launch. The narrower Rust typed-client test
  passes and must not be broadened into an end-to-end claim.

## Reproduce

```powershell
cd spikes/v4-tauri-studio
npm ci
npm run build:web
npm test
npm run test:a11y
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo test --manifest-path src-tauri/Cargo.toml --workspace --all-targets
npm run build
powershell -File scripts/measure-release.ps1 -Launches 3 -IdleSeconds 3 -OutputDirectory results/2026-08-02-target
powershell -File scripts/summarize-results.ps1 -InputPath results/2026-08-02-target/measurements.jsonl
```

The raw service stdout files preserve each protected pipe name. Generated media
does not exist; this spike performs no codec, recording, timestamp, or frame-copy
work.
