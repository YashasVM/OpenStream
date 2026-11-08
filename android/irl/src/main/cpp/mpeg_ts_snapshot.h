// Transport-independent snapshot of app/src/main/cpp/openstream_srt.cpp.
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <map>
#include <optional>
#include <vector>
namespace irl {
constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannelCount = 1;
enum class VideoCodec { Avc, Hevc };
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


}
