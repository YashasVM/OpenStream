#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#if OPENSTREAM_HAVE_LIBSRT
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <srt/srt.h>
#endif

namespace {

constexpr const char *kTag = "OpenStreamSRT";
constexpr int kMediaCodecBufferFlagKeyFrame = 1;
constexpr int kMediaCodecBufferFlagCodecConfig = 2;
constexpr int kAudioSampleRate = 44100;
constexpr int kAudioChannelCount = 1;

void logInfo(const char *message) {
  __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message);
}

void logError(const char *message) {
  __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}

enum class VideoCodec {
  Avc,
  Hevc,
};

std::optional<VideoCodec> parseCodec(const std::string &mime) {
  if (mime == "video/avc") {
    return VideoCodec::Avc;
  }
  if (mime == "video/hevc") {
    return VideoCodec::Hevc;
  }
  return std::nullopt;
}

uint32_t crc32Mpeg(const uint8_t *data, size_t size) {
  uint32_t crc = 0xffffffffu;
  for (size_t i = 0; i < size; ++i) {
    crc ^= static_cast<uint32_t>(data[i]) << 24u;
    for (int bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x80000000u) ? (crc << 1u) ^ 0x04c11db7u : crc << 1u;
    }
  }
  return crc;
}

void append16(std::vector<uint8_t> &out, uint16_t value) {
  out.push_back(static_cast<uint8_t>((value >> 8u) & 0xffu));
  out.push_back(static_cast<uint8_t>(value & 0xffu));
}

void append32(std::vector<uint8_t> &out, uint32_t value) {
  out.push_back(static_cast<uint8_t>((value >> 24u) & 0xffu));
  out.push_back(static_cast<uint8_t>((value >> 16u) & 0xffu));
  out.push_back(static_cast<uint8_t>((value >> 8u) & 0xffu));
  out.push_back(static_cast<uint8_t>(value & 0xffu));
}

void appendPts(std::vector<uint8_t> &out, uint8_t prefix, uint64_t pts90k) {
  const uint64_t pts = pts90k & ((1ull << 33u) - 1u);
  out.push_back(static_cast<uint8_t>((prefix << 4u) | (((pts >> 30u) & 0x07u) << 1u) | 1u));
  out.push_back(static_cast<uint8_t>((pts >> 22u) & 0xffu));
  out.push_back(static_cast<uint8_t>((((pts >> 15u) & 0x7fu) << 1u) | 1u));
  out.push_back(static_cast<uint8_t>((pts >> 7u) & 0xffu));
  out.push_back(static_cast<uint8_t>(((pts & 0x7fu) << 1u) | 1u));
}

void writePcr(uint8_t *out, uint64_t pts90k) {
  const uint64_t base = pts90k & ((1ull << 33u) - 1u);
  out[0] = static_cast<uint8_t>((base >> 25u) & 0xffu);
  out[1] = static_cast<uint8_t>((base >> 17u) & 0xffu);
  out[2] = static_cast<uint8_t>((base >> 9u) & 0xffu);
  out[3] = static_cast<uint8_t>((base >> 1u) & 0xffu);
  out[4] = static_cast<uint8_t>(((base & 0x01u) << 7u) | 0x7eu);
  out[5] = 0x00;
}

bool hasStartCode(const std::vector<uint8_t> &data, size_t offset) {
  if (offset + 4 <= data.size() && data[offset] == 0x00 && data[offset + 1] == 0x00 &&
      data[offset + 2] == 0x00 && data[offset + 3] == 0x01) {
    return true;
  }
  return offset + 3 <= data.size() && data[offset] == 0x00 && data[offset + 1] == 0x00 &&
         data[offset + 2] == 0x01;
}

