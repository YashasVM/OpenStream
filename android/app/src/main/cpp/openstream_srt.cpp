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

constexpr const char *kTag = "shinSRT";
constexpr int kMinSrtLatencyMs = 80;
constexpr int kMaxSrtLatencyMs = 200;
constexpr int kListenerAcceptPollMs = 100;
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

std::vector<uint8_t> normalizeAnnexB(std::vector<uint8_t> input) {
  if (input.empty() || hasStartCode(input, 0)) {
    return input;
  }

  // Validate the complete access unit before changing any length prefixes.
  // Invalid input must retain its original bytes for the existing fallback.
  size_t offset = 0;
  while (offset + 4 <= input.size()) {
    const uint32_t nalSize = (static_cast<uint32_t>(input[offset]) << 24u) |
                             (static_cast<uint32_t>(input[offset + 1]) << 16u) |
                             (static_cast<uint32_t>(input[offset + 2]) << 8u) |
                             static_cast<uint32_t>(input[offset + 3]);
    offset += 4;
    if (nalSize == 0 || nalSize > input.size() - offset) {
      return input;
    }
    offset += nalSize;
  }
  if (offset != input.size()) {
    return input;
  }

  // Four-byte NAL lengths and Annex B start codes occupy the same space.
  offset = 0;
  while (offset < input.size()) {
    const uint32_t nalSize = (static_cast<uint32_t>(input[offset]) << 24u) |
                             (static_cast<uint32_t>(input[offset + 1]) << 16u) |
                             (static_cast<uint32_t>(input[offset + 2]) << 8u) |
                             static_cast<uint32_t>(input[offset + 3]);
    input[offset] = 0;
    input[offset + 1] = 0;
    input[offset + 2] = 0;
    input[offset + 3] = 1;
    offset += 4 + nalSize;
  }
  return input;
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
  bool irl = false;
  std::optional<std::string> streamId;
  std::optional<std::string> passphrase;
  int pbkeylen = 16;
};

std::optional<std::string> percentDecode(const std::string &value) {
  std::string decoded;
  for (size_t i = 0; i < value.size(); ++i) {
    char byte = value[i];
    if (byte == '%') {
      if (i + 2 >= value.size()) return std::nullopt;
      unsigned int hex = 0;
      const auto result = std::from_chars(value.data() + i + 1, value.data() + i + 3, hex, 16);
      if (result.ec != std::errc{} || result.ptr != value.data() + i + 3) return std::nullopt;
      byte = static_cast<char>(hex);
      i += 2;
    }
    if (byte == '\0') return std::nullopt;
    decoded.push_back(byte); // A literal '+' is not a form-encoded space.
  }
  return decoded;
}

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
    // Detect the opt-in before applying stricter IRL validation. OBS URLs keep
    // their original permissive parser, defaults, and 80–200 ms latency clamp.
    std::vector<std::pair<std::string, std::string>> options;
    size_t start = 0;
    while (start < query.size()) {
      const size_t end = query.find('&', start);
      const std::string part = query.substr(start, end == std::string::npos ? end : end - start);
      const size_t equals = part.find('=');
      if (!part.empty()) {
        const std::string key = part.substr(0, equals);
        const std::string value = equals == std::string::npos ? "" : part.substr(equals + 1);
        options.emplace_back(key, value);
        if (key == "irl" && percentDecode(value) == std::optional<std::string>("1")) parsed.irl = true;
      }
      if (end == std::string::npos) break;
      start = end + 1;
    }
    if (parsed.irl) {
      parsed.latencyMs = 2000;
      std::map<std::string, std::string> values;
      for (const auto &[key, rawValue] : options) {
        if (key != "irl" && key != "mode" && key != "latency" && key != "streamid" &&
            key != "passphrase" && key != "pbkeylen") return std::nullopt;
        const auto value = percentDecode(rawValue);
        if (!value || !values.emplace(key, *value).second) return std::nullopt;
      }
      if (values["irl"] != "1" ||
          (values.count("mode") && values["mode"] != "caller")) return std::nullopt;
      for (const char *key : {"latency", "pbkeylen"}) {
        const auto entry = values.find(key);
        if (entry == values.end()) continue;
        const std::string &value = entry->second;
        int number = 0;
        const auto result = std::from_chars(value.data(), value.data() + value.size(), number);
        if (result.ec != std::errc{} || result.ptr != value.data() + value.size()) return std::nullopt;
        if (std::strcmp(key, "latency") == 0) parsed.latencyMs = std::clamp(number, 500, 5000);
        else parsed.pbkeylen = number;
      }
      if (parsed.pbkeylen != 16 && parsed.pbkeylen != 24 && parsed.pbkeylen != 32) return std::nullopt;
      if (values.count("streamid")) {
        parsed.streamId = values["streamid"];
        if (parsed.streamId->size() > 512) return std::nullopt;
      }
      if (values.count("passphrase")) {
        parsed.passphrase = values["passphrase"];
        if (parsed.passphrase->size() < 10 || parsed.passphrase->size() > 79) return std::nullopt;
      }
      return parsed;
    }
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

