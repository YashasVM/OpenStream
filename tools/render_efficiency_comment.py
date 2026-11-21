#!/usr/bin/env python3
"""Strictly validate efficiency JSON artifacts against the trusted default-branch
specification and render a sticky PR comment.

Designed to run in a trusted `workflow_run` job without checking out or
executing untrusted PR code. Metric IDs, labels, units, and ceilings are taken
solely from the trusted specification on the default branch — never from the
untrusted PR artifact.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from measure_efficiency import CEILINGS as TRUSTED_METRICS  # noqa: E402
from measure_efficiency import SCENARIO_SPECS as TRUSTED_SCENARIOS  # noqa: E402

MARKER = "<!-- openstream-efficiency-report -->"
SHA_MARKER_PREFIX = "<!-- openstream-efficiency-result-sha:"
SUPPORTED_SCHEMA_VERSION = 1
ALLOWED_NOTES = {
    "mux_1080p30_us_per_frame",
    "mux_1080p60_us_per_frame",
    "mux_audio_us_per_frame",
}
HEX_SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")
SAFE_CONCLUSIONS = {"success", "failure", "cancelled", "timed_out", "action_required"}


class ValidationError(ValueError):
    """Raised when an untrusted report artifact fails strict validation."""


def _validate_number(value: Any, field: str) -> int | float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"Invalid {field}: expected number")
    if isinstance(value, int):
        if value < 0 or value > 10**12:
            raise ValidationError(f"Out-of-range {field}")
        return value
    if not math.isfinite(value) or value < 0 or value > 10**12:
        raise ValidationError(f"Out-of-range {field}")
    return value


def validate_report(raw: Any, *, require_build_metrics: bool = True) -> dict[str, Any]:
    """Validate an untrusted report against the trusted default-branch spec.

    Only numeric values for known IDs are extracted from `raw`; all labels,
    areas, units, ceilings, and pass/fail verdicts come from the trusted spec.
    """
    if not isinstance(raw, dict):
        raise ValidationError("Report root must be a JSON object")
    if raw.get("schemaVersion") != SUPPORTED_SCHEMA_VERSION:
        raise ValidationError(f"Unsupported schemaVersion: {raw.get('schemaVersion')!r}")

    metrics_raw = raw.get("metrics")
    if not isinstance(metrics_raw, list) or not (1 <= len(metrics_raw) <= 64):
        raise ValidationError("metrics must be a non-empty list of at most 64 items")

    metric_values: dict[str, int | float] = {}
    for item in metrics_raw:
        if not isinstance(item, dict):
            raise ValidationError("Each metric must be an object")
        mid = item.get("id")
        if not isinstance(mid, str) or mid not in TRUSTED_METRICS:
            raise ValidationError(f"Unknown or invalid metric id: {mid!r}")
        if mid in metric_values:
            raise ValidationError(f"Duplicate metric id: {mid}")
        metric_values[mid] = _validate_number(item.get("value"), f"{mid}.value")

    required_metric_ids = [
        mid
        for mid, spec in TRUSTED_METRICS.items()
        if require_build_metrics or not spec.get("build_artifact")
    ]
    missing_metrics = [mid for mid in required_metric_ids if mid not in metric_values]
    if missing_metrics:
        raise ValidationError(f"Missing required metric(s): {', '.join(missing_metrics)}")

    metrics: list[dict[str, Any]] = []
    for mid, spec in TRUSTED_METRICS.items():
        if mid not in metric_values:
            continue
        value = metric_values[mid]
        ceiling = spec["ceiling"]
        metrics.append(
            {
                "id": mid,
                "area": spec["area"],
                "label": spec["label"],
                "value": value,
                "unit": spec["unit"],
                "ceiling": ceiling,
                "passed": value <= ceiling,
            }
        )

    scenarios_raw = raw.get("scenarios")
    if not isinstance(scenarios_raw, list) or len(scenarios_raw) > 128:
        raise ValidationError("scenarios must be a list of at most 128 items")

    scenario_values: dict[str, int | float] = {}
    for idx, item in enumerate(scenarios_raw):
        if not isinstance(item, dict):
            raise ValidationError(f"Scenario {idx} must be an object")
        sid = item.get("id")
        if not isinstance(sid, str) or sid not in TRUSTED_SCENARIOS:
            raise ValidationError(f"Unknown or invalid scenario id: {sid!r}")
        if sid in scenario_values:
            raise ValidationError(f"Duplicate scenario id: {sid}")
        scenario_values[sid] = _validate_number(item.get("value"), f"{sid}.value")

    required_scenario_ids = [
        sid
        for sid, spec in TRUSTED_SCENARIOS.items()
        if require_build_metrics or not spec.get("build_artifact")
    ]
    missing_scenarios = [sid for sid in required_scenario_ids if sid not in scenario_values]
    if missing_scenarios:
        raise ValidationError(f"Missing required scenario(s): {', '.join(missing_scenarios)}")

    scenarios: list[dict[str, Any]] = []
    for sid, spec in TRUSTED_SCENARIOS.items():
        if sid not in scenario_values:
            continue
        value = scenario_values[sid]
        ceiling = spec["ceiling"]
        scenarios.append(
            {
                "id": sid,
                "area": spec["area"],
                "scenario": spec["scenario"],
                "metric": spec["metric"],
                "value": value,
                "unit": spec["unit"],
                "ceiling": ceiling,
                "passed": value <= ceiling,
            }
        )

    notes_raw = raw.get("notes", {})
    notes: dict[str, float] = {}
    if isinstance(notes_raw, dict):
        for k, v in notes_raw.items():
            if k in ALLOWED_NOTES:
                notes[k] = float(_validate_number(v, f"notes.{k}"))

    all_passed = all(m["passed"] for m in metrics) and all(s["passed"] for s in scenarios)
    return {
        "schemaVersion": SUPPORTED_SCHEMA_VERSION,
        "passed": all_passed,
        "metrics": metrics,
        "scenarios": scenarios,
        "notes": notes,
    }


