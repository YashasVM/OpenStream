#!/usr/bin/env python3
"""Apply deterministic network impairment while preserving V4 source timestamps."""

import argparse
import json
import math
import random
from collections import defaultdict
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--loss-percent", type=float, default=0)
    parser.add_argument("--burst-length", type=int, default=1)
    parser.add_argument("--jitter-ms", type=float, default=0)
    parser.add_argument("--reorder-percent", type=float, default=0)
    parser.add_argument("--outage-start-ms", type=float)
    parser.add_argument("--outage-ms", type=float, default=0)
    parser.add_argument("--disconnect-session", type=int)
    parser.add_argument("--disconnect-start-ms", type=float, default=0)
    parser.add_argument("--disconnect-ms", type=float, default=0)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args()
    numeric_values = {
        "loss-percent": args.loss_percent,
        "jitter-ms": args.jitter_ms,
        "reorder-percent": args.reorder_percent,
        "outage-start-ms": args.outage_start_ms,
        "outage-ms": args.outage_ms,
        "disconnect-start-ms": args.disconnect_start_ms,
        "disconnect-ms": args.disconnect_ms,
    }
    if any(value is not None and not math.isfinite(value) for value in numeric_values.values()):
        parser.error("impairment values must be finite")
    if not 0 <= args.loss_percent <= 100 or not 0 <= args.reorder_percent <= 100:
        parser.error("loss and reorder percentages must be in 0..100")
    if args.burst_length < 1:
        parser.error("burst length must be at least 1")
    if args.jitter_ms < 0:
        parser.error("jitter must not be negative")
    if args.outage_start_ms is not None and args.outage_start_ms < 0 or args.outage_ms < 0:
        parser.error("outage timing must not be negative")
    if args.outage_ms and args.outage_start_ms is None:
        parser.error("outage start is required when outage duration is non-zero")
    if args.disconnect_start_ms < 0 or args.disconnect_ms < 0:
        parser.error("disconnect timing must not be negative")
    if args.disconnect_session is not None and args.disconnect_session < 1:
        parser.error("disconnect session must be positive")
    if args.disconnect_ms and args.disconnect_session is None:
        parser.error("disconnect session is required when disconnect duration is non-zero")
    manifest_path = args.input.with_suffix(args.input.suffix + ".manifest.json")
    if not manifest_path.exists():
        parser.error(f"missing expected corpus manifest: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        parser.error(f"invalid expected corpus manifest: {error}")
    rng = random.Random(args.seed)
    delivered, missing = [], defaultdict(list)
    burst_remaining = defaultdict(int)
    with args.input.open(encoding="utf-8") as source:
        for line in source:
            unit = json.loads(line)
            session, sequence = unit["session"], unit["sequence"]
            source_ms = unit["source_time_ns"] / 1_000_000
            outage = args.outage_start_ms is not None and args.outage_start_ms <= source_ms < args.outage_start_ms + args.outage_ms
            disconnected = session == args.disconnect_session and args.disconnect_start_ms <= source_ms < args.disconnect_start_ms + args.disconnect_ms
            if burst_remaining[session] or outage or disconnected or rng.random() * 100 < args.loss_percent:
                missing[session].append(sequence)
                if not (outage or disconnected) and burst_remaining[session] == 0:
                    burst_remaining[session] = max(0, args.burst_length - 1)
                elif burst_remaining[session]:
                    burst_remaining[session] -= 1
                continue
            unit["arrival_time_ns"] = unit["source_time_ns"] + round(rng.uniform(0, args.jitter_ms) * 1_000_000)
            delivered.append(unit)
    delivered.sort(key=lambda unit: unit["arrival_time_ns"])
    for index in range(len(delivered) - 1):
        if rng.random() * 100 < args.reorder_percent:
            delivered[index], delivered[index + 1] = delivered[index + 1], delivered[index]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for unit in delivered:
            output.write(json.dumps(unit, separators=(",", ":")) + "\n")
    report = {"version": 4, "delivered": len(delivered), "missing": {str(k): v for k, v in sorted(missing.items())}}
    args.output.with_suffix(args.output.suffix + ".gaps.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    args.output.with_suffix(args.output.suffix + ".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
