# V4 timestamp model

## Clock domains

Video preserves Camera2 sensor time, encoder presentation time, and frame sequence. Android inspects `SENSOR_INFO_TIMESTAMP_SOURCE`; it maps sensor time explicitly to phone monotonic time instead of assuming a shared domain. Audio preserves its own sequence and `AudioTimestamp`/captured-frame position at 48 kHz.

Every encoded unit carries source time. Arrival time is recorded separately for network diagnostics only.

## Engine mapping

Each session repeats an NTP-style four-timestamp exchange:

```text
phone send t0 -> engine receive t1 -> engine send t2 -> phone receive t3
rtt    = (t3 - t0) - (t2 - t1)
offset = ((t1 - t0) + (t2 - t3)) / 2
```

The mapper rejects high-RTT outliers, fits offset and drift over a bounded sample window, publishes confidence, and records every accepted correction. Source time maps to one engine monotonic timeline. Reconnect starts with low confidence and a fresh calibration without rewriting historical mappings.

## Presentation

Studio Sync targets a bounded 150–250 ms jitter buffer; Live targets 80–150 ms. Delivery deadlines prevent recovery queues growing after media is no longer useful. At 1080p30, the gate is 95% of aligned frames within about 33 ms. At 60 fps the target is about 16.7 ms best effort; V4 does not claim genlock or guaranteed frame-perfect sync.

Programme audio is the live reference. Small drift is corrected by high-quality resampling without abrupt sample insertion/deletion. Compressed ISO audio timestamps remain untouched where the container permits. Optional offline waveform refinement writes a new offset to metadata without altering the source tracks.

## Per-frame metadata

Video metadata includes sensor timestamp, encoder PTS, sequence, orientation, lens, exposure, ISO, focus distance, zoom, and white-balance state. Mapping metadata includes offset, drift, RTT, confidence, jitter-buffer decision, and any discontinuity.
