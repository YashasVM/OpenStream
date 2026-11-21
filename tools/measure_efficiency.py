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


def measure_native_muxer() -> tuple[dict[str, int], dict[str, int | float], dict[str, float]]:
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        raise RuntimeError("C++ compiler (g++ or clang++) required to benchmark native muxer")

    source = read_repo_text("android/app/src/main/cpp/openstream_srt.cpp")
    media = source[source.index("enum class VideoCodec") : source.index("struct SrtUrl")]

    harness = r"""
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <new>
#include <optional>
#include <string>
#include <utility>
#include <vector>

static std::atomic<uint64_t> g_alloc_count{0};
static thread_local bool g_track_allocs = false;

void *operator new(std::size_t size) {
  if (g_track_allocs) {
    g_alloc_count.fetch_add(1, std::memory_order_relaxed);
  }
  if (void *p = std::malloc(size == 0 ? 1 : size)) return p;
  throw std::bad_alloc();
}
void operator delete(void *p) noexcept { std::free(p); }
void operator delete(void *p, std::size_t) noexcept { std::free(p); }

constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannelCount = 1;
""" + media + r"""
int main() {
  const std::vector<uint8_t> spsPps = {
      0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x28, 0xd9, 0x00, 0x78, 0x02, 0x27, 0xe5,
      0, 0, 0, 1, 0x68, 0xce, 0x38, 0x80};
  std::vector<uint8_t> keyFrame(120 * 1024, 0x65);
  keyFrame[0] = 0; keyFrame[1] = 0; keyFrame[2] = 0; keyFrame[3] = 1;
  std::vector<uint8_t> pFrame(48 * 1024, 0x41);
  pFrame[0] = 0; pFrame[1] = 0; pFrame[2] = 0; pFrame[3] = 1;
  std::vector<uint8_t> pFrame60(50 * 1024, 0x41);
  pFrame60[0] = 0; pFrame60[1] = 0; pFrame60[2] = 0; pFrame60[3] = 1;
  const std::vector<uint8_t> asc = {0x11, 0x88};
  std::vector<uint8_t> audioFrame(320, 0x21);

  MpegTsMuxer mux30(VideoCodec::Avc);
  (void)mux30.muxAccessUnit(spsPps.data(), spsPps.size(), keyFrame.data(), keyFrame.size(), 0, true);

  g_alloc_count = 0;
  g_track_allocs = true;
  auto kfTs = mux30.muxAccessUnit(spsPps.data(), spsPps.size(), keyFrame.data(), keyFrame.size(), 33333, true);
  g_track_allocs = false;
  const uint64_t kfAllocs = g_alloc_count.load();
  const uint64_t kfWire = kfTs.size();
  assert(kfWire % 188 == 0);

  uint64_t totalWire30 = kfWire;
  uint64_t totalRaw30 = spsPps.size() + keyFrame.size();
  g_alloc_count = 0;
  const auto t0 = std::chrono::steady_clock::now();
  g_track_allocs = true;
  for (int i = 1; i < 60; ++i) {
    auto ts = mux30.muxAccessUnit(nullptr, 0, pFrame.data(), pFrame.size(),
                                  static_cast<int64_t>(i + 1) * 33333, false);
    assert(ts.size() % 188 == 0);
    totalWire30 += ts.size();
    totalRaw30 += pFrame.size();
  }
  g_track_allocs = false;
  const auto t1 = std::chrono::steady_clock::now();
  const uint64_t pAllocsPerFrame = (g_alloc_count.load() + 58) / 59;
  const double usPerFrame30 =
      std::chrono::duration<double, std::micro>(t1 - t0).count() / 59.0;

  MpegTsMuxer mux60(VideoCodec::Avc);
  (void)mux60.muxAccessUnit(spsPps.data(), spsPps.size(), keyFrame.data(), keyFrame.size(), 0, true);
  uint64_t totalWire60 = 0;
  g_alloc_count = 0;
  const auto t2 = std::chrono::steady_clock::now();
  g_track_allocs = true;
  for (int i = 0; i < 120; ++i) {
    const bool kf = (i == 0);
    auto ts = mux60.muxAccessUnit(
        kf ? spsPps.data() : nullptr,
        kf ? spsPps.size() : 0,
        kf ? keyFrame.data() : pFrame60.data(),
        kf ? keyFrame.size() : pFrame60.size(),
        static_cast<int64_t>(i + 1) * 16667,
        kf);
    assert(ts.size() % 188 == 0);
    totalWire60 += ts.size();
  }
  g_track_allocs = false;
  const auto t3 = std::chrono::steady_clock::now();
  const uint64_t allocs60PerFrame = (g_alloc_count.load() + 119) / 120;
  const double usPerFrame60 =
      std::chrono::duration<double, std::micro>(t3 - t2).count() / 120.0;

  MpegTsMuxer muxAudio(VideoCodec::Avc);
  (void)muxAudio.muxAudioAccessUnit(audioFrame, asc, 0);
  uint64_t totalWireAudio = 0;
  g_alloc_count = 0;
  const auto t4 = std::chrono::steady_clock::now();
  g_track_allocs = true;
  for (int i = 0; i < 100; ++i) {
    auto ts = muxAudio.muxAudioAccessUnit(audioFrame, asc, static_cast<int64_t>(i + 1) * 21333);
    assert(ts.size() % 188 == 0);
    totalWireAudio += ts.size();
  }
  g_track_allocs = false;
  const auto t5 = std::chrono::steady_clock::now();
  const uint64_t audioAllocsPerFrame = (g_alloc_count.load() + 99) / 100;
  const double usPerFrameAudio =
      std::chrono::duration<double, std::micro>(t5 - t4).count() / 100.0;

  const double overheadPct30 =
      100.0 * (static_cast<double>(totalWire30) - static_cast<double>(totalRaw30)) /
      static_cast<double>(totalRaw30);

  std::printf(
      "{\"kfAllocs\":%llu,\"pAllocsPerFrame\":%llu,\"totalWire30\":%llu,"
      "\"overheadPct30\":%.3f,\"usPerFrame30\":%.2f,"
      "\"totalWire60\":%llu,\"allocs60PerFrame\":%llu,\"usPerFrame60\":%.2f,"
      "\"totalWireAudio\":%llu,\"audioAllocsPerFrame\":%llu,\"usPerFrameAudio\":%.2f}\n",
      static_cast<unsigned long long>(kfAllocs),
      static_cast<unsigned long long>(pAllocsPerFrame),
      static_cast<unsigned long long>(totalWire30),
      overheadPct30,
      usPerFrame30,
      static_cast<unsigned long long>(totalWire60),
      static_cast<unsigned long long>(allocs60PerFrame),
      usPerFrame60,
      static_cast<unsigned long long>(totalWireAudio),
      static_cast<unsigned long long>(audioAllocsPerFrame),
      usPerFrameAudio);
  return 0;
}
"""

    with tempfile.TemporaryDirectory(prefix="openstream-mux-bench-") as tmp:
        cpp = Path(tmp) / "mux_bench.cpp"
        exe = Path(tmp) / "mux_bench"
        cpp.write_text(harness, encoding="utf-8")
        subprocess.run([compiler, "-std=c++20", "-O2", str(cpp), "-o", str(exe)], check=True)
        out = subprocess.run([str(exe)], check=True, capture_output=True, text=True, timeout=20)
        bench = json.loads(out.stdout.strip())

    summary = {
        "mux_video_allocs_per_frame": int(bench["pAllocsPerFrame"]),
        "mux_keyframe_allocs": int(bench["kfAllocs"]),
        "mux_1080p30_wire_bytes": int(bench["totalWire30"]),
    }
    scenarios: dict[str, int | float] = {
        "mux_1080p30_overhead_pct": round(float(bench["overheadPct30"]), 2),
        "mux_1080p60_wire_bytes": int(bench["totalWire60"]),
        "mux_1080p60_allocs": int(bench["allocs60PerFrame"]),
        "mux_audio_wire_bytes": int(bench["totalWireAudio"]),
        "mux_audio_allocs": int(bench["audioAllocsPerFrame"]),
    }
    timing_notes = {
        "mux_1080p30_us_per_frame": round(float(bench["usPerFrame30"]), 2),
        "mux_1080p60_us_per_frame": round(float(bench["usPerFrame60"]), 2),
        "mux_audio_us_per_frame": round(float(bench["usPerFrameAudio"]), 2),
    }
    return summary, scenarios, timing_notes


