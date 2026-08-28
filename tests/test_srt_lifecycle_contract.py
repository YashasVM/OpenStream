from pathlib import Path


SOURCE = Path("android/app/src/main/cpp/openstream_srt.cpp")


def test_srt_runtime_lifetime_is_owned_and_balanced():
    source = SOURCE.read_text(encoding="utf-8")

    # connect() and listen() each take one libsrt runtime reference only after
    # startup succeeds; disconnect() owns the matching cleanup exactly once.
    assert source.count("srtStarted_ = true;") == 2
    assert "bool srtStarted_ = false;" in source
    assert "if (srtStarted_) {\n      srt_cleanup();\n      srtStarted_ = false;\n    }" in source

    # Failure paths after a successful startup must funnel through disconnect(),
    # rather than only closing the socket and leaking the runtime reference.
    assert "Could not create SRT socket\");\n      disconnect();\n      return false;" in source
    assert "Could not resolve SRT host\");\n      disconnect();\n      return false;" in source
    assert "SRT connect failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in source
    assert "Could not create SRT listener socket\");\n      disconnect();\n      return false;" in source
