# V4 transport benchmark

## Decision discipline

Candidate A is SRT message mode carrying one custom envelope per encoded access unit, without MPEG-TS. Candidate B is RTP/RTCP/SRTP with separate audio/video clocks, sender and receiver reports, NACK, PLI/FIR, replay protection, and bounded deadline-aware recovery.

Both candidates consume the exact same recorded access-unit corpus and impairment seed. Congestion control is not replaced in Phase 0. Simulated impairment validates the harness only; it is not evidence for selecting a transport.

## Access-unit envelope

All integer fields are network byte order. The prototype header is versioned and length-delimited:

```text
magic:u32 = 0x4f535634 ('OSV4')
version:u8 = 4
kind:u8 = video|audio|control
flags:u16 = keyframe|codec_config|discontinuity
session_id:u64
sequence:u64
source_time_ns:u64
decode_time_ns:u64
duration_ns:u32
codec_config_generation:u32
fragment_index:u16
fragment_count:u16
payload_bytes:u32
payload_crc32:u32
```

Arrival time is telemetry only and never replaces `source_time_ns`.
Large access units are fragmented even when the carrier preserves message boundaries. Reassembly is bounded by fragment count, bytes, and delivery deadline; expiry emits a gap. RTP uses codec-standard H.264 FU-A and HEVC AP/FU packetisation, RTCP SR/RR, Generic NACK and PLI, plus a bounded retransmit cache per SSRC.

## Required matrix

Run for four 1080p30 streams and two 1080p60 streams, H.264 and HEVC where supported:

| Case | Loss | Jitter | Reorder | Outage | Disconnect |
|---|---:|---:|---:|---:|---|
| clean | 0% | 0 ms | 0% | none | none |
| random-1 | 1% random | 20 ms | 0% | none | none |
| burst-3 | 3% burst | 50 ms | 5% | none | none |
| outage-500 | 0% | 20 ms | 2% | 500 ms | none |
| isolated-reconnect | 1% | 20 ms | 2% | none | one session; other three continue |

The full acceptance run additionally includes sudden bitrate changes and a five-second outage. Use at least three deterministic seeds and publish raw JSON results.

## Result template

| Candidate | Case | CPU avg/peak | P50/P95 latency | recovered/unrecoverable frames | overhead | audio gaps | memory/slope | queue HWM/overflow | reconnect | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| SRT AU | clean | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | pending |
| RTP/SRTP | clean | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN | pending |

Store one row per matrix case using `benchmark-results-template.csv`. Include tool/library versions, commit, adapter, driver, Wi-Fi topology, bitrate, queue capacities, overflow policy, warm-up, duration, and seed.

Each measured cell runs three deterministic seeds. Baseline cells cover both codecs at four 1080p30 and two 1080p60 for both transport and decoder candidates. Reliability cells cover every impairment for both transports. The leading combination then runs one-hour H.264, HEVC, and 1080p60 soaks, failing one session midway while the others continue.

## Selection rule

Score recording stability first, followed by latency, CPU, controlled memory, session isolation, timestamp accuracy, and maintainability. A candidate fails regardless of average score if it silently loses recording ranges, grows an unbounded queue, couples sessions, or substitutes arrival timestamps.

## Current recommendation

No transport is selected. The repository currently proves only legacy SRT/MPEG-TS interoperability. This phase provides deterministic workload, impairment, and validation tools; native custom-SRT and SRTP implementations must complete the matrix before a recommendation is legitimate.