std::vector<uint8_t> normalizeAnnexB(const uint8_t *bytes, size_t size) {
  std::vector<uint8_t> input(bytes, bytes + size);
  if (input.empty() || hasStartCode(input, 0)) {
    return input;
  }

  std::vector<uint8_t> output;
  size_t offset = 0;
  while (offset + 4 <= input.size()) {
    const uint32_t nalSize = (static_cast<uint32_t>(input[offset]) << 24u) |
                             (static_cast<uint32_t>(input[offset + 1]) << 16u) |
                             (static_cast<uint32_t>(input[offset + 2]) << 8u) |
                             static_cast<uint32_t>(input[offset + 3]);
    offset += 4;
    if (nalSize == 0 || offset + nalSize > input.size()) {
      return input;
    }
    output.insert(output.end(), {0x00, 0x00, 0x00, 0x01});
    output.insert(output.end(), input.begin() + static_cast<std::ptrdiff_t>(offset),
                  input.begin() + static_cast<std::ptrdiff_t>(offset + nalSize));
    offset += nalSize;
  }

  return offset == input.size() ? output : input;
}

class MpegTsMuxer {
 public:
  explicit MpegTsMuxer(VideoCodec codec) : codec_(codec) {}

  std::vector<uint8_t> muxAccessUnit(const std::vector<uint8_t> &accessUnit,
                                     int64_t presentationTimeUs,
                                     bool keyFrame) {
    std::vector<uint8_t> output;
    if (forceTables_ || keyFrame || packetIndex_ % 30 == 0) {
      writePat(output);
      writePmt(output);
      forceTables_ = false;
    }

    std::vector<uint8_t> payload = buildPes(accessUnit, presentationTimeUs);
    packetizePayload(output, kVideoPid, payload, true, pts90k(presentationTimeUs));
    ++packetIndex_;
    return output;
  }

  void reset() {
    continuity_.clear();
    forceTables_ = true;
    packetIndex_ = 0;
    audioPacketIndex_ = 0;
  }

 private:
  static constexpr uint16_t kProgramNumber = 1;
  static constexpr uint16_t kPmtPid = 0x100;
  static constexpr uint16_t kVideoPid = 0x101;
  static constexpr uint16_t kAudioPid = 0x102;

  uint8_t nextContinuity(uint16_t pid) {
    uint8_t &counter = continuity_[pid];
    const uint8_t value = counter & 0x0fu;
    counter = static_cast<uint8_t>((counter + 1u) & 0x0fu);
    return value;
  }

  uint8_t streamType() const {
    return codec_ == VideoCodec::Hevc ? 0x24 : 0x1b;
  }

  static uint64_t pts90k(int64_t presentationTimeUs) {
    return presentationTimeUs <= 0 ? 0 : static_cast<uint64_t>(presentationTimeUs) * 90ull / 1000ull;
  }

  void packetizeSection(std::vector<uint8_t> &output, uint16_t pid, const std::vector<uint8_t> &section) {
    std::vector<uint8_t> payload;
    payload.push_back(0x00);
    payload.insert(payload.end(), section.begin(), section.end());
    packetizePayload(output, pid, payload, true, std::nullopt);
  }

  void writePat(std::vector<uint8_t> &output) {
    std::vector<uint8_t> section;
    section.push_back(0x00);
    append16(section, 0xb000 | 13);
    append16(section, 0x0001);
    section.push_back(0xc1);
    section.push_back(0x00);
    section.push_back(0x00);
    append16(section, kProgramNumber);
    append16(section, 0xe000 | kPmtPid);
    append32(section, crc32Mpeg(section.data(), section.size()));
    packetizeSection(output, 0x0000, section);
  }

  void writePmt(std::vector<uint8_t> &output) {
    std::vector<uint8_t> section;
    section.push_back(0x02);
    append16(section, 0xb000 | 23);  // section_length: 23 = 5 + 5 (video) + 5 (audio) + 4 (CRC) + 4 (header)
    append16(section, kProgramNumber);
    section.push_back(0xc1);
    section.push_back(0x00);
    section.push_back(0x00);
    append16(section, 0xe000 | kVideoPid);
    append16(section, 0xf000);
    // Video elementary stream
    section.push_back(streamType());
    append16(section, 0xe000 | kVideoPid);
    append16(section, 0xf000);
    // Audio elementary stream (AAC = 0x0f)
    section.push_back(0x0f);
    append16(section, 0xe000 | kAudioPid);
    append16(section, 0xf000);
    append32(section, crc32Mpeg(section.data(), section.size()));
    packetizeSection(output, kPmtPid, section);
  }

  std::vector<uint8_t> buildPes(const std::vector<uint8_t> &accessUnit, int64_t presentationTimeUs) {
    std::vector<uint8_t> pes;
    const uint64_t pts = pts90k(presentationTimeUs);
    const size_t pesPayloadLength = accessUnit.size() + 8;
    const uint16_t packetLength = pesPayloadLength > 0xffff ? 0 : static_cast<uint16_t>(pesPayloadLength);

    pes.insert(pes.end(), {0x00, 0x00, 0x01, 0xe0});
    append16(pes, packetLength);
    pes.push_back(0x80);
    pes.push_back(0x80);
    pes.push_back(0x05);
    appendPts(pes, 0x02, pts);
    pes.insert(pes.end(), accessUnit.begin(), accessUnit.end());
    return pes;
  }

  void packetizePayload(std::vector<uint8_t> &output,
                        uint16_t pid,
                        const std::vector<uint8_t> &payload,
                        bool payloadUnitStart,
                        std::optional<uint64_t> pcr90k) {
    size_t offset = 0;
    bool first = true;
    while (offset < payload.size()) {
      std::array<uint8_t, 188> packet{};
      packet.fill(0xff);
      packet[0] = 0x47;
      packet[1] = static_cast<uint8_t>(((payloadUnitStart && first) ? 0x40 : 0x00) | ((pid >> 8u) & 0x1fu));
      packet[2] = static_cast<uint8_t>(pid & 0xffu);

      const bool includePcr = first && pcr90k.has_value();
      const size_t remaining = payload.size() - offset;
      const size_t minAdaptationBody = includePcr ? 7 : 0;
      const size_t maxPayloadWithMinAdaptation = 188 - 4 - (minAdaptationBody == 0 ? 0 : 1 + minAdaptationBody);
      size_t chunk = std::min(remaining, maxPayloadWithMinAdaptation);
      bool hasAdaptation = includePcr || chunk < remaining || chunk < 184;
      if (!includePcr && remaining >= 184) {
        hasAdaptation = false;
        chunk = 184;
      }

      packet[3] = static_cast<uint8_t>((hasAdaptation ? 0x30 : 0x10) | nextContinuity(pid));
      size_t payloadOffset = 4;
      if (hasAdaptation) {
        const size_t adaptationLength = 188 - 4 - 1 - chunk;
        packet[payloadOffset++] = static_cast<uint8_t>(adaptationLength);
        if (adaptationLength > 0) {
          packet[payloadOffset++] = includePcr ? 0x10 : 0x00;
          if (includePcr) {
            writePcr(&packet[payloadOffset], *pcr90k);
            payloadOffset += 6;
          }
          while (payloadOffset < 188 - chunk) {
            packet[payloadOffset++] = 0xff;
          }
        }
      }

      std::copy(payload.begin() + static_cast<std::ptrdiff_t>(offset),
                payload.begin() + static_cast<std::ptrdiff_t>(offset + chunk),
                packet.begin() + static_cast<std::ptrdiff_t>(payloadOffset));
      output.insert(output.end(), packet.begin(), packet.end());
      offset += chunk;
      first = false;
    }
  }

  VideoCodec codec_;
  std::map<uint16_t, uint8_t> continuity_;
  bool forceTables_ = true;
  uint64_t packetIndex_ = 0;
  uint64_t audioPacketIndex_ = 0;

