from pathlib import Path


def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected one match, found {n}")
    return text.replace(old, new, 1)


kp = Path("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")
k = kp.read_text()

k = replace_once(
    k,
    '''            establishSession("listener") { _ ->
                SrtNativeBridge.listen(url, codecMime, width, height, fps)
''',
    '''            establishSession("listener") { generation ->
                SrtNativeBridge.listen(url, codecMime, width, height, fps, generation)
''',
    "listener generation",
)

k = replace_once(
    k,
    '''        synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
            connected = false
            // listen() blocks in native accept before connected becomes true. This
            // must not take operationLock so lifecycle stop can cancel that accept.
            SrtNativeBridge.disconnect()
        }
''',
    '''        synchronized(stateLock) {
            val generation = sessionGeneration.incrementAndGet()
            connected = false
            // Native generation invalidation and socket teardown are ordered before
            // any stale connect/listen can publish a replacement socket.
            SrtNativeBridge.disconnect(generation)
        }
''',
    "disconnect generation",
)

k = replace_once(
    k,
    '''            if (SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps)) {
''',
    '''            if (SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)) {
''',
    "connect generation",
)

k = replace_once(
    k,
    '''        val generation = synchronized(stateLock) {
            connected = false
            sessionGeneration.incrementAndGet()
        }
''',
    '''        val generation = synchronized(stateLock) {
            connected = false
            sessionGeneration.incrementAndGet().also { generation ->
                SrtNativeBridge.beginSession(generation)
            }
        }
''',
    "begin native generation",
)

k = replace_once(
    k,
    '''            if (didConnect) SrtNativeBridge.disconnect()
''',
    '''            if (didConnect) SrtNativeBridge.disconnect(sessionGeneration.get())
''',
    "cancel cleanup",
)

k = replace_once(
    k,
    '''    external fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int): Boolean
    external fun listen(url: String, codecMime: String, width: Int, height: Int, fps: Int): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun sendAudio(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect()
''',
    '''    external fun beginSession(sessionGeneration: Long)
    external fun connect(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        sessionGeneration: Long,
    ): Boolean
    external fun listen(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        sessionGeneration: Long,
    ): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun sendAudio(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect(sessionGeneration: Long)
''',
    "bridge signatures",
)
kp.write_text(k)

np = Path("android/app/src/main/cpp/openstream_srt.cpp")
n = np.read_text()

n = replace_once(
    n,
    '''  bool connect(const std::string &url) {
''',
    '''  void advanceLifecycleGeneration(uint64_t generation) {
#if OPENSTREAM_HAVE_LIBSRT
    std::lock_guard<std::mutex> lock(socketMutex_);
    lifecycleGeneration_ = generation;
#else
    (void)generation;
#endif
  }

  bool connect(const std::string &url, uint64_t expectedLifecycleGeneration) {
''',
    "native connect signature",
)

n = replace_once(
    n,
    '''    setSocket(socket);
''',
    '''    if (!setSocketForLifecycle(socket, expectedLifecycleGeneration)) {
      srt_close(socket);
      return false;
    }
''',
    "caller socket guard",
)

n = replace_once(
    n,
    '''  bool listen(const std::string &url) {
''',
    '''  bool listen(const std::string &url, uint64_t expectedLifecycleGeneration) {
''',
    "native listen signature",
)

n = replace_once(
    n,
    '''    setListenerSocket(listenerSocket);
''',
    '''    if (!setListenerSocketForLifecycle(listenerSocket, expectedLifecycleGeneration)) {
      srt_close(listenerSocket);
      return false;
    }
''',
    "listener socket guard",
)

n = replace_once(
    n,
    '''    setSocket(acceptedSocket);
    logInfo("OBS connected to Android SRT listener");
''',
    '''    if (!setSocketForLifecycle(acceptedSocket, expectedLifecycleGeneration)) {
      srt_close(acceptedSocket);
      return false;
    }
    logInfo("OBS connected to Android SRT listener");
''',
    "accepted socket guard",
)

n = replace_once(
    n,
    '''  void setSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    socket_ = socket;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    healthy_.store(true, std::memory_order_release);
  }
''',
    '''  bool setSocketForLifecycle(SRTSOCKET socket, uint64_t expectedLifecycleGeneration) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (lifecycleGeneration_ != expectedLifecycleGeneration) {
      return false;
    }
    socket_ = socket;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    healthy_.store(true, std::memory_order_release);
    return true;
  }
''',
    "socket helper",
)

