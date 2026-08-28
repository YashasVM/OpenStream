#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <condition_variable>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
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
constexpr int kMinSrtLatencyMs = 80;
constexpr int kMaxSrtLatencyMs = 200;
constexpr int kMediaCodecBufferFlagKeyFrame = 1;
constexpr int kMediaCodecBufferFlagCodecConfig = 2;
constexpr int kAudioSampleRate = 48000;
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
  output.reserve(input.size() + 16);
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

int aacSampleRateIndex(int sampleRate) {
  switch (sampleRate) {
    case 96000: return 0;
    case 88200: return 1;
    case 64000: return 2;
    case 48000: return 3;
    case 44100: return 4;
    case 32000: return 5;
    case 24000: return 6;
    case 22050: return 7;
    case 16000: return 8;
    case 12000: return 9;
    case 11025: return 10;
    case 8000: return 11;
    case 7350: return 12;
    default: return 4;
  }
}

struct AacAdtsConfig {
  int profile = 1;
  int sampleRateIndex = aacSampleRateIndex(kAudioSampleRate);
  int channelConfig = kAudioChannelCount;
};

AacAdtsConfig parseAacConfig(const std::vector<uint8_t> &audioSpecificConfig) {
  AacAdtsConfig config;
  if (audioSpecificConfig.size() < 2) {
    return config;
  }

  const int audioObjectType = (audioSpecificConfig[0] >> 3) & 0x1f;
  const int sampleRateIndex =
      ((audioSpecificConfig[0] & 0x07) << 1) | ((audioSpecificConfig[1] >> 7) & 0x01);
  const int channelConfig = (audioSpecificConfig[1] >> 3) & 0x0f;
  if (audioObjectType > 0) {
    config.profile = std::clamp(audioObjectType - 1, 0, 3);
  }
  if (sampleRateIndex >= 0 && sampleRateIndex <= 12) {
    config.sampleRateIndex = sampleRateIndex;
  }
  if (channelConfig > 0 && channelConfig <= 7) {
    config.channelConfig = channelConfig;
  }
  return config;
}

bool hasAdtsHeader(const std::vector<uint8_t> &accessUnit) {
  return accessUnit.size() >= 2 && accessUnit[0] == 0xff && (accessUnit[1] & 0xf0) == 0xf0;
}

std::vector<uint8_t> makeAdtsFrame(const std::vector<uint8_t> &accessUnit,
                                   const std::vector<uint8_t> &audioSpecificConfig) {
  if (hasAdtsHeader(accessUnit)) {
    return accessUnit;
  }

  const AacAdtsConfig config = parseAacConfig(audioSpecificConfig);
  const size_t frameLength = accessUnit.size() + 7;
  std::vector<uint8_t> frame;
  frame.reserve(frameLength);
  frame.push_back(0xff);
  frame.push_back(0xf1);
  frame.push_back(static_cast<uint8_t>(((config.profile & 0x03) << 6) |
                                       ((config.sampleRateIndex & 0x0f) << 2) |
                                       ((config.channelConfig >> 2) & 0x01)));
  frame.push_back(static_cast<uint8_t>(((config.channelConfig & 0x03) << 6) |
                                       ((frameLength >> 11u) & 0x03u)));
  frame.push_back(static_cast<uint8_t>((frameLength >> 3u) & 0xffu));
  frame.push_back(static_cast<uint8_t>(((frameLength & 0x07u) << 5u) | 0x1fu));
  frame.push_back(0xfc);
  frame.insert(frame.end(), accessUnit.begin(), accessUnit.end());
  return frame;
}

class MpegTsMuxer {
 public:
  explicit MpegTsMuxer(VideoCodec codec) : codec_(codec) {}

