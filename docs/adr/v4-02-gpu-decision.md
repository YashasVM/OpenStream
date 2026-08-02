# ADR V4-02: GPU decoder decision remains blocked

Status: **NOT_RUN / no decoder selected**
Date: 2026-08-02

## Context

V4 requires one DXGI adapter for hardware decode, processing, composition, shared surfaces, and eventual programme encoding. Media Foundation must expose `IMFDXGIBuffer`; FFmpeg must expose `AV_PIX_FMT_D3D11`. Software output, ordinary GPU-to-CPU-to-GPU frame routes, cross-adapter texture paths, and silent fallback are forbidden.

This spike added isolated C++20 probes under `spikes/gpu`. Both candidates receive an explicitly selected adapter LUID and fail closed if a decoded resource is not a D3D11 texture owned by that device. The Media Foundation probe additionally exercises D3D11 video-processor scale, NV12 colour conversion, 90-degree rotation, and 2x2 composition without a CPU frame upload or readback. These are probes only; no engine or product compositor was implemented.

## Evidence obtained

On the target i5-13420H/RTX 4050 laptop, five-second smoke runs covered H.264 and HEVC for four 1080p30 streams and two 1080p60 streams. Every Media Foundation output exposed `IMFDXGIBuffer/ID3D11Texture2D`; every FFmpeg output exposed `AV_PIX_FMT_D3D11/ID3D11Texture2D`. All textures belonged to NVIDIA adapter LUID `0x0:0x16933`. Instrumented upload, readback, ordinary CPU frame-copy, and FFmpeg hardware-frame-transfer counters remained zero. Raw hashes and counts are in `docs/evidence/v4-02-gpu-smoke.json`.

This is smoke evidence, not performance or copy-ledger acceptance. Five seconds cannot establish CPU/GPU/memory/latency distributions or memory slope, and counters without ETW/PIX corroboration cannot prove the full copy ledger.

## Decision

Do not choose Media Foundation or FFmpeg yet. V4-02 remains blocked and downstream production GPU decode/compositor work must not treat either backend as selected.

Adapter policy is nevertheless reaffirmed: the caller supplies one DXGI LUID; missing adapters fail visibly; there is no automatic adapter selection, cross-adapter texture path, or silent switch. Software fallback remains disabled by default, requires explicit operator consent and a visible warning, and is excluded from hardware claims.

Device-removal policy remains the architecture policy: stop GPU consumers, preserve independent ingress and compressed ISO, rebuild the full graph only on the same LUID when still present, otherwise remain in a visible recoverable failure. The policy was not validated by this run.

## Incomplete acceptance cells

- One-hour candidate × codec × workload matrix: `NOT_RUN` under the lead timebox. The drive had about 1.95 GB free before corpus generation; probes now support long-lived in-process looping of a small hashed corpus for a later run.
- CPU/GPU/private-memory/latency comparison and memory slope: `NOT_RUN`; short-lived wrapper sampling was incomplete and is not used for a decision.
- ETW/PIX copy-ledger corroboration: `NOT_RUN`; WPR is available but no elevated trace and review were completed.
- Genuine device removal with independent ingress/ISO continuity: `NOT_RUN`; Graphics Tools/dxcap was absent. A logical teardown was explicitly rejected as a substitute.
- One hardware programme encode: `NOT_RUN`; no encoder was added to this decoder/composition spike.

## Completion command

After installing a pinned MSVC-compatible FFmpeg development package and Windows Graphics Tools, run:

```powershell
cmake -S spikes/gpu -B out/gpu-build -G "Visual Studio 17 2022" -A x64 -DOPENSTREAM_FFMPEG_ROOT=C:/ffmpeg-dev
cmake --build out/gpu-build --config Release
spikes/gpu/run-benchmark.ps1 -CorpusRoot out/gpu-corpus -OutputDirectory out/gpu-results -GenerateCorpus -CorpusSeconds 10 -AdapterLuid 92467 -DurationSeconds 3600 -WarmupSeconds 60
```

Capture and review clean plus device-removal ETW traces, add an isolated hardware-encode probe, and publish the complete raw JSONL before revisiting this ADR.

## Rollback

Delete `spikes/gpu`, `tests/test_v4_gpu_spike.py`, this ADR, and its smoke-evidence file. No product or legacy path depends on the spike.
