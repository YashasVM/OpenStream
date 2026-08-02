# ADR 0001: V4 transport selection is blocked

Status: blocked — no transport selected
Date: 2026-08-02

## Decision

Neither custom-framed SRT message mode nor RTP/RTCP/SRTP is selected. Product
transport implementation must not begin from this spike.

The required comparison cannot be run reproducibly from this repository and
host state. Selecting from the Phase 0 metadata impairment simulator would
violate `docs/v4-transport-benchmark.md`, which says simulated impairment is
harness validation rather than transport-selection evidence.

## Reproduced blockers

The checked-in audit reports these unmet gates:

1. No pinned Windows x64 libsrt development package.
2. No pinned Windows x64 libsrtp2 development package.
3. No native custom-AU SRT and RTP/RTCP/SRTP probe exists.
4. No identical real H.264/HEVC AU corpus preserves source/decode timestamps,
   sequence, codec-config generation, orientation, payload bytes and CRC.
5. No target Wi-Fi impairment topology/configuration was supplied. Local JSONL
   loss injection is not a substitute.

The V4 envelope documented before this spike also omits orientation even though
the timestamp and master architecture require it. The native probe/corpus must
resolve and version that field before measurements begin.

## Commands and raw evidence

Run from the repository root:

```powershell
python spikes/transport/audit.py --output out/transport-audit
python -m pytest tests/test_v4_transport_audit.py
```

The first command intentionally exits 2 while blocked and writes:

- `out/transport-audit/environment.json`: versions, queue declarations and
  individual prerequisite verdicts.
- `out/transport-audit/matrix.jsonl`: all 192 candidate/codec/profile/case/seed
  cells, explicitly `NOT_RUN` with blocker IDs.

The checked-in reproduction from this host is under
`docs/evidence/v4-01-transport-audit/`. These files are prerequisite inventory,
not raw transport measurements. The audit deliberately cannot turn a cell into
a pass: native linking, real payload/CRC validation and target-network behavior
must be established by the future benchmark runner.

The matrix is the documented superset: clean, random loss, burst loss+jitter,
explicit reorder, 500 ms outage, 5 s outage, bitrate step and isolated reconnect;
both codecs; 4x1080p30 and 2x1080p60; three seeds.

## Acceptance gate to unblock

Pin the two native libraries; check in a spike-only native probe; provide the
versioned real-AU corpus and target topology; then run every raw cell. Only the
measured leader may run the required one-hour H.264, HEVC and 1080p60 soaks,
including isolated camera failure. A leader may be selected only if timestamps,
sequence, codec config, orientation and CRC are preserved; all queues remain
within declared capacity/deadline/policy; gaps are explicit; memory slope is at
most 1 MiB/hour; and copy, CPU, private-memory and maintenance evidence passes.

## Scope and rollback

This change adds audit/test/documentation files only. It does not implement a
transport, re-encode media, or touch Android, OBS, engine, or legacy production
paths. Rollback is removal of `spikes/transport`, its test, and this ADR.