#if OPENSTREAM_HAVE_LIBSRT
bool configureIrlSocket(SRTSOCKET socket, const SrtUrl &url, std::string &error) {
  const auto set = [&](SRT_SOCKOPT option, const void *value, int size, const char *name) {
    if (srt_setsockopt(socket, 0, option, value, size) == 0) return true;
    error = std::string("CONFIG: Could not set SRT ") + name;
    return false;
  };
  const int yes = 1, type = SRTT_LIVE, payload = 188 * 7, sendTimeout = 120;
  const int connectTimeout = 5000, idleTimeout = 10000;
  if (!set(SRTO_TRANSTYPE, &type, sizeof type, "transport type") ||
      !set(SRTO_SENDER, &yes, sizeof yes, "sender mode") ||
      !set(SRTO_PAYLOADSIZE, &payload, sizeof payload, "payload size") ||
      !set(SRTO_SNDTIMEO, &sendTimeout, sizeof sendTimeout, "send timeout") ||
      !set(SRTO_TLPKTDROP, &yes, sizeof yes, "late packet dropping") ||
      !set(SRTO_CONNTIMEO, &connectTimeout, sizeof connectTimeout, "connect timeout") ||
      !set(SRTO_PEERIDLETIMEO, &idleTimeout, sizeof idleTimeout, "peer idle timeout") ||
      !set(SRTO_LATENCY, &url.latencyMs, sizeof url.latencyMs, "latency") ||
      !set(SRTO_PEERLATENCY, &url.latencyMs, sizeof url.latencyMs, "peer latency")) return false;
  if (url.streamId && !set(SRTO_STREAMID, url.streamId->data(),
                          static_cast<int>(url.streamId->size()), "stream ID")) return false;
  // The parser already bounds pbkeylen and passphrase length, so a failure here
  // means this libsrt build lacks encryption support (SRT_ENABLE_ENCRYPTION).
  if (url.passphrase &&
      (srt_setsockopt(socket, 0, SRTO_PBKEYLEN, &url.pbkeylen, sizeof url.pbkeylen) != 0 ||
       srt_setsockopt(socket, 0, SRTO_PASSPHRASE, url.passphrase->data(),
                      static_cast<int>(url.passphrase->size())) != 0)) {
    error = "CONFIG: SRT passphrase encryption is not supported by this build";
    return false;
  }
  return true;
}

