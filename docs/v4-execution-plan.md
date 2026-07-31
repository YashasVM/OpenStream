# OpenStream V4 architecture and execution ledger

Status: proposed architecture; Phase 0 evidence in progress
Last updated: 2026-08-01
Central owner: lead/orchestrator thread only

This is the central plan and progress ledger for V4. Feature worktrees must not
edit this file; the lead thread updates it after a PR is reviewed or merged.
That rule avoids a guaranteed merge conflict across every parallel branch.

## 1. Decision summary

OpenStream V4 is a greenfield media engine beside the working V2 Android and
OBS product. V2 remains releasable until V4 passes its end-to-end gates. V4
does not incrementally turn the current OBS plugin into the engine.

The recommended architecture is:

- one service-owned Android camera session and one hardware video encoder per
  phone in standard mode;
- encoded access units fan out by reference to live transport and local safety
  recording, without a second video encoder;
- one independent encrypted media connection and one independent authenticated
  control connection per phone;
- source capture timestamps preserved end to end and mapped into one Windows
  monotonic timeline with measured offset, drift, RTT, and confidence;
- a per-user native C++20 `OpenStreamEngine.exe` that owns every media session,
  queue, recorder, decoder, clock mapper, audio route, D3D11 texture, programme
  decision, virtual-camera source, and recovery job;
- a disposable `OpenStreamStudio.exe` that owns operator views and commands,
  never media lifetime;
- one selected DXGI adapter for hardware decode, D3D11 processing/composition,
  shared previews, virtual-camera frames, and at most one programme encoder;
- compressed segmented MKV ISO recording before decode, plus immutable gap and
  recovery metadata;
- an engine IPC adapter for OBS, with no OBS dependency in the engine;
- current-user Media Foundation virtual-camera registration on Windows 11;
- explicit, visible software fallback only. A fallback is never silently
  enabled and is excluded from hardware-path performance claims.

The provisional media choice is **custom-framed SRT message mode**, one session
per phone, because it reuses the smallest existing native dependency and is
designed for bounded live retransmission. It is not final. Task V4-01 must
compare it with RTP/RTCP/SRTP on identical captured access units. RTP wins if it
materially improves deadline delivery, isolation, or resource use without
making recording continuity and maintenance worse.

The provisional decoder choice is **FFmpeg D3D11 hardware frames** because the
engine already needs libavformat for MKV and FFmpeg exposes D3D11 textures.
It is not final. Task V4-02 must compare FFmpeg D3D11 with Media Foundation MFT
decode on the same adapter and corpus. Keep only the winning production path;
do not retain two permanent decoder frameworks without measured fallback value.

The provisional Studio choice is **C++/WinRT with WinUI 3**, using a native
D3D11 swap chain/shared texture presenter. Task V4-03 must compare a minimal
WinUI 3 shell with Qt 6 for cold start, private bytes, installed size, D3D11
integration, DPI, keyboard and accessibility. The engine is UI-framework-free.

## 2. Runtime architecture

```text
Android phone (one isolated session per phone)
  Camera2 sensor timestamp + AudioTimestamp
       |                         |
  Surface -> MediaCodec video    AudioRecord -> MediaCodec AAC
       |                         |
       +------ encoded AU fan-out (bounded, reference-counted) ------+
       |                                                              |
  live media transport                                      segmented fMP4 safety
       |                                                              |
       +------------------- encrypted Wi-Fi ---------------------------+
                              |
Windows OpenStreamEngine.exe (per-user, single instance)
  per-phone receiver -> AU validation -> compressed MKV ISO -> decoder
                              |                    |
                       clock/jitter/audio       D3D11 NV12/P010
                              |                    |
                              +---- D3D11 compositor ----+
                                                        |
                                      programme preview / one HW encode
                                      virtual camera / OBS adapter

OpenStreamStudio.exe <--- versioned named-pipe commands/events ---> Engine
                           shared D3D11 texture handles
```

### Process and ownership rules

`OpenStreamEngine.exe` is a normal per-user process, not a Windows service. It
is single-instance, can be launched by Studio or the OBS adapter, and remains
alive while a media session, recording, virtual camera, or client needs it.
Every phone has its own cancellation tree, transport state, AU pool, clock
mapper, recorder, decoder, audio state, reconnect state, and health state.
There is no global media lock and one camera failure cannot cancel another.

`OpenStreamStudio.exe` reconnects to the engine after a UI crash. Named pipes
carry length-prefixed, versioned control JSON and state snapshots. This traffic
is small enough that shared-memory telemetry is deferred until profiling proves
it necessary. Video crosses process boundaries only as keyed/shared D3D11
handles on the engine-selected adapter.

The Android foreground service owns Camera2, AudioRecord, MediaCodec, transport,
control, safety files, wake lock and Wi-Fi lock after explicit user arming.
Activities render state and send commands. They may disappear without ending an
armed session. Android never attempts an illegal cold background camera start
after force-stop, reboot, permission loss, or unarmed process death.

### Control, discovery and pairing

V4 discovery is a versioned UDP advertisement on the existing discovery port,
with stable engine and phone IDs. V1 payloads remain readable only through
legacy adapters.

Control is a separate outbound phone-to-engine TLS 1.3 connection using Android
platform TLS and Windows Schannel. The engine certificate fingerprint and a
one-time 256-bit pairing secret are transferred by QR; manual pairing uses a
high-entropy code, not the legacy six-digit code. The phone pins the engine
certificate. Long-term device credentials are protected with Android Keystore
and Windows DPAPI. The channel carries commands, applied state, tally, health,
time exchanges, key rotation and reconnect state. It never carries video.

