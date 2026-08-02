import importlib.util
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("transport_audit", ROOT / "spikes/transport/audit.py")
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def test_all_planned_queues_declare_capacity_deadline_and_policy() -> None:
    assert AUDIT.QUEUES
    assert len({queue["name"] for queue in AUDIT.QUEUES}) == len(AUDIT.QUEUES)
    for queue in AUDIT.QUEUES:
        assert isinstance(queue["capacity"], int) and queue["capacity"] > 0
        assert isinstance(queue["deadline_ms"], int) and queue["deadline_ms"] > 0
        assert queue["overflow_policy"]


def test_missing_prerequisites_emit_exhaustive_not_run_matrix(tmp_path: Path) -> None:
    output = tmp_path / "audit"
    result = subprocess.run(
        [sys.executable, str(ROOT / "spikes/transport/audit.py"), "--output", str(output)],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    assert result.returncode == 2
    environment = json.loads((output / "environment.json").read_text(encoding="utf-8"))
    assert environment["inputs_declared"] is False
    rows = [json.loads(line) for line in (output / "matrix.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(rows) == 2 * 2 * 2 * 8 * 3
    assert {row["result"] for row in rows} == {"NOT_RUN"}
    assert {row["candidate"] for row in rows} == {"srt-au", "rtp-srtp"}
    assert {row["codec"] for row in rows} == {"h264", "hevc"}
    assert {row["case"] for row in rows} == set(AUDIT.CASES)
    assert {row["seed"] for row in rows} == set(AUDIT.SEEDS)
    assert {(row["profile"]["streams"], row["profile"]["fps"]) for row in rows} == set(AUDIT.PROFILES)
    tuples = {
        (row["candidate"], row["codec"], row["profile"]["streams"],
         row["profile"]["fps"], row["case"], row["seed"])
        for row in rows
    }
    assert tuples == {
        (candidate, codec, streams, fps, case, seed)
        for candidate in AUDIT.CANDIDATES
        for codec in AUDIT.CODECS
        for streams, fps in AUDIT.PROFILES
        for case in AUDIT.CASES
        for seed in AUDIT.SEEDS
    }
    assert all(row["blockers"] for row in rows)


def test_corpus_requires_preservation_fields_and_real_payload_attestation(tmp_path: Path) -> None:
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "manifest.json").write_text(json.dumps({
        "real_encoded_payloads": True,
        "access_unit_fields": [
            "codec", "sequence", "source_time_ns", "decode_time_ns",
            "codec_config_generation", "orientation", "payload_bytes", "payload_crc32",
        ],
    }), encoding="utf-8")
    assert AUDIT.find_corpus(corpus) == (True, "real AU manifest present")