 public:
  std::vector<uint8_t> muxAudioAccessUnit(const std::vector<uint8_t> &accessUnit,
                                           int64_t presentationTimeUs) {
    std::vector<uint8_t> output;
    // Include tables periodically for audio too
    if (audioPacketIndex_ % 50 == 0) {
      writePat(output);
      writePmt(output);
    }

    // Build audio PES (stream ID 0xC0 = audio stream 0)
    std::vector<uint8_t> pes;
    const uint64_t pts = pts90k(presentationTimeUs);
    const size_t pesPayloadLength = accessUnit.size() + 8;
    const uint16_t packetLength = pesPayloadLength > 0xffff ? 0 : static_cast<uint16_t>(pesPayloadLength);
    pes.insert(pes.end(), {0x00, 0x00, 0x01, 0xc0});
    append16(pes, packetLength);
    pes.push_back(0x80);
    pes.push_back(0x80);
    pes.push_back(0x05);
    appendPts(pes, 0x02, pts);
    pes.insert(pes.end(), accessUnit.begin(), accessUnit.end());

    packetizePayload(output, kAudioPid, pes, true, std::nullopt);
    ++audioPacketIndex_;
    return output;
  }
};

struct SrtUrl {
  std::string host;
  std::string port;
  int latencyMs = 120;
};

std::optional<SrtUrl> parseSrtUrl(const std::string &url) {
  constexpr const char *scheme = "srt://";
  if (url.rfind(scheme, 0) != 0) {
    return std::nullopt;
  }

  const size_t authorityStart = std::strlen(scheme);
  const size_t queryStart = url.find('?', authorityStart);
  const std::string authority = url.substr(authorityStart, queryStart - authorityStart);
  const size_t colon = authority.rfind(':');
  if (colon == std::string::npos || colon == 0 || colon + 1 >= authority.size()) {
    return std::nullopt;
  }

  SrtUrl parsed;
  parsed.host = authority.substr(0, colon);
  parsed.port = authority.substr(colon + 1);

  if (queryStart != std::string::npos) {
    const std::string query = url.substr(queryStart + 1);
    size_t offset = 0;
    while (offset < query.size()) {
      const size_t amp = query.find('&', offset);
      const std::string part = query.substr(offset, amp == std::string::npos ? std::string::npos : amp - offset);
      constexpr const char *latencyKey = "latency=";
      if (part.rfind(latencyKey, 0) == 0) {
        parsed.latencyMs = std::max(20, std::min(2000, std::atoi(part.c_str() + std::strlen(latencyKey))));
      }
      if (amp == std::string::npos) {
        break;
      }
      offset = amp + 1;
    }
  }

  return parsed;
}

class NativeSender {
 public:
  bool connect(const std::string &url) {
#if OPENSTREAM_HAVE_LIBSRT
    disconnect();
    const auto parsed = parseSrtUrl(url);
    if (!parsed) {
      logError("Invalid SRT URL");
      return false;
    }

    if (srt_startup() != 0) {
      logError("libsrt startup failed");
      return false;
    }

    socket_ = srt_create_socket();
    if (socket_ == SRT_INVALID_SOCK) {
      logError("Could not create SRT socket");
      return false;
    }

    int yes = 1;
    int transportType = SRTT_LIVE;
    int payloadSize = 188 * 7;
    srt_setsockopt(socket_, 0, SRTO_TRANSTYPE, &transportType, sizeof transportType);
    srt_setsockopt(socket_, 0, SRTO_SENDER, &yes, sizeof yes);
    srt_setsockopt(socket_, 0, SRTO_PAYLOADSIZE, &payloadSize, sizeof payloadSize);
    const int latency = parsed->latencyMs;
    srt_setsockopt(socket_, 0, SRTO_LATENCY, &latency, sizeof latency);
    srt_setsockopt(socket_, 0, SRTO_PEERLATENCY, &latency, sizeof latency);

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *result = nullptr;
    if (getaddrinfo(parsed->host.c_str(), parsed->port.c_str(), &hints, &result) != 0) {
      logError("Could not resolve SRT host");
      srt_close(socket_);
      socket_ = SRT_INVALID_SOCK;
      return false;
    }

    bool connected = false;
    for (addrinfo *candidate = result; candidate != nullptr; candidate = candidate->ai_next) {
      if (srt_connect(socket_, candidate->ai_addr, static_cast<int>(candidate->ai_addrlen)) == 0) {
        connected = true;
        break;
      }
    }
    freeaddrinfo(result);

    if (!connected) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT connect failed: %s", srt_getlasterror_str());
      srt_close(socket_);
      socket_ = SRT_INVALID_SOCK;
      return false;
    }

