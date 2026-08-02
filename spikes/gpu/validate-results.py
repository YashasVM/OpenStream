#!/usr/bin/env python3
import json
import math
import sys
from collections import defaultdict
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"GPU RESULT INVALID: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate-results.py metrics.jsonl")
    path = Path(sys.argv[1])
    records = [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    if not records or records[0].get("type") != "environment":
        fail("environment record must be first")
    samples: dict[tuple[str, str], list[dict]] = defaultdict(list)
    summaries: dict[tuple[str, str], dict] = {}
    workloads = set()
    for record in records:
        if record.get("schema_version") != 1:
            fail("unsupported schema")
        if record.get("type") == "workload":
            workload = record["workload"]
            workloads.add(workload["id"])
            if (workload["width"], workload["height"], workload["streams"], workload["fps"]) not in {
                (1920, 1080, 4, 30), (1920, 1080, 2, 60)
            }:
                fail(f"invalid workload {workload['id']}")
            for queue in record["queues"]:
                if int(queue["capacity"]) <= 0 or not queue["overflow_policy"]:
                    fail("every queue needs capacity and overflow policy")
        elif record.get("type") == "sample":
            for field in ("cpu_percent", "private_mib", "gpu_process_percent"):
                if not math.isfinite(float(record[field])):
                    fail(f"non-finite {field}")
            samples[(record["workload"], record["backend"])].append(record)
        elif record.get("type") == "summary":
            summaries[(record["workload"], record["backend"])] = record["probe"]
    for workload in workloads:
        for backend in ("media-foundation", "ffmpeg-d3d11"):
            key = workload, backend
            if key not in summaries or not samples[key]:
                fail(f"missing evidence for {workload}/{backend}")
            proof = summaries[key]
            if proof.get("result") != "PASS" or int(proof.get("ordinary_cpu_frame_copies", -1)) != 0:
                fail(f"hardware/copy gate failed for {workload}/{backend}")
            if not proof.get("texture_type") or int(proof.get("cpu_frame_uploads", -1)) != 0 or int(proof.get("cpu_frame_readbacks", -1)) != 0:
                fail(f"texture/copy proof incomplete for {workload}/{backend}")
    expected_workloads = {
        "four-1080p30-h264", "four-1080p30-hevc", "two-1080p60-h264", "two-1080p60-hevc"
    }
    if workloads != expected_workloads:
        fail(f"incomplete workload matrix: expected {sorted(expected_workloads)}, got {sorted(workloads)}")
    print(json.dumps({"ok": True, "workloads": sorted(workloads), "candidate_cells": len(summaries)}))


if __name__ == "__main__":
    main()
