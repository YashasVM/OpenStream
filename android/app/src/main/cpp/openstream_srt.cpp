#include <jni.h>
#include <android/log.h>
#include <string>

namespace {
constexpr const char *kTag = "OpenStreamSRT";
std::string g_url;
bool g_connected = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_connect(
    JNIEnv *env,
    jobject,
    jstring url) {
  const char *raw_url = env->GetStringUTFChars(url, nullptr);
  g_url = raw_url;
  env->ReleaseStringUTFChars(url, raw_url);
  g_connected = true;
  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "SRT bridge placeholder accepted %s. Link real libsrt here.",
      g_url.c_str());
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_sendVideo(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags) {
  if (!g_connected) {
    return JNI_FALSE;
  }
  const jsize size = env->GetArrayLength(data);
  __android_log_print(
      ANDROID_LOG_VERBOSE,
      kTag,
      "Encoded access unit size=%d pts=%lld flags=%d",
      static_cast<int>(size),
      static_cast<long long>(presentation_time_us),
      static_cast<int>(flags));
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_disconnect(JNIEnv *, jobject) {
  g_connected = false;
  g_url.clear();
  __android_log_print(ANDROID_LOG_INFO, kTag, "SRT bridge disconnected");
}
