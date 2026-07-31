# V4 Phase 0 status

## Checked-in evidence at this checkpoint

- Base commit: `b1eea06e995ce5cc699336217e2e6c3fba190f1d`.
- Branch: `spike/v4-media-pipeline`.
- Legacy Android/OBS production code is unchanged.
- Deterministic four-stream 1080p30 and two-stream 1080p60 AU workload generation passes.
- Deterministic loss, burst, jitter, reorder, outage, and isolated-session-disconnect injection records source timestamps and missing sequences.
- [Reproducible one-second NVENC/remux smoke evidence](evidence/v4-00-media-smoke.md) records the exact commands, host/FFmpeg environment, output hashes, and validator output for four 1080p30 H.264 and two 1080p60 HEVC segments. It is only a local smoke; it does not establish performance or production readiness.
- Local capability probes found FFmpeg 8.0.1 with SRT, Media Foundation, D3D11VA, NVDEC and NVENC, plus an RTX 4050 Laptop GPU. These are capability observations, not performance claims.

## Not yet proven

- Native custom-framed SRT and RTP/RTCP/SRTP data paths.
- Packet feedback, retransmission deadlines, encryption overhead, or reconnect measurements.
- Media Foundation versus FFmpeg D3D11 decoder comparison.
- Native zero-copy D3D11 2×2 composition. The installed FFmpeg lacks a D3D11 overlay/xstack filter, so this requires the native probe.
- One-hour four-stream recording and memory soak.
- A completed copy ledger, CPU/GPU performance measurements, or repaired phone safety segments.

## Recommendations

Transport: **none yet**. Legacy SRT/MPEG-TS is evidence only for current interoperability and does not compare the V4 candidates.

Decoder: **none yet**. D3D11VA availability is a smoke result, not proof of texture-preserving composition or a comparison with Media Foundation.

The native spike needs pinned desktop libsrt, libsrtp2, and FFmpeg development packages. The bundled Android libsrt archive is not a Windows dependency. Until the matrix and copy tracing are complete, all selection cells remain `NOT_RUN`.