n = replace_once(
    n,
    '''  void setListenerSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    listener_socket_ = socket;
  }
''',
    '''  bool setListenerSocketForLifecycle(SRTSOCKET socket, uint64_t expectedLifecycleGeneration) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (lifecycleGeneration_ != expectedLifecycleGeneration) {
      return false;
    }
    listener_socket_ = socket;
    return true;
  }
''',
    "listener helper",
)

n = replace_once(
    n,
    '''  SRTSOCKET listener_socket_ = SRT_INVALID_SOCK;
  bool srtStarted_ = false;
''',
    '''  SRTSOCKET listener_socket_ = SRT_INVALID_SOCK;
  uint64_t lifecycleGeneration_ = 0;
  bool srtStarted_ = false;
''',
    "generation field",
)

n = replace_once(
    n,
    '''}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_connect(
''',
    '''}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_beginSession(
    JNIEnv *,
    jobject,
    jlong session_generation) {
  g_state.sender.advanceLifecycleGeneration(static_cast<uint64_t>(session_generation));
  g_state.sender.disconnect();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_connect(
''',
    "beginSession JNI",
)

n = replace_once(
    n,
    '''Java_dev_openstream_app_stream_SrtNativeBridge_connect(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint) {
''',
    '''Java_dev_openstream_app_stream_SrtNativeBridge_connect(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint,
    jlong session_generation) {
''',
    "connect JNI signature",
)

n = replace_once(
    n,
    '''  const bool connected = g_state.sender.connect(urlString);
''',
    '''  const bool connected =
      g_state.sender.connect(urlString, static_cast<uint64_t>(session_generation));
''',
    "connect JNI forwarding",
)

n = replace_once(
    n,
    '''Java_dev_openstream_app_stream_SrtNativeBridge_listen(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint) {
''',
    '''Java_dev_openstream_app_stream_SrtNativeBridge_listen(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint,
    jlong session_generation) {
''',
    "listen JNI signature",
)

n = replace_once(
    n,
    '''  const bool connected = g_state.sender.listen(urlString);
''',
    '''  const bool connected =
      g_state.sender.listen(urlString, static_cast<uint64_t>(session_generation));
''',
    "listen JNI forwarding",
)

n = replace_once(
    n,
    '''Java_dev_openstream_app_stream_SrtNativeBridge_disconnect(JNIEnv *, jobject) {
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
''',
    '''Java_dev_openstream_app_stream_SrtNativeBridge_disconnect(
    JNIEnv *, jobject, jlong session_generation) {
  g_state.sender.advanceLifecycleGeneration(static_cast<uint64_t>(session_generation));
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
''',
    "disconnect JNI invalidation",
)

np.write_text(n)

test = '''from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
NATIVE = ROOT / "android/app/src/main/cpp/openstream_srt.cpp"


def _block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:i]
    raise AssertionError(f"unclosed block after {marker!r}")


def test_native_connect_and_listen_publish_only_current_generation():
    kotlin = KOTLIN.read_text()
    native = NATIVE.read_text()
    establish = _block_after(kotlin, "private inline fun establishSession")
    assert "SrtNativeBridge.beginSession(generation)" in establish
    assert "SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)" in kotlin
    assert "SrtNativeBridge.listen(url, codecMime, width, height, fps, generation)" in kotlin

    publish = _block_after(native, "bool setSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in publish
    assert publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < publish.index("socket_ = socket")

    listener_publish = _block_after(native, "bool setListenerSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in listener_publish
    assert listener_publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < listener_publish.index("listener_socket_ = socket")

    connect = _block_after(native, "bool connect(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert connect.index("setSocketForLifecycle(socket, expectedLifecycleGeneration)") < connect.index("srt_connect(")

    listen = _block_after(native, "bool listen(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert "setListenerSocketForLifecycle(listenerSocket, expectedLifecycleGeneration)" in listen
    assert "setSocketForLifecycle(acceptedSocket, expectedLifecycleGeneration)" in listen


def test_disconnect_invalidates_generation_before_native_teardown():
    native = NATIVE.read_text()
    body = _block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_disconnect")
    invalidate = "g_state.sender.advanceLifecycleGeneration(static_cast<uint64_t>(session_generation))"
    teardown = "g_state.sender.disconnect()"
    assert body.index(invalidate) < body.index(teardown)
'''
Path("tests/test_srt_native_lifecycle_handoff_contract.py").write_text(test)
