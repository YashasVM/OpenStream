from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_architecture_documents_practical_v1_transport() -> None:
    architecture = read("docs/architecture.md")
    assert "MediaCodec hardware HEVC/H.264 video encode" in architecture
    assert "MediaCodec AAC audio encode" in architecture
    assert "SRT caller" in architecture
    assert "UDP discovery" in architecture
    assert "PTP" in architecture
    assert "not required for the first prototype" in architecture


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
    assert "OPENSTREAM_PHONE/1" in discovery
    assert "DISCOVERY_PORT = 51515" in discovery
    assert "dev.openstream.phone" in discovery
    assert "DatagramSocket" in discovery
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
    assert "Discovered phones" in source
    assert "Refresh Phones" in source
    assert "refresh_devices" in source
    assert "kAutoPhoneId" in source
    assert "std::map<std::string, PhoneDevice> devices_" in source
    assert "reserve_phone" in source
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
    assert "reserved_by == source_instance_id" in source
    assert "set_slot_status(ctx, \"Reconnecting\")" in source
    assert "set_active_phone(ctx, reserved_phone)" in source
    assert '"reservedBy"' in advertiser
    assert "RECONNECT_RESERVATION_MS = 45_000L" in app
    assert "Holding $it for reconnect" in app
    assert "scheduleReservationRelease" in app
    assert "cancelReservationRelease" in app


def test_android_discovery_ui_parses_and_displays_obs_slots() -> None:
    device = read("android/app/src/main/java/dev/openstream/app/discovery/DiscoveredObsDevice.kt")
    discovery = read("android/app/src/main/java/dev/openstream/app/discovery/ObsDiscoveryClient.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    layout = read("android/app/src/main/res/layout/activity_main.xml")
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
    assert "Available OBS cameras" in app
    assert "obsSlotList" in layout
    assert 'android:text="ADV"' in layout


def test_identify_camera_control_round_trip_exists() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    layout = read("android/app/src/main/res/layout/activity_main.xml")
    assert "Show Slot Label on Phone" in source
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


def test_protocol_documents_media_and_telemetry_contracts() -> None:
    protocol = read("docs/protocol.md")
    assert "MediaCodec" in protocol
    assert "MPEG-TS" in protocol
    assert "OPENSTREAM/1" in protocol
    assert "openstream://connect" in protocol
    assert "sourceInstanceId" in protocol
    assert "slotId" in protocol
    assert "slotLabel" in protocol
    assert "pairingUrl" in protocol
    assert "deviceName" in protocol
    assert "reservedBy" in protocol
    assert "latencyMs" in protocol


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
    assert ":app:assembleRelease" in release_workflow
    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" in release_workflow
    assert "OPENSTREAM_VERSION_NAME" in release_workflow
    assert "OPENSTREAM_VERSION_CODE" in release_workflow
    assert "OPENSTREAM_SKIP_INSTALL=1" in obs_workflow
    assert "OPENSTREAM_PLUGIN_PACKAGE_DIR" in obs_workflow
    assert "openstream-obs-windows-x64.zip" in obs_workflow
    assert "gh release create" in release_workflow
    assert "docs/release-notes-template.md" in release_workflow
    assert "openstream-android.apk" in release_workflow
    assert "openstream-android-debug.apk" not in release_workflow
    assert "openstream-obs-windows-x64.zip" in release_workflow
    assert "Do not pass `-Popenstream.nonStreamingCiBuild=true`" in release_docs
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
    assert '.orElse("20")' in app_gradle
    assert "*.keystore" in gitignore
    assert "*.jks" in gitignore


def test_v2_release_metadata_defaults_are_aligned() -> None:
    app_gradle = read("android/app/build.gradle.kts")
    cmake = read("obs-plugin/CMakeLists.txt")
    installer = read("tools/installer/openstream-obs-plugin.iss")

    assert '"2.0.0-beta"' in app_gradle
    assert "project(openstream_obs_plugin VERSION 2.0.0" in cmake
    assert '#define OpenStreamVersion "2.0.0-beta"' in installer
