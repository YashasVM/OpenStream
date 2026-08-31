from pathlib import Path


SOURCE = Path("obs-plugin/src/openstream-source.cpp")


def test_obs_srt_input_has_bounded_io_and_connect_timeouts():
    source = SOURCE.read_text(encoding="utf-8")

    assert "constexpr int64_t kSrtIoTimeoutUs = 4'500'000;" in source
    assert "constexpr int64_t kSrtConnectTimeoutMs = 2'000;" in source

    options_start = source.index("AVDictionary *options = nullptr;")
    open_start = source.index("avformat_open_input(", options_start)
    options = source[options_start:open_start]

    assert 'av_dict_set_int(&options, "timeout", kSrtIoTimeoutUs, 0);' in options
    assert 'av_dict_set_int(&options, "connect_timeout", kSrtConnectTimeoutMs, 0);' in options