std::string irlConnectFailure(int rejectReason) {
  const std::string suffix = " (reject reason " + std::to_string(rejectReason) + ")";
  if (rejectReason == SRT_REJ_BADSECRET || rejectReason == SRT_REJ_UNSECURE)
    return "AUTH: SRT passphrase rejected" + suffix;
  if (rejectReason == SRT_REJ_PEER || rejectReason == 1401 || rejectReason == 1403 || rejectReason >= 2000)
    return "AUTH: SRT publishing rejected; check stream ID and credentials" + suffix;
  if (rejectReason == SRT_REJ_VERSION || rejectReason == SRT_REJ_MESSAGEAPI ||
      rejectReason == SRT_REJ_CONGESTION || rejectReason == SRT_REJ_FILTER ||
      rejectReason == SRT_REJ_GROUP || (rejectReason >= 1000 && rejectReason < 1500 && rejectReason != 1402))
    return "CONFIG: SRT ingest options rejected" + suffix;
  return "TRANSIENT: SRT connection failed or timed out" + suffix;
}
#endif

class NativeSender {
 public:
  NativeSender() {
    sendWorker_ = std::thread(&NativeSender::runSendWorker, this);
  }

  void initializeRuntime() {
#if OPENSTREAM_HAVE_LIBSRT
    srtStarted_ = srt_startup() == 0;
    if (!srtStarted_) {
      logError("libsrt startup failed");
    }
#endif
  }

  ~NativeSender() {
    stopSendWorker();
    disconnect();
#if OPENSTREAM_HAVE_LIBSRT
    if (srtStarted_) {
      srt_cleanup();
      srtStarted_ = false;
    }
#endif
  }

  void advanceLifecycleGeneration(uint64_t generation) {
#if OPENSTREAM_HAVE_LIBSRT
    std::lock_guard<std::mutex> lock(socketMutex_);
    lifecycleGeneration_ = generation;
#else
    (void)generation;
#endif
  }

  bool connect(const std::string &url, uint64_t expectedLifecycleGeneration) {
#if OPENSTREAM_HAVE_LIBSRT
    // Every native side effect of this attempt is scoped to its lifecycle and
    // to the socket it publishes. A cancelled attempt can still be running
    // here (blocked in DNS or srt_connect) after a replacement attempt from
    // another client installed its own socket; it must never tear that down.
    if (!disconnectLifecycle(expectedLifecycleGeneration)) {
      return false;
    }
    setConnectErrorForLifecycle(expectedLifecycleGeneration, "TRANSIENT: SRT connection failed");
    const auto parsed = parseSrtUrl(url);
    if (!parsed) {
      setConnectErrorForLifecycle(expectedLifecycleGeneration, "CONFIG: Invalid SRT publish URL");
      logError("Invalid SRT URL");
      return false;
    }
    if (!srtStarted_) {
      setConnectErrorForLifecycle(expectedLifecycleGeneration, "CONFIG: libsrt runtime unavailable");
      logError("libsrt runtime is unavailable");
      return false;
    }

    const SRTSOCKET socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) {
      logError("Could not create SRT socket");
      return false;
    }
    // From here on, disconnect() cancels this attempt by taking and closing
    // the published socket, after which the attempt owns nothing.
    const uint64_t attempt = setSocketForLifecycle(socket, expectedLifecycleGeneration);
    if (attempt == 0) {
      srt_close(socket);
      return false;
    }

    if (parsed->irl) {
      std::string error;
      if (!configureIrlSocket(socket, *parsed, error)) {
        setConnectErrorForLifecycle(expectedLifecycleGeneration, error);
        releaseAttemptSocket(socket, attempt);
        return false;
      }
    } else {
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
    }

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *result = nullptr;
    if (getaddrinfo(parsed->host.c_str(), parsed->port.c_str(), &hints, &result) != 0) {
      logError("Could not resolve SRT host");
      releaseAttemptSocket(socket, attempt);
      return false;
    }

