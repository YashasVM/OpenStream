"""Emit a reproducible, exhaustive V4-01 prerequisite and NOT_RUN audit."""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CASES = (
    "clean",
    "random-1",
    "burst-3",
    "reorder",
    "outage-500",
    "outage-5000",
    "bitrate-step",
    "isolated-reconnect",
)
CANDIDATES = ("srt-au", "rtp-srtp")
CODECS = ("h264", "hevc")
PROFILES = ((4, 30), (2, 60))
SEEDS = (1, 7, 19)

# These are declarations for the proposed native probe. No queue is created by
# this audit tool. A future implementation must expose depth/HWM/overflow for
# every declaration and must not add undeclared queues.
QUEUES = (
    {"name": "encoded_au_ingress_per_session", "capacity": 120,
     "deadline_ms": 150, "overflow_policy": "reject_past_deadline_and_declare_gap"},
    {"name": "received_datagrams_per_session", "capacity": 2048,
     "deadline_ms": 150, "overflow_policy": "discard_past_deadline_and_declare_gap"},
    {"name": "fragment_reassembly_per_session", "capacity": 120,
     "capacity_bytes": 67108864, "max_fragments_per_au": 4096,
     "deadline_ms": 150, "overflow_policy": "expire_incomplete_au_and_declare_gap"},
    {"name": "assembled_video_per_session", "capacity": 120,
     "deadline_ms": 150, "overflow_policy": "drop_oldest_non_key_request_keyframe_declare_gap"},
    {"name": "retransmit_cache_per_session", "capacity": 4096,
     "deadline_ms": 120, "overflow_policy": "expire_oldest_past_deadline_and_count_range"},
    {"name": "telemetry", "capacity": 1024,
     "deadline_ms": 1000, "overflow_policy": "coalesce_gauges_retain_fault_transitions"},
)


def command_version(command: str, *arguments: str) -> str | None:
    executable = shutil.which(command)
    if executable is None:
        return None
    try:
        result = subprocess.run(
            [executable, *arguments], capture_output=True, text=True, check=False,
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"unavailable: {error}"
    output = (result.stdout or result.stderr).splitlines()
    return output[0].strip() if output else f"exit={result.returncode}"


def find_corpus(corpus: Path | None) -> tuple[bool, str]:
    if corpus is None:
        return False, "no --corpus supplied"
    manifest = corpus / "manifest.json"
    if not manifest.is_file():
        return False, f"missing {manifest}"
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return False, f"invalid corpus manifest: {error}"
    required = {"codec", "sequence", "source_time_ns", "decode_time_ns",
                "codec_config_generation", "orientation", "payload_bytes", "payload_crc32"}
    fields = set(data.get("access_unit_fields", []))
    if not required <= fields:
        return False, f"manifest missing AU fields: {sorted(required - fields)}"
    if not data.get("real_encoded_payloads", False):
        return False, "manifest does not attest real encoded payloads"
    return True, "real AU manifest present"


def prerequisites(corpus: Path | None, topology: Path | None) -> list[dict[str, object]]:
    vcpkg_root = os.environ.get("VCPKG_ROOT")
    installed = Path(vcpkg_root) / "installed" / "x64-windows" if vcpkg_root else None
    srt = bool(installed and (installed / "include" / "srt" / "srt.h").is_file())
    srtp = bool(installed and (installed / "include" / "srtp2" / "srtp.h").is_file())
    corpus_ok, corpus_detail = find_corpus(corpus)
    topology_ok = bool(topology and topology.is_file())
    native_probe = (ROOT / "spikes" / "transport" / "native" / "CMakeLists.txt").is_file()
    return [
        {"gate": "pinned_windows_libsrt", "pass": srt,
         "detail": str(installed) if installed else "VCPKG_ROOT is unset"},
        {"gate": "pinned_windows_libsrtp2", "pass": srtp,
         "detail": str(installed) if installed else "VCPKG_ROOT is unset"},
        {"gate": "native_both_candidate_probe", "pass": native_probe,
         "detail": "native/CMakeLists.txt" if native_probe else "native probe absent"},
        {"gate": "identical_real_au_corpus", "pass": corpus_ok, "detail": corpus_detail},
        {"gate": "target_wifi_impairment_topology", "pass": topology_ok,
         "detail": str(topology) if topology else "no --topology supplied"},
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--topology", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    gates = prerequisites(args.corpus, args.topology)
    ready = all(bool(gate["pass"]) for gate in gates)
    fingerprint = {
        "schema_version": 4,
        "commit": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True,
            text=True, check=True,
        ).stdout.strip(),
        "platform": platform.platform(),
        "python": platform.python_version(),
        "ffmpeg": command_version("ffmpeg", "-version"),
        "cmake": command_version("cmake", "--version"),
        "vcpkg_root": os.environ.get("VCPKG_ROOT"),
        "queues": QUEUES,
        "prerequisites": gates,
        # This is an input inventory, not validation of library ABI/linking,
        # payload CRCs, native protocol behavior, or the network topology.
        "inputs_declared": ready,
        "decision": "NOT_RUN",
    }
    (args.output / "environment.json").write_text(
        json.dumps(fingerprint, indent=2) + "\n", encoding="utf-8"
    )

    with (args.output / "matrix.jsonl").open("w", encoding="utf-8", newline="\n") as output:
        for candidate in CANDIDATES:
            for codec in CODECS:
                for streams, fps in PROFILES:
                    for case in CASES:
                        for seed in SEEDS:
                            output.write(json.dumps({
                                "schema_version": 4,
                                "candidate": candidate,
                                "codec": codec,
                                "profile": {"width": 1920, "height": 1080,
                                            "fps": fps, "streams": streams},
                                "case": case,
                                "seed": seed,
                                "result": "NOT_RUN",
                                "blockers": [gate["gate"] for gate in gates if not gate["pass"]],
                            }, separators=(",", ":")) + "\n")

    print(json.dumps({"inputs_declared": ready, "output": str(args.output),
                      "matrix_cells": len(CANDIDATES) * len(CODECS) * len(PROFILES) * len(CASES) * len(SEEDS)}))
    return 0 if ready else 2


if __name__ == "__main__":
    raise SystemExit(main())
