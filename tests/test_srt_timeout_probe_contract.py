from pathlib import Path


SOURCE = Path("tools/srt_timeout_probe.py")


def test_probe_cannot_block_on_unread_ffmpeg_stderr():
    source = SOURCE.read_text(encoding="utf-8")

    assert "stderr=subprocess.PIPE" not in source
    assert source.count("stderr=subprocess.DEVNULL") >= 2

    receiver_start = source.index("receiver = subprocess.Popen(")
    receiver_end = source.index("progress_thread =", receiver_start)
    receiver = source[receiver_start:receiver_end]

    assert "stdout=subprocess.PIPE" in receiver
    assert "-progress" in receiver
    assert "pipe:1" in receiver
