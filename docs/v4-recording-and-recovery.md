# V4 recording and recovery

## Desktop ISO contract

Each camera writes its encoded video and original audio to segmented MKV before decode. Segments roll every 2–5 minutes and at reconnect, codec, resolution, or orientation changes. The recorder journals open/closed state; completed segments remain readable after an engine crash.

```text
OpenStream Session <timestamp>/
  camera-01/segment-0001.mkv
  camera-01/metadata.jsonl
  ...
  program/events.jsonl
  recovery/
  session.json
```

The muxer may rewrite container framing and timestamps but never decode or re-encode video. A codec change starts a new segment. Disk space and sequential write speed are preflight gates; network shares are opt-in only.

## Gap contract

Every input sequence discontinuity creates a metadata record containing camera, first/last missing sequence, mapped start/end, detection source, and recovery state. Unknown duration is explicit. Recording must never hide a discontinuity behind a continuous container timeline.

## Phone safety and repair

The standard phone encoder fans the same access units to transport and fragmented MP4. Internal fragments target two seconds and files roll every 2–5 minutes. On reconnect the engine requests only segments overlapping known gaps, throttles recovery behind live traffic, validates hashes and timestamps, then updates the manifest. Original desktop segments remain immutable; recovery is a relink operation with provenance.

## Prototype validation

`tools/recording-validator/validate.py` uses `ffprobe` to verify readable streams, codecs, start/duration, monotonic packet timestamps, and declared gaps. Access-unit validation also requires an explicit `<recording>.manifest.json` declaring each expected session and its inclusive sequence range, so leading, trailing, and fully absent sessions cannot evade gap detection. It emits machine-readable JSON and returns non-zero for unreadable media, timestamp regression, or undeclared sequence gaps.

One-hour acceptance requires every completed segment to open, zero video re-encodes, all expected tracks, all missing ranges declared, stable queue memory, and successful reconstruction of the injected 500 ms outage.