    return true;
#else
    (void)url;
    logError("openstream_srt was built without libsrt. Rebuild with OPENSTREAM_ENABLE_LIBSRT=ON.");
    return false;
#endif
  }

  bool listen(const std::string &url) {
#if OPENSTREAM_HAVE_LIBSRT
    disconnect();
    const auto parsed = parseSrtUrl(url);
    if (!parsed) {
      logError("Invalid SRT listener URL");
      return false;
    }

    if (srt_startup() != 0) {
      logError("libsrt startup failed");
      return false;
    }

    listener_socket_ = srt_create_socket();
    if (listener_socket_ == SRT_INVALID_SOCK) {
      logError("Could not create SRT listener socket");
      return false;
    }

    int yes = 1;
    int transportType = SRTT_LIVE;
    int payloadSize = 188 * 7;
    const int latency = parsed->latencyMs;
    srt_setsockopt(listener_socket_, 0, SRTO_TRANSTYPE, &transportType, sizeof transportType);
    srt_setsockopt(listener_socket_, 0, SRTO_SENDER, &yes, sizeof yes);
    srt_setsockopt(listener_socket_, 0, SRTO_REUSEADDR, &yes, sizeof yes);
    srt_setsockopt(listener_socket_, 0, SRTO_PAYLOADSIZE, &payloadSize, sizeof payloadSize);
    srt_setsockopt(listener_socket_, 0, SRTO_LATENCY, &latency, sizeof latency);
    srt_setsockopt(listener_socket_, 0, SRTO_PEERLATENCY, &latency, sizeof latency);

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(std::atoi(parsed->port.c_str())));
    address.sin_addr.s_addr = INADDR_ANY;

    if (srt_bind(listener_socket_, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT bind failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }
    if (srt_listen(listener_socket_, 1) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT listen failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }

    sockaddr_storage peer{};
    int peer_len = sizeof(peer);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Waiting for OBS caller on SRT port %s", parsed->port.c_str());
    socket_ = srt_accept(listener_socket_, reinterpret_cast<sockaddr *>(&peer), &peer_len);
    srt_close(listener_socket_);
    listener_socket_ = SRT_INVALID_SOCK;
    if (socket_ == SRT_INVALID_SOCK) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT accept failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }
    logInfo("OBS connected to Android SRT listener");
    return true;
#else
    (void)url;
    logError("openstream_srt was built without libsrt. Rebuild with OPENSTREAM_ENABLE_LIBSRT=ON.");
    return false;
#endif
  }

  bool send(const std::vector<uint8_t> &bytes) {
#if OPENSTREAM_HAVE_LIBSRT
    if (socket_ == SRT_INVALID_SOCK) {
      return false;
    }
    constexpr size_t kChunkSize = 188 * 7;
    size_t offset = 0;
    while (offset < bytes.size()) {
      const size_t chunk = std::min(kChunkSize, bytes.size() - offset);
      const int sent = srt_sendmsg(socket_,
                                   reinterpret_cast<const char *>(bytes.data() + offset),
                                   static_cast<int>(chunk),
                                   -1,
                                   0);
      if (sent == SRT_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT send failed: %s", srt_getlasterror_str());
        return false;
      }
      if (sent <= 0) {
        logError("SRT send made no progress");
        return false;
      }
      offset += static_cast<size_t>(sent);
    }
    return true;
#else
    (void)bytes;
    return false;
#endif
  }

  void disconnect() {
#if OPENSTREAM_HAVE_LIBSRT
    if (listener_socket_ != SRT_INVALID_SOCK) {
      srt_close(listener_socket_);
      listener_socket_ = SRT_INVALID_SOCK;
    }
    if (socket_ != SRT_INVALID_SOCK) {
      srt_close(socket_);
      socket_ = SRT_INVALID_SOCK;
    }
    srt_cleanup();
#endif
  }

 private:
#if OPENSTREAM_HAVE_LIBSRT
  SRTSOCKET socket_ = SRT_INVALID_SOCK;
  SRTSOCKET listener_socket_ = SRT_INVALID_SOCK;