    // Resolution cannot be interrupted, and a cancelled srt_connect returns
    // quickly once disconnect() closed the socket; stop before the next
    // address instead of retrying on a socket this attempt no longer owns.
    bool connected = false;
    for (addrinfo *candidate = result; candidate != nullptr; candidate = candidate->ai_next) {
      if (!ownsAttemptSocket(socket, attempt)) break;
      if (srt_connect(socket, candidate->ai_addr, static_cast<int>(candidate->ai_addrlen)) == 0) {
        connected = true;
        break;
      }
    }
    freeaddrinfo(result);

    if (!connected) {
      if (parsed->irl) {
        int rejectReason = SRT_REJ_UNKNOWN;
        if (releaseAttemptSocket(socket, attempt, &rejectReason)) {
          setConnectErrorForLifecycle(expectedLifecycleGeneration, irlConnectFailure(rejectReason));
        }
        return false;
      }
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT connect failed: %s", srt_getlasterror_str());
      releaseAttemptSocket(socket, attempt);
      return false;
    }

    // A connect that completed just as it was cancelled has already lost its
    // socket to disconnect(), which closed it.
    return ownsAttemptSocket(socket, attempt);
#else
    (void)url;
    setConnectError("CONFIG: Native SRT library built without libsrt");
    logError("openstream_srt was built without libsrt. Rebuild with OPENSTREAM_ENABLE_LIBSRT=ON.");
    return false;
#endif
  }

  std::string connectError() {
    std::lock_guard<std::mutex> lock(connectErrorMutex_);
    return connectError_;
  }

  std::array<jlong, 5> publisherStats() {
    std::array<jlong, 5> result{};
#if OPENSTREAM_HAVE_LIBSRT
    {
      std::lock_guard<std::mutex> lock(socketMutex_);
      SRT_TRACEBSTATS perf{};
      if (socket_ != SRT_INVALID_SOCK && srt_bistats(socket_, &perf, 0, 1) == 0) {
        result[0] = static_cast<jlong>(perf.byteSentUniqueTotal);
        result[1] = perf.pktSndDropTotal;
        result[2] = perf.pktSndBuf;
        result[3] = perf.msSndBuf;
      }
    }
    std::lock_guard<std::mutex> lock(sendQueueMutex_);
    result[4] = static_cast<jlong>(sendQueue_.size());
#endif
    return result;
  }

  bool listen(const std::string &url, uint64_t expectedLifecycleGeneration) {
#if OPENSTREAM_HAVE_LIBSRT
    disconnect();
    const auto parsed = parseSrtUrl(url);
    if (!parsed) {
      logError("Invalid SRT listener URL");
      return false;
    }
    if (!srtStarted_) {
      logError("libsrt runtime is unavailable");
      return false;
    }

    const SRTSOCKET listenerSocket = srt_create_socket();
    if (listenerSocket == SRT_INVALID_SOCK) {
      logError("Could not create SRT listener socket");
      disconnect();
      return false;
    }
    if (!setListenerSocketForLifecycle(listenerSocket, expectedLifecycleGeneration)) {
      srt_close(listenerSocket);
      return false;
    }

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
    // Keep accept nonblocking. Cancellation is observed through a short epoll
    // wait, and the thread that owns this listener closes it after leaving the
    // wait. Closing it from disconnect() while srt_accept() is blocked can
    // race libsrt's internal accept state and crash in CUDTUnited::accept.
    int receiveSync = 0;
    srt_setsockopt(listenerSocket, 0, SRTO_RCVSYN, &receiveSync, sizeof receiveSync);

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(parsed->portNumber);
    address.sin_addr.s_addr = INADDR_ANY;

    if (srt_bind(listenerSocket, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT bind failed: %s", srt_getlasterror_str());
      closeListenerSocket(listenerSocket);
      return false;
    }
    if (srt_listen(listenerSocket, 1) == SRT_ERROR) {
      __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT listen failed: %s", srt_getlasterror_str());
      closeListenerSocket(listenerSocket);
      return false;
    }

    const int epoll = srt_epoll_create();
    if (epoll == SRT_ERROR) {
      logError("Could not create SRT listener poller");
      closeListenerSocket(listenerSocket);
      return false;
    }
    const int listenerEvents = SRT_EPOLL_IN;
    if (srt_epoll_add_usock(epoll, listenerSocket, &listenerEvents) == SRT_ERROR) {
      logError("Could not register SRT listener with poller");
      srt_epoll_release(epoll);
      closeListenerSocket(listenerSocket);
      return false;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "Waiting for OBS caller on SRT port %s", parsed->port.c_str());
    SRTSOCKET acceptedSocket = SRT_INVALID_SOCK;
    bool cancelled = false;
    while (isLifecycleCurrent(expectedLifecycleGeneration)) {
      SRT_EPOLL_EVENT event{};
      const int ready = srt_epoll_uwait(epoll, &event, 1, kListenerAcceptPollMs);
      if (ready == 0) continue;
      if (ready == SRT_ERROR) {
        if (isLifecycleCurrent(expectedLifecycleGeneration)) {
          __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT listener poll failed: %s", srt_getlasterror_str());
        } else {
          cancelled = true;
        }
        break;
      }
      if (!isLifecycleCurrent(expectedLifecycleGeneration)) {
        cancelled = true;
        break;
      }

      sockaddr_storage peer{};
      int peer_len = sizeof(peer);
      acceptedSocket = srt_accept(listenerSocket, reinterpret_cast<sockaddr *>(&peer), &peer_len);
      if (acceptedSocket != SRT_INVALID_SOCK) break;

      int systemError = 0;
      const int acceptError = srt_getlasterror(&systemError);
      if (acceptError != SRT_EASYNCRCV) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT accept failed: %s", srt_getlasterror_str());
        break;
      }
    }
    srt_epoll_remove_usock(epoll, listenerSocket);
    srt_epoll_release(epoll);
    closeListenerSocket(listenerSocket);
    if (acceptedSocket == SRT_INVALID_SOCK) {
      if (!cancelled && isLifecycleCurrent(expectedLifecycleGeneration)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "SRT accept failed: %s", srt_getlasterror_str());
      }
      return false;
    }
    if (!isLifecycleCurrent(expectedLifecycleGeneration)) {
      srt_close(acceptedSocket);
      return false;
    }
    srt_setsockopt(acceptedSocket, 0, SRTO_SNDTIMEO, &sendTimeoutMs, sizeof sendTimeoutMs);
    srt_setsockopt(acceptedSocket, 0, SRTO_TLPKTDROP, &tooLatePacketDrop, sizeof tooLatePacketDrop);
    srt_setsockopt(acceptedSocket, 0, SRTO_PEERIDLETIMEO, &peerIdleTimeoutMs, sizeof peerIdleTimeoutMs);
    if (!setSocketForLifecycle(acceptedSocket, expectedLifecycleGeneration)) {
      srt_close(acceptedSocket);
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
      if (generation != connectionGeneration_.load(std::memory_order_acquire)) {
        return true;
      }
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
      const bool accessUnitTooLarge = byteCount > kMaximumAccessUnitBytes;
      const bool oversizedAccessUnit = byteCount > kMaximumSendQueueBytes;
      const bool allowIsolatedOversizedAccessUnit =
          !accessUnitTooLarge && oversizedAccessUnit && sendQueue_.empty() && sendQueueBytes_ == 0;
      const bool wouldExceedQueueLimit =
          accessUnitTooLarge ||
          (!allowIsolatedOversizedAccessUnit &&
           (oversizedAccessUnit || sendQueueBytes_ > kMaximumSendQueueBytes - byteCount));
      if (!healthy_.load(std::memory_order_acquire) ||
          sendWorkerStopping_ ||
          wouldExceedQueueLimit) {
        if (healthy_.load(std::memory_order_relaxed)) {
          healthy_ = false;
          connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
          sendQueue_.clear();
          sendQueueBytes_ = 0;
          __android_log_print(
              ANDROID_LOG_WARN,
              kTag,
              accessUnitTooLarge
                  ? "SRT access unit exceeds safety limit; dropping the session"
                  : "SRT send queue saturated; dropping the session to avoid latency growth");
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
    const SRTSOCKET socket = takeSocket();
    if (socket != SRT_INVALID_SOCK) {
      srt_close(socket);
    }
#endif
  }

 private:
  void setConnectError(const std::string &error) {
    std::lock_guard<std::mutex> lock(connectErrorMutex_);
    connectError_ = error;
  }

  std::mutex connectErrorMutex_;
  std::string connectError_;

  struct PendingSend {
    uint64_t generation;
    std::vector<uint8_t> bytes;
  };

  void markGenerationFailed(uint64_t generation) {
#if OPENSTREAM_HAVE_LIBSRT
    std::lock_guard<std::mutex> socketLock(socketMutex_);
    if (generation != connectionGeneration_.load(std::memory_order_acquire)) {
      return;
    }
#endif
    healthy_ = false;
    std::lock_guard<std::mutex> queueLock(sendQueueMutex_);
    sendQueue_.clear();
    sendQueueBytes_ = 0;
  }

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
      }
      const bool sent = sendNow(pending.bytes, pending.generation);
      {
        std::lock_guard<std::mutex> lock(sendQueueMutex_);
        if (pending.generation == connectionGeneration_.load(std::memory_order_acquire)) {
          sendQueueBytes_ -= pending.bytes.size();
        }
      }
      if (!sent) {
        markGenerationFailed(pending.generation);
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

  // Returns the token that identifies this publication of socket_, or 0 when
  // the lifecycle has moved on. Tokens are never reused, so a stale attempt
  // cannot mistake a replacement (even one reusing a socket id) for its own.
  uint64_t setSocketForLifecycle(SRTSOCKET socket, uint64_t expectedLifecycleGeneration) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (lifecycleGeneration_ != expectedLifecycleGeneration) {
      return 0;
    }
    socket_ = socket;
    socketAttempt_ = ++lastSocketAttempt_;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    healthy_.store(true, std::memory_order_release);
    return socketAttempt_;
  }

  SRTSOCKET takeSocket() {
    std::lock_guard<std::mutex> lock(socketMutex_);
    return takeSocketLocked();
  }

  SRTSOCKET takeSocketLocked() {
    const SRTSOCKET socket = socket_;
    socket_ = SRT_INVALID_SOCK;
    socketAttempt_ = 0;
    return socket;
  }

  bool ownsAttemptSocketLocked(SRTSOCKET socket, uint64_t attempt) const {
    return attempt != 0 && socketAttempt_ == attempt && socket_ == socket;
  }

  bool ownsAttemptSocket(SRTSOCKET socket, uint64_t attempt) const {
    std::lock_guard<std::mutex> lock(socketMutex_);
    return ownsAttemptSocketLocked(socket, attempt);
  }

  void retireConnectionLocked() {
    healthy_ = false;
    connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);
    std::lock_guard<std::mutex> queueLock(sendQueueMutex_);
    sendQueue_.clear();
    sendQueueBytes_ = 0;
  }

  // Closes a connect attempt's socket only while that attempt still owns it.
  // Otherwise disconnect() already took and closed it, and socket_ may now
  // belong to a replacement attempt; neither may be touched again. The IRL
  // reject reason is read while ownership guarantees the socket is open.
  bool releaseAttemptSocket(SRTSOCKET socket, uint64_t attempt, int *rejectReason = nullptr) {
    std::lock_guard<std::mutex> ioLock(ioMutex_);
    {
      std::lock_guard<std::mutex> lock(socketMutex_);
      if (!ownsAttemptSocketLocked(socket, attempt)) {
        return false;
      }
      if (rejectReason != nullptr) {
        *rejectReason = srt_getrejectreason(socket);
      }
      takeSocketLocked();
      retireConnectionLocked();
    }
    srt_close(socket);
    return true;
  }

  // disconnect() for one lifecycle only. Returns false, touching nothing, when
  // a newer lifecycle owns the sender.
  bool disconnectLifecycle(uint64_t expectedLifecycleGeneration) {
    std::lock_guard<std::mutex> ioLock(ioMutex_);
    SRTSOCKET socket = SRT_INVALID_SOCK;
    {
      std::lock_guard<std::mutex> lock(socketMutex_);
      if (lifecycleGeneration_ != expectedLifecycleGeneration) {
        return false;
      }
      socket = takeSocketLocked();
      retireConnectionLocked();
    }
    if (socket != SRT_INVALID_SOCK) {
      srt_close(socket);
    }
    return true;
  }

  void setConnectErrorForLifecycle(uint64_t expectedLifecycleGeneration, const std::string &error) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (lifecycleGeneration_ == expectedLifecycleGeneration) {
      setConnectError(error);
    }
  }

  bool setListenerSocketForLifecycle(SRTSOCKET socket, uint64_t expectedLifecycleGeneration) {
    std::lock_guard<std::mutex> lock(socketMutex_);
    if (lifecycleGeneration_ != expectedLifecycleGeneration) {
      return false;
    }
    listener_socket_ = socket;
    return true;
  }

  bool isLifecycleCurrent(uint64_t expectedLifecycleGeneration) const {
    std::lock_guard<std::mutex> lock(socketMutex_);
    return lifecycleGeneration_ == expectedLifecycleGeneration;
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
  uint64_t socketAttempt_ = 0;
  uint64_t lastSocketAttempt_ = 0;
  uint64_t lifecycleGeneration_ = 0;
  bool srtStarted_ = false;
#endif
  static constexpr size_t kMaximumSendQueueBytes = 768 * 1024;
  static constexpr size_t kMaximumAccessUnitBytes = 2 * 1024 * 1024;
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
  uint64_t mediaSessionGeneration = 0;
  bool connected = false;
};

