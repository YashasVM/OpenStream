# V4 transport decision spike

This directory contains decision-spike tooling only. It is not a product
transport and is not linked by Android, OBS, or the V4 engine.

`audit.py` records whether a host has the minimum inputs needed to run the
native custom-framed SRT versus RTP/RTCP/SRTP comparison. When a prerequisite
is absent it emits the complete matrix as `NOT_RUN`; it never substitutes the
Phase 0 metadata simulator for native transport evidence.

This is a prerequisite inventory, not raw transport-result evidence. Even when
all paths are declared, it does not validate native linking, corpus payloads or
network behavior and therefore never marks a matrix cell passed.

```powershell
python spikes/transport/audit.py --output out/transport-audit
python -m pytest tests/test_v4_transport_audit.py
```

To become runnable, the spike still needs all of the following checked in or
provided explicitly: pinned Windows x64 libsrt and libsrtp2 development
packages, a native probe for both candidates, an extracted real H.264/HEVC AU
corpus with source timestamps/config/orientation, and a documented target Wi-Fi
impairment topology. See `docs/decisions/0001-v4-transport-blocked.md`.