  std::vector<uint8_t> muxAccessUnit(const std::vector<uint8_t> &accessUnit,
                                     int64_t presentationTimeUs,
                                     bool keyFrame) {
    std::vector<uint8_t> output;
    output.reserve(estimatePacketizedSize(accessUnit.size() + 32) + kTableBytes);
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
  static constexpr size_t kTableBytes = 188 * 2;

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

  static size_t estimatePacketizedSize(size_t payloadSize) {
    return ((payloadSize + 183) / 184) * 188;
  }

  void packetizeSection(std::vector<uint8_t> &output, uint16_t pid, const std::vector<uint8_t> &section) {
    std::vector<uint8_t> payload;
    payload.reserve(section.size() + 1);
    payload.push_back(0x00);
    payload.insert(payload.end(), section.begin(), section.end());
    packetizePayload(output, pid, payload, true, std::nullopt);
  }

  void writePat(std::vector<uint8_t> &output) {
    std::vector<uint8_t> section;
    section.reserve(17);
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
    section.reserve(27);
    section.push_back(0x02);
    append16(section, 0xb000 | 23);
    append16(section, kProgramNumber);
    section.push_back(0xc1);
    section.push_back(0x00);
    section.push_back(0x00);
    append16(section, 0xe000 | kVideoPid);
    append16(section, 0xf000);
    section.push_back(streamType());
    append16(section, 0xe000 | kVideoPid);
    append16(section, 0xf000);
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
    pes.reserve(accessUnit.size() + 19);

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
                                           const std::vector<uint8_t> &audioSpecificConfig,
                                           int64_t presentationTimeUs) {
    std::vector<uint8_t> output;
    output.reserve(estimatePacketizedSize(accessUnit.size() + 32) + kTableBytes);
    if (audioPacketIndex_ % 50 == 0) {
      writePat(output);
      writePmt(output);
    }

    const std::vector<uint8_t> adtsFrame = makeAdtsFrame(accessUnit, audioSpecificConfig);

    std::vector<uint8_t> pes;
    const uint64_t pts = pts90k(presentationTimeUs);
    const size_t pesPayloadLength = adtsFrame.size() + 8;
    const uint16_t packetLength = pesPayloadLength > 0xffff ? 0 : static_cast<uint16_t>(pesPayloadLength);
    pes.reserve(adtsFrame.size() + 19);
    pes.insert(pes.end(), {0x00, 0x00, 0x01, 0xc0});
    append16(pes, packetLength);
    pes.push_back(0x80);
    pes.push_back(0x80);
    pes.push_back(0x05);
    appendPts(pes, 0x02, pts);
    pes.insert(pes.end(), adtsFrame.begin(), adtsFrame.end());

    packetizePayload(output, kAudioPid, pes, true, std::nullopt);
    ++audioPacketIndex_;
    return output;
  }
};

struct SrtUrl {
  std::string host;
  std::string port;
  uint16_t portNumber = 0;
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
  if (parsed.host.size() >= 2 && parsed.host.front() == '[' && parsed.host.back() == ']') {
    parsed.host = parsed.host.substr(1, parsed.host.size() - 2);
  }
  unsigned int portNumber = 0;
  const auto portResult = std::from_chars(
      parsed.port.data(), parsed.port.data() + parsed.port.size(), portNumber);
  if (portResult.ec != std::errc{} ||
      portResult.ptr != parsed.port.data() + parsed.port.size() ||
      portNumber == 0 || portNumber > 65535) {
    return std::nullopt;
  }
  parsed.portNumber = static_cast<uint16_t>(portNumber);