StreamState g_state;

}  // namespace

// Android calls JNI_OnLoad only after the shared object's C++ global
// constructors have completed. libsrt's startup uses process-wide static
// registries, so starting it from NativeSender's global constructor can run
// before those registries are initialized and crash in PacketFilter setup.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
  g_state.sender.initializeRuntime();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeBeginSession(
    JNIEnv *,
    jobject,
    jlong session_generation) {
  const uint64_t generation = static_cast<uint64_t>(session_generation);
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (generation < g_state.mediaSessionGeneration) {
    return;
  }
  g_state.mediaSessionGeneration = generation;
  g_state.connected = false;
  g_state.muxer.reset();
  g_state.codecConfig.clear();
  g_state.audioCodecConfig.clear();
  g_state.sender.advanceLifecycleGeneration(generation);
  g_state.sender.disconnect();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeConnect(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint,
    jlong session_generation) {
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
    if (static_cast<uint64_t>(session_generation) != g_state.mediaSessionGeneration) {
      return JNI_FALSE;
    }
    g_state.muxer.emplace(*codec);
    g_state.muxer->reset();
    g_state.codecConfig.clear();
    g_state.audioCodecConfig.clear();
    g_state.connected = false;
  }
  // connect() tears down only this generation's previous socket; an
  // unconditional disconnect() here could close a newer client's socket.
  const bool connected =
      g_state.sender.connect(urlString, static_cast<uint64_t>(session_generation));
  bool published = false;
  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    if (static_cast<uint64_t>(session_generation) == g_state.mediaSessionGeneration) {
      g_state.connected = connected;
      published = connected;
    }
  }
  if (published) {
    if (const auto parsed = parseSrtUrl(urlString); parsed && parsed->irl) {
      logInfo("Connected IRL SRT MPEG-TS publisher");
      return JNI_TRUE;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Connected SRT MPEG-TS sender to %s", urlString.c_str());
  }
  return published ? JNI_TRUE : JNI_FALSE;
}

