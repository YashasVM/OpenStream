import importlib.util
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run_script(relative: str, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([sys.executable, str(ROOT / relative), *arguments], capture_output=True, text=True, check=False)


def test_four_stream_workload_and_bounded_impairment(tmp_path: Path) -> None:
    source = tmp_path / "source.jsonl"
    impaired = tmp_path / "impaired.jsonl"
    generated = run_script("tools/stream-simulator/generate.py", "--streams", "4", "--fps", "30", "--seconds", "1", "--output", str(source))
    assert generated.returncode == 0, generated.stderr
    units = [json.loads(line) for line in source.read_text().splitlines()]
    assert len(units) == 120
    assert {unit["session"] for unit in units} == {1, 2, 3, 4}
    assert all("source_time_ns" in unit and "arrival_time_ns" not in unit for unit in units)
    assert source.with_suffix(".jsonl.manifest.json").exists()

    result = run_script("tools/network-impairment/impair.py", str(source), str(impaired), "--loss-percent", "3", "--burst-length", "3", "--jitter-ms", "50", "--reorder-percent", "5", "--seed", "7")
    assert result.returncode == 0, result.stderr
    delivered = [json.loads(line) for line in impaired.read_text().splitlines()]
    assert len(delivered) <= len(units)
    assert all(unit["arrival_time_ns"] >= unit["source_time_ns"] for unit in delivered)
    assert impaired.with_suffix(".jsonl.gaps.json").exists()
    assert impaired.with_suffix(".jsonl.manifest.json").exists()
    validation = run_script("tools/recording-validator/validate.py", str(impaired))
    assert validation.returncode == 0, validation.stdout


def test_two_stream_60fps_profile(tmp_path: Path) -> None:
    output = tmp_path / "source.jsonl"
    result = run_script("tools/stream-simulator/generate.py", "--streams", "2", "--fps", "60", "--seconds", "1", "--output", str(output))
    assert result.returncode == 0, result.stderr
    assert len(output.read_text().splitlines()) == 120


def test_invalid_capacity_profile_is_rejected(tmp_path: Path) -> None:
    result = run_script("tools/stream-simulator/generate.py", "--streams", "4", "--fps", "60", "--output", str(tmp_path / "bad.jsonl"))
    assert result.returncode != 0


def test_recording_validator_detects_leading_trailing_and_missing_session_gaps(tmp_path: Path) -> None:
    recording = tmp_path / "recording.jsonl"
    recording.write_text(
        "\n".join(
            [
                json.dumps({"session": 1, "sequence": 1}),
                json.dumps({"session": 1, "sequence": 2}),
                json.dumps({"session": 1, "sequence": 3}),
            ]
        )
        + "\n"
    )
    recording.with_suffix(".jsonl.manifest.json").write_text(
        json.dumps(
            {
                "version": 4,
                "sessions": [
                    {"session": 1, "first_sequence": 0, "last_sequence": 4},
                    {"session": 2, "first_sequence": 0, "last_sequence": 4},
                ],
            }
        )
    )
    recording.with_suffix(".jsonl.gaps.json").write_text(json.dumps({"version": 4, "missing": {"1": [0]}}))

    result = run_script("tools/recording-validator/validate.py", str(recording))
    assert result.returncode != 0
    report = json.loads(result.stdout)["results"][0]
    assert report["actual_missing"] == {"1": [0, 4], "2": [0, 1, 2, 3, 4]}


def test_recording_validator_rejects_malformed_and_duplicate_sequences(tmp_path: Path) -> None:
    recording = tmp_path / "recording.jsonl"
    manifest_path = recording.with_suffix(".jsonl.manifest.json")
    gaps_path = recording.with_suffix(".jsonl.gaps.json")
    valid_manifest = {"version": 4, "sessions": [{"session": 1, "first_sequence": 0, "last_sequence": 1}]}
    cases = [
        ([{"session": 1, "sequence": 0}, {"session": 1, "sequence": 1}], valid_manifest, {"version": 4, "missing": []}),
        ([{"session": 1, "sequence": 0}, {"session": 1, "sequence": 0}, {"session": 1, "sequence": 1}], valid_manifest, {"version": 4, "missing": {}}),
        ([{"session": 1, "sequence": 0}], {"version": 4, "sessions": [{"session": 1.9, "first_sequence": 0, "last_sequence": 1}]}, {"version": 4, "missing": {"1": [1]}}),
        ([{"session": 1, "sequence": True}], valid_manifest, {"version": 4, "missing": {"1": [1]}}),
    ]
    for records, manifest, gaps in cases:
        recording.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        gaps_path.write_text(json.dumps(gaps), encoding="utf-8")
        result = run_script("tools/recording-validator/validate.py", str(recording))
        assert result.returncode != 0
        assert json.loads(result.stdout)["ok"] is False
        assert "Traceback" not in result.stderr


def test_network_impairment_rejects_invalid_timing_and_burst_inputs(tmp_path: Path) -> None:
    source = tmp_path / "source.jsonl"
    generated = run_script("tools/stream-simulator/generate.py", "--seconds", "1", "--output", str(source))
    assert generated.returncode == 0, generated.stderr
    invalid_arguments = [
        ("--burst-length", "0"),
        ("--jitter-ms", "-1"),
        ("--outage-start-ms", "-1"),
        ("--outage-ms", "-1"),
        ("--outage-ms", "1"),
        ("--disconnect-start-ms", "-1"),
        ("--disconnect-ms", "-1"),
        ("--disconnect-ms", "1"),
        ("--disconnect-session", "0"),
        ("--jitter-ms", "nan"),
    ]
    for option, value in invalid_arguments:
        result = run_script("tools/network-impairment/impair.py", str(source), str(tmp_path / "impaired.jsonl"), option, value)
        assert result.returncode != 0, f"{option} {value} was accepted"
