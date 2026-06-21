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
        '"latencyMs":120,"bitrateMbps":20,"busy":false,'
        '"pairingUrl":"openstream://connect?host=192.168.1.10&port=9000"}'
    )

    device = parse_obs_beacon(payload, packet_host="192.168.1.10", now_ms=1234)

    assert device == {
        "name": "OpenStream",
        "host": "192.168.1.10",
        "port": 9000,
        "latencyMs": 120,
        "bitrateMbps": 20,
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


def test_release_workflow_requires_signed_android_release_apk() -> None:
    release_workflow = read(".github/workflows/release.yml")

    assert "OPENSTREAM_RELEASE_KEYSTORE_BASE64" in release_workflow
    assert "OPENSTREAM_RELEASE_STORE_PASSWORD" in release_workflow
    assert "OPENSTREAM_RELEASE_KEY_ALIAS" in release_workflow
    assert "OPENSTREAM_RELEASE_KEY_PASSWORD" in release_workflow
    assert ":app:assembleRelease" in release_workflow
    assert "app/build/outputs/apk/release/app-release.apk" in release_workflow
    assert "dist/openstream-android.apk" in release_workflow
    assert "openstream-android-debug.apk" not in release_workflow
    assert ":app:assembleDebug" not in release_workflow