The wire format remains boring: a 32-bit length prefix plus UTF-8 JSON with a
required protocol version, message type, request ID and session ID. Golden
fixtures and strict validation are required before either endpoint ships. High
rate media uses the binary AU envelope selected by the transport spike.

### Media, recording and recovery

The Android encoder uses a Camera2 surface and must report the actual selected
hardware codec. H.264 is the baseline interoperability codec; HEVC is enabled
only when both endpoints report a proven hardware path. Portrait/landscape is
represented by per-frame/session orientation metadata and compositor transforms;
rotation never causes an ordinary CPU frame copy.

Each encoded AU retains sequence, codec-config generation, keyframe flag,
capture/source timestamp, encoder PTS, duration, orientation and payload bounds.
Arrival time is telemetry only. Large AUs are bounded and fragmented by the
selected transport. A reconnect starts a new transport epoch without rewriting
historical time.

The desktop ISO writer receives compressed AUs before decode and writes 2--5
minute MKV segments. Codec, resolution, orientation discontinuity, or reconnect
rolls a segment. It journals open/closed state and writes every missing range to
metadata. It may reframe packets for the container but never decode/re-encode
ISO video.

The phone writes the same standard-mode AUs to short fragmented-MP4 safety
segments. Recovery is lower priority than live media. The engine requests only
hash-verified ranges overlapping declared gaps, keeps original desktop segments
immutable, and records provenance in a relink manifest.

### Video and audio

Decode, scale, colour conversion, rotation, multiview, programme composition,
virtual-camera delivery and programme encode stay on one D3D11 adapter. Normal
operation has no GPU-to-CPU-to-GPU frame route. A decoder output that becomes a
software pixel format fails the hardware gate and produces a user-visible
warning before any fallback is enabled.

Each microphone remains an independent 48 kHz source and an independent ISO
track. Programme audio has one explicit selected source, with optional
follow-video behaviour; OpenStream never mixes all microphones automatically.
The selected live audio is the programme sync reference. Small clock drift is
corrected by bounded high-quality resampling in the live path only. Compressed
ISO timestamps are not rewritten to hide drift or loss.

### Operator surfaces

Studio uses a dense, restrained production layout: scalable multiview, Preview
and Programme, explicit camera health, one primary transition action, recording
safety state, audio source selection, and progressively disclosed technical
controls. Status is never colour-only. Windows must work at 100/150/200% scale,
keyboard-only and with screen readers. Android uses native controls, 48 dp touch
targets, TalkBack labels, safe areas, large text, dark/light themes and both
orientations. UI animation is first to degrade under resource pressure.

## 3. Repository disposition

### Reuse or adapt

- `Camera2Controller` capability discovery, complete repeating-request idea,
  focus coordinate mapping, and their Camera2-independent tests.
- `CameraModels`/`CameraStateStore` authority, revision and applied-state
  semantics, after moving them behind the V4 wire contract.
- MediaCodec surface input, no-B-frame/real-time configuration, Camera2 lens
  discovery, audio level and device telemetry concepts.
- stable source IDs, V7/V8 scene loading, installer/release patterns and the
  compatibility matrix in the current OBS product.
- PR #16 corpus generation, impairment injection, result schema, recording
  validator and performance-budget discipline.

### Rewrite beside V2

- Android session lifetime: `MainActivity` currently coordinates camera,
  encoders, SRT, control, discovery, reservations, UI and service attachment.
  V4 moves all media lifetime into a service-owned session object.
- transport/muxing: the JNI path synchronously copies each MediaCodec output to
  a byte array, holds shared mutexes, builds MPEG-TS, and sends SRT from codec
  callbacks. V4 uses bounded AU ownership and independent workers.
- Windows receive/render: the OBS plugin currently decodes inside OBS, may use
  `swscale`, and assigns `os_gettime_ns()` on output. V4 preserves mapped source
  timestamps in an OBS-free engine and shares GPU resources with consumers.
- control and discovery networking as versioned V4 components with legacy
  adapters at the boundary.
- the Windows product: there is currently no standalone engine or Studio app.

### Remove after V4 migration, not before

- V4 reliance on the Activity-owned media lifecycle and 1x1/placeholder
  headless preview ownership.
- V4 reliance on MPEG-TS, arrival-time output timestamps, CPU `swscale`, or an
  unbounded `AsyncControlClient` command queue.
- checked-in prebuilt SRT archives once a pinned, reproducible dependency build
  supplies all Android ABIs and license/provenance output.
- duplicate setup documentation after one V4 migration guide replaces it.

The website and V2 updater are outside the V4 media critical path. Leave them
alone unless a release task explicitly changes them.

## 4. Evidence gates

No benchmark result is inferred from API availability. Raw results include the
commit, tool/library/driver versions, phone and PC hardware, power mode, Wi-Fi
topology, queue capacities/policies, warm-up, seed and commands.

1. **Transport:** SRT-AU and RTP/RTCP/SRTP run the same real H.264/HEVC AUs for
   4x1080p30 and 2x1080p60 across clean, 1% random loss, 3% burst/50 ms jitter,
   reorder, 500 ms and 5 s outages, bitrate step and isolated reconnect. Three
   seeds plus one-hour leading-candidate soak are required.
2. **Decode/GPU:** Media Foundation and FFmpeg decode the same corpus to D3D11
   textures on the selected adapter for one hour. PIX/ETW corroborates the copy
   ledger. Native 2x2 composition and one hardware encode are measured.
3. **Studio framework:** identical WinUI 3 and Qt 6 shells present four shared
   textures and are compared for installed bytes, cold/warm start, private
   bytes, idle CPU/GPU, resize/DPI behaviour, keyboard and screen-reader access.
