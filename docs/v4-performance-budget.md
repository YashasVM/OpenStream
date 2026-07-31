# V4 performance budget

## Target system and gates

Target: Windows 11, i5-13420H, 16 GB RAM, RTX 4050 Laptop GPU, AX211 Wi-Fi 6E.

| Resource | Four 1080p30 gate | Measurement |
|---|---:|---|
| Engine CPU | <25% average; no sustained saturation | process CPU sampled each second |
| Private memory | <1.5 GB and flat after warm-up | working/private bytes and linear growth |
| Packet queues | declared capacity; zero hidden growth | depth, high-water mark, overflow count |
| Video copies | zero GPU→CPU→GPU in normal path | backend copy audit plus GPU trace |
| ISO encodes | zero | codec/container trace and validator |
| Programme encodes | at most one NVENC session | encoder/session telemetry |
| Alignment | 95% within ~33 ms at 30 fps | mapped presentation timestamps |
| Recording | all closed segments readable; every gap logged | `recording-validator` |

Two 1080p60 streams use the same limits except sync is best-effort toward 16.7 ms.

## Queue budgets

| Queue | Capacity | Overflow policy |
|---|---:|---|
| received datagrams/session | 2048 packets | discard packets past delivery deadline; count range |
| assembled video/session | 120 access units | drop oldest non-key frame, request keyframe if continuity breaks |
| assembled audio/session | 256 access units | reject newest beyond deadline; preserve discontinuity metadata |
| ISO writer/session | 256 access units | enter unsafe state; never silently drop |
| decoded preview/session | 4 surfaces | replace oldest non-presented preview |
| programme frames | 4 surfaces | replace oldest frame before programme deadline |
| recovery transfer/session | 32 chunks | pause recovery; live media has priority |
| telemetry | 1024 events | coalesce gauges, retain fault transitions |

Capacities are prototype starting points and must be tuned from high-water measurements. Disk queues cannot discard recording data: a full queue raises a visible unsafe condition and records the affected range.

## Overload order

Reduce UI animation, scopes, non-selected preview rate, and multiview resolution before changing wireless bitrate or non-programme frame rate. Optional programme recording stops before any ISO recorder. Phone safety recording and desktop ISO are never sacrificed for preview.

## Soak evidence

Record one-second samples of CPU, private bytes, GPU decode/encode/utilisation, queue depth/high-water/overflow, delivered/missing frames, and end-to-end latency. Report warm-up separately. A one-hour run passes bounded memory when the post-warm-up regression slope is at most 1 MiB/hour and no queue exceeds capacity.
