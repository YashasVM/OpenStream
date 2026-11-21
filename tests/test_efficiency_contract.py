"""Contracts and behavioral tests for OpenStream's lightweight & thermal optimizations
and the trusted PR efficiency-report bot.
"""

from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from _helpers import ROOT, block_after, read_text

TOOLS_DIR = ROOT / "tools"


def _load_tool(name: str):
    path = TOOLS_DIR / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def test_default_stream_profile_is_thermally_sustainable():
    config = read_text("android/app/src/main/java/dev/openstream/app/stream/StreamConfig.kt")
    settings_layout = read_text("android/app/src/main/res/layout/activity_settings.xml")

    assert "keyframeIntervalSeconds = 2" in config
    assert "VideoQuality.High -> Default1080p30.copy(fps = 60, bitrate = 25_000_000" in config
    assert "entries.firstOrNull { it.name == value } ?: Balanced" in config
    assert "High · 1080p60 · 25 Mbps" in settings_layout


def test_display_off_stops_hidden_animations_ticker_and_preview_surface():
    activity = read_text("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    camera = read_text("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")

    set_display = block_after(activity, "private fun setDisplayOff(enabled: Boolean)")
    assert "stopLiveDotAnimation()" in set_display
    assert "mainHandler.removeCallbacks(statsTicker)" in set_display
    assert "camera.setPreviewSuppressed(true)" in set_display
    assert "camera.setPreviewSuppressed(false)" in set_display

    show_live = block_after(activity, "private fun showLiveState(targetName: String)")
    assert "if (!displayOff)" in show_live

    suppress = block_after(camera, "fun setPreviewSuppressed(suppressed: Boolean)")
    assert "synchronized(lifecycleLock)" in suppress
    assert "rebuildRepeatingRequest()" in suppress

    create_session = block_after(camera, "private fun createSession()")
    assert "if (encoded == null || !previewSuppressed)" in create_session
    rebuild = block_after(camera, "private fun rebuildRepeatingRequest()")
    assert "if (encoded == null || !previewSuppressed)" in rebuild


def test_camera_and_display_apply_refresh_cap_supported_preview_size_and_isp_efficiency():
    activity = read_text("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    camera = read_text("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")

    assert "capDisplayRefreshRate()" in activity
    assert "preferredRefreshRate = PREFERRED_DISPLAY_REFRESH_HZ" in activity
    assert "camera.recommendedPreviewSize(currentLens, streamConfig.width, streamConfig.height)" in activity
    assert "cameraPreview.holder.setFixedSize(width, height)" in activity
    assert "pendingPreviewBufferSize" in activity
    assert "pendingLensSwitch" in activity
    assert "pendingLensRestartStream" in activity
    assert "pendingPreviewBufferSize == null" in block_after(activity, "private fun startPreviewIfAllowed()")
    assert "if (pendingPreviewBufferSize != null) return" in block_after(
        activity, "private fun startPhoneServerIfAllowed()"
    )
    # Preview layout must use the confirmed preview buffer aspect ratio (not hardcoded stream 16:9).
    adjust_aspect = block_after(activity, "private fun adjustPreviewAspectRatio()")
    assert "appliedPreviewBufferSize ?: pendingPreviewBufferSize" in adjust_aspect
    # Listener startup must serialize thermal bitrate with callerLifecycleLock and abort on cancel.
    listener_fn = block_after(activity, "private fun startPhoneServerIfAllowed()")
    assert "synchronized(callerLifecycleLock)" in listener_fn
    assert listener_fn.index("obsDiscoveryClient.stop()") < listener_fn.index("synchronized(callerLifecycleLock)")

    assert "chooseCaptureFpsRange(" in camera
    assert "IDLE_PREVIEW_MIN_UPPER_FPS = 15" in camera
    assert "IDLE_PREVIEW_MAX_FPS = 30" in camera
    assert "MAX_PREVIEW_LONG_EDGE = 960" in camera
    assert "choosePreviewBufferSize(" in camera
    assert "SCALER_STREAM_CONFIGURATION_MAP" in camera
    assert "CONTROL_VIDEO_STABILIZATION_MODE_OFF" in camera
    assert "NOISE_REDUCTION_MODE_FAST" in camera
    assert "EDGE_MODE_FAST" in camera
    assert "applyFrameRate(this, streaming = encoded != null)" in camera


def test_background_wakeups_and_multicast_lock_are_reduced_while_streaming():
    activity = read_text("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    discovery = read_text("android/app/src/main/java/dev/openstream/app/discovery/ObsDiscoveryClient.kt")
    advertiser = read_text("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    control = read_text("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")

    # Discovery (and its WifiManager.MulticastLock) pauses while live (guarded by generation)
    # and resumes on every teardown, including Disconnect (stopPhoneServer).
    assert activity.count("obsDiscoveryClient.stop()") >= 3
    start_stream = block_after(activity, "private fun startStream(")
    assert "if (callerGeneration == generation) {\n                    obsDiscoveryClient.stop()" in start_stream
    stop_server = block_after(activity, "private fun stopPhoneServer(")
    assert "resumeDiscoveryIfIdle()" in stop_server
    stop_stream = block_after(activity, "private fun stopStream(")
    assert "resumeDiscoveryIfIdle()" in stop_stream

    # Listener thread waits without polling timeout while connected.
    assert "LISTENER_POLL_MS" not in activity
    assert "listenerWaitLock.wait()" in activity
    assert "listenerWaitLock.notifyAll()" in activity

    # Control server blocks on accept() with zero idle soTimeout wakeups.
    control_run = block_after(control, "private fun run()")
    assert "soTimeout" not in control_run

    # Discovery client blocks with soTimeout = 0 when no devices are cached.
    assert "multicast.soTimeout = if (hasDevices()) EXPIRY_POLL_MS else 0" in discovery

    # Advertiser slows down in both listener AND caller live modes and caches static addresses.
    assert "BUSY_BEACON_INTERVAL_MS = 2_000L" in advertiser
    assert "val slowCadence = busy || streamingProvider()" in advertiser
    assert "streamingProvider = { phoneConnected || activeTargetName != null }" in activity
    assert "val broadcastAddress =" in advertiser
    assert "val multicastAddress =" in advertiser


def test_thermal_listener_reapplies_bitrate_on_every_codec_start_and_caches_only_on_success():
    activity = read_text("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    encoder = read_text("android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt")
    policy = read_text("android/app/src/main/java/dev/openstream/app/stream/ThermalPolicy.kt")

    assert "addThermalStatusListener" in activity
    assert "removeThermalStatusListener" in activity
    assert "ThermalPolicy.evaluate(" in activity
    # Forced re-application after encoder.start() in caller, listener, and physical lens restart.
    assert activity.count("applyThermalBitrate(force = true)") == 3

    # Caller publishes activeTargetName under callerLifecycleLock before applying thermal bitrate
    # so concurrent thermal callbacks cannot miss the live transition.
    start_stream = block_after(activity, "private fun startStream(")
    caller_lock_block = block_after(start_stream, "synchronized(callerLifecycleLock)")
    assert caller_lock_block.index("activeTargetName = target.name") < caller_lock_block.index(
        "applyThermalBitrate(force = true)"
    )

    apply_fn = block_after(activity, "private fun applyThermalBitrate(force: Boolean = false)")
    assert "if (encoder.updateBitrate(mitigation.targetBitrateBps))" in apply_fn
    assert apply_fn.index("encoder.updateBitrate(") < apply_fn.index("appliedThermalBitrate = mitigation.targetBitrateBps")

    stop_enc = block_after(activity, "private fun stopActiveEncoding(")
    assert "appliedThermalBitrate = 0" in stop_enc

    assert "fun updateBitrate(newBitrateBps: Int): Boolean" in encoder
    assert "PARAMETER_KEY_VIDEO_BITRATE" in encoder
    assert "requestSyncFrame" not in encoder
    assert "MIN_THERMAL_BITRATE_BPS" in policy


def test_release_build_enables_r8_shrinking_and_keeps_jni_symbols():
    gradle = read_text("android/app/build.gradle.kts")
    proguard = read_text("android/app/proguard-rules.pro")

    assert "isMinifyEnabled = true" in gradle
    assert "isShrinkResources = true" in gradle
    assert "proguard-rules.pro" in gradle
    assert "dev.openstream.app.stream.SrtNativeBridge" in proguard
    assert "java.util.function.Predicate" in proguard


def test_native_muxer_zero_copy_prefix_matches_concat_and_validates_mpegts(tmp_path):
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler is None:
        pytest.skip("requires C++ compiler")

    source = read_text("android/app/src/main/cpp/openstream_srt.cpp")
    media = source[source.index("enum class VideoCodec") : source.index("struct SrtUrl")]

    harness = r"""
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <map>
#include <new>
#include <optional>
#include <string>
#include <utility>
#include <vector>

static std::atomic<uint64_t> g_allocs{0};
static thread_local bool g_count = false;
void *operator new(std::size_t sz) {
  if (g_count) g_allocs.fetch_add(1, std::memory_order_relaxed);
  if (void *p = std::malloc(sz == 0 ? 1 : sz)) return p;
  throw std::bad_alloc();
}
void operator delete(void *p) noexcept { std::free(p); }
void operator delete(void *p, std::size_t) noexcept { std::free(p); }

constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannelCount = 1;
""" + media + r"""
static void verifyTsPackets(const std::vector<uint8_t> &ts,
                            std::map<uint16_t, uint8_t> &expectedCc,
                            std::vector<uint8_t> &reconstructedVideoPes) {
  assert(ts.size() % 188 == 0);
  for (size_t off = 0; off < ts.size(); off += 188) {
    const uint8_t *p = ts.data() + off;
    assert(p[0] == 0x47);
    const uint16_t pid = static_cast<uint16_t>(((p[1] & 0x1fu) << 8u) | p[2]);
    const uint8_t afc = (p[3] >> 4u) & 0x03u;
    const uint8_t cc = p[3] & 0x0fu;
    if (expectedCc.count(pid)) {
      assert(cc == expectedCc[pid]);
    }
    expectedCc[pid] = static_cast<uint8_t>((cc + 1u) & 0x0fu);

    size_t idx = 4;
    if (afc == 2 || afc == 3) {
      const uint8_t adaptLen = p[idx++];
      assert(idx + adaptLen <= 188);
      idx += adaptLen;
    }
    if ((afc == 1 || afc == 3) && pid == 0x101) {
      reconstructedVideoPes.insert(reconstructedVideoPes.end(), p + idx, p + 188);
    }
    if ((afc == 1 || afc == 3) && (pid == 0x0000 || pid == 0x0100)) {
      const uint8_t pointer = p[idx++];
      idx += pointer;
      const uint16_t secLen = static_cast<uint16_t>(((p[idx + 1] & 0x0fu) << 8u) | p[idx + 2]);
      const size_t totalSecLen = 3u + secLen;
      assert(idx + totalSecLen <= 188);
      assert(crc32Mpeg(p + idx, totalSecLen) == 0);
    }
  }
}

int main() {
  const std::vector<uint8_t> spsPps = {0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x28, 0, 0, 0, 1, 0x68, 0xce};
  std::vector<uint8_t> idr(4096, 0x65);
  idr[0] = 0; idr[1] = 0; idr[2] = 0; idr[3] = 1;
  std::vector<uint8_t> combined;
  combined.insert(combined.end(), spsPps.begin(), spsPps.end());
  combined.insert(combined.end(), idr.begin(), idr.end());

  MpegTsMuxer splitMux(VideoCodec::Avc);
  MpegTsMuxer concatMux(VideoCodec::Avc);
  std::map<uint16_t, uint8_t> ccMap;

  for (int i = 0; i < 65; ++i) {
    const bool kf = (i % 30 == 0);
    const auto tsSplit = splitMux.muxAccessUnit(
        kf ? spsPps.data() : nullptr,
        kf ? spsPps.size() : 0,
        idr.data(),
        idr.size(),
        static_cast<int64_t>(i) * 33333,
        kf);
    const auto tsConcat = concatMux.muxAccessUnit(
        kf ? combined : idr,
        static_cast<int64_t>(i) * 33333,
        kf);
    assert(tsSplit == tsConcat);

    std::vector<uint8_t> pes;
    verifyTsPackets(tsSplit, ccMap, pes);
    assert(pes.size() == 14 + (kf ? combined.size() : idr.size()));
    assert(pes[0] == 0x00 && pes[1] == 0x00 && pes[2] == 0x01 && pes[3] == 0xe0);
    const auto &expectedBody = kf ? combined : idr;
    assert(std::equal(expectedBody.begin(), expectedBody.end(), pes.begin() + 14));
  }

  // Steady-state heap allocation bound: 1 allocation per video frame (output vector only).
  g_allocs = 0;
  g_count = true;
  auto kfTs = splitMux.muxAccessUnit(spsPps.data(), spsPps.size(), idr.data(), idr.size(), 9999999, true);
  g_count = false;
  assert(kfTs.size() % 188 == 0);
  assert(g_allocs.load() <= 1);

  g_allocs = 0;
  g_count = true;
  auto pTs = splitMux.muxAccessUnit(nullptr, 0, idr.data(), idr.size(), 10033332, false);
  g_count = false;
  assert(pTs.size() % 188 == 0);
  assert(g_allocs.load() <= 1);
}
"""
    cpp = tmp_path / "mux_verify.cpp"
    exe = tmp_path / "mux_verify"
    cpp.write_text(harness, encoding="utf-8")
    subprocess.run([compiler, "-std=c++20", "-O2", str(cpp), "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True, timeout=20)


def test_measure_efficiency_report_passes_all_ceilings_and_fails_on_missing_explicit_outputs(tmp_path):
    measure = _load_tool("measure_efficiency")
    report = measure.build_report(apk_path=None)

    assert report["schemaVersion"] == 1
    assert report["passed"] is True, f"Ceiling violations: {report['violations']}"
    metrics = {m["id"]: m for m in report["metrics"]}
    assert metrics["mux_video_allocs_per_frame"]["value"] == 1
    assert metrics["mux_keyframe_allocs"]["value"] == 1
    assert metrics["default_bitrate_mbps"]["value"] == 12
    assert metrics["default_fps"]["value"] == 30
    assert metrics["high_preset_bitrate_mbps"]["value"] == 25
    assert metrics["preview_buffer_pixels"]["value"] == 960 * 540
    assert metrics["live_bg_wakeups_per_min"]["value"] == 30

    # Explicitly supplied missing APK or release DEX dir must fail hard.
    with pytest.raises(RuntimeError, match="Supplied APK does not exist"):
        measure.build_report(apk_path=tmp_path / "missing.apk")
    with pytest.raises(RuntimeError, match="Supplied release DEX directory does not exist"):
        measure.build_report(apk_path=None, release_dex_dir=tmp_path / "missing-dex")


def test_efficiency_comment_renderer_enforces_trusted_spec_and_rejects_tampering(tmp_path):
    measure = _load_tool("measure_efficiency")
    render = _load_tool("render_efficiency_comment")

    baseline = measure.build_report(apk_path=None)
    pr = measure.build_report(apk_path=None)
    # Tamper with PR report: exceed bitrate ceiling while lying about ceiling, label, unit, and passed.
    for m in pr["metrics"]:
        if m["id"] == "default_bitrate_mbps":
            m["value"] = 18
            m["ceiling"] = 999
            m["label"] = "<script>alert(1)</script>"
            m["unit"] = "fake"
            m["passed"] = True

    md = render.render_comment(
        pr,
        baseline,
        pr_sha="abcdef1234567890",
        pr_run_url="https://github.com/YashasVM/OpenStream/actions/runs/123",
        baseline_sha="11111112222222",
        baseline_run_url="https://github.com/YashasVM/OpenStream/actions/runs/100",
        source_conclusion="failure",
        require_build_metrics=False,
    )

    assert render.MARKER in md
    assert "<!-- openstream-efficiency-result-sha:abcdef1234567890 -->" in md
    # Trusted ceiling (12 Mbps) must still fail despite PR JSON claiming ceiling=999.
    assert "❌ 1 trusted efficiency ceiling(s) exceeded." in md
    assert "+6 Mbps (+50.0%)" in md
    assert "<script>" not in md
    assert "Default stream bitrate" in md
    assert "Measurements are PR-produced" in md

    # Omitting a required metric must fail validation (cannot hide regressions).
    incomplete = measure.build_report(apk_path=None)
    incomplete["metrics"] = [m for m in incomplete["metrics"] if m["id"] != "default_bitrate_mbps"]
    with pytest.raises(render.ValidationError, match="Missing required metric"):
        render.validate_report(incomplete, require_build_metrics=False)

    # Full CI validation requires build metrics (APK + R8 DEX).
    with pytest.raises(render.ValidationError, match="apk_total_bytes"):
        render.validate_report(baseline, require_build_metrics=True)

    # Unknown metric IDs and NaN values must fail closed.
    bad_id = {
        "schemaVersion": 1,
        "metrics": [{"id": "evil_metric", "value": 1}],
        "scenarios": [],
    }
    with pytest.raises(render.ValidationError):
        render.validate_report(bad_id, require_build_metrics=False)

    bad_nan = measure.build_report(apk_path=None)
    bad_nan["metrics"][0]["value"] = float("nan")
    with pytest.raises(render.ValidationError):
        render.validate_report(bad_nan, require_build_metrics=False)

    # CLI must handle malformed JSON AND untrusted validation errors cleanly without
    # echoing PR-supplied IDs or JSON payloads into the public comment.
    malformed_path = tmp_path / "malformed.json"
    malformed_path.write_text("{not-valid-json <script>evil()</script>", encoding="utf-8")
    out_malformed = tmp_path / "out-malformed.md"
    assert render.main(["--pr-report", str(malformed_path), "--pr-sha", "abcdef1", "--output", str(out_malformed)]) == 0
    malformed_md = out_malformed.read_text(encoding="utf-8")
    assert render.MARKER in malformed_md
    assert "Efficiency report unavailable" in malformed_md
    assert "<script>" not in malformed_md

    tampered_id_path = tmp_path / "tampered-id.json"
    tampered_id_path.write_text('{"schemaVersion":1,"metrics":[{"id":"<img src=x onerror=alert(1)>","value":1}],"scenarios":[]}', encoding="utf-8")
    out_tampered = tmp_path / "out-tampered.md"
    assert render.main(["--pr-report", str(tampered_id_path), "--pr-sha", "abcdef1", "--output", str(out_tampered)]) == 0
    tampered_md = out_tampered.read_text(encoding="utf-8")
    assert render.MARKER in tampered_md
    assert "Efficiency report unavailable" in tampered_md
    assert "onerror" not in tampered_md

    # Oversized JSON integers (400-digit float overflow AND 5000-digit Python int-str limit)
    # must produce an unavailable comment cleanly instead of crashing.
    huge_int_report = measure.build_report(apk_path=None)
    huge_int_report["metrics"][0]["value"] = 10**400
    with pytest.raises(render.ValidationError):
        render.validate_report(huge_int_report, require_build_metrics=False)
    for digits in (400, 5000):
        huge_path = tmp_path / f"huge-int-{digits}.json"
        huge_path.write_text(
            '{"schemaVersion":1,"metrics":[{"id":"default_fps","value":' + ("9" * digits) + '}],"scenarios":[]}',
            encoding="utf-8",
        )
        out_huge = tmp_path / f"out-huge-{digits}.md"
        assert render.main(["--pr-report", str(huge_path), "--pr-sha", "abcdef1", "--output", str(out_huge)]) == 0
        assert "Efficiency report unavailable" in out_huge.read_text(encoding="utf-8")

    # Deeply nested JSON triggering RecursionError must also emit an unavailable comment.
    deep_path = tmp_path / "deep-nested.json"
    deep_path.write_text("[" * 2500 + "]" * 2500, encoding="utf-8")
    out_deep = tmp_path / "out-deep.md"
    assert render.main(["--pr-report", str(deep_path), "--pr-sha", "abcdef1", "--output", str(out_deep)]) == 0
    assert "Efficiency report unavailable" in out_deep.read_text(encoding="utf-8")

    # Invalid baseline is discarded and its SHA must not appear as the active baseline.
    md_bad_base = render.render_comment(
        baseline,
        {"schemaVersion": 999},
        pr_sha="abcdef1234567890",
        baseline_sha="deadbeef1234567",
        baseline_run_url="https://github.com/YashasVM/OpenStream/actions/runs/999",
        require_build_metrics=False,
    )
    assert "Baseline: none yet" in md_bad_base
    assert "deadbee" not in md_bad_base


def test_efficiency_pr_bot_workflow_is_isolated_serialized_and_trusted():
    android_wf = read_text(".github/workflows/android.yml")
    bot_wf = read_text(".github/workflows/efficiency-report.yml")

    assert ":app:minifyReleaseWithR8" in android_wf
    assert "--release-dex-dir" in android_wf
    assert "tools/measure_efficiency.py" in android_wf
    assert "--enforce-ceilings" in android_wf
    assert "openstream-efficiency-report" in android_wf
    assert "pull-requests: write" not in android_wf

    assert "workflow_run:" in bot_wf
    assert 'workflows: ["Android APK"]' in bot_wf
    assert "pull-requests: write" in bot_wf
    assert "persist-credentials: false" in bot_wf
    assert "cancel-in-progress: false" in bot_wf
    assert "queue: max" in bot_wf
    assert "ref: ${{ github.event.workflow_run.head_sha }}" not in bot_wf
    assert "len(matched) == 1" in bot_wf
    assert "size_in_bytes" in bot_wf
    assert "head -c $((max_zip_bytes + 1))" in bot_wf
    assert "--unavailable" in bot_wf
    assert "info.file_size > MAX_BYTES" in bot_wf
    assert "stream.read(MAX_BYTES + 1)" in bot_wf
    assert "tools/render_efficiency_comment.py" in bot_wf
    assert "<!-- openstream-efficiency-report -->" in bot_wf
    # Comment lookup must fail hard (no || true) and final PR state/head verification
    # must happen AFTER comment lookup, right before PATCH/POST.
    lookup_idx = bot_wf.index('--slurp "repos/${REPO}/issues/${pr_number}/comments"')
    final_check_idx = bot_wf.index('if [ "$pr_still_matches" != "true" ]')
    patch_idx = bot_wf.index("gh api -X PATCH")
    assert lookup_idx < final_check_idx < patch_idx
