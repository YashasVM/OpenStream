from pathlib import Path


SOURCE = Path("android/app/src/main/cpp/openstream_srt.cpp")


def test_srt_runtime_lifetime_is_process_scoped_and_disconnect_safe():
    source = SOURCE.read_text(encoding="utf-8")

    # NativeSender owns one libsrt runtime reference for its full lifetime.
    # connect()/listen() must never acquire or release that global reference,
    # so disconnect cannot tear the runtime down while setup is in progress.
    constructor_start = source.index("NativeSender()")
    destructor_start = source.index("~NativeSender()", constructor_start)
    connect_start = source.index("bool connect(")
    constructor = source[constructor_start:destructor_start]
    destructor = source[destructor_start:connect_start]
    connect = source[connect_start : source.index("bool listen(")]
    listen = source[source.index("bool listen(") : source.index("bool sendNow(")]
    disconnect = source[source.index("void disconnect()") : source.index("private:", source.index("void disconnect()"))]

    assert "srt_startup()" in constructor
    assert "srtStarted_ = srt_startup() == 0;" in constructor
    assert "srt_cleanup()" not in constructor
    assert "srt_cleanup();" in destructor
    assert "srt_startup()" not in connect
    assert "srt_startup()" not in listen
    assert "srt_cleanup()" not in connect
    assert "srt_cleanup()" not in listen
    assert "srt_cleanup()" not in disconnect

    # Connection attempts still fail cleanly if process-level startup failed.
    assert connect.count("if (!srtStarted_)") == 1
    assert listen.count("if (!srtStarted_)") == 1

    # Post-startup failures close their sockets/session state via disconnect,
    # without changing the process-scoped libsrt runtime ownership.
    assert "Could not create SRT socket\");\n      disconnect();\n      return false;" in connect
    assert "Could not resolve SRT host\");\n      disconnect();\n      return false;" in connect
    assert "SRT connect failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in connect
    assert "Could not create SRT listener socket\");\n      disconnect();\n      return false;" in listen
    assert "SRT bind failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen
    assert "SRT listen failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen
    assert "SRT accept failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen
