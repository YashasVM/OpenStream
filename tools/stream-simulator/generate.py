#!/usr/bin/env python3
"""Generate a deterministic V4 access-unit workload without allocating frame payloads."""

import argparse
import json
from pathlib import Path


def generate(streams: int, fps: int, seconds: int, bitrate_mbps: float):
    duration_ns = round(1_000_000_000 / fps)
    average_bytes = bitrate_mbps * 1_000_000 / 8 / fps
    for sequence in range(fps * seconds):
        for session in range(1, streams + 1):
            keyframe = sequence % fps == 0
            variation = 1 + (((sequence * 17 + session * 13) % 21) - 10) / 100
            yield {
                "version": 4,
                "session": session,
                "kind": "video",
                "sequence": sequence,
                "source_time_ns": sequence * duration_ns,
                "duration_ns": duration_ns,
                "keyframe": keyframe,
                "payload_bytes": round(average_bytes * variation * (2.5 if keyframe else 1)),
            }


def expected_manifest(streams: int, fps: int, seconds: int) -> dict:
    """Describe every source sequence expected by a generated corpus."""
    last_sequence = fps * seconds - 1
    return {
        "version": 4,
        "sessions": [
            {"session": session, "first_sequence": 0, "last_sequence": last_sequence}
            for session in range(1, streams + 1)
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--streams", type=int, choices=(2, 4), default=4)
    parser.add_argument("--fps", type=int, choices=(30, 60), default=30)
    parser.add_argument("--seconds", type=int, default=10)
    parser.add_argument("--bitrate-mbps", type=float, default=16)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.streams == 4 and args.fps != 30:
        parser.error("four-stream profile is 30 fps")
    if args.streams == 2 and args.fps != 60:
        parser.error("two-stream profile is 60 fps")
    if args.seconds <= 0 or args.bitrate_mbps <= 0:
        parser.error("seconds and bitrate must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for unit in generate(args.streams, args.fps, args.seconds, args.bitrate_mbps):
            output.write(json.dumps(unit, separators=(",", ":")) + "\n")
    args.output.with_suffix(args.output.suffix + ".manifest.json").write_text(
        json.dumps(expected_manifest(args.streams, args.fps, args.seconds), indent=2) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
