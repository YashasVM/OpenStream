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


