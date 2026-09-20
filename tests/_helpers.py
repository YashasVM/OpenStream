"""Shared helpers for behavioural contract tests.

Canonical home for the brace scanner (previously copied into ~15 files),
the function extractor (previously `_function_body`/`_function`), the
ROOT-relative file reader (previously `def read`), and the Python mirrors
of the Kotlin discovery parsers (ObsDiscoveryClient beacon parsing and
ConnectionTarget pairing-URL parsing — behavioural contract coverage for
the acceptance tests lives in test_production_contracts.py).
"""

import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]


def read_text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : index]
    raise AssertionError(f"Unclosed block after {marker!r}")


def function_body(source: str, name: str, next_name: str | None = None) -> str:
    if next_name is not None:
        start = source.index(name)
        end = source.index(next_name, start)
        return source[start:end]
    start = source.index(f"private fun {name}(")
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start : index + 1]
    raise AssertionError(f"Unclosed function: {name}")


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