  if (queryStart != std::string::npos) {
    const std::string query = url.substr(queryStart + 1);
    size_t offset = 0;
    while (offset < query.size()) {
      const size_t amp = query.find('&', offset);
      const std::string part = query.substr(offset, amp == std::string::npos ? std::string::npos : amp - offset);
      constexpr const char *latencyKey = "latency=";
      if (part.rfind(latencyKey, 0) == 0) {
        const std::string value = part.substr(std::strlen(latencyKey));
        int latency = 0;
        const auto latencyResult =
            std::from_chars(value.data(), value.data() + value.size(), latency);
        if (latencyResult.ec == std::errc{} &&
            latencyResult.ptr == value.data() + value.size()) {
          parsed.latencyMs = std::clamp(latency, kMinSrtLatencyMs, kMaxSrtLatencyMs);
        }
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
  NativeSender() : sendWorker_(&NativeSender::runSendWorker, this) {}

  ~NativeSender() {
    stopSendWorker();
    disconnect();
  }

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
    srtStarted_ = true;

    const SRTSOCKET socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) {
      logError("Could not create SRT socket");
      disconnect();
      return false;
    }
    setSocket(socket);

    int yes = 1;
    int transportType = SRTT_LIVE;
    int payloadSize = 188 * 7;
    int sendTimeoutMs = 120;
    int tooLatePacketDrop = 1;
    int connectTimeoutMs = 2000;
    int peerIdleTimeoutMs = 4000;
    srt_setsockopt(socket, 0, SRTO_TRANSTYPE, &transportType, sizeof transportType);
    srt_setsockopt(socket, 0, SRTO_SENDER, &yes, sizeof yes);
    srt_setsockopt(socket, 0, SRTO_PAYLOADSIZE, &payloadSize, sizeof payloadSize);
    srt_setsockopt(socket, 0, SRTO_SNDTIMEO, &sendTimeoutMs, sizeof sendTimeoutMs);
    srt_setsockopt(socket, 0, SRTO_TLPKTDROP, &tooLatePacketDrop, sizeof tooLatePacketDrop);
    srt_setsockopt(socket, 0, SRTO_CONNTIMEO, &connectTimeoutMs, sizeof connectTimeoutMs);
    srt_setsockopt(socket, 0, SRTO_PEERIDLETIMEO, &peerIdleTimeoutMs, sizeof peerIdleTimeoutMs);
    const int latency = parsed->latencyMs;
    srt_setsockopt(socket, 0, SRTO_LATENCY, &latency, sizeof latency);
    srt_setsockopt(socket, 0, SRTO_PEERLATENCY, &latency, sizeof latency);

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *result = nullptr;
    if (getaddrinfo(parsed->host.c_str(), parsed->port.c_str(), &hints, &result) != 0) {
      logError("Could not resolve SRT host");
      disconnect();
      return false;
    }

    bool connected = false;
    for (addrinfo *candidate = result; candidate != nullptr; candidate = candidate->ai_next) {
      if (srt_connect(socket, candidate->ai_addr, static_cast<int>(candidate->ai_addrlen)) == 0) {
        connected = true;
        break;
      }
    }
    freeaddrinfo(result);

    if (!connected) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT connect failed: %s", srt_getlasterror_str());
      disconnect();
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
    srtStarted_ = true;

    const SRTSOCKET listenerSocket = srt_create_socket();
    if (listenerSocket == SRT_INVALID_SOCK) {
      logError("Could not create SRT listener socket");
      disconnect();
      return false;
    }
    setListenerSocket(listenerSocket);

    int yes = 1;
    int transportType = SRTT_LIVE;
    int payloadSize = 188 * 7;
    int sendTimeoutMs = 120;
    int tooLatePacketDrop = 1;
    int peerIdleTimeoutMs = 4000;
    const int latency = parsed->latencyMs;
    srt_setsockopt(listenerSocket, 0, SRTO_TRANSTYPE, &transportType, sizeof transportType);
    srt_setsockopt(listenerSocket, 0, SRTO_SENDER, &yes, sizeof yes);
    srt_setsockopt(listenerSocket, 0, SRTO_REUSEADDR, &yes, sizeof yes);
    srt_setsockopt(listenerSocket, 0, SRTO_PAYLOADSIZE, &payloadSize, sizeof payloadSize);
    srt_setsockopt(listenerSocket, 0, SRTO_SNDTIMEO, &sendTimeoutMs, sizeof sendTimeoutMs);
    srt_setsockopt(listenerSocket, 0, SRTO_TLPKTDROP, &tooLatePacketDrop, sizeof tooLatePacketDrop);
    srt_setsockopt(listenerSocket, 0, SRTO_PEERIDLETIMEO, &peerIdleTimeoutMs, sizeof peerIdleTimeoutMs);
    srt_setsockopt(listenerSocket, 0, SRTO_LATENCY, &latency, sizeof latency);
    srt_setsockopt(listenerSocket, 0, SRTO_PEERLATENCY, &latency, sizeof latency);

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(parsed->portNumber);
    address.sin_addr.s_addr = INADDR_ANY;

    if (srt_bind(listenerSocket, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT bind failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }
    if (srt_listen(listenerSocket, 1) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT listen failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }

    sockaddr_storage peer{};
    int peer_len = sizeof(peer);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Waiting for OBS caller on SRT port %s", parsed->port.c_str());
    const SRTSOCKET acceptedSocket = srt_accept(listenerSocket, reinterpret_cast<sockaddr *>(&peer), &peer_len);
    closeListenerSocket(listenerSocket);
    if (acceptedSocket == SRT_INVALID_SOCK) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT accept failed: %s", srt_getlasterror_str());
      disconnect();
      return false;
    }
    srt_setsockopt(acceptedSocket, 0, SRTO_SNDTIMEO, &sendTimeoutMs, sizeof sendTimeoutMs);
    srt_setsockopt(acceptedSocket, 0, SRTO_TLPKTDROP, &tooLatePacketDrop, sizeof tooLatePacketDrop);
    srt_setsockopt(acceptedSocket, 0, SRTO_PEERIDLETIMEO, &peerIdleTimeoutMs, sizeof peerIdleTimeoutMs);
    setSocket(acceptedSocket);
    logInfo("OBS connected to Android SRT listener");
    return true;
#else
    (void)url;
    logError("openstream_srt was built without libsrt. Rebuild with OPENSTREAM_ENABLE_LIBSRT=ON.");
    return false;
#endif
  }

  bool sendNow(const std::vector<uint8_t> &bytes, uint64_t generation) {
#if OPENSTREAM_HAVE_LIBSRT
    std::lock_guard<std::mutex> ioLock(ioMutex_);
    if (generation != connectionGeneration_.load(std::memory_order_acquire)) {
      return true;
    }
    const SRTSOCKET socket = currentSocket();
    if (socket == SRT_INVALID_SOCK) {
      return false;
    }
    constexpr size_t kChunkSize = 188 * 7;
    size_t offset = 0;
    while (offset < bytes.size()) {
      const size_t chunk = std::min(kChunkSize, bytes.size() - offset);
      const int sent = srt_sendmsg(socket,
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
    (void)generation;
    return false;
#endif
  }

  bool send(std::vector<uint8_t> bytes) {
#if OPENSTREAM_HAVE_LIBSRT
    const size_t byteCount = bytes.size();
    {
      std::lock_guard<std::mutex> lock(sendQueueMutex_);
      if (!healthy_.load(std::memory_order_acquire) ||
          sendWorkerStopping_ ||
          byteCount > kMaximumSendQueueBytes ||
          sendQueueBytes_ > kMaximumSendQueueBytes - byteCount) {
        if (healthy_.load(std::memory_order_relaxed)) {
          healthy_ = false;
          sendQueue_.clear();
          sendQueueBytes_ = 0;
          __android_log_print(
              ANDROID_LOG_WARN,
              kTag,
              "SRT send queue saturated; dropping the session to avoid latency growth");
        }
        return false;
      }
      const uint64_t generation = connectionGeneration_.load(std::memory_order_acquire);
      sendQueue_.push_back(PendingSend{generation, std::move(bytes)});
      sendQueueBytes_ += byteCount;
    }
    sendQueueWake_.notify_one();
    return true;
#else
    (void)bytes;
    return false;
#endif
  }

  void disconnect() {
#if OPENSTREAM_HAVE_LIBSRT
    healthy_ = false;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    {
      std::lock_guard<std::mutex> lock(sendQueueMutex_);
      sendQueue_.clear();
      sendQueueBytes_ = 0;
    }
    std::lock_guard<std::mutex> ioLock(ioMutex_);
    const SRTSOCKET listenerSocket = takeListenerSocket();
    const SRTSOCKET socket = takeSocket();
    if (listenerSocket != SRT_INVALID_SOCK) {
      srt_close(listenerSocket);
    }
    if (socket != SRT_INVALID_SOCK) {
      srt_close(socket);
    }
    if (srtStarted_) {
      srt_cleanup();
      srtStarted_ = false;
    }
#endif
  }

 private:
  struct PendingSend {
    uint64_t generation;
    std::vector<uint8_t> bytes;
  };

  void runSendWorker() {
    for (;;) {
      PendingSend pending{};
      {
        std::unique_lock<std::mutex> lock(sendQueueMutex_);
        sendQueueWake_.wait(lock, [this] {
          return sendWorkerStopping_ || !sendQueue_.empty();
        });
        if (sendWorkerStopping_) return;
        pending = std::move(sendQueue_.front());
        sendQueue_.pop_front();
        sendQueueBytes_ -= pending.bytes.size();
      }
      if (!sendNow(pending.bytes, pending.generation)) {
        healthy_ = false;
        std::lock_guard<std::mutex> lock(sendQueueMutex_);
        sendQueue_.clear();
        sendQueueBytes_ = 0;
      }
    }
  }

  void stopSendWorker() {
    {
      std::lock_guard<std::mutex> lock(sendQueueMutex_);
      sendWorkerStopping_ = true;
      sendQueue_.clear();
      sendQueueBytes_ = 0;
    }
    sendQueueWake_.notify_one();
    if (sendWorker_.joinable()) sendWorker_.join();
  }

#if OPENSTREAM_HAVE_LIBSRT
  SRTSOCKET currentSocket() const {
    std::lock_guard<std::mutex> lock(socketMutex_);
    return socket_;
  }

  void setSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    socket_ = socket;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    healthy_.store(true, std::memory_order_release);
  }

  SRTSOCKET takeSocket() {
    std::lock_guard<std::mutex> lock(socketMutex_);
    const SRTSOCKET socket = socket_;
    socket_ = SRT_INVALID_SOCK;
    return socket;
  }

  void closeSocket(SRTSOCKET socket) {
    bool ownsSocket = false;
    {
      std::lock_guard<std::mutex> lock(socketMutex_);
      if (socket_ == socket) {
        socket_ = SRT_INVALID_SOCK;
        healthy_ = false;
        ownsSocket = true;
      }
    }
    if (ownsSocket) {
      srt_close(socket);
    }
  }

  void setListenerSocket(SRTSOCKET socket) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    listener_socket_ = socket;
  }

  SRTSOCKET takeListenerSocket() {
    std::lock_guard<std::mutex> lock(socketMutex_);
    const SRTSOCKET socket = listener_socket_;
    listener_socket_ = SRT_INVALID_SOCK;
    return socket;
  }

  void closeListenerSocket(SRTSOCKET socket) {
    bool ownsSocket = false;
    {
      std::lock_guard<std::mutex> lock(socketMutex_);
      if (listener_socket_ == socket) {
        listener_socket_ = SRT_INVALID_SOCK;
        ownsSocket = true;
      }
    }
    if (ownsSocket) {
      srt_close(socket);
    }
  }

  mutable std::mutex socketMutex_;
  std::mutex ioMutex_;
  SRTSOCKET socket_ = SRT_INVALID_SOCK;
  SRTSOCKET listener_socket_ = SRT_INVALID_SOCK;
  bool srtStarted_ = false;
#endif
  static constexpr size_t kMaximumSendQueueBytes = 768 * 1024;
  std::atomic<bool> healthy_{false};
  std::atomic<uint64_t> connectionGeneration_{0};
  std::mutex sendQueueMutex_;
  std::condition_variable sendQueueWake_;
  std::deque<PendingSend> sendQueue_;
  size_t sendQueueBytes_ = 0;
  bool sendWorkerStopping_ = false;
  std::thread sendWorker_;
};

struct StreamState {
  NativeSender sender;
  std::optional<MpegTsMuxer> muxer;
  std::vector<uint8_t> codecConfig;
  std::vector<uint8_t> audioCodecConfig;
  std::mutex mediaMutex;
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

  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    g_state.muxer.emplace(*codec);
    g_state.muxer->reset();
    g_state.codecConfig.clear();
    g_state.audioCodecConfig.clear();
    g_state.connected = false;
  }
  g_state.sender.disconnect();
  const bool connected = g_state.sender.connect(urlString);
  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    g_state.connected = connected;
  }
  if (connected) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Connected SRT MPEG-TS sender to %s", urlString.c_str());
  }
  return connected ? JNI_TRUE : JNI_FALSE;
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

  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    g_state.muxer.emplace(*codec);
    g_state.muxer->reset();
    g_state.codecConfig.clear();
    g_state.audioCodecConfig.clear();
    g_state.connected = false;
  }
  g_state.sender.disconnect();
  const bool connected = g_state.sender.listen(urlString);
  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    g_state.connected = connected;
  }
  if (connected) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Accepted OBS SRT caller at %s", urlString.c_str());
  }
  return connected ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_sendVideo(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags) {
  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (!g_state.connected || !g_state.muxer) {
    return JNI_FALSE;
  }

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

  std::vector<uint8_t> ts =
      g_state.muxer->muxAccessUnit(annexB, static_cast<int64_t>(presentation_time_us), keyFrame);
  const bool sent = g_state.sender.send(std::move(ts));
  if (!sent) {
    g_state.connected = false;
  }
  return sent ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_disconnect(JNIEnv *, jobject) {
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  g_state.muxer.reset();
  g_state.codecConfig.clear();
  g_state.audioCodecConfig.clear();
  g_state.connected = false;
  g_state.sender.disconnect();
  logInfo("SRT bridge disconnected");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_sendAudio(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags) {
  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (!g_state.connected || !g_state.muxer) {
    return JNI_FALSE;
  }

  if ((flags & kMediaCodecBufferFlagCodecConfig) != 0) {
    g_state.audioCodecConfig = std::move(bytes);
    logInfo("Stored audio codec config (ASC)");
    return JNI_TRUE;
  }

  std::vector<uint8_t> ts = g_state.muxer->muxAudioAccessUnit(
      bytes, g_state.audioCodecConfig, static_cast<int64_t>(presentation_time_us));
  const bool sent = g_state.sender.send(std::move(ts));
  if (!sent) {
    g_state.connected = false;
  }
  return sent ? JNI_TRUE : JNI_FALSE;
}