def _extract_int(source: str, pattern: str, label: str) -> int:
    match = re.search(pattern, source)
    if not match:
        raise ValueError(f"Could not extract {label} using pattern {pattern!r}")
    return int(match.group(1).replace("_", ""))


def measure_power_and_thermal_budgets() -> tuple[dict[str, int], dict[str, int | float]]:
    stream_cfg = read_repo_text("android/app/src/main/java/dev/openstream/app/stream/StreamConfig.kt")
    camera_ctrl = read_repo_text("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")
    advertiser = read_repo_text("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    main_act = read_repo_text("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    control_srv = read_repo_text("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    srt_cpp = read_repo_text("android/app/src/main/cpp/openstream_srt.cpp")
    thermal_policy = read_repo_text("android/app/src/main/java/dev/openstream/app/stream/ThermalPolicy.kt")

    default_quality_match = re.search(
        r"fun fromPreference\(value: String\?\): VideoQuality\s*=\s*entries\.firstOrNull\s*\{[^}]+\}\s*\?:\s*(\w+)",
        stream_cfg,
    )
    if not default_quality_match:
        raise ValueError("Could not locate VideoQuality.fromPreference default")
    default_quality = default_quality_match.group(1)

    default_width = _extract_int(stream_cfg, r"width\s*=\s*(\d+)", "Default width")
    default_height = _extract_int(stream_cfg, r"height\s*=\s*(\d+)", "Default height")
    balanced_fps = _extract_int(stream_cfg, r"fps\s*=\s*(\d+)", "Default fps")
    balanced_bitrate_bps = _extract_int(stream_cfg, r"bitrate\s*=\s*([\d_]+)", "Default bitrate")
    keyframe_interval_s = _extract_int(
        stream_cfg, r"keyframeIntervalSeconds\s*=\s*(\d+)", "keyframeIntervalSeconds"
    )
    high_fps = _extract_int(
        stream_cfg, r"VideoQuality\.High\s*->[^\n]*fps\s*=\s*(\d+)", "High preset fps"
    )
    high_bitrate_bps = _extract_int(
        stream_cfg, r"VideoQuality\.High\s*->[^\n]*bitrate\s*=\s*([\d_]+)", "High preset bitrate"
    )

    if default_quality == "High":
        eff_default_fps = high_fps
        eff_default_bitrate_mbps = high_bitrate_bps // 1_000_000
    elif default_quality == "DataSaver":
        eff_default_fps = balanced_fps
        eff_default_bitrate_mbps = 8
    else:
        eff_default_fps = balanced_fps
        eff_default_bitrate_mbps = balanced_bitrate_bps // 1_000_000

    max_preview_long = _extract_int(
        camera_ctrl, r"MAX_PREVIEW_LONG_EDGE\s*=\s*(\d+)", "MAX_PREVIEW_LONG_EDGE"
    )
    idle_preview_max_fps = _extract_int(
        camera_ctrl, r"IDLE_PREVIEW_MAX_FPS\s*=\s*(\d+)", "IDLE_PREVIEW_MAX_FPS"
    )
    scale = min(1.0, max_preview_long / float(max(default_width, default_height)))
    preview_w = (int(default_width * scale) & ~1)
    preview_h = (int(default_height * scale) & ~1)
    preview_pixels = preview_w * preview_h

    busy_beacon_ms = _extract_int(
        advertiser, r"BUSY_BEACON_INTERVAL_MS\s*=\s*([\d_]+)L", "BUSY_BEACON_INTERVAL_MS"
    )
    idle_beacon_ms = _extract_int(
        advertiser, r"IDLE_BEACON_INTERVAL_MS\s*=\s*([\d_]+)L", "IDLE_BEACON_INTERVAL_MS"
    )
    # Worst-case live mode (listener vs caller): caller mode must also slow
    # beacon cadence via streamingProvider even without a reservation.
    caller_slows_beacons = (
        "streamingProvider = { phoneConnected || activeTargetName != null }" in main_act
        and "val slowCadence = busy || streamingProvider()" in advertiser
    )
    effective_live_beacon_ms = busy_beacon_ms if caller_slows_beacons else idle_beacon_ms
    listener_unbounded_wait = "listenerWaitLock.wait()" in main_act
    listener_wakeups_per_min = 0 if listener_unbounded_wait else 240
    accept_poll_ms = _extract_int(
        srt_cpp, r"kListenerAcceptPollMs\s*=\s*(\d+)", "kListenerAcceptPollMs"
    )
    run_start = control_srv.index("private fun run()")
    run_end = control_srv.index("private fun handleClient(", run_start)
    control_accept_loop = control_srv[run_start:run_end]
    control_has_so_timeout = "soTimeout" in control_accept_loop
    control_wakeups_per_min = 60 if control_has_so_timeout else 0

    def _fn_slice(src: str, start_marker: str, end_marker: str) -> str:
        s = src.index(start_marker)
        e = src.index(end_marker, s)
        return src[s:e]

    caller_fn = _fn_slice(main_act, "private fun startStream(", "private fun startAudioIfAllowed(")
    listener_fn = _fn_slice(main_act, "private fun startPhoneServerIfAllowed(", "private fun isListenerActive(")
    stop_server_fn = _fn_slice(main_act, "private fun stopPhoneServer(", "private fun stopStream(")
    stop_stream_fn = _fn_slice(main_act, "private fun stopStream(", "private fun stopActiveEncoding(")
    discovery_paused_live = (
        "obsDiscoveryClient.stop()" in caller_fn
        and "obsDiscoveryClient.stop()" in listener_fn
        and "resumeDiscoveryIfIdle()" in stop_server_fn
        and "resumeDiscoveryIfIdle()" in stop_stream_fn
    )
    discovery_live_wakeups_per_min = 0 if discovery_paused_live else 120
    live_bg_wakeups_per_min = (
        (60_000 // effective_live_beacon_ms)
        + listener_wakeups_per_min
        + control_wakeups_per_min
        + discovery_live_wakeups_per_min
    )

    severe_scale_match = re.search(
        r"STATUS_SEVERE\s*->\s*Triple\(([\d.]+)f", thermal_policy
    )
    severe_scale_pct = int(round(float(severe_scale_match.group(1)) * 100)) if severe_scale_match else 100

    summary = {
        "default_bitrate_mbps": eff_default_bitrate_mbps,
        "default_fps": eff_default_fps,
        "high_preset_bitrate_mbps": high_bitrate_bps // 1_000_000,
        "preview_buffer_pixels": preview_pixels,
        "live_bg_wakeups_per_min": live_bg_wakeups_per_min,
    }
    scenarios: dict[str, int | float] = {
        "thermal_keyframe_cadence": 60 // max(1, keyframe_interval_s),
        "thermal_idle_preview_max_fps": idle_preview_max_fps,
        "thermal_severe_scale_pct": severe_scale_pct,
        "power_srt_accept_poll_rate": round(1000.0 / accept_poll_ms, 2),
        "power_control_idle_wakeups": control_wakeups_per_min,
        "power_discovery_live_wakeups": discovery_live_wakeups_per_min,
    }
    return summary, scenarios


def build_report(
    apk_path: Path | None,
    release_dex_dir: Path | None = None,
) -> dict[str, Any]:
    apk_summary, apk_scenarios = measure_apk(apk_path, release_dex_dir)
    mux_summary, mux_scenarios, timing_notes = measure_native_muxer()
    power_summary, power_scenarios = measure_power_and_thermal_budgets()

    combined_values: dict[str, int | float] = {}
    combined_values.update(apk_summary)
    combined_values.update(mux_summary)
    combined_values.update(power_summary)

    combined_scenarios: dict[str, int | float] = {}
    combined_scenarios.update(apk_scenarios)
    combined_scenarios.update(mux_scenarios)
    combined_scenarios.update(power_scenarios)

    metrics: list[dict[str, Any]] = []
    violations: list[str] = []
    for metric_id, spec in CEILINGS.items():
        if metric_id not in combined_values:
            continue
        value = combined_values[metric_id]
        ceiling = spec["ceiling"]
        passed = value <= ceiling
        if not passed:
            violations.append(f"{spec['label']}: {value} > ceiling {ceiling} ({spec['unit']})")
        metrics.append(
            {
                "id": metric_id,
                "area": spec["area"],
                "label": spec["label"],
                "value": value,
                "unit": spec["unit"],
                "ceiling": ceiling,
                "passed": passed,
            }
        )

    scenarios: list[dict[str, Any]] = []
    for scenario_id, spec in SCENARIO_SPECS.items():
        if scenario_id not in combined_scenarios:
            continue
        value = combined_scenarios[scenario_id]
        ceiling = spec["ceiling"]
        passed = value <= ceiling
        if not passed:
            violations.append(
                f"{spec['scenario']} ({spec['metric']}): {value} > ceiling {ceiling}"
            )
        scenarios.append(
            {
                "id": scenario_id,
                "area": spec["area"],
                "scenario": spec["scenario"],
                "metric": spec["metric"],
                "value": value,
                "unit": spec["unit"],
                "ceiling": ceiling,
                "passed": passed,
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "gitSha": os.environ.get("GITHUB_SHA", ""),
        "passed": len(violations) == 0,
        "violations": violations,
        "metrics": metrics,
        "scenarios": scenarios,
        "notes": timing_notes,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Measure OpenStream efficiency metrics")
    parser.add_argument("--apk", type=Path, default=None, help="Path to built Android APK")
    parser.add_argument(
        "--release-dex-dir",
        type=Path,
        default=None,
        help="Path to R8-minified release DEX output directory",
    )
    parser.add_argument("--output", type=Path, required=True, help="Output JSON report path")
    parser.add_argument(
        "--enforce-ceilings",
        action="store_true",
        help="Exit non-zero if any efficiency ceiling is exceeded",
    )
    args = parser.parse_args(argv)

    report = build_report(args.apk, args.release_dex_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(f"Wrote efficiency report to {args.output}")
    for m in report["metrics"]:
        status = "PASS" if m["passed"] else "FAIL"
        print(f"  [{status}] {m['area']} | {m['label']}: {m['value']} (ceiling {m['ceiling']} {m['unit']})")

    if args.enforce_ceilings and not report["passed"]:
        print("Efficiency ceiling violations:", file=sys.stderr)
        for v in report["violations"]:
            print(f"  - {v}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
