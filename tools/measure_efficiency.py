#!/usr/bin/env python3
"""Measure OpenStream's lightweight, memory, and thermal-efficiency budgets.

Outputs a deterministic, schema-versioned JSON report consumed by the trusted
PR comment workflow (`.github/workflows/efficiency-report.yml`).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
ROOT = Path(__file__).resolve().parents[1]

# Enforced ceilings for summary metrics. Keep values deterministic (no wall-clock
# timing ceilings so noisy CI runners never cause flaky failures).
CEILINGS: dict[str, dict[str, Any]] = {
    "apk_total_bytes": {
        "area": "APK",
        "label": "Debug APK total size",
        "unit": "bytes",
        "ceiling": int(10.5 * 1024 * 1024),  # 10.5 MiB (current ~8.62 MiB)
        "build_artifact": True,
    },
    "release_r8_dex_bytes": {
        "area": "APK",
        "label": "Release R8 DEX size",
        "unit": "bytes",
        "ceiling": 256 * 1024,  # 256 KiB (current ~138.5 KiB)
        "build_artifact": True,
    },
    "apk_arm64_native_bytes": {
        "area": "APK",
        "label": "arm64-v8a native lib",
        "unit": "bytes",
        "ceiling": int(2.2 * 1024 * 1024),  # 2.2 MiB (current ~1.62 MiB)
        "build_artifact": True,
    },
    "mux_video_allocs_per_frame": {
        "area": "Muxer (C++)",
        "label": "Video heap allocs / frame",
        "unit": "count",
        "ceiling": 2,
    },
    "mux_keyframe_allocs": {
        "area": "Muxer (C++)",
        "label": "Keyframe heap allocs (with SPS/PPS)",
        "unit": "count",
        "ceiling": 2,
    },
    "mux_1080p30_wire_bytes": {
        "area": "Muxer (C++)",
        "label": "1080p30 TS wire (2s window)",
        "unit": "bytes",
        "ceiling": 3_300_000,  # ~12 Mbps payload + <5% TS/PES/PAT/PMT overhead
    },
    "default_bitrate_mbps": {
        "area": "Thermal",
        "label": "Default stream bitrate",
        "unit": "mbps",
        "ceiling": 12,
    },
    "default_fps": {
        "area": "Thermal",
        "label": "Default target FPS",
        "unit": "fps",
        "ceiling": 30,
    },
    "high_preset_bitrate_mbps": {
        "area": "Thermal",
        "label": "High preset bitrate",
        "unit": "mbps",
        "ceiling": 25,
    },
    "preview_buffer_pixels": {
        "area": "Power",
        "label": "Preview SurfaceView pixels",
        "unit": "pixels",
        "ceiling": 550_000,  # 960x540 = 518,400 px
    },
    "live_bg_wakeups_per_min": {
        "area": "Power",
        "label": "Live background wakeups / min",
        "unit": "count",
        "ceiling": 40,
    },
}

SCENARIO_SPECS: dict[str, dict[str, Any]] = {
    "apk_debug_dex_bytes": {
        "area": "APK",
        "scenario": "Debug DEX bytecode",
        "metric": "uncompressed size",
        "unit": "bytes",
        "ceiling": int(3.2 * 1024 * 1024),
        "build_artifact": True,
    },
    "apk_res_bytes": {
        "area": "APK",
        "scenario": "APK resources + arsc",
        "metric": "uncompressed size",
        "unit": "bytes",
        "ceiling": 1 * 1024 * 1024,
        "build_artifact": True,
    },
    "apk_abi_count": {
        "area": "APK",
        "scenario": "APK native ABIs",
        "metric": "abi count",
        "unit": "count",
        "ceiling": 4,
        "build_artifact": True,
    },
    "mux_1080p30_overhead_pct": {
        "area": "Muxer (C++)",
        "scenario": "1080p30 Balanced (60f)",
        "metric": "TS container overhead",
        "unit": "percent",
        "ceiling": 6.0,
    },
    "mux_1080p60_wire_bytes": {
        "area": "Muxer (C++)",
        "scenario": "1080p60 High (120f)",
        "metric": "TS wire (2s window)",
        "unit": "bytes",
        "ceiling": 6_600_000,
    },
    "mux_1080p60_allocs": {
        "area": "Muxer (C++)",
        "scenario": "1080p60 High (120f)",
        "metric": "heap allocs / frame",
        "unit": "count",
        "ceiling": 2,
    },
    "mux_audio_wire_bytes": {
        "area": "Muxer (C++)",
        "scenario": "Audio AAC 48kHz (100f)",
        "metric": "TS wire (2s window)",
        "unit": "bytes",
        "ceiling": 45_000,
    },
    "mux_audio_allocs": {
        "area": "Muxer (C++)",
        "scenario": "Audio AAC 48kHz (100f)",
        "metric": "heap allocs / frame",
        "unit": "count",
        "ceiling": 4,
    },
    "thermal_keyframe_cadence": {
        "area": "Thermal",
        "scenario": "Default keyframe cadence",
        "metric": "IDR frames / min",
        "unit": "count",
        "ceiling": 30,
    },
    "thermal_idle_preview_max_fps": {
        "area": "Thermal",
        "scenario": "Idle preview FPS cap",
        "metric": "max idle FPS",
        "unit": "fps",
        "ceiling": 30,
    },
    "thermal_severe_scale_pct": {
        "area": "Thermal",
        "scenario": "SEVERE thermal stepdown",
        "metric": "retained bitrate",
        "unit": "percent",
        "ceiling": 60,
    },
    "power_srt_accept_poll_rate": {
        "area": "Power",
        "scenario": "SRT idle listener epoll",
        "metric": "wakeups / sec",
        "unit": "rate",
        "ceiling": 3.0,
    },
    "power_control_idle_wakeups": {
        "area": "Power",
        "scenario": "Control server idle accept",
        "metric": "wakeups / min",
        "unit": "count",
        "ceiling": 0,
    },
    "power_discovery_live_wakeups": {
        "area": "Power",
        "scenario": "Discovery while streaming",
        "metric": "wakeups / min",
        "unit": "count",
        "ceiling": 0,
    },
}


def read_repo_text(rel_path: str) -> str:
    return (ROOT / rel_path).read_text(encoding="utf-8")


def measure_apk(
    apk_path: Path | None,
    release_dex_dir: Path | None = None,
) -> tuple[dict[str, int], dict[str, int]]:
    summary: dict[str, int] = {}
    scenarios: dict[str, int] = {}
    if apk_path is not None:
        if not apk_path.is_file():
            raise RuntimeError(f"Supplied APK does not exist: {apk_path}")
        total_bytes = apk_path.stat().st_size
        dex_bytes = 0
        arm64_bytes = 0
        res_bytes = 0
        native_by_abi: dict[str, int] = {}
        with zipfile.ZipFile(apk_path, "r") as zf:
            for info in zf.infolist():
                name = info.filename
                if name.endswith(".dex"):
                    dex_bytes += info.file_size
                elif name.startswith("lib/") and name.endswith(".so"):
                    parts = name.split("/")
                    if len(parts) >= 3:
                        abi = parts[1]
                        native_by_abi[abi] = native_by_abi.get(abi, 0) + info.file_size
                        if abi == "arm64-v8a":
                            arm64_bytes += info.file_size
                elif name.startswith("res/") or name == "resources.arsc":
                    res_bytes += info.file_size

        summary["apk_total_bytes"] = total_bytes
        summary["apk_arm64_native_bytes"] = arm64_bytes
        scenarios["apk_debug_dex_bytes"] = dex_bytes
        scenarios["apk_res_bytes"] = res_bytes
        scenarios["apk_abi_count"] = len(native_by_abi)

    if release_dex_dir is not None:
        if not release_dex_dir.is_dir():
            raise RuntimeError(f"Supplied release DEX directory does not exist: {release_dex_dir}")
        dex_files = sorted(release_dex_dir.glob("*.dex"))
        if not dex_files:
            raise RuntimeError(f"No .dex files found in release DEX directory: {release_dex_dir}")
        summary["release_r8_dex_bytes"] = sum(p.stat().st_size for p in dex_files)

    return summary, scenarios


