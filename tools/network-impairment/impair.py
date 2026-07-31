#!/usr/bin/env python3
"""Apply deterministic network impairment while preserving V4 source timestamps."""

import argparse
import json
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
    if not 0 <= args.loss_percent <= 100 or not 0 <= args.reorder_percent <= 100:
        parser.error("loss and reorder percentages must be in 0..100")
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
