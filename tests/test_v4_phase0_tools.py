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

    result = run_script("tools/network-impairment/impair.py", str(source), str(impaired), "--loss-percent", "3", "--burst-length", "3", "--jitter-ms", "50", "--reorder-percent", "5", "--seed", "7")
    assert result.returncode == 0, result.stderr
    delivered = [json.loads(line) for line in impaired.read_text().splitlines()]
    assert len(delivered) <= len(units)
    assert all(unit["arrival_time_ns"] >= unit["source_time_ns"] for unit in delivered)
    assert impaired.with_suffix(".jsonl.gaps.json").exists()


def test_two_stream_60fps_profile(tmp_path: Path) -> None:
    output = tmp_path / "source.jsonl"
    result = run_script("tools/stream-simulator/generate.py", "--streams", "2", "--fps", "60", "--seconds", "1", "--output", str(output))
    assert result.returncode == 0, result.stderr
    assert len(output.read_text().splitlines()) == 120


def test_invalid_capacity_profile_is_rejected(tmp_path: Path) -> None:
    result = run_script("tools/stream-simulator/generate.py", "--streams", "4", "--fps", "60", "--output", str(tmp_path / "bad.jsonl"))
    assert result.returncode != 0
