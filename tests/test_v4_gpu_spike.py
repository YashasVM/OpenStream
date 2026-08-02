import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GPU = ROOT / "spikes" / "gpu"


def test_gpu_workloads_cover_locked_profiles_and_codecs() -> None:
    document = json.loads((GPU / "workloads" / "v4-gpu-workloads.json").read_text(encoding="utf-8"))
    cells = {
        (item["codec"], item["width"], item["height"], item["fps"], item["streams"])
        for item in document["workloads"]
    }
    assert cells == {
        ("h264", 1920, 1080, 30, 4),
        ("hevc", 1920, 1080, 30, 4),
        ("h264", 1920, 1080, 60, 2),
        ("hevc", 1920, 1080, 60, 2),
    }
    for name in ("decoded_preview_queue", "programme_queue", "ingress_queue_per_stream", "iso_queue_per_stream"):
        queue = document[name]
        assert queue["capacity"] > 0
        assert queue["overflow_policy"]


def test_result_validator_rejects_missing_backend_and_cpu_copies(tmp_path: Path) -> None:
    metrics = tmp_path / "metrics.jsonl"
    workload = {"id": "four-1080p30-h264", "width": 1920, "height": 1080, "fps": 30, "streams": 4}
    records = [
        {"schema_version": 1, "type": "environment"},
        {"schema_version": 1, "type": "workload", "workload": workload, "queues": [{"capacity": 4, "overflow_policy": "replace_oldest"}]},
        {"schema_version": 1, "type": "sample", "workload": workload["id"], "backend": "media-foundation", "cpu_percent": 1, "private_mib": 10, "gpu_process_percent": 1},
        {"schema_version": 1, "type": "summary", "workload": workload["id"], "backend": "media-foundation", "probe": {"result": "PASS", "texture_type": "IMFDXGIBuffer", "ordinary_cpu_frame_copies": 1, "cpu_frame_uploads": 0, "cpu_frame_readbacks": 0}},
    ]
    metrics.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
    result = subprocess.run([sys.executable, str(GPU / "validate-results.py"), str(metrics)], capture_output=True, text=True)
    assert result.returncode != 0
    assert "INVALID" in result.stderr


def test_native_probes_fail_closed_on_software_paths() -> None:
    mf = (GPU / "src" / "mf_gpu_probe.cpp").read_text(encoding="utf-8")
    ffmpeg = (GPU / "src" / "ffmpeg_d3d11_probe.cpp").read_text(encoding="utf-8")
    assert "IMFDXGIBuffer" in mf
    assert "software buffer; hardware gate failed" in mf
    assert "GetDeviceRemovedReason" in mf
    assert "AV_PIX_FMT_D3D11" in ffmpeg
    assert "av_hwframe_transfer_data(" not in ffmpeg
    assert "ordinary_cpu_frame_copies" in mf
    assert "ordinary_cpu_frame_copies" in ffmpeg
