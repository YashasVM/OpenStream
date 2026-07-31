# OpenStream V4 master plan

## Product boundary

OpenStream V4 is a local multicamera production studio for Windows 11 and Android. It is a greenfield engine beside the existing OBS-centred product. Legacy source IDs, discovery, pairing, and media remain behind adapters until their replacement phase is approved.

The locked first release supports four 1080p30 cameras or two 1080p60 cameras, independent audio and compressed ISO recording, one GPU-composited programme output, one virtual camera, a thin OBS adapter, and phone safety recordings.

## Three independent paths

```text
master:    encoded access unit -> transport -> segmented MKV + recovery -> decoder
monitor:   decoder -> D3D11 NV12 texture -> GPU downscale -> multiview
programme: decoded textures -> D3D11 compositor -> preview/virtual camera/OBS/NVENC
```

ISO recording is upstream of decode. UI, OBS, virtual-camera consumers, and programme encoding cannot own or gate recording.

## Process model

- `OpenStreamEngine.exe`: per-user background engine owning sessions, clocks, packet recovery, decode, audio, recording, compositor, output, recovery, and telemetry.
- `OpenStreamStudio.exe`: disposable control client owning views and operator commands only.
- Named pipes carry versioned commands/events/state; shared D3D11 handles carry video; bounded shared memory may carry meters and high-rate telemetry.

Each phone has an independent session, packet pool, clock mapper, recorder, decoder, cancellation source, reconnect state, and health state. There is no global media mutex.

## Phase sequence

0. `spike/v4-media-pipeline`: measure transports, decode backends, compressed recording, multiview, and one programme encode.
1. `feat/v4-protocol-simulator`: versioned schema and deterministic simulators.
2. `feat/v4-engine-foundation`: headless engine, transport, clock, ISO, decode, D3D11 sharing, telemetry, IPC.
3. `feat/android-v4-node`: service-owned capture, timestamps, safety recording, recovery, thermal controls.
4. `feat/v4-multicam-scheduler`: four isolated sessions and resource degradation.
5. `feat/v4-sync-audio`: common timeline, jitter buffers, drift correction, separate tracks.
6. `feat/v4-studio-ui`: multiview and controls; no media ownership.
7. `feat/v4-program-output`: compositor, edit decisions, optional NVENC.
8. `feat/v4-virtual-camera`: one current-user Media Foundation virtual camera.
9. `feat/v4-obs-adapter`: engine IPC client preserving source identifiers.
10. `feat/v4-master-recovery`: gap repair and optional gated Cinema Master.
11. `release/v4-windows`: packaging, migration, diagnostics, soak gates.

Every phase is a separate reviewed PR with tests, measurements, failure injection, documentation, and an explicit rollback. Phase 1 does not begin until Phase 0 evidence is accepted.

## Phase 0 scope

Phase 0 produces command-line probes and reproducible result templates only. A transport or decoder is selected only after the full matrix in `v4-transport-benchmark.md` has real results on the target laptop. Missing measurements are marked `NOT_RUN`, never inferred.

## Exact Phase 1 acceptance criteria

Phase 1 may start only when reviewers have accepted all of the following:

1. Native custom-SRT and RTP/RTCP/SRTP candidates consume the same versioned AU corpus and complete every required impairment cell with raw results.
2. A transport is selected from recording stability, latency, CPU, memory bounds, isolation, timestamp accuracy, and maintenance evidence.
3. Media Foundation and FFmpeg decode the same corpus to D3D11 textures for one hour; a backend is selected with a completed copy ledger.
4. Four 1080p30 and two 1080p60 workloads pass bounded-queue, independent-failure, readable-segment, declared-gap, and zero-ISO-re-encode gates.
5. Post-warm-up private-memory slope is at most 1 MiB/hour and every queue high-water mark stays at or below capacity.
6. Every completed MKV segment passes `ffprobe` and AU timestamp/CRC validation; the injected 500 ms outage is represented and recoverable.
7. Reproducible commands, an environment fingerprint, raw JSONL metrics, three seeds, one-hour soak results, failures, and rollback are attached to the Phase 0 PR.
8. Legacy Android and OBS production paths remain unchanged.

If any item is absent, Phase 0 remains open and recommendations remain `NOT_RUN`.
