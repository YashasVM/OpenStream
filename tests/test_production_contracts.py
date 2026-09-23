import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def parse_obs_beacon(payload: str, packet_host: str, now_ms: int) -> dict | None:
    prefix = "SHIN/1 "
    if not payload.startswith(prefix):
        return None

    try:
        beacon = json.loads(payload.removeprefix(prefix))
    except json.JSONDecodeError:
        return None

    if beacon.get("type") != "dev.shin.listener":
        return None
    if beacon.get("version") != 1:
        return None

    port = int(beacon.get("listenerPort", -1))
    if not 1 <= port <= 65535:
        return None

    instance_id = beacon.get("instanceId") or f"{packet_host}:{port}"
    name = beacon.get("name") or "shin Phone Link"
    return {
        "name": name,
        "host": (beacon.get("host") or "").strip() or packet_host,
        "port": port,
        "latencyMs": clamp(int(beacon.get("latencyMs", 120)), 80, 200),
        "bitrateMbps": clamp(int(beacon.get("bitrateMbps", 12)), 1, 200),
        "instanceId": instance_id,
        "sourceInstanceId": beacon.get("sourceInstanceId") or instance_id,
        "slotId": beacon.get("slotId") or instance_id,
        "slotLabel": beacon.get("slotLabel") or name or "CAM A",
        "pairingUrl": beacon.get("pairingUrl") or "",
        "lastSeenMs": now_ms,
        "busy": bool(beacon.get("busy", False)),
    }


def parse_pairing_url(url: str) -> dict | None:
    parsed = urlparse(url)
    if parsed.scheme != "shin" or parsed.netloc != "connect":
        return None

    params = parse_qs(parsed.query)
    host = (params.get("host", [""])[0]).strip()
    if not host:
        return None

    def int_param(name: str, default: int, low: int, high: int) -> int:
        try:
            value = int(params.get(name, [default])[0])
        except (TypeError, ValueError):
            value = default
        return clamp(value, low, high)

    return {
        "name": params.get("name", ["shin Phone Link"])[0] or "shin Phone Link",
        "host": host,
        "port": int_param("port", 9100, 1, 65535),
        "latencyMs": int_param("latency", 120, 80, 200),
    }


def test_obs_slot_beacon_acceptance_contract() -> None:
    payload = (
        'SHIN/1 {"type":"dev.shin.listener","version":1,'
        '"name":"shin","instanceId":"obs-main","sourceInstanceId":"source-a",'
        '"slotId":"slot-a","slotLabel":"CAM A","host":"","listenerPort":9100,'
        '"latencyMs":120,"bitrateMbps":50,"busy":false,'
        '"pairingUrl":"shin://connect?host=192.168.1.10&port=9100"}'
    )

    device = parse_obs_beacon(payload, packet_host="192.168.1.10", now_ms=1234)

    assert device == {
        "name": "shin",
        "host": "192.168.1.10",
        "port": 9100,
        "latencyMs": 120,
        "bitrateMbps": 50,
        "instanceId": "obs-main",
        "sourceInstanceId": "source-a",
        "slotId": "slot-a",
        "slotLabel": "CAM A",
        "pairingUrl": "shin://connect?host=192.168.1.10&port=9100",
        "lastSeenMs": 1234,
        "busy": False,
    }


def test_obs_beacon_rejects_invalid_protocol_and_ports() -> None:
    assert parse_obs_beacon("SHIN_PHONE/1 {}", "192.168.1.10", 1) is None
    assert parse_obs_beacon("SHIN/1 not-json", "192.168.1.10", 1) is None
    assert (
        parse_obs_beacon(
            'SHIN/1 {"type":"dev.shin.listener","version":1,"listenerPort":0}',
            "192.168.1.10",
            1,
        )
        is None
    )


def test_pairing_url_acceptance_contract_clamps_network_values() -> None:
    target = parse_pairing_url(
        "shin://connect?host=192.168.1.10&port=70000&latency=20&name=CAM%20B"
    )

    assert target == {
        "name": "CAM B",
        "host": "192.168.1.10",
        "port": 65535,
        "latencyMs": 80,
    }
    assert parse_pairing_url("shin://connect?port=9100") is None
    assert parse_pairing_url("https://example.test") is None


def test_android_and_obs_release_artifacts_stay_atomic() -> None:
    android_workflow = read(".github/workflows/android.yml")
    release_workflow = read(".github/workflows/release.yml")
    release_docs = read("docs/release.md")

    assert "gh release create" not in android_workflow
    assert "python -m pytest -q" in android_workflow
    assert ":app:lintDebug" in android_workflow
    assert "openstream-android.apk.sha256" in android_workflow
    assert "android/gradle.properties" in release_docs
    assert "release/version.properties" in release_workflow
    assert "productVersion=" in release_workflow
    assert "androidVersionCode=" in release_workflow
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
