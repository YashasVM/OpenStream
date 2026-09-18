from _helpers import ROOT, parse_obs_beacon, parse_pairing_url, read_text as read


def test_obs_slot_beacon_acceptance_contract() -> None:
    payload = (
        'OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,'
        '"name":"OpenStream","instanceId":"obs-main","sourceInstanceId":"source-a",'
        '"slotId":"slot-a","slotLabel":"CAM A","host":"","listenerPort":9000,'
        '"latencyMs":120,"bitrateMbps":50,"busy":false,'
        '"pairingUrl":"openstream://connect?host=192.168.1.10&port=9000"}'
    )

    device = parse_obs_beacon(payload, packet_host="192.168.1.10", now_ms=1234)

    assert device == {
        "name": "OpenStream",
        "host": "192.168.1.10",
        "port": 9000,
        "latencyMs": 120,
        "bitrateMbps": 50,
        "instanceId": "obs-main",
        "sourceInstanceId": "source-a",
        "slotId": "slot-a",
        "slotLabel": "CAM A",
        "pairingUrl": "openstream://connect?host=192.168.1.10&port=9000",
        "lastSeenMs": 1234,
        "busy": False,
    }


def test_obs_beacon_rejects_invalid_protocol_and_ports() -> None:
    assert parse_obs_beacon("OPENSTREAM_PHONE/1 {}", "192.168.1.10", 1) is None
    assert parse_obs_beacon("OPENSTREAM/1 not-json", "192.168.1.10", 1) is None
    assert (
        parse_obs_beacon(
            'OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"listenerPort":0}',
            "192.168.1.10",
            1,
        )
        is None
    )


def test_pairing_url_acceptance_contract_clamps_network_values() -> None:
    target = parse_pairing_url(
        "openstream://connect?host=192.168.1.10&port=70000&latency=20&name=CAM%20B"
    )

    assert target == {
        "name": "CAM B",
        "host": "192.168.1.10",
        "port": 65535,
        "latencyMs": 80,
    }
    assert parse_pairing_url("openstream://connect?port=9000") is None
    assert parse_pairing_url("https://example.test") is None


def test_android_and_obs_release_artifacts_stay_atomic() -> None:
    android_workflow = read(".github/workflows/android.yml")
    release_workflow = read(".github/workflows/release.yml")
    release_docs = read("docs/release.md")

    assert "gh release create" not in android_workflow
    assert "python -m pytest -q" in android_workflow
    assert ":app:lintDebug" in android_workflow
    assert "openstream-android.apk.sha256" in android_workflow
    assert "git log -1 --format=%ct" in android_workflow
    assert "git log -1 --format=%ct" in release_workflow
    assert "openstream-android-update.json" not in android_workflow
    assert "openstream-android-update.json" not in release_workflow
    assert not (ROOT / "android/app/src/main/java/dev/openstream/app/update/AppUpdater.kt").exists()
    assert "/releases/latest/download/" in read("README.md")
    assert "Android APK and OBS artifacts" in release_docs


def test_android_update_surface_is_removed() -> None:
    manifest = read("android/app/src/main/AndroidManifest.xml")
    main_activity = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    settings_activity = read("android/app/src/main/java/dev/openstream/app/SettingsActivity.kt")
    settings_layout = read("android/app/src/main/res/layout/activity_settings.xml")

    assert "REQUEST_INSTALL_PACKAGES" not in manifest
    assert "AppUpdater" not in main_activity
    assert "AppUpdater" not in settings_activity
    assert "btnCheckUpdates" not in settings_layout
    assert "dialog_custom_update" not in main_activity


def test_android_pr_builds_do_not_receive_signing_secrets_or_write_token() -> None:
    android_workflow = read(".github/workflows/android.yml")
    build_job_text = android_workflow.split("  build:", 1)[1]

    assert "permissions:\n  contents: read" in android_workflow
    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" not in build_job_text
    assert "OPENSTREAM_RELEASE_STORE_PASSWORD" not in build_job_text
    assert "contents: write" not in build_job_text
    assert "persist-credentials: false" in build_job_text
