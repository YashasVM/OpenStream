import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, value))


def parse_obs_beacon(payload: str, packet_host: str, now_ms: int) -> dict | None:
    prefix = "OPENSTREAM/1 "
    if not payload.startswith(prefix):
        return None

    try:
        beacon = json.loads(payload.removeprefix(prefix))
    except json.JSONDecodeError:
        return None

    if beacon.get("type") != "dev.openstream.listener":
        return None
    if beacon.get("version") != 1:
        return None

    port = int(beacon.get("listenerPort", -1))
    if not 1 <= port <= 65535:
        return None

    instance_id = beacon.get("instanceId") or f"{packet_host}:{port}"
    name = beacon.get("name") or "OpenStream Phone Link"
    return {
        "name": name,
        "host": (beacon.get("host") or "").strip() or packet_host,
        "port": port,
        "latencyMs": clamp(int(beacon.get("latencyMs", 120)), 20, 2000),
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
    if parsed.scheme != "openstream" or parsed.netloc != "connect":
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
        "name": params.get("name", ["OpenStream Phone Link"])[0] or "OpenStream Phone Link",
        "host": host,
        "port": int_param("port", 9000, 1, 65535),
        "latencyMs": int_param("latency", 120, 80, 200),
    }


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


def test_release_workflow_requires_signed_android_apk_without_debug_fallback() -> None:
    release_workflow = read(".github/workflows/release.yml")

    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" in release_workflow
    assert "OPENSTREAM_RELEASE_STORE_PASSWORD" in release_workflow
    assert "OPENSTREAM_RELEASE_KEY_ALIAS" in release_workflow
    assert "OPENSTREAM_RELEASE_KEY_PASSWORD" in release_workflow
    assert ":app:assembleRelease" in release_workflow
    assert ":app:assembleDebug" not in release_workflow
    assert "debug-signed-beta" not in release_workflow
    assert "Public releases require all Android signing secrets" in release_workflow
    assert "openstream-android.apk.sha256" in release_workflow
    assert '"apkSha256"' in release_workflow
    assert "apkSha256" in release_workflow
    assert "dist/openstream-android.apk" in release_workflow


def test_android_auto_update_release_does_not_replace_public_latest_release() -> None:
    android_workflow = read(".github/workflows/android.yml")
    release_workflow = read(".github/workflows/release.yml")
    updater = read("android/app/src/main/java/dev/openstream/app/update/AppUpdater.kt")
    release_docs = read("docs/release.md")

    assert "RELEASE_TAG: android-latest" in android_workflow
    assert "--latest=false" in android_workflow
    assert "concurrency:" in android_workflow
    assert "android-latest-publish" in android_workflow
    assert "gh release upload \"$LATEST_TAG\"" not in android_workflow
    assert "gh release delete \"$RELEASE_TAG\"" not in android_workflow
    assert "git log -1 --format=%ct" in android_workflow
    assert "git log -1 --format=%ct" in release_workflow
    assert "releases/tags/android-latest" in updater
    assert "/releases/latest/download/" in read("README.md")
    assert "Windows OBS installer and zip" in release_docs


def test_android_updater_requires_a_release_digest_before_installing() -> None:
    updater = read("android/app/src/main/java/dev/openstream/app/update/AppUpdater.kt")
    release_docs = read("docs/release.md")

    assert 'optString("apkSha256")' in updater
    assert "hasExpectedApkDigest()" in updater
    assert "MessageDigest.getInstance(\"SHA-256\")" in updater
    assert "Update verification failed" in updater
    assert "verify the published SHA-256 digest" in release_docs


def test_ci_executes_pytest_for_pushes_and_pull_requests() -> None:
    android_workflow = read(".github/workflows/android.yml")

    assert "name: Run repository tests" in android_workflow
    assert "actions/setup-python@v5" in android_workflow
    assert "python -m pytest -q" in android_workflow


def test_android_pr_builds_do_not_receive_signing_secrets_or_write_token() -> None:
    android_workflow = read(".github/workflows/android.yml")
    build_job_text = android_workflow.split("  build:", 1)[1].split("  publish-android-update:", 1)[0]
    publish_job_text = android_workflow.split("  publish-android-update:", 1)[1]

    assert "permissions:\n  contents: read" in android_workflow
    assert "if: github.event_name == 'push' && github.ref == 'refs/heads/main'" in publish_job_text
    assert "permissions:\n      contents: write" in publish_job_text
    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" not in build_job_text
    assert "OPENSTREAM_RELEASE_STORE_PASSWORD" not in build_job_text
    assert "contents: write" not in build_job_text