4. **End to end:** on the target i5-13420H/16 GB/RTX 4050 system, four 1080p30
   or two 1080p60 cameras meet the checked-in budget, every queue remains
   bounded, memory slope is <=1 MiB/hour after warm-up, every segment validates,
   and one failed camera does not interrupt the rest.

## 5. Worktree task graph and progress

Create each worktree only after its dependencies are merged into `main`, unless
the row explicitly says to stack it. Suggested directory names are siblings of
the primary checkout.

| ID | Status | Branch | Worktree directory | Depends on | Primary ownership | Model |
|---|---|---|---|---|---|---|
| V4-00 | In progress (PR #16) | `spike/v4-media-pipeline` | `OpenStream-wt-v4-00-evidence` | `main` | `tools/{stream-simulator,network-impairment,recording-validator}`, Phase 0 docs/tests | Terra Medium |
| V4-01 | Planned | `spike/v4-transport-decision` | `OpenStream-wt-v4-01-transport` | V4-00 | `spikes/transport`, transport result docs | Terra Medium |
| V4-02 | Planned | `spike/v4-gpu-decision` | `OpenStream-wt-v4-02-gpu` | V4-00 | `spikes/gpu`, GPU result docs | Terra Medium |
| V4-03 | Planned | `spike/v4-studio-framework` | `OpenStream-wt-v4-03-ui` | V4-00 | `spikes/studio-shell`, UI result docs | Terra Medium |
| V4-04 | Planned | `feat/v4-wire-contract` | `OpenStream-wt-v4-04-wire` | V4-00 | `protocol/v4`, golden fixtures/tests | Luna Max |
| V4-05 | Planned | `build/v4-windows-foundation` | `OpenStream-wt-v4-05-build` | V4-00 | root/CMake presets, `cmake`, dependency/build scripts | Luna Max |
| V4-06 | Planned | `feat/v4-engine-runtime` | `OpenStream-wt-v4-06-engine` | V4-04, V4-05 | `engine/app`, `engine/core`, `engine/ipc` | Terra Medium |
| V4-07 | Planned | `feat/v4-android-session-core` | `OpenStream-wt-v4-07-android-session` | V4-04 | `android/.../v4/session`, service/manifest tests | Terra Medium |
| V4-08A | Planned | `feat/v4-engine-control` | `OpenStream-wt-v4-08a-engine-control` | V4-06 | `engine/control`, Windows control tests | Terra Medium |
| V4-08B | Planned | `feat/v4-android-control` | `OpenStream-wt-v4-08b-android-control` | V4-04, V4-07 | `android/.../v4/control`, Android control tests | Terra Medium |
| V4-09 | Planned | `feat/v4-android-capture` | `OpenStream-wt-v4-09-capture` | V4-07 | `android/.../v4/capture`, `v4/encode` | Terra Medium |
| V4-10A | Planned | `feat/v4-android-transport` | `OpenStream-wt-v4-10a-android-transport` | V4-01, V4-08B, V4-09 | `android/.../v4/transport` | Terra Medium |
| V4-10B | Planned | `feat/v4-engine-transport` | `OpenStream-wt-v4-10b-engine-transport` | V4-01, V4-08A, V4-06 | `engine/transport`, receiver tests | Terra Medium |
| V4-11 | Planned | `feat/v4-iso-recorder` | `OpenStream-wt-v4-11-iso` | V4-04, V4-05 | `engine/recording` | Terra Medium |
| V4-12 | Planned | `feat/v4-gpu-decode` | `OpenStream-wt-v4-12-decode` | V4-02, V4-06, V4-10B | `engine/video/decode`, D3D device code | Terra Medium |
| V4-13 | Planned | `feat/v4-sync-audio` | `OpenStream-wt-v4-13-sync` | V4-09, V4-10A/B, V4-12 | `engine/sync`, `engine/audio`, Android V4 timestamp plumbing | Terra Medium |
| V4-14 | Planned | `feat/v4-gpu-compositor` | `OpenStream-wt-v4-14-compositor` | V4-02, V4-12, V4-13 | `engine/video/compositor` | Terra Medium |
| V4-15 | Planned | `feat/v4-phone-safety-recording` | `OpenStream-wt-v4-15-safety` | V4-09 | `android/.../v4/recording` | Terra Medium |
| V4-16 | Planned | `feat/v4-gap-recovery` | `OpenStream-wt-v4-16-recovery` | V4-10A/B, V4-11, V4-15 | `engine/recovery`, recovery protocol handlers | Terra Medium |
| V4-17 | Planned | `feat/v4-studio-shell` | `OpenStream-wt-v4-17-studio-shell` | V4-03, V4-06 | `studio/app`, `studio/ipc`, design tokens | Luna Max |
| V4-18 | Planned | `feat/v4-program-switcher` | `OpenStream-wt-v4-18-switcher` | V4-13, V4-14 | `engine/program` state/edit decisions | Terra Medium |
| V4-19 | Planned | `feat/v4-studio-operator-ui` | `OpenStream-wt-v4-19-studio-ui` | V4-17, V4-18 | `studio/views`, `studio/viewmodels` | Luna Max |
| V4-20 | Planned | `feat/v4-program-recording` | `OpenStream-wt-v4-20-program-record` | V4-11, V4-18 | `engine/program/encode`, programme muxing | Terra Medium |
| V4-21 | Planned | `feat/v4-virtual-camera` | `OpenStream-wt-v4-21-vcam` | V4-14, V4-18 | `virtual-camera`, engine virtual-camera adapter | Terra Medium |
| V4-22 | Planned | `feat/v4-obs-adapter` | `OpenStream-wt-v4-22-obs` | V4-06, V4-13, V4-14, V4-18 | new `obs-adapter`; legacy `obs-plugin` only for ID adapters | Terra Medium |
| V4-23 | Planned | `test/v4-end-to-end` | `OpenStream-wt-v4-23-e2e` | V4-08--V4-22 | `tests/e2e`, soak/failure scripts and reports | Terra Medium |
| V4-24 | Planned | `release/v4-windows` | `OpenStream-wt-v4-24-release` | V4-23 | packaging, CI, migration/release docs | Luna Max |

Useful parallel waves after dependencies merge:

- Wave A: V4-01, V4-02, V4-03, V4-04, V4-05.
- Wave B: V4-06 and V4-07; then V4-08A, V4-08B, V4-09, V4-11.
- Wave C: V4-10A, V4-10B, V4-15; then V4-12 and V4-16 where ready.
- Wave D: V4-13; then V4-14 and V4-17; then V4-18.
- Wave E: V4-19, V4-20, V4-21 and V4-22.
- Wave F: V4-23, then V4-24.

## 6. PR acceptance criteria by task

All PRs must be based on current `main`, stay within listed ownership, preserve
V2 unless the task explicitly owns an adapter, pass `git diff --check`, include
tests at the behavioural boundary, document every queue capacity/overflow
policy, and attach before/after evidence for performance changes. A different
Terra Medium thread reviews each PR before merge.

- **V4-00:** PR #16 is a harness PR, not a selection PR. Commands are
  reproducible, generated workloads/impairments are deterministic, source time
  is preserved, validators fail undeclared gaps, Python/Android/OBS checks stay
  green, and all unmeasured decisions remain `NOT_RUN`.
- **V4-01:** both native candidates process identical real AUs through the full
  matrix and three seeds; raw JSONL/results and one-hour leading-candidate soak
  are attached; per-session queues are bounded; one session failure is isolated;
  an ADR selects one transport or explicitly blocks implementation.
- **V4-02:** both decoder candidates yield texture-backed D3D11 frames for both
  codecs; native four-way composition and one hardware encode run for one hour;
  PIX/ETW plus counters prove the copy ledger; CPU/GPU/memory are reported; an
  ADR chooses the production decoder and explicit fallback.
- **V4-03:** equivalent four-preview shells are measured on the target PC;
  100/150/200% DPI, resize, keyboard, screen reader and dark/light checks are
  recorded; installed size/startup/private-bytes data choose one framework.
- **V4-04:** schemas cover hello/pair/auth/state/command/event/tally/time-sync,
  media metadata, errors, reconnect and compatibility; malformed/oversize/
  unknown-version messages fail; Kotlin and C++ fixtures round-trip identically;
  nullable patch fields can distinguish omitted from cleared.
- **V4-05:** clean Windows x64 configure/build/test works from documented pinned
  dependencies; licenses/SHA256s are emitted; no OBS dependency enters engine;
  cache and artifacts are ignored.
- **V4-06:** engine is single-instance and per-user; version negotiation,
  reconnect and snapshot IPC tests pass; pipe ACL is user-scoped; all work is
  off UI threads; shutdown is bounded; four fake sessions prove independent
  cancellation and queue telemetry.
- **V4-07:** after explicit arming the foreground service, not Activity, owns the
  session state and locks; removing/recreating Activity does not stop a fake
  session; notification Stop always works; force-stop/reboot/permission-loss
  states require re-arm; no real media change is hidden in this PR.
- **V4-08A/B:** TLS 1.3, certificate pinning, one-time high-entropy pairing,
  Keystore/DPAPI storage, credential rotation, replay/request limits, bounded
  send queues and reconnect are tested; no legacy six-digit credential is used
  for V4; media continues during control reconnect.
- **V4-09:** service-owned Camera2 feeds exactly one hardware surface encoder;
  H.264 baseline and capability-gated HEVC report actual codec; sensor/readout,
  encoder and audio capture timestamps plus orientation are emitted; codec
  fallback is explicit; Activity removal and orientation changes are tested on
  devices; MediaCodec callbacks never do network or disk work.
- **V4-10A/B:** selected transport implements AU fragmentation/reassembly,
  encryption/keying, epochs, deadlines, keyframe requests, bounded pools and
  independent reconnect; 4x30 and 2x60 pass the selected Phase 0 cases; source
  timestamps/sequence survive; no MPEG-TS is introduced.
- **V4-11:** compressed H.264/HEVC plus AAC write readable segmented MKV without
  video decode/encode; crash/reconnect/codec/orientation roll segments; journal
  recovery and disk-full/slow-disk states are visible; every gap is logged;
  queue saturation becomes unsafe instead of silently dropping.
- **V4-12:** chosen decoder returns NV12/P010 D3D11 textures on the engine
  adapter; four sessions remain isolated; software output warns and is excluded
  until explicitly allowed; no ordinary readback/upload; copy counters and
  30-minute resource evidence are attached.
- **V4-13:** Camera2 and AudioTimestamp domains map to engine monotonic time with
  bounded filters/outlier rejection/confidence; reconnect never rewrites old
  mapping; selected programme audio drift is corrected without abrupt samples;
  microphones remain separate; deterministic drift/jitter/gap tests and a
  physical two-phone sync report pass the documented tolerance.
- **V4-14:** four decoded textures compose to multiview/programme on D3D11 with
  aspect-correct portrait/landscape transforms; preview queues are bounded and
  newest-frame biased; no CPU frame path; 4x30 and 2x60 meet frame-time/resource
  budgets and expose shared keyed handles.
- **V4-15:** the standard encoder AUs are stream-copied into short fMP4 safety
  segments with hashes/indexes; there is no second video encoder; disk-full,
  process death and roll boundaries are surfaced; two-hour screen-off evidence
  includes heat, battery, dropped frames and segment validation.
- **V4-16:** only declared missing ranges are requested; live traffic has
  priority and recovery queues are bounded; hashes/timestamps/provenance are
  validated; original ISO files stay immutable; 500 ms and 5 s injected gaps
  reconstruct or remain explicitly unresolved.
- **V4-17:** chosen native shell launches/reconnects without owning sessions,
  presents fake shared textures, uses semantic theme/spacing/type tokens, and
  has keyboard/screen-reader/DPI tests. Closing/crashing Studio leaves fake
  engine recording alive.
- **V4-18:** engine owns Preview/Programme state and atomic cut/dissolve edit
  decisions; tally derives from state; switching has deterministic timestamps;
  no UI or OBS object owns it; failure of the selected camera has a documented
  hold/fallback behaviour.
- **V4-19:** multiview, Preview/Programme, camera health, ISO safety, audio select,
  transition and settings flows are complete; status is not colour-only;
  keyboard, screen reader, 100/150/200% DPI, narrow/wide, light/dark and reduced
  motion checks pass; UI work does not exceed frame/input budgets.
- **V4-20:** one and only one hardware programme encoder consumes the compositor
  texture; programme recording is independently stoppable and never gates ISO;
  software fallback warns; cut/dissolve timestamps, audio selection, crash and
  disk-full behaviours validate.
- **V4-21:** current-user `MFCreateVirtualCamera` registration works without admin
  on supported Windows 11 builds; frames preserve aspect/fps through consumer
  negotiation; engine/session restart and camera privacy denial are handled;
  Teams/Camera/OBS consumer smoke passes; no virtual microphone is added.
- **V4-22:** OBS loads legacy scene IDs through an adapter and new V4 sources use
  engine IPC/shared textures; the plugin is thin, bounded and has no transport,
  recorder or decoder ownership; mapped source timestamps reach OBS; OBS exit or
  source deletion cannot stop engine ISO; OBS 32.2.1 load/migration passes.
- **V4-23:** the final prompt in section 8 passes on physical Windows/Android
  hardware with raw logs. No flaky rerun is counted as a pass without a root
  cause. Every unmet gate blocks release.
- **V4-24:** signed MSIX/installer and APK come from one commit; clean install,
  upgrade, uninstall, rollback and V2 migration pass; dependency/license/SBOM,
  hashes, diagnostics bundle, release notes and CI artifacts are complete.

## 7. Copy-paste prompts for manual T3 Code threads

Each prompt assumes its named dependencies are already merged. Replace no model:
use the stated model exactly. The thread must create/push its branch and PR.

### V4-00 — Phase 0 harness / PR #16 — Terra Medium

```text
Work in YashasVM/OpenStream on existing branch spike/v4-media-pipeline and PR #16. Read AGENTS.md and docs/v4-execution-plan.md. Keep this PR a deterministic evidence harness; do not select a transport/decoder and do not change legacy Android or OBS production paths. Verify and tighten the corpus, impairment, benchmark schemas and recording validator so all unmeasured cells remain NOT_RUN. Run every documented check, attach exact commands/results, update only Phase 0-owned docs, push the branch, and leave the PR draft if any V4-00 acceptance criterion is missing. Do not edit docs/v4-execution-plan.md. Model: Terra Medium.
```

### V4-01 — transport decision — Terra Medium

```text
Create branch spike/v4-transport-decision in worktree OpenStream-wt-v4-01-transport from current main after V4-00. Read AGENTS.md and docs/v4-execution-plan.md. Implement native benchmark probes only under spikes/transport for custom-AU SRT message mode and RTP/RTCP/SRTP. Feed identical real H.264/HEVC access units through every required impairment/profile/seed, declare all capacities and deadlines, isolate one-session failure, publish raw results and a one-hour leading-candidate soak, then write an evidence-based ADR selecting one transport or blocking selection. No product transport implementation and no legacy edits. Run checks, commit, push, open a focused PR with commands/evidence/rollback. Do not edit the central ledger. Model: Terra Medium.
```

### V4-02 — decoder/GPU decision — Terra Medium

```text
Create branch spike/v4-gpu-decision in worktree OpenStream-wt-v4-02-gpu from current main after V4-00. Build isolated C++20 probes under spikes/gpu comparing Media Foundation hardware MFT decode with FFmpeg AV_PIX_FMT_D3D11 on the same adapter and corpus, plus native D3D11 2x2 composition and one hardware programme encode. Prove texture types and the copy ledger with counters plus PIX/ETW, run both codecs/profiles including one hour, publish CPU/GPU/memory/raw results, and write an ADR choosing production decode and explicit fallback. Do not build engine features. Follow AGENTS.md and the V4-02 acceptance criteria, then commit/push/open a focused PR. Do not edit docs/v4-execution-plan.md. Model: Terra Medium.
```

### V4-03 — Studio framework decision — Terra Medium

```text
Create branch spike/v4-studio-framework in worktree OpenStream-wt-v4-03-ui after V4-00. Under spikes/studio-shell build equivalent minimal WinUI 3 C++/WinRT and Qt 6 shells that present four fake/shared D3D11 textures. Measure installed bytes, cold/warm start, private bytes, idle CPU/GPU and resize; test 100/150/200% DPI, keyboard, screen reader and light/dark. Publish reproducible raw results and an ADR choosing one framework. Delete or quarantine losing prototype output from product builds. No product UI. Commit/push/open a focused PR meeting V4-03. Do not edit the central ledger. Model: Terra Medium.
```

### V4-04 — wire contract — Luna Max

```text
Create branch feat/v4-wire-contract in worktree OpenStream-wt-v4-04-wire after V4-00. Implement only protocol/v4 schemas, limits, golden JSON/binary fixtures and Kotlin/C++ contract tests for discovery, pairing/auth, state/commands/events, tally, time sync, media metadata, errors, reconnect and compatibility. Distinguish omitted nullable patch values from explicit clear; reject malformed, oversize and unknown versions. Do not implement networking. Follow AGENTS.md and V4-04 acceptance, run checks, commit/push/open a focused PR, and do not edit docs/v4-execution-plan.md. Model: Luna Max.
```

### V4-05 — Windows build foundation — Luna Max

```text
Create branch build/v4-windows-foundation in worktree OpenStream-wt-v4-05-build after V4-00. Add the smallest reproducible Windows x64 CMake/preset/dependency foundation needed by future engine, Studio and probes. Pin versions/hashes, emit licenses, keep OBS out of engine, add clean configure/build/test CI and artifact ignores, and document exact commands. No runtime feature code. Meet V4-05, commit/push/open a focused PR, and do not edit the central ledger. Model: Luna Max.
```

### V4-06 — engine runtime and IPC — Terra Medium

```text
Create branch feat/v4-engine-runtime in worktree OpenStream-wt-v4-06-engine after V4-04 and V4-05. Implement a per-user single-instance C++20 OpenStreamEngine skeleton with independent fake sessions, structured cancellation, bounded queues/telemetry, user-scoped versioned named-pipe IPC, snapshots/reconnect and bounded shutdown. No transport, decode, recording, OBS or UI dependency. Prove one fake session can fail without affecting three others and no network/disk runs on a client UI thread. Meet V4-06, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-07 — Android session ownership — Terra Medium

```text
Create branch feat/v4-android-session-core in worktree OpenStream-wt-v4-07-android-session after V4-04. Add a V4 foreground-service-owned session state machine under android/.../v4/session and the minimum manifest/service integration. Prove explicit arming, Activity removal/recreation, notification emergency Stop, locks, bounded shutdown and required re-arm after invalid background-start states using fake media resources. Do not move real capture/encoder/transport yet and preserve V2. Meet V4-07, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-08A — engine secure control — Terra Medium

```text
Create branch feat/v4-engine-control in worktree OpenStream-wt-v4-08a-engine-control after V4-06. Implement only the engine side of the V4 outbound-phone TLS 1.3 control channel with Schannel, certificate/fingerprint lifecycle, high-entropy one-time pairing, DPAPI credentials, framed wire messages, request/replay/size limits, bounded queues, reconnect and fake-client tests. Keep media independent and keep all I/O off client UI threads. Meet V4-08A, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-08B — Android secure control — Terra Medium

```text
Create branch feat/v4-android-control in worktree OpenStream-wt-v4-08b-android-control after V4-04 and V4-07. Implement only the Android outbound TLS 1.3 control client: QR fingerprint/one-time-secret pairing, certificate pinning, Keystore credentials, framed protocol, limits, bounded queues, reconnect and applied-state tests. It must remain service-owned and media-independent; do not reuse the six-digit V1 secret for V4. Meet V4-08B, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-09 — Android capture and single encoder — Terra Medium

```text
Create branch feat/v4-android-capture in worktree OpenStream-wt-v4-09-capture after V4-07. Implement service-owned V4 Camera2 + AudioRecord + MediaCodec capture under v4/capture and v4/encode, reusing tested camera capability/request logic through adapters. Produce bounded encoded-AU callbacks with sequence, actual codec/config, source/readout/audio timestamps, PTS and orientation. Exactly one hardware video encoder is allowed; no network/disk in codec callbacks; fallback is explicit. Preserve V2 and meet V4-09 with unit plus physical-device evidence. Commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-10A — Android selected media transport — Terra Medium

```text
Create branch feat/v4-android-transport in worktree OpenStream-wt-v4-10a-android-transport after V4-01, V4-08B and V4-09. Implement only the selected ADR transport sender in android/.../v4/transport with encryption/keying from control, bounded AU/packet pools, fragmentation, deadlines, epochs, stats, keyframe requests and reconnect. Preserve source timestamps, never add MPEG-TS, and never block MediaCodec callbacks. Run selected matrix cases on device and meet V4-10A. Commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-10B — engine selected media transport — Terra Medium

```text
Create branch feat/v4-engine-transport in worktree OpenStream-wt-v4-10b-engine-transport after V4-01, V4-08A and V4-06. Implement only the selected ADR receiver in engine/transport with independent per-phone sessions, bounded slabs/reassembly, validation, deadlines, epochs, feedback/keyframe requests, encryption and reconnect. Emit validated encoded AUs with original sequence/timestamps and prove one receiver failure cannot affect others. No decode or recording. Meet V4-10B, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-11 — compressed ISO recorder — Terra Medium

```text
Create branch feat/v4-iso-recorder in worktree OpenStream-wt-v4-11-iso after V4-04 and V4-05. Implement engine/recording as a bounded compressed-AU-to-segmented-MKV writer with journal, immutable gap metadata, preflight, roll rules, crash repair and visible slow/disk-full unsafe states. Test H.264/HEVC/AAC, reconnect/config/orientation changes and injected gaps; prove zero video decode/re-encode with hashes/ffprobe. No transport or UI. Meet V4-11, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-12 — production GPU decode — Terra Medium

```text
Create branch feat/v4-gpu-decode in worktree OpenStream-wt-v4-12-decode after V4-02, V4-06 and V4-10B. Implement only the decoder selected by the ADR plus its explicitly approved fallback. Return NV12/P010 D3D11 textures on the engine adapter, use bounded surface pools, isolate four sessions, expose copy/fallback telemetry, and never silently accept a software format. Attach 4x30/2x60 resource/copy evidence and behavioural tests. Meet V4-12, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-13 — clock mapping, sync and audio routing — Terra Medium

```text
Create branch feat/v4-sync-audio in worktree OpenStream-wt-v4-13-sync after V4-09, V4-10A/B and V4-12. Implement source-clock mapping, bounded jitter/deadline buffers, outlier rejection, drift/confidence telemetry, audio decode and explicit programme-audio selection with bounded live resampling. Preserve compressed ISO time, keep microphones separate, reset confidence on reconnect without rewriting history, and add deterministic drift/jitter/gap tests plus physical two-phone evidence. Meet V4-13, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-14 — D3D11 compositor — Terra Medium

```text
Create branch feat/v4-gpu-compositor in worktree OpenStream-wt-v4-14-compositor after V4-02, V4-12 and V4-13. Implement engine/video/compositor only: D3D11 multiview and programme surfaces, aspect-correct portrait/landscape transforms, bounded newest-frame preview scheduling and keyed shared handles. No CPU frame route and no programme encoder/UI. Benchmark 4x1080p30 and 2x1080p60 with copy/frame-time/resource evidence and meet V4-14. Commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-15 — phone safety recording — Terra Medium

```text
Create branch feat/v4-phone-safety-recording in worktree OpenStream-wt-v4-15-safety after V4-09. Implement Android v4/recording to stream-copy the same standard encoder AUs into bounded short fragmented-MP4 safety segments with index/hash metadata. Never create a second video encoder. Test codec-config/roll/disk-full/process-death behaviour and attach a two-hour screen-off device report with validation, heat, battery and drops. No recovery transfer yet. Meet V4-15, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-16 — gap recovery — Terra Medium

```text
Create branch feat/v4-gap-recovery in worktree OpenStream-wt-v4-16-recovery after V4-10A/B, V4-11 and V4-15. Implement bounded, live-traffic-lower-priority gap negotiation/transfer and engine recovery manifests. Request only ranges overlapping declared gaps, validate hashes/timestamps/provenance, keep original ISO immutable, and make unresolved gaps visible. Prove 500 ms/5 s recovery and interrupted recovery. Meet V4-16, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-17 — Studio shell — Luna Max

```text
Create branch feat/v4-studio-shell in worktree OpenStream-wt-v4-17-studio-shell after V4-03 and V4-06. Using the selected framework, implement only the disposable Studio shell, versioned IPC client, reconnect/snapshot state, fake shared-texture presenter and semantic design tokens. It must never own media sessions. Test launch/reconnect/crash, keyboard/screen-reader basics and 100/150/200% DPI; closing Studio leaves fake engine recording alive. Meet V4-17, commit/push/open a focused PR; do not edit the central ledger. Model: Luna Max.
```

### V4-18 — programme switcher — Terra Medium

```text
Create branch feat/v4-program-switcher in worktree OpenStream-wt-v4-18-switcher after V4-13 and V4-14. Implement engine-owned Preview/Programme state, atomic timestamped cut/dissolve edit decisions, derived tally, control/IPC events and documented selected-camera failure hold/fallback. No Studio views, encoder, virtual camera or OBS code. Deterministic switching/tally/failure tests must pass. Meet V4-18, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-19 — polished Studio operator UI — Luna Max

```text
Create branch feat/v4-studio-operator-ui in worktree OpenStream-wt-v4-19-studio-ui after V4-17 and V4-18. Build the Studio operator views/viewmodels for scalable multiview, Preview/Programme, cut/dissolve, camera health, ISO/gap safety, explicit audio source and progressively disclosed settings. Use semantic tokens/native controls, non-colour status, stable focus and reduced motion. Test keyboard, screen reader, light/dark, narrow/wide and 100/150/200% DPI; profile input/frame budgets. Do not add media ownership. Meet V4-19, commit/push/open a focused PR; do not edit the central ledger. Model: Luna Max.
```

### V4-20 — programme hardware recording — Terra Medium

```text
Create branch feat/v4-program-recording in worktree OpenStream-wt-v4-20-program-record after V4-11 and V4-18. Implement one hardware programme encoder consuming the compositor texture plus programme muxing with the explicitly selected audio. Enforce at most one session, warn before software fallback, keep ISO independent, and test cuts/dissolves, crash, stop and disk-full. Attach before/after CPU/GPU/copy evidence. No UI/virtual-camera/OBS work. Meet V4-20, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-21 — Windows virtual camera — Terra Medium

```text
Create branch feat/v4-virtual-camera in worktree OpenStream-wt-v4-21-vcam after V4-14 and V4-18. Implement a current-user Media Foundation software virtual camera for supported Windows 11 builds, fed from the engine programme surface with bounded negotiation/conversion on D3D11. Handle privacy denial, registration/removal, engine restart, no-signal and consumer format/fps/aspect negotiation. Smoke Camera/Teams/OBS; add no virtual microphone. Meet V4-21, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-22 — thin OBS adapter — Terra Medium

```text
Create branch feat/v4-obs-adapter in worktree OpenStream-wt-v4-22-obs after V4-06, V4-13, V4-14 and V4-18. Build a new thin obs-adapter that consumes engine IPC/shared textures/audio and preserves mapped timestamps. Keep transport/decode/recording out of OBS. Adapt legacy V7/V8 IDs/scenes without contaminating engine core; preserve the old plugin until migration passes. Prove OBS/source exit cannot stop engine ISO and run OBS 32.2.1 load/migration tests. Meet V4-22, commit/push/open a focused PR; do not edit the central ledger. Model: Terra Medium.
```

### V4-23 — integration and end-to-end — Terra Medium

Use the full prompt in section 8.

### V4-24 — packaging and release — Luna Max

```text
Create branch release/v4-windows in worktree OpenStream-wt-v4-24-release only after V4-23 passes. Package signed Windows engine/Studio/current-user virtual camera/OBS adapter and signed Android APK from one commit. Add CI, pinned dependency/license/SBOM/hash output, clean install/upgrade/uninstall/rollback, V2 migration, diagnostics bundle and release docs. Do not add features or weaken a failed gate. Meet V4-24, commit/push/open a focused PR; do not edit the central ledger. Model: Luna Max.
```

## 8. Final integration and end-to-end testing prompt

```text
Create branch test/v4-end-to-end in worktree OpenStream-wt-v4-23-e2e from current main only after V4-08 through V4-22 are merged. Use Terra Medium. Read AGENTS.md, docs/v4-execution-plan.md, every selected ADR and the compatibility matrix. Do not add product features; add only test orchestration, failure injection, diagnostics and narrowly necessary testability hooks in tests/e2e.

Build release-equivalent Windows and Android artifacts from one commit. On the target Windows 11 i5-13420H/16 GB/RTX 4050/AX211 system and real Android devices, run three deterministic seeds for 4x1080p30 and 2x1080p60, H.264 and supported HEVC. Record one-second CPU, private bytes, GPU decode/encode/3D/copy, disk, network, queue depth/high-water/overflow, latency, mapped A/V error, dropped/missing/recovered frames, reconnects, heat and battery. Separate warm-up and run the leading configurations for one hour; run phone screen-off for two hours.

Verify: hardware encode/decode and D3D11 composition; no normal GPU->CPU->GPU frame path; zero ISO re-encodes; at most one programme encoder; one independent mic selected and no automatic all-mic mix; readable ISO/program/safety segments; every gap surfaced; portrait/landscape and rotation changes; cut/dissolve/tally timing; Studio crash/restart while sessions/recording continue; OBS 32.2.1 legacy and V4 source migration; current-user virtual camera in Camera, Teams and OBS; Windows DPI 100/150/200%, keyboard and screen reader; Android small/large phone, portrait/landscape, TalkBack, large text and emergency Stop.

Inject clean, 1% random loss, 3% burst loss with 50 ms jitter and 5% reorder, 500 ms and 5 s outages, bitrate steps, discovery failure, control-only loss, media-only loss, simultaneous loss, disk slow/full, engine crash, Studio crash, Android Activity removal, permission revocation, thermal warning, and one-camera disconnect while all other cameras record. Prove bounded queues and explicit overflow policy in every case, memory slope <=1 MiB/hour after warm-up, engine CPU <25% average for 4x1080p30 on the target, no sustained saturation, private memory <1.5 GiB, and the checked-in sync/latency budgets.

Publish raw machine-readable logs, environment fingerprint, exact commands, summaries and failed cells. A flaky rerun is not a pass without root cause. Do not silently enable software codecs, substitute arrival timestamps, hide recording gaps, or weaken budgets. If a gate fails, leave V4-23 and release blocked, identify the owning task/branch, and open a focused corrective PR rather than folding a broad fix into this test PR. When all gates pass, commit/push/open the focused V4-23 PR. Do not edit docs/v4-execution-plan.md; report results to the lead thread for the ledger update.
```

## 9. Review and merge order

1. Review PR #16 as a harness and merge V4-00 without pretending its
   `NOT_RUN` cells are decisions.
2. Merge V4-04 and V4-05 when independently green. Review V4-01, V4-02 and
   V4-03 with Terra Medium and merge only with raw evidence and accepted ADRs.
3. Merge V4-06 and V4-07; then V4-08A, V4-08B, V4-09 and V4-11. Rebase every
   branch after its dependencies land.
4. Merge both selected transport endpoints only after cross-endpoint fixtures
   pass; then V4-12, V4-15 and V4-16 in dependency order.
5. Merge V4-13 before V4-14. Merge V4-17 independently once its framework and
   IPC dependencies are present. Merge V4-18 after compositor/sync.
6. Merge V4-19, V4-20, V4-21 and V4-22 one at a time, running the growing smoke
   matrix after each. Any shared contract change returns to its owning layer;
   do not patch around it in consumers.
7. Merge V4-23 only when every end-to-end gate passes. V4-24 is last and may
   change packaging/CI/docs only.

Every PR receives a separate Terra Medium correctness/performance review before
merge, even when Luna Max implemented it. Squash merge focused PRs in the order
above so rollback aligns with subsystem boundaries.

## 10. Primary technical references

- Microsoft `MFCreateVirtualCamera`: Windows build 22000+, current-user access
  does not require administrator rights, and packaged apps must not call it on
  the UI thread: <https://learn.microsoft.com/windows/win32/api/mfvirtualcamera/nf-mfvirtualcamera-mfcreatevirtualcamera>
- Android camera timestamp domains and `SENSOR_INFO_TIMESTAMP_SOURCE`:
  <https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics>
- Android captured-frame audio timestamps:
  <https://developer.android.com/reference/android/media/AudioTimestamp>
- SRT live/message options and latency/buffer constraints:
  <https://github.com/Haivision/srt/blob/master/docs/API/API-socket-options.md>
- FFmpeg D3D11 hardware frame context:
  <https://ffmpeg.org/doxygen/8.0/hwcontext__d3d11va_8c_source.html>
