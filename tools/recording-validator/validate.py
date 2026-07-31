#!/usr/bin/env python3
"""Validate V4 compressed recordings and gap declarations."""

import argparse
import json
import shutil
import subprocess
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


def expected_sequences(path: Path) -> dict[int, set[int]]:
    manifest_path = path.with_suffix(path.suffix + ".manifest.json")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict) or manifest.get("version") != 4:
            raise ValueError("expected version 4 object")
        sessions = manifest["sessions"]
        if not isinstance(sessions, list):
            raise ValueError("sessions must be an array")
        expected = {}
        for session in sessions:
            session_id = session["session"]
            first, last = session["first_sequence"], session["last_sequence"]
            if any(type(value) is not int for value in (session_id, first, last)):
                raise ValueError("session ranges must contain integers")
            if session_id < 1 or first < 0 or last < first or session_id in expected:
                raise ValueError("invalid session range")
            expected[session_id] = set(range(first, last + 1))
        if not expected:
            raise ValueError("no expected sessions")
        return expected
    except (FileNotFoundError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid expected corpus manifest {manifest_path.name}: {error}") from error


def validate_gaps(path: Path) -> dict:
    declaration_path = path.with_suffix(path.suffix + ".gaps.json")
    try:
        expected = expected_sequences(path)
        declaration = json.loads(declaration_path.read_text(encoding="utf-8"))
        if not isinstance(declaration, dict) or declaration.get("version") != 4:
            raise ValueError("gap declaration must be a version 4 object")
        missing = declaration["missing"]
        if not isinstance(missing, dict):
            raise ValueError("missing must be an object")
        declared = {}
        for session, values in missing.items():
            if not isinstance(session, str) or not session.isdecimal() or int(session) < 1 or str(int(session)) != session:
                raise ValueError("missing session keys must be canonical positive integers")
            if not isinstance(values, list) or any(type(value) is not int or value < 0 for value in values):
                raise ValueError("missing sequences must be arrays of non-negative integers")
            if len(values) != len(set(values)):
                raise ValueError(f"duplicate missing sequence in session {session}")
            declared[session] = set(values)
        present = {session: set() for session in expected}
        for line in path.read_text(encoding="utf-8").splitlines():
            unit = json.loads(line)
            session, sequence = unit["session"], unit["sequence"]
            if type(session) is not int or type(sequence) is not int:
                raise ValueError("access-unit session and sequence must be integers")
            if session not in present:
                raise ValueError(f"unexpected session {session}")
            if sequence not in expected[session]:
                raise ValueError(f"unexpected sequence {sequence} in session {session}")
            if sequence in present[session]:
                raise ValueError(f"duplicate sequence {sequence} in session {session}")
            present[session].add(sequence)
        actual = {str(session): sorted(expected[session] - present[session]) for session in expected}
        declared_normalized = {session: sorted(values) for session, values in declared.items()}
        ok = actual == {session: declared_normalized.get(session, []) for session in actual} and set(declared_normalized) <= set(actual)
        return {"path": str(path), "ok": ok, "actual_missing": actual, "declared_missing": declared_normalized}
    except (FileNotFoundError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        return {"path": str(path), "ok": False, "error": str(error)}


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
