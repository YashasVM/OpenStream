#include <jni.h>
#include "mpeg_ts_snapshot.h"

struct Mux {
  irl::MpegTsMuxer ts{irl::VideoCodec::Avc};
  std::vector<uint8_t> videoConfig;
  std::vector<uint8_t> audioConfig;
};
extern "C" JNIEXPORT jlong JNICALL
Java_dev_openstream_irl_NativeMux_create(JNIEnv *, jobject) {
  return reinterpret_cast<jlong>(new Mux());
}
extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_irl_NativeMux_destroy(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<Mux *>(handle);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_openstream_irl_NativeMux_write(JNIEnv *env, jobject, jlong handle,
                                      jbyteArray bytes, jlong pts, jint flags, jboolean audio) {
  auto &mux = *reinterpret_cast<Mux *>(handle);
  std::vector<uint8_t> data(env->GetArrayLength(bytes));
  env->GetByteArrayRegion(bytes, 0, data.size(), reinterpret_cast<jbyte *>(data.data()));
  std::vector<uint8_t> output;
  if ((flags & 2) != 0) {
    if (audio) mux.audioConfig = data;
    else mux.videoConfig = irl::normalizeAnnexB(std::move(data));
  } else if (audio) {
    output = mux.ts.muxAudioAccessUnit(data, mux.audioConfig, pts);
  } else {
    data = irl::normalizeAnnexB(std::move(data));
    if ((flags & 1) != 0) data.insert(data.begin(), mux.videoConfig.begin(), mux.videoConfig.end());
    output = mux.ts.muxAccessUnit(data, pts, (flags & 1) != 0);
  }
  auto result = env->NewByteArray(output.size());
  if (result && !output.empty()) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
  return result;
}