#endif
};

struct StreamState {
  NativeSender sender;
  std::optional<MpegTsMuxer> muxer;
  std::vector<uint8_t> codecConfig;
  std::vector<uint8_t> audioCodecConfig;
  bool connected = false;
};

StreamState g_state;

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_connect(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint) {
  const char *rawUrl = env->GetStringUTFChars(url, nullptr);
  const char *rawCodec = env->GetStringUTFChars(codec_mime, nullptr);
  const std::string urlString(rawUrl);
  const std::string codecString(rawCodec);
  env->ReleaseStringUTFChars(url, rawUrl);
  env->ReleaseStringUTFChars(codec_mime, rawCodec);

  const auto codec = parseCodec(codecString);
  if (!codec) {
    logError("Unsupported video codec for SRT bridge");
    return JNI_FALSE;
  }

  g_state.sender.disconnect();
  g_state.muxer.emplace(*codec);
  g_state.muxer->reset();
  g_state.codecConfig.clear();
  g_state.connected = g_state.sender.connect(urlString);
  if (g_state.connected) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Connected SRT MPEG-TS sender to %s", urlString.c_str());
  }
  return g_state.connected ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_listen(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint) {
  const char *rawUrl = env->GetStringUTFChars(url, nullptr);
  const char *rawCodec = env->GetStringUTFChars(codec_mime, nullptr);
  const std::string urlString(rawUrl);
  const std::string codecString(rawCodec);
  env->ReleaseStringUTFChars(url, rawUrl);
  env->ReleaseStringUTFChars(codec_mime, rawCodec);

  const auto codec = parseCodec(codecString);
  if (!codec) {
    logError("Unsupported video codec for SRT bridge");
    return JNI_FALSE;
  }

  g_state.sender.disconnect();
  g_state.muxer.emplace(*codec);
  g_state.muxer->reset();
  g_state.codecConfig.clear();
  g_state.connected = g_state.sender.listen(urlString);
  if (g_state.connected) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Accepted OBS SRT caller at %s", urlString.c_str());
  }
  return g_state.connected ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_sendVideo(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags) {
  if (!g_state.connected || !g_state.muxer) {
    return JNI_FALSE;
  }

  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  std::vector<uint8_t> annexB = normalizeAnnexB(bytes.data(), bytes.size());
  if ((flags & kMediaCodecBufferFlagCodecConfig) != 0) {
    g_state.codecConfig = std::move(annexB);
    logInfo("Stored codec config for keyframe pre-roll");
    return JNI_TRUE;
  }

  const bool keyFrame = (flags & kMediaCodecBufferFlagKeyFrame) != 0;
  if (keyFrame && !g_state.codecConfig.empty()) {
    std::vector<uint8_t> withConfig = g_state.codecConfig;
    withConfig.insert(withConfig.end(), annexB.begin(), annexB.end());
    annexB = std::move(withConfig);
  }

  const std::vector<uint8_t> ts =
      g_state.muxer->muxAccessUnit(annexB, static_cast<int64_t>(presentation_time_us), keyFrame);
  return g_state.sender.send(ts) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_disconnect(JNIEnv *, jobject) {
  g_state.sender.disconnect();
  g_state.muxer.reset();
  g_state.codecConfig.clear();
  g_state.audioCodecConfig.clear();
  g_state.connected = false;
  logInfo("SRT bridge disconnected");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_sendAudio(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags) {
  if (!g_state.connected || !g_state.muxer) {
    return JNI_FALSE;
  }

  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  if ((flags & kMediaCodecBufferFlagCodecConfig) != 0) {
    g_state.audioCodecConfig = std::move(bytes);
    logInfo("Stored audio codec config (ADTS/ASC)");
    return JNI_TRUE;
  }

  const std::vector<uint8_t> ts =
      g_state.muxer->muxAudioAccessUnit(bytes, static_cast<int64_t>(presentation_time_us));
  return g_state.sender.send(ts) ? JNI_TRUE : JNI_FALSE;
}
