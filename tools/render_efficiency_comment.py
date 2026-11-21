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


def format_value(value: int | float, unit: str) -> str:
    if unit == "bytes":
        if value >= 1024 * 1024:
            return f"{value / (1024 * 1024):.2f} MiB"
        if value >= 1024:
            return f"{value / 1024:.1f} KiB"
        return f"{int(value)} B"
    if unit == "mbps":
        return f"{int(value)} Mbps"
    if unit == "fps":
        return f"{int(value)} fps"
    if unit == "pixels":
        if value >= 1_000_000:
            return f"{value / 1_000_000:.2f} Mpx"
        if value >= 1_000:
            return f"{value / 1_000:.1f} Kpx"
        return f"{int(value)} px"
    if unit == "percent":
        return f"{value:.2f}%" if isinstance(value, float) and not value.is_integer() else f"{int(value)}%"
    if unit == "rate":
        return f"{value:.1f}/s"
    return str(int(value))


def format_delta(pr_val: int | float, base_val: int | float | None, unit: str) -> str:
    if base_val is None:
        return "—"
    diff = pr_val - base_val
    if abs(diff) < 1e-9:
        zero = "0 B" if unit == "bytes" else "0"
        return f"{zero} (0.0%)"
    sign = "+" if diff > 0 else "-"
    abs_formatted = format_value(abs(diff), unit)
    if base_val == 0:
        return f"{sign}{abs_formatted}"
    pct = (diff / base_val) * 100.0
    return f"{sign}{abs_formatted} ({pct:+.1f}%)"


def render_unavailable_comment(
    *,
    pr_sha: str = "",
    pr_run_url: str = "",
    source_conclusion: str = "failure",
    reason: str = "PR efficiency artifact was not uploaded for this commit.",
) -> str:
    safe_pr_sha = pr_sha if HEX_SHA_RE.fullmatch(pr_sha) else "unknown"
    safe_conclusion = source_conclusion if source_conclusion in SAFE_CONCLUSIONS else "unknown"
    short_pr = safe_pr_sha[:7] if safe_pr_sha != "unknown" else "unknown"
    run_ref = (
        f"[`{short_pr}`]({pr_run_url})"
        if pr_run_url.startswith("https://github.com/")
        else f"`{short_pr}`"
    )
    return "\n".join(
        [
            MARKER,
            f"{SHA_MARKER_PREFIX}{safe_pr_sha} -->",
            "## Efficiency & thermal impact",
            "",
            f"⚠️ Efficiency report unavailable for {run_ref} (Source CI: {safe_conclusion}).",
            "",
            reason,
            "",
            "_Updated in place by a trusted workflow. PR artifacts are strictly validated against default-branch ceilings and never executed._",
            "",
        ]
    )


