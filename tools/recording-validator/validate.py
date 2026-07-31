#!/usr/bin/env python3
"""Validate V4 compressed recordings and gap declarations."""

import argparse
import json
import shutil
import subprocess
from collections import defaultdict
from pathlib import Path


def validate_media(path: Path) -> dict:
    command = ["ffprobe", "-v", "error", "-show_streams", "-show_packets", "-of", "json", str(path)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        return {"path": str(path), "ok": False, "error": result.stderr.strip()}
    data = json.loads(result.stdout)
    video = [stream for stream in data.get("streams", []) if stream.get("codec_type") == "video"]
    timestamps = [float(packet["pts_time"]) for packet in data.get("packets", []) if packet.get("codec_type") == "video" and "pts_time" in packet]
    monotonic = all(a <= b for a, b in zip(timestamps, timestamps[1:]))
    return {"path": str(path), "ok": bool(video) and monotonic, "video_codecs": [s.get("codec_name") for s in video], "video_packets": len(timestamps), "timestamps_monotonic": monotonic}


def validate_gaps(path: Path) -> dict:
    declaration_path = path.with_suffix(path.suffix + ".gaps.json")
    if not declaration_path.exists():
        return {"path": str(path), "ok": False, "error": f"missing {declaration_path.name}"}
    sequences = defaultdict(list)
    for line in path.read_text(encoding="utf-8").splitlines():
        unit = json.loads(line)
        sequences[unit["session"]].append(unit["sequence"])
    actual = {}
    for session, values in sequences.items():
        present = set(values)
        actual[str(session)] = [value for value in range(min(values), max(values) + 1) if value not in present]
    declared = json.loads(declaration_path.read_text(encoding="utf-8")).get("missing", {})
    ok = all(set(actual.get(session, [])) <= set(values) for session, values in declared.items()) and all(session in declared for session in actual)
    return {"path": str(path), "ok": ok, "actual_internal_gaps": actual, "declared_missing": declared}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    if shutil.which("ffprobe") is None and any(path.suffix.lower() in {".mkv", ".mp4"} for path in args.paths):
        parser.error("ffprobe is required for media validation")
    results = [validate_gaps(path) if path.suffix == ".jsonl" else validate_media(path) for path in args.paths]
    report = {"version": 4, "ok": all(item["ok"] for item in results), "results": results}
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