// OBS and IRL intentionally share one process-global native session. The app
// must stop the current mode before starting the other.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeConnectError(JNIEnv *env, jobject) {
  return env->NewStringUTF(g_state.sender.connectError().c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeStats(
    JNIEnv *env, jobject, jlong session_generation) {
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  std::array<jlong, 5> values{};
  if (static_cast<uint64_t>(session_generation) == g_state.mediaSessionGeneration && g_state.connected) {
    values = g_state.sender.publisherStats();
  }
  jlongArray result = env->NewLongArray(values.size());
  if (result) env->SetLongArrayRegion(result, 0, values.size(), values.data());
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeListen(
    JNIEnv *env,
    jobject,
    jstring url,
    jstring codec_mime,
    jint,
    jint,
    jint,
    jlong session_generation) {
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
    if (static_cast<uint64_t>(session_generation) != g_state.mediaSessionGeneration) {
      return JNI_FALSE;
    }
    g_state.muxer.emplace(*codec);
    g_state.muxer->reset();
    g_state.codecConfig.clear();
    g_state.audioCodecConfig.clear();
    g_state.connected = false;
  }
  g_state.sender.disconnect();
  const bool connected =
      g_state.sender.listen(urlString, static_cast<uint64_t>(session_generation));
  bool published = false;
  {
    std::lock_guard<std::mutex> lock(g_state.mediaMutex);
    if (static_cast<uint64_t>(session_generation) == g_state.mediaSessionGeneration) {
      g_state.connected = connected;
      published = connected;
    }
  }
  if (published) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Accepted OBS SRT caller at %s", urlString.c_str());
  }
  return published ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeSendVideo(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags,
    jlong session_generation) {
  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (static_cast<uint64_t>(session_generation) != g_state.mediaSessionGeneration ||
      !g_state.connected || !g_state.muxer) {
    return JNI_FALSE;
  }

  std::vector<uint8_t> annexB = normalizeAnnexB(std::move(bytes));
  if ((flags & kMediaCodecBufferFlagCodecConfig) != 0) {
    g_state.codecConfig = std::move(annexB);
    logInfo("Stored codec config for keyframe pre-roll");
    return JNI_TRUE;
  }

  const bool keyFrame = (flags & kMediaCodecBufferFlagKeyFrame) != 0;
  if (keyFrame && !g_state.codecConfig.empty()) {
    std::vector<uint8_t> withConfig;
    withConfig.reserve(g_state.codecConfig.size() + annexB.size());
    withConfig.insert(withConfig.end(), g_state.codecConfig.begin(), g_state.codecConfig.end());
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
Java_dev_openstream_app_stream_SrtNativeBridge_nativeDisconnect(
    JNIEnv *, jobject, jlong session_generation) {
  const uint64_t generation = static_cast<uint64_t>(session_generation);
  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (generation < g_state.mediaSessionGeneration) {
    return;
  }
  g_state.mediaSessionGeneration = generation;
  g_state.sender.advanceLifecycleGeneration(generation);
  g_state.muxer.reset();
  g_state.codecConfig.clear();
  g_state.audioCodecConfig.clear();
  g_state.connected = false;
  g_state.sender.disconnect();
  logInfo("SRT bridge disconnected");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_openstream_app_stream_SrtNativeBridge_nativeSendAudio(
    JNIEnv *env,
    jobject,
    jbyteArray data,
    jlong presentation_time_us,
    jint flags,
    jlong session_generation) {
  const jsize size = env->GetArrayLength(data);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

  std::lock_guard<std::mutex> lock(g_state.mediaMutex);
  if (static_cast<uint64_t>(session_generation) != g_state.mediaSessionGeneration ||
      !g_state.connected || !g_state.muxer) {
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
