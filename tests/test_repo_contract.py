import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_architecture_documents_professional_camera_control_boundaries() -> None:
    architecture = read("docs/architecture.md")
    assert "Android camera head" in architecture
    assert "OBS control room" in architecture
    assert "CameraCapabilities" in architecture
    assert "CameraStateStore" in architecture
    assert "expected state" in architecture
    assert "OpenStreamCameraService" in architecture
    assert "Source Properties" in architecture
    assert "OpenStream Control Room" in architecture
    assert "SRT caller" in architecture
    assert "UDP discovery" in architecture
    assert "PTP" in architecture
    assert "Legacy discovery" in architecture


def test_android_project_declares_camera_media_codec_srt_discovery_boundaries() -> None:
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    discovery = read("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    manifest = read("android/app/src/main/AndroidManifest.xml")
    assert "Camera2" in app
    assert "MediaCodec" in app
    assert "SrtStreamClient" in app
    assert "status_ready" in app
    assert "PhoneDiscoveryAdvertiser" in app
    assert "startPreviewIfAllowed" in app
    assert "startPhoneServerIfAllowed" in app
    assert "MediaCodecAudioEncoder" in app
    stream_config = read("android/app/src/main/java/dev/openstream/app/stream/StreamConfig.kt")
    assert "PreferHevc" in stream_config
    assert "50_000_000" in stream_config
    assert "OPENSTREAM_PHONE/1" in discovery
    assert "DISCOVERY_PORT = 51515" in discovery
    assert "dev.openstream.phone" in discovery
    assert "DatagramSocket" in discovery
    assert "advertisedMimeType()" in discovery
    assert "CHANGE_WIFI_MULTICAST_STATE" in manifest
    assert "RECORD_AUDIO" in manifest
    assert "RECORD_AUDIO" in app


def test_android_connection_target_builds_srt_caller_url_and_pairing_targets() -> None:
    target = read("android/app/src/main/java/dev/openstream/app/stream/ConnectionTarget.kt")
    stream_client = read("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")
    assert "toSrtCallerUrl" in target
    assert "mode=caller" in target
    assert "DEFAULT_PORT = 9000" in target
    assert "fromDiscoveredDevice" in target
    assert "fromPairingUri" in target
    assert "openstream" in target
    assert "val stats: StreamStats" in stream_client
    assert "AtomicLong" in stream_client
    assert "accessUnitsSent.incrementAndGet()" in stream_client


def test_obs_plugin_registers_openstream_source_and_discovery() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "openstream_phone_v8_source" in source
    assert "openstream_phone_v7_source" in source
    assert "openstream_legacy_source_info" in source
    assert "obs_register_source" in source
    assert "OpenStream" in source
    assert "listener_enabled" in source
    assert "discovery_broadcast_addresses" in source
    assert "kDiscoveryMulticastAddress" in source
    assert "DiscoveryAdvertiser" in source
    assert "kDiscoveryPort = 51515" in source
    assert "OPENSTREAM/1" in source
    assert "srt_url" in source
    assert "listener_port" in source
    assert "phone_target_hint" in source
    assert "pairing_url" in source


def test_audio_path_uses_adts_aac_and_obs_planar_formats() -> None:
    native = read("android/app/src/main/cpp/openstream_srt.cpp")
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "makeAdtsFrame" in native
    assert "hasAdtsHeader" in native
    assert "muxAudioAccessUnit(" in native
    assert "g_state.audioCodecConfig" in native
    assert "output.reserve" in native
    assert "pes.reserve" in native
    assert "AUDIO_FORMAT_FLOAT_PLANAR" in source
    assert "audio_frame->format" in source
    assert "obs_source_output_audio" in source


def test_obs_plugin_routes_multiple_phones_by_selected_slot() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "selected_phone_id" in source
    assert "refresh_devices" in source
    assert "kAutoPhoneId" in source
    assert "std::map<std::string, PhoneDevice> devices_" in source
    assert "reserve_phone" in source
    assert '"bitrateMbps\\":" << ctx->bitrate_mbps' in source
    assert "release_phone" in source
    assert "control_phone(ctx)" in source


def test_obs_sources_are_named_camera_slots_with_advanced_transport() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "cam_label_for_index" in source
    assert '"CAM "' in source
    assert "next_available_slot_label_locked" in source
    assert "source_instance_id" in source
    assert "slot_id" in source
    assert "slot_label" in source
    assert "slot_status" in source
    assert "Waiting for a phone to choose " in source
    assert "pairing_hint" in source
    assert "OBS_GROUP_CHECKABLE, advanced_group" in source
    assert "listener_port" in source
    assert "SRT latency (ms)" in source
    assert '"bitrate_mbps", 50' in source
    assert '"bitrate_mbps", "Expected bitrate (Mbps)", 8, 120, 1' in source


def test_obs_control_room_api_is_capability_revision_and_event_driven() -> None:
    cmake = read("obs-plugin/CMakeLists.txt")
    api = read("obs-plugin/src/openstream-ui-api.hpp")
    source = read("obs-plugin/src/openstream-source.cpp")
    dock = read("obs-plugin/src/openstream-dock.cpp")

    assert "find_package(Qt6 6.2 COMPONENTS Widgets REQUIRED)" in cmake
    assert "option(OPENSTREAM_BUILD_DOCK" in cmake
    assert "src/openstream-dock.cpp" in cmake
    assert "src/openstream-dock-stub.cpp" in cmake
    assert "struct OpenStreamCameraCapabilities" in api
    assert "struct OpenStreamCameraState" in api
    assert "struct OpenStreamCameraSnapshot" in api
    assert "enum class OpenStreamCommandType" in api
    assert "struct OpenStreamSettingsPatch" in api
    assert "expected_revision" in api
    assert "FocusAt" in api
    assert "SetAuthority" in api
    assert "SetTally" in api
    assert "openstream_camera_snapshots()" in source
    assert "openstream_run_command_async" in source
    assert "openstream_subscribe_camera_changes" in api
    assert "openstream_unsubscribe_camera_changes" in api
    assert "obs_frontend_add_dock_by_id" in dock
    assert "obs_frontend_add_event_callback" in dock
    assert "obs_frontend_remove_event_callback" in dock
    assert "openstream_dock_create();" in source
    assert "openstream_dock_destroy();" in source


def test_obs_source_properties_are_setup_only() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    properties = source.split("obs_properties_t *openstream_properties", 1)[1].split(
        "return props;", 1
    )[0]

    assert '"slot_setup", "Camera setup"' in properties
    assert '"show_advanced", "Troubleshooting"' in properties
    assert '"selected_phone_id"' in properties
    assert '"connect"' in properties
    assert '"disconnect"' in properties
    assert '"listener_port"' in properties
    assert '"latency_ms"' in properties
    assert '"camera_controls"' not in properties
    assert '"cam_zoom"' not in properties
    assert '"cam_torch_on"' not in properties
    assert '"cam_lens_front"' not in properties


def test_android_professional_camera_state_contract_is_capability_driven() -> None:
    models = read("android/app/src/main/java/dev/openstream/app/camera/CameraModels.kt")
    store = read("android/app/src/main/java/dev/openstream/app/camera/CameraStateStore.kt")

    for model in (
        "CameraCapabilities",
        "CameraSettings",
        "CameraSettingsPatch",
        "CameraTelemetry",
        "CameraState",
        "TallyState",
    ):
        assert f"data class {model}" in models

    assert 'Collaborative("collaborative")' in models
    assert 'ObsLock("obs_lock")' in models
    assert "sealed interface CameraControlResult" in models
    assert "data class Conflict" in models
    assert "data class Unsupported" in models
    assert "data class Locked" in models
    assert "expectedRevision" in store
    assert "state.revision + 1" in store
    assert "patch.exposureMode ?: current.exposureMode" in store
    assert "patch.whiteBalanceKelvin ?: current.whiteBalanceKelvin" in store
    assert "state.authority == AuthorityMode.ObsLock" in store
    assert "actor == CameraActor.Camera" in store


def test_android_tap_focus_uses_normalized_transmitted_frame_coordinates() -> None:
    mapper = read("android/app/src/main/java/dev/openstream/app/camera/FocusCoordinateMapper.kt")
    store = read("android/app/src/main/java/dev/openstream/app/camera/CameraStateStore.kt")

    assert "normalizedX in 0f..1f" in mapper
    assert "normalizedY in 0f..1f" in mapper
    assert "rotationDegrees" in mapper
    assert "mirrored" in mapper
    assert "cropRegion" in mapper
    assert "activeArray" in mapper
    assert "caps.supportsTapFocus" in store
    assert "x !in 0f..1f" in store
    assert "y !in 0f..1f" in store


def test_camera2_controller_applies_manual_controls_from_complete_state() -> None:
    camera = read("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")

    assert "CameraStateStore" in camera
    assert "REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR" in camera
    assert "CONTROL_AE_MODE_OFF" in camera
    assert "SENSOR_SENSITIVITY" in camera
    assert "SENSOR_EXPOSURE_TIME" in camera
    assert "CONTROL_AWB_MODE" in camera
    assert "CONTROL_AWB_LOCK" in camera
    assert "LENS_FOCUS_DISTANCE" in camera
    assert "CONTROL_AF_REGIONS" in camera
    assert "CONTROL_AE_REGIONS" in camera
    assert "CONTROL_ZOOM_RATIO" in camera
    assert "SCALER_CROP_REGION" in camera
    assert "onCaptureCompleted" in camera
    assert "updateTelemetry" in camera


def test_obs_discovery_beacons_advertise_slots_not_raw_listener_only() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "sourceInstanceId" in source
    assert "slotId" in source
    assert "slotLabel" in source
    assert "pairingUrl" in source
    assert "listenerPort" in source
    assert "latencyMs" in source
    assert "busy" in source
    assert "ctx->discovery.start" in source
    assert "&ctx->slot_busy" in source


def test_slot_reservation_allows_owned_busy_phone_and_reconnect_hold() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    advertiser = read("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    strings = read("android/app/src/main/res/values/strings.xml")
    assert "reserved_by == source_instance_id" in source
    assert "set_slot_status(ctx, \"Reconnecting\")" in source
    assert "set_active_phone(ctx, reserved_phone)" in source
    assert '"reservedBy"' in advertiser
    assert "RECONNECT_RESERVATION_MS = 45_000L" in app
    assert "OpenStreamUiState.Reconnecting" in app
    assert 'name="status_holding_slot">Holding %1$s for reconnect<' in strings
    assert "scheduleReservationRelease" in app
    assert "cancelReservationRelease" in app


def test_android_discovery_ui_parses_and_displays_obs_slots() -> None:
    device = read("android/app/src/main/java/dev/openstream/app/discovery/DiscoveredObsDevice.kt")
    discovery = read("android/app/src/main/java/dev/openstream/app/discovery/ObsDiscoveryClient.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    layout = read("android/app/src/main/res/layout/activity_main.xml")
    strings = read("android/app/src/main/res/values/strings.xml")
    assert "val sourceInstanceId" in device
    assert "val slotId" in device
    assert "val slotLabel" in device
    assert "val pairingUrl" in device
    assert 'json.optString("slotLabel"' in discovery
    assert "ObsDiscoveryClient(" in app
    assert "renderObsSlots" in app
    assert "reserveForSlot" in app
    assert "slotAvailabilityLabel" in app
    assert "device.busy && reservedBy != device.sourceInstanceId" in app
    assert "compareBy<DiscoveredObsDevice> { it.displayLabel }" in discovery
    assert "obsSlotList" in layout
    assert 'name="status_waiting">Ready for OBS. Add an OpenStream Camera source on the same network.<' in strings
    assert "btnSettings" in app


def test_android_ui_state_and_settings_copy_cover_the_redesigned_workflow() -> None:
    state = read("android/app/src/main/java/dev/openstream/app/OpenStreamUiState.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    strings = read("android/app/src/main/res/values/strings.xml")

    for variant in (
        "Discovering",
        "Reserved",
        "Connecting",
        "Live",
        "Reconnecting",
        "Error",
        "Stopped",
    ):
        assert variant in state
        assert f"OpenStreamUiState.{variant}" in app

    for resource in (
        "settings_section_connection",
        "settings_section_streaming",
        "settings_section_advanced",
        "settings_section_updates",
        "settings_subtitle",
        "settings_connection_help",
        "control_torch",
        "control_flip",
        "control_awake",
        "control_dim",
        "control_settings",
        "action_stop_stream",
    ):
        assert f'name="{resource}"' in strings


def test_identify_camera_control_round_trip_exists() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    layout = read("android/app/src/main/res/layout/activity_main.xml")
    assert "OpenStreamCommandType::Identify" in source
    assert '"/identify"' in source
    assert 'path == "/identify"' in control
    assert "handleIdentify" in control
    assert "showIdentifyOverlay" in app
    assert "identifyOverlay" in layout


def test_android_control_server_supports_source_reservations() -> None:
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    assert 'path == "/reserve"' in control
    assert 'path == "/release"' in control
    assert "reservationProvider" in control
    assert "reserveForSource" in app
    assert "releaseForSource" in app
    assert "phoneConnected || reservedBy != null" in app
    assert "private var activeStreamBitrate" in app
    assert "useStreamBitrate(bitrateMbps)" in app
    assert "reserveForSource(device.sourceInstanceId, device.displayLabel, device.bitrateMbps)" in app
    assert "val bitrateMbps = if (json.has(\"bitrateMbps\"))" in control


def test_android_v2_control_plane_requires_pairing_and_bearer_auth() -> None:
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    token_store = read("android/app/src/main/java/dev/openstream/app/control/PairingTokenStore.kt")

    pair_route = 'method == "POST" && path == "/v2/pair"'
    auth_gate = 'path.startsWith("/v2/") && !pairingTokenStore.validateBearer'
    assert pair_route in control
    assert auth_gate in control
    assert control.index(pair_route) < control.index(auth_gate)
    for route in (
        "/v2/capabilities",
        "/v2/state",
        "/v2/settings",
        "/v2/focus",
        "/v2/authority",
        "/v2/tally",
    ):
        assert f'path == "{route}"' in control
    assert 'headers["authorization"]' in control
    assert 'errorJson("unauthorized"' in control
    assert '"expectedRevision"' in control
    assert '"revision_conflict"' in control
    assert '"unsupported"' in control
    assert '"obs_locked"' in control

    assert "SecureRandom" in token_store
    assert "TOKEN_BYTES = 32" in token_store
    assert "KEY_PAIRING_CODE, newPairingCode()" in token_store
    assert 'BEARER_PREFIX = "Bearer "' in token_store
    assert "MessageDigest.isEqual" in token_store


def test_obs_v2_client_uses_android_canonical_camera_schema() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")

    assert 'obs_data_create_from_json(json.c_str())' in source
    assert 'read_range(root, "shutterRangeNs", 0.001)' in source
    assert 'data_bool(root, "supportsTapFocus")' in source
    assert 'data_bool(root, "manualSensor")' in source
    assert 'data_string(settings, "stabilizationMode")' in source
    assert 'data_bool(tally, "program")' in source
    assert 'append_json_number(body, first, "shutterNs", shutter_ns)' in source
    assert 'append_json_number(body, first, "fps", command.settings.frame_rate)' in source
    assert 'append_json_number(body, first, "focusDistanceDiopters"' in source
    assert 'append_json_string(body, first, "stabilizationMode"' in source
    assert 'append_json_number(body, first, "shutterUs"' not in source
    assert 'append_json_number(body, first, "frameRate"' not in source


def test_android_unattended_service_is_explicit_and_non_sticky() -> None:
    manifest = read("android/app/src/main/AndroidManifest.xml")
    service = read("android/app/src/main/java/dev/openstream/app/service/OpenStreamCameraService.kt")

    assert "android.permission.FOREGROUND_SERVICE_CAMERA" in manifest
    assert "android.permission.FOREGROUND_SERVICE_MICROPHONE" in manifest
    assert 'android:foregroundServiceType="camera|microphone"' in manifest
    assert "ACTION_ARM" in service
    assert "ACTION_STOP" in service
    assert "START_NOT_STICKY" in service
    assert "PowerManager.PARTIAL_WAKE_LOCK" in service
    assert "WifiManager.WIFI_MODE_FULL_HIGH_PERF" in service
    assert "startForeground" in service
    assert "stopForeground" in service
    assert "onHeadlessSurfaceAvailable" in service
    assert "onRemoteStop" in service
    assert "LocalBinder" in service


def test_camera_controller_supports_preview_before_streaming() -> None:
    camera = read("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")
    assert "fun startPreview()" in camera
    assert "fun startStreaming(encodedSurface: Surface)" in camera
    assert "fun stopStreaming()" in camera
    assert "TEMPLATE_PREVIEW" in camera
    assert "TEMPLATE_RECORD" in camera


def test_android_default_build_requires_libsrt_with_ci_escape_hatch() -> None:
    gradle = read("android/app/build.gradle.kts")
    cmake = read("android/app/src/main/cpp/CMakeLists.txt")
    assert "openstream.nonStreamingCiBuild" in gradle
    assert "?: !nonStreamingCiBuild" in gradle
    assert "third_party/srt" in cmake
    assert "lib/${ANDROID_ABI}/libsrt.a" in cmake
    assert "OPENSTREAM_ENABLE_LIBSRT" in cmake
    assert "OPENSTREAM_HAVE_LIBSRT=1" in cmake


def test_receiver_validates_srt_support() -> None:
    receiver = read("tools/openstream_receiver.py")
    assert "ffmpeg_supports_srt" in receiver
    assert "mode=listener" in receiver
    assert "mode=caller" in receiver


def test_protocol_documents_media_control_and_compatibility_contracts() -> None:
    protocol = read("docs/protocol.md")
    assert "MediaCodec" in protocol
    assert "MPEG-TS" in protocol
    assert "OPENSTREAM/1" in protocol
    assert "openstream://connect" in protocol
    assert "sourceInstanceId" in protocol
    assert "slotId" in protocol
    assert "slotLabel" in protocol
    assert "pairingUrl" in protocol
    assert "reservedBy" in protocol
    assert "CameraCapabilities" in protocol
    assert "CameraState" in protocol
    assert "expectedRevision" in protocol
    assert "Authorization: Bearer" in protocol
    assert "/v2/focus" in protocol
    assert "normalized coordinates" in protocol
    assert "collaborative" in protocol
    assert "obs_lock" in protocol
    assert "Legacy V1 compatibility" in protocol


def test_docs_keep_python_receiver_as_developer_tool_only() -> None:
    setup = read("docs/setup.md")
    assert "developer/debug path only" in setup
    assert "not part of the normal user workflow" in setup


def test_release_workflows_build_streaming_apk_and_plugin_package() -> None:
    android_workflow = read(".github/workflows/android.yml")
    obs_workflow = read(".github/workflows/obs-plugin-windows.yml")
    release_workflow = read(".github/workflows/release.yml")
    release_docs = read("docs/release.md")
    plugin_builder = read("build_plugin.bat")
    gradle_properties = read("android/gradle.properties")

    assert ":app:assembleDebug" in android_workflow
    assert "openstream.nonStreamingCiBuild" not in android_workflow
    assert "openstream-android-debug-apk" in android_workflow
    assert "python -m pytest -q" in android_workflow
    assert ":app:lintDebug" in android_workflow
    assert ":app:assembleRelease" in release_workflow
    assert ":app:assembleDebug" not in release_workflow
    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" in release_workflow
    assert "OPENSTREAM_VERSION_NAME" in release_workflow
    assert "OPENSTREAM_VERSION_CODE" in release_workflow
    assert "OPENSTREAM_SKIP_INSTALL=1" in obs_workflow
    assert "OPENSTREAM_PLUGIN_PACKAGE_DIR" in obs_workflow
    assert "openstream-obs-windows-x64.zip" in obs_workflow
    assert "gh release create" in release_workflow
    assert "docs/release-notes-template.md" in release_workflow
    assert "openstream-android.apk" in release_workflow
    assert "openstream-android.apk.sha256" in release_workflow
    assert '"apkSha256"' in release_workflow
    assert "Public releases require all Android signing secrets" in release_workflow
    assert "openstream-obs-windows-x64.zip" in release_workflow
    assert "never publishes a debug-signed fallback" in release_docs
    assert "Android Signing Secrets" in release_docs
    assert "OPENSTREAM_SKIP_INSTALL" in plugin_builder
    assert "OPENSTREAM_PLUGIN_PACKAGE_DIR" in plugin_builder
    assert "Compress-Archive" in plugin_builder
    assert "org.gradle.java.home" not in gradle_properties


def test_manual_obs_installer_replaces_known_plugin_copies() -> None:
    installer = read("tools/installer/Install-OpenStreamPlugin.ps1")

    assert "Get-OpenStreamPluginTargets" in installer
    assert "ProgramData" in installer
    assert "APPDATA" in installer
    assert "OpenStream V8" in installer


def test_release_build_fails_without_signing_and_keystores_are_ignored() -> None:
    app_gradle = read("android/app/build.gradle.kts")
    gitignore = read(".gitignore")

    assert "Release builds require OPENSTREAM_RELEASE_KEYSTORE" in app_gradle
    assert "openstream.versionName" in app_gradle
    assert "openstream.versionCode" in app_gradle
    assert '"2.0.0-beta"' in app_gradle
    version_code = re.search(
        r"openStreamVersionCode.*?\.orElse\(\"(\d+)\"\)",
        app_gradle,
        re.DOTALL,
    )
    assert version_code is not None
    assert int(version_code.group(1)) > 0
    assert "*.keystore" in gitignore
    assert "*.jks" in gitignore


def test_v2_release_metadata_defaults_are_aligned() -> None:
    app_gradle = read("android/app/build.gradle.kts")
    cmake = read("obs-plugin/CMakeLists.txt")
    installer = read("tools/installer/openstream-obs-plugin.iss")

    assert '"2.0.0-beta"' in app_gradle
    assert "project(openstream_obs_plugin VERSION 2.0.0" in cmake
    assert '#define OpenStreamVersion "2.0.0-beta"' in installer