def render_comment(
    pr_report_raw: Any,
    baseline_report_raw: Any | None = None,
    *,
    pr_sha: str = "",
    pr_run_url: str = "",
    baseline_sha: str = "",
    baseline_run_url: str = "",
    source_conclusion: str = "success",
    require_build_metrics: bool = True,
) -> str:
    pr = validate_report(pr_report_raw, require_build_metrics=require_build_metrics)
    baseline = None
    if baseline_report_raw is not None:
        try:
            baseline = validate_report(baseline_report_raw, require_build_metrics=False)
        except ValidationError:
            baseline = None

    safe_pr_sha = pr_sha if HEX_SHA_RE.fullmatch(pr_sha) else "unknown"
    safe_base_sha = baseline_sha if HEX_SHA_RE.fullmatch(baseline_sha) else ""
    safe_conclusion = source_conclusion if source_conclusion in SAFE_CONCLUSIONS else "unknown"

    base_metrics = {m["id"]: m for m in baseline["metrics"]} if baseline else {}
    base_scenarios = {s["id"]: s for s in baseline["scenarios"]} if baseline else {}

    lines = [
        MARKER,
        f"{SHA_MARKER_PREFIX}{safe_pr_sha} -->",
        "## Efficiency & thermal impact",
        "",
    ]

    if pr["passed"]:
        lines.append("✅ PR-reported measurements remain within every trusted efficiency and thermal ceiling.")
    else:
        failed_count = sum(1 for m in pr["metrics"] if not m["passed"]) + sum(
            1 for s in pr["scenarios"] if not s["passed"]
        )
        lines.append(f"❌ {failed_count} trusted efficiency ceiling(s) exceeded.")

    lines.extend(
        [
            "",
            "| Area | Metric | Main baseline | This PR | Impact | PR ceiling | |",
            "| --- | --- | ---: | ---: | ---: | ---: | --- |",
        ]
    )

    for m in pr["metrics"]:
        base_item = base_metrics.get(m["id"])
        base_str = format_value(base_item["value"], m["unit"]) if base_item else "—"
        pr_str = format_value(m["value"], m["unit"])
        delta_str = format_delta(m["value"], base_item["value"] if base_item else None, m["unit"])
        ceil_str = format_value(m["ceiling"], m["unit"])
        badge = "✅" if m["passed"] else "❌"
        lines.append(
            f"| {m['area']} | {m['label']} | {base_str} | {pr_str} | {delta_str} | {ceil_str} | {badge} |"
        )

    if pr["scenarios"]:
        changed = [
            s for s in pr["scenarios"] if (base_scenarios.get(s["id"]) or {}).get("value") != s["value"]
        ]
        lines.extend(["", "### Other scenarios", ""])
        if baseline and not changed:
            lines.append(f"All {len(pr['scenarios'])} scenario measurements match `main`.")
        elif not baseline:
            lines.append(
                f"Recorded {len(pr['scenarios'])} PR-produced scenario measurements (no `main` baseline artifact yet)."
            )
        else:
            lines.append(
                f"{len(changed)} of {len(pr['scenarios'])} scenario measurements changed relative to `main`."
            )

        lines.extend(
            [
                "",
                "<details>",
                f"<summary>All {len(pr['scenarios'])} scenario measurements</summary>",
                "",
                "| Area | Scenario | Metric | Main baseline | This PR | Impact | PR ceiling | |",
                "| --- | --- | --- | ---: | ---: | ---: | ---: | --- |",
            ]
        )
        for s in pr["scenarios"]:
            base_s = base_scenarios.get(s["id"])
            base_str = format_value(base_s["value"], s["unit"]) if base_s else "—"
            pr_str = format_value(s["value"], s["unit"])
            delta_str = format_delta(s["value"], base_s["value"] if base_s else None, s["unit"])
            ceil_str = format_value(s["ceiling"], s["unit"])
            badge = "✅" if s["passed"] else "❌"
            lines.append(
                f"| {s['area']} | `{s['scenario']}` | `{s['metric']}` | {base_str} | {pr_str} | {delta_str} | {ceil_str} | {badge} |"
            )
        lines.extend(["", "</details>"])

    footer_parts: list[str] = []
    if baseline is not None and safe_base_sha:
        short_base = safe_base_sha[:7]
        if baseline_run_url.startswith("https://github.com/"):
            footer_parts.append(f"Baseline: [`{short_base}`]({baseline_run_url})")
        else:
            footer_parts.append(f"Baseline: `{short_base}`")
    else:
        footer_parts.append("Baseline: none yet")

    short_pr = safe_pr_sha[:7] if safe_pr_sha != "unknown" else "unknown"
    if pr_run_url.startswith("https://github.com/"):
        footer_parts.append(f"PR result: [`{short_pr}`]({pr_run_url})")
    else:
        footer_parts.append(f"PR result: `{short_pr}`")
    footer_parts.append(f"Source CI: {safe_conclusion}")

    lines.extend(["", " · ".join(footer_parts)])

    if pr["notes"]:
        lines.extend(
            [
                "",
                "<details>",
                "<summary>Informational native muxer timings (non-gating)</summary>",
                "",
            ]
        )
        for k, v in pr["notes"].items():
            lines.append(f"- `{k}`: {v:.2f} µs/frame")
        lines.extend(["", "</details>"])

    lines.extend(
        [
            "",
            "_Updated in place by a trusted workflow. Measurements are PR-produced; schema and ceilings are enforced from the trusted default branch and PR artifacts are never executed._",
        ]
    )
    return "\n".join(lines) + "\n"


