#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#else
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

#include <obs-module.h>
#include <util/platform.h>

#include <chrono>
#include <atomic>
#include <cstdint>
#include <inttypes.h>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("openstream-obs", "en-US")

namespace {
struct AvPacketDeleter {
  void operator()(AVPacket *packet) const {
    av_packet_free(&packet);
  }
};

struct AvFrameDeleter {
  void operator()(AVFrame *frame) const {
    av_frame_free(&frame);
  }
};

struct AvCodecContextDeleter {
  void operator()(AVCodecContext *ctx) const {
    avcodec_free_context(&ctx);
  }
};

struct AvFormatContextDeleter {
  void operator()(AVFormatContext *ctx) const {
    avformat_close_input(&ctx);
  }
};

struct SwsContextDeleter {
  void operator()(SwsContext *ctx) const {
    sws_freeContext(ctx);
  }
};

using PacketPtr = std::unique_ptr<AVPacket, AvPacketDeleter>;
using FramePtr = std::unique_ptr<AVFrame, AvFrameDeleter>;
using CodecContextPtr = std::unique_ptr<AVCodecContext, AvCodecContextDeleter>;
using FormatContextPtr = std::unique_ptr<AVFormatContext, AvFormatContextDeleter>;
using SwsContextPtr = std::unique_ptr<SwsContext, SwsContextDeleter>;

constexpr int kDiscoveryPort = 51515;
constexpr int kDefaultListenerPort = 9000;
constexpr const char *kOpenStreamSourceName = "OpenStream Phone V4";
constexpr const char *kDiscoveryMulticastAddress = "239.255.42.99";
constexpr const char *kPhoneDiscoveryPrefix = "OPENSTREAM_PHONE/1 ";

#ifdef _WIN32
using SocketHandle = SOCKET;
constexpr SocketHandle kInvalidSocket = INVALID_SOCKET;
bool g_winsock_started = false;
void close_socket(SocketHandle socket) {
  closesocket(socket);
}
#else
using SocketHandle = int;
constexpr SocketHandle kInvalidSocket = -1;
void close_socket(SocketHandle socket) {
  close(socket);
}
#endif

std::string json_escape(const std::string &value) {
  std::string escaped;
  escaped.reserve(value.size());
  for (const char ch : value) {
    switch (ch) {
      case '\\':
        escaped += "\\\\";
        break;
      case '"':
        escaped += "\\\"";
        break;
      case '\n':
        escaped += "\\n";
        break;
      case '\r':
        escaped += "\\r";
        break;
      case '\t':
        escaped += "\\t";
        break;
      default:
        escaped += ch;
        break;
    }
  }
  return escaped;
}

std::string make_instance_id(const void *source) {
  std::ostringstream stream;
  stream << "openstream-" << source << "-" << os_gettime_ns();
  return stream.str();
}

std::vector<std::string> local_ipv4_addresses() {
  std::vector<std::string> addresses;
#ifdef _WIN32
  ULONG buffer_size = 15 * 1024;
  std::vector<uint8_t> buffer(buffer_size);
  IP_ADAPTER_ADDRESSES *adapters =
      reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data());
  ULONG result = GetAdaptersAddresses(AF_INET,
                                      GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                          GAA_FLAG_SKIP_DNS_SERVER,
                                      nullptr,
                                      adapters,
                                      &buffer_size);
  if (result == ERROR_BUFFER_OVERFLOW) {
    buffer.assign(buffer_size, 0);
    adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data());
    result = GetAdaptersAddresses(AF_INET,
                                  GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                      GAA_FLAG_SKIP_DNS_SERVER,
                                  nullptr,
                                  adapters,
                                  &buffer_size);
  }
  if (result == NO_ERROR) {
    for (auto *adapter = adapters; adapter != nullptr; adapter = adapter->Next) {
      if (adapter->OperStatus != IfOperStatusUp) {
        continue;
      }
      for (auto *unicast = adapter->FirstUnicastAddress; unicast != nullptr;
           unicast = unicast->Next) {
        auto *addr = reinterpret_cast<sockaddr_in *>(unicast->Address.lpSockaddr);
        char host[INET_ADDRSTRLEN] = {};
        if (addr && inet_ntop(AF_INET, &addr->sin_addr, host, sizeof(host)) &&
            std::strncmp(host, "127.", 4) != 0) {
          addresses.emplace_back(host);
        }
      }
    }
  }
#else
  ifaddrs *interfaces = nullptr;
  if (getifaddrs(&interfaces) == 0) {
    for (ifaddrs *iface = interfaces; iface != nullptr; iface = iface->ifa_next) {
      if (!iface->ifa_addr || iface->ifa_addr->sa_family != AF_INET ||
          (iface->ifa_flags & IFF_LOOPBACK) != 0) {
        continue;
      }
      auto *addr = reinterpret_cast<sockaddr_in *>(iface->ifa_addr);
      char host[INET_ADDRSTRLEN] = {};
      if (inet_ntop(AF_INET, &addr->sin_addr, host, sizeof(host))) {
        addresses.emplace_back(host);
      }
    }
    freeifaddrs(interfaces);
  }
#endif
  if (addresses.empty()) {
    addresses.emplace_back("0.0.0.0");
  }
  return addresses;
}

std::vector<std::string> discovery_broadcast_addresses() {
  std::vector<std::string> addresses;
#ifdef _WIN32
  ULONG buffer_size = 15 * 1024;
  std::vector<uint8_t> buffer(buffer_size);
  IP_ADAPTER_ADDRESSES *adapters =
      reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data());
  ULONG result = GetAdaptersAddresses(AF_INET,
                                      GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                          GAA_FLAG_SKIP_DNS_SERVER,
                                      nullptr,
                                      adapters,
                                      &buffer_size);
  if (result == ERROR_BUFFER_OVERFLOW) {
    buffer.assign(buffer_size, 0);
    adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data());
    result = GetAdaptersAddresses(AF_INET,
                                  GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                      GAA_FLAG_SKIP_DNS_SERVER,
                                  nullptr,
                                  adapters,
                                  &buffer_size);
  }
  if (result == NO_ERROR) {
    for (auto *adapter = adapters; adapter != nullptr; adapter = adapter->Next) {
      if (adapter->OperStatus != IfOperStatusUp) {
        continue;
      }
      for (auto *unicast = adapter->FirstUnicastAddress; unicast != nullptr;
           unicast = unicast->Next) {
        auto *addr = reinterpret_cast<sockaddr_in *>(unicast->Address.lpSockaddr);
        if (!addr || unicast->OnLinkPrefixLength > 32) {
          continue;
        }
        const uint32_t ip = ntohl(addr->sin_addr.s_addr);
        if ((ip >> 24u) == 127u) {
          continue;
        }
        const uint32_t mask =
            unicast->OnLinkPrefixLength == 0
                ? 0u
                : (0xffffffffu << (32u - unicast->OnLinkPrefixLength));
        const uint32_t broadcast = (ip & mask) | ~mask;
        in_addr broadcast_addr = {};
        broadcast_addr.s_addr = htonl(broadcast);
        char host[INET_ADDRSTRLEN] = {};
        if (inet_ntop(AF_INET, &broadcast_addr, host, sizeof(host))) {
          addresses.emplace_back(host);
        }
      }
    }
  }
#else
  ifaddrs *interfaces = nullptr;
  if (getifaddrs(&interfaces) == 0) {
    for (ifaddrs *iface = interfaces; iface != nullptr; iface = iface->ifa_next) {
      if (!iface->ifa_addr || iface->ifa_addr->sa_family != AF_INET ||
          (iface->ifa_flags & IFF_LOOPBACK) != 0 ||
          (iface->ifa_flags & IFF_BROADCAST) == 0 || !iface->ifa_broadaddr) {
        continue;
      }
      auto *addr = reinterpret_cast<sockaddr_in *>(iface->ifa_broadaddr);
      char host[INET_ADDRSTRLEN] = {};
      if (inet_ntop(AF_INET, &addr->sin_addr, host, sizeof(host))) {
        addresses.emplace_back(host);
      }
    }
    freeifaddrs(interfaces);
  }
#endif
  addresses.emplace_back("255.255.255.255");
  std::sort(addresses.begin(), addresses.end());
  addresses.erase(std::unique(addresses.begin(), addresses.end()), addresses.end());
  return addresses;
}

std::string first_pairing_host() {
  for (const std::string &address : local_ipv4_addresses()) {
    if (address != "0.0.0.0") {
      return address;
    }
  }
  return "<OBS-PC-IP>";
}

std::optional<std::string> json_string_value(const std::string &json, const std::string &key) {
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) {
    return std::nullopt;
  }
  const size_t colon = json.find(':', key_pos + quoted_key.size());
  if (colon == std::string::npos) {
    return std::nullopt;
  }
  const size_t start_quote = json.find('"', colon + 1);
  if (start_quote == std::string::npos) {
    return std::nullopt;
  }
  const size_t end_quote = json.find('"', start_quote + 1);
  if (end_quote == std::string::npos) {
    return std::nullopt;
  }
  return json.substr(start_quote + 1, end_quote - start_quote - 1);
}

std::optional<int> json_int_value(const std::string &json, const std::string &key) {
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) {
    return std::nullopt;
  }
  const size_t colon = json.find(':', key_pos + quoted_key.size());
  if (colon == std::string::npos) {
    return std::nullopt;
  }
  const size_t first_digit = json.find_first_of("0123456789", colon + 1);
  if (first_digit == std::string::npos) {
    return std::nullopt;
  }
  const size_t end = json.find_first_not_of("0123456789", first_digit);
  return std::atoi(json.substr(first_digit, end - first_digit).c_str());
}

struct PhoneDevice {
  std::string name;
  std::string host;
  int port = 9000;
  int latency_ms = 120;
  int width = 1920;
  int height = 1080;
  int fps = 30;
  int bitrate_mbps = 12;
};

class PhoneDiscoveryReceiver {
 public:
  void start() {
    if (running_.exchange(true)) {
      return;
    }
    worker_ = std::thread(&PhoneDiscoveryReceiver::run, this);
  }

  void stop() {
    running_ = false;
    if (worker_.joinable()) {
      worker_.join();
    }
  }

  std::optional<PhoneDevice> latest() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return latest_;
  }

 private:
  void run() {
    SocketHandle socket = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket == kInvalidSocket) {
      blog(LOG_WARNING, "[OpenStream] Could not create phone discovery socket");
      return;
    }

    int reuse = 1;
    setsockopt(socket, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char *>(&reuse), sizeof(reuse));
#ifdef _WIN32
    DWORD timeout = 500;
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
#else
    timeval timeout = {};
    timeout.tv_usec = 500 * 1000;
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
#endif

    sockaddr_in local = {};
    local.sin_family = AF_INET;
    local.sin_port = htons(kDiscoveryPort);
    local.sin_addr.s_addr = INADDR_ANY;
    if (bind(socket, reinterpret_cast<sockaddr *>(&local), sizeof(local)) != 0) {
      blog(LOG_WARNING, "[OpenStream] Could not bind phone discovery UDP port");
      close_socket(socket);
      return;
    }
    ip_mreq multicast_request = {};
    multicast_request.imr_multiaddr.s_addr = inet_addr(kDiscoveryMulticastAddress);
    multicast_request.imr_interface.s_addr = INADDR_ANY;
    setsockopt(socket,
               IPPROTO_IP,
               IP_ADD_MEMBERSHIP,
               reinterpret_cast<const char *>(&multicast_request),
               sizeof(multicast_request));

    while (running_.load()) {
      char buffer[4096] = {};
      sockaddr_in source = {};
      int source_len = sizeof(source);
      const int received =
          recvfrom(socket,
                   buffer,
                   static_cast<int>(sizeof(buffer) - 1),
                   0,
                   reinterpret_cast<sockaddr *>(&source),
                   &source_len);
      if (received <= 0) {
        continue;
      }
      std::string payload(buffer, buffer + received);
      if (payload.rfind(kPhoneDiscoveryPrefix, 0) != 0) {
        continue;
      }
      const std::string json = payload.substr(std::strlen(kPhoneDiscoveryPrefix));
      if (json_string_value(json, "type").value_or("") != "dev.openstream.phone") {
        continue;
      }
      char packet_host[INET_ADDRSTRLEN] = {};
      inet_ntop(AF_INET, &source.sin_addr, packet_host, sizeof(packet_host));
      PhoneDevice device;
      device.name = json_string_value(json, "name").value_or("Android Phone");
      device.host = json_string_value(json, "host").value_or(packet_host);
      if (device.host.empty()) {
        device.host = packet_host;
      }
      device.port = json_int_value(json, "listenerPort").value_or(9000);
      device.latency_ms = json_int_value(json, "latencyMs").value_or(120);
      device.width = json_int_value(json, "width").value_or(1920);
      device.height = json_int_value(json, "height").value_or(1080);
      device.fps = json_int_value(json, "fps").value_or(30);
      device.bitrate_mbps = json_int_value(json, "bitrateMbps").value_or(12);
      {
        std::lock_guard<std::mutex> lock(mutex_);
        latest_ = device;
      }
      blog(LOG_INFO,
           "[OpenStream] Discovered phone %s at %s:%d",
           device.name.c_str(),
           device.host.c_str(),
           device.port);
    }

    close_socket(socket);
  }

  std::atomic<bool> running_ = false;
  std::thread worker_;
  mutable std::mutex mutex_;
  std::optional<PhoneDevice> latest_;
};

class DiscoveryAdvertiser {
 public:
  void start(int listener_port,
             int latency_ms,
             int bitrate_mbps,
             std::string source_name,
             std::string instance_id,
             std::atomic<bool> *busy) {
    stop();
    listener_port_ = listener_port;
    latency_ms_ = latency_ms;
    bitrate_mbps_ = bitrate_mbps;
    source_name_ = std::move(source_name);
    instance_id_ = std::move(instance_id);
    busy_ = busy;
    stop_requested_ = false;
    worker_ = std::thread(&DiscoveryAdvertiser::run, this);
  }

  void stop() {
    stop_requested_ = true;
    if (worker_.joinable()) {
      worker_.join();
    }
  }

 private:
  std::string beacon_payload() const {
    const std::string host = first_pairing_host();
    std::ostringstream payload;
    payload << "OPENSTREAM/1 {"
            << "\"type\":\"dev.openstream.listener\","
            << "\"version\":1,"
            << "\"name\":\"" << json_escape(source_name_) << "\","
            << "\"instanceId\":\"" << json_escape(instance_id_) << "\","
            << "\"host\":\"" << json_escape(host) << "\","
            << "\"listenerPort\":" << listener_port_ << ","
            << "\"latencyMs\":" << latency_ms_ << ","
            << "\"bitrateMbps\":" << bitrate_mbps_ << ","
            << "\"busy\":" << ((busy_ && busy_->load()) ? "true" : "false")
            << "}";
    return payload.str();
  }

  void run() {
    SocketHandle socket = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket == kInvalidSocket) {
      blog(LOG_WARNING, "[OpenStream] Could not create discovery UDP socket");
      return;
    }

    int broadcast = 1;
    setsockopt(socket,
               SOL_SOCKET,
               SO_BROADCAST,
               reinterpret_cast<const char *>(&broadcast),
               sizeof(broadcast));
    int ttl = 1;
    setsockopt(socket,
               IPPROTO_IP,
               IP_MULTICAST_TTL,
               reinterpret_cast<const char *>(&ttl),
               sizeof(ttl));

    sockaddr_in destination = {};
    destination.sin_family = AF_INET;
    destination.sin_port = htons(kDiscoveryPort);

    std::vector<sockaddr_in> destinations;
    for (const std::string &address : discovery_broadcast_addresses()) {
      destination.sin_addr.s_addr = inet_addr(address.c_str());
      destinations.push_back(destination);
    }
    destination.sin_addr.s_addr = inet_addr(kDiscoveryMulticastAddress);
    destinations.push_back(destination);

    while (!stop_requested_.load()) {
      const std::string payload = beacon_payload();
      for (sockaddr_in &target : destinations) {
        sendto(socket,
               payload.c_str(),
               static_cast<int>(payload.size()),
               0,
               reinterpret_cast<sockaddr *>(&target),
               sizeof(target));
      }
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    close_socket(socket);
  }

  std::atomic<bool> stop_requested_ = false;
  std::thread worker_;
  int listener_port_ = 9000;
  int latency_ms_ = 120;
  int bitrate_mbps_ = 12;
  std::string source_name_ = kOpenStreamSourceName;
  std::string instance_id_;
  std::atomic<bool> *busy_ = nullptr;
};

struct OpenStreamSource {
  obs_source_t *source = nullptr;
  std::string srt_url;
  std::string device_name;
  std::string phone_target_hint;
  std::string pairing_url;
  std::string instance_id;
  int listener_port = 0;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  bool listener_enabled = true;
  std::atomic<bool> listener_running = false;
  std::atomic<bool> phone_connected = false;
  std::atomic<bool> stop_requested = false;
  DiscoveryAdvertiser discovery;
  PhoneDiscoveryReceiver phone_discovery;
  std::thread worker;
  std::mutex settings_mutex;
  std::string active_srt_url;
  int active_listener_port = 0;
  int active_latency_ms = 0;
  int active_bitrate_mbps = 0;
  std::string active_device_name;
  uint64_t frames_output = 0;
};

std::string av_error(int error) {
  char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
  av_strerror(error, buffer, sizeof(buffer));
  return buffer;
}

video_colorspace obs_colorspace_for_frame(const AVFrame *frame) {
  switch (frame->colorspace) {
    case AVCOL_SPC_BT709:
      return VIDEO_CS_709;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_FCC:
      return VIDEO_CS_601;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      if (frame->color_trc == AVCOL_TRC_SMPTE2084) {
        return VIDEO_CS_2100_PQ;
      }
      if (frame->color_trc == AVCOL_TRC_ARIB_STD_B67) {
        return VIDEO_CS_2100_HLG;
      }
      return VIDEO_CS_709;
    default:
      return frame->height >= 720 ? VIDEO_CS_709 : VIDEO_CS_601;
  }
}

video_range_type obs_range_for_frame(const AVFrame *frame, AVPixelFormat format) {
  return frame->color_range == AVCOL_RANGE_JPEG || format == AV_PIX_FMT_YUVJ420P
             ? VIDEO_RANGE_FULL
             : VIDEO_RANGE_PARTIAL;
}

video_trc obs_trc_for_frame(const AVFrame *frame) {
  switch (frame->color_trc) {
    case AVCOL_TRC_SMPTE2084:
      return VIDEO_TRC_PQ;
    case AVCOL_TRC_ARIB_STD_B67:
      return VIDEO_TRC_HLG;
    case AVCOL_TRC_IEC61966_2_1:
      return VIDEO_TRC_SRGB;
    default:
      return VIDEO_TRC_DEFAULT;
  }
}

int ffmpeg_interrupt_callback(void *opaque) {
  auto *ctx = static_cast<OpenStreamSource *>(opaque);
  return ctx->stop_requested.load() ? 1 : 0;
}

const char *openstream_get_name(void *) {
  return kOpenStreamSourceName;
}

void openstream_stop_worker(OpenStreamSource *ctx) {
  ctx->stop_requested = true;
  ctx->listener_running = false;
  ctx->phone_connected = false;
  ctx->discovery.stop();
  if (ctx->worker.joinable()) {
    ctx->worker.join();
  }
}

bool open_video_decoder(AVFormatContext *format_ctx,
                        int *video_stream_index,
                        CodecContextPtr *decoder_ctx) {
  const int stream_result = avformat_find_stream_info(format_ctx, nullptr);
  if (stream_result < 0) {
    blog(LOG_WARNING,
         "[OpenStream] Could not read stream info: %s",
         av_error(stream_result).c_str());
    return false;
  }

  const int best_stream = av_find_best_stream(
      format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (best_stream < 0) {
    blog(LOG_WARNING,
         "[OpenStream] No video stream found in SRT input: %s",
         av_error(best_stream).c_str());
    return false;
  }

  AVStream *stream = format_ctx->streams[best_stream];
  const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!decoder) {
    blog(LOG_WARNING,
         "[OpenStream] No FFmpeg decoder found for codec id %d",
         stream->codecpar->codec_id);
    return false;
  }

  CodecContextPtr codec_ctx(avcodec_alloc_context3(decoder));
  if (!codec_ctx) {
    blog(LOG_WARNING, "[OpenStream] Could not allocate decoder context");
    return false;
  }

  int result = avcodec_parameters_to_context(codec_ctx.get(), stream->codecpar);
  if (result < 0) {
    blog(LOG_WARNING,
         "[OpenStream] Could not copy decoder parameters: %s",
         av_error(result).c_str());
    return false;
  }

  codec_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
  result = avcodec_open2(codec_ctx.get(), decoder, nullptr);
  if (result < 0) {
    blog(LOG_WARNING,
         "[OpenStream] Could not open decoder: %s",
         av_error(result).c_str());
    return false;
  }

  *video_stream_index = best_stream;
  *decoder_ctx = std::move(codec_ctx);
  return true;
}

bool output_decoded_frame(OpenStreamSource *ctx,
                          AVStream *stream,
                          AVCodecContext *decoder_ctx,
                          AVFrame *decoded_frame,
                          SwsContextPtr *sws_ctx,
                          std::vector<uint8_t> *bgra_buffer) {
  if (decoded_frame->width <= 0 || decoded_frame->height <= 0) {
    return false;
  }

  const int width = decoded_frame->width;
  const int height = decoded_frame->height;
  const AVPixelFormat source_format =
      static_cast<AVPixelFormat>(decoded_frame->format);

  const bool can_output_i420 =
      (source_format == AV_PIX_FMT_YUV420P || source_format == AV_PIX_FMT_YUVJ420P) &&
      decoded_frame->data[0] && decoded_frame->data[1] && decoded_frame->data[2];
  const bool can_output_nv12 =
      source_format == AV_PIX_FMT_NV12 && decoded_frame->data[0] && decoded_frame->data[1];
  if (can_output_i420 || can_output_nv12) {
    struct obs_source_frame2 yuv_frame = {};
    yuv_frame.format = can_output_i420 ? VIDEO_FORMAT_I420 : VIDEO_FORMAT_NV12;
    yuv_frame.width = static_cast<uint32_t>(width);
    yuv_frame.height = static_cast<uint32_t>(height);
    yuv_frame.timestamp = os_gettime_ns();
    yuv_frame.range = obs_range_for_frame(decoded_frame, source_format);
    yuv_frame.trc = static_cast<uint8_t>(obs_trc_for_frame(decoded_frame));
    yuv_frame.flip = false;
    yuv_frame.data[0] = decoded_frame->data[0];
    yuv_frame.data[1] = decoded_frame->data[1];
    yuv_frame.linesize[0] = static_cast<uint32_t>(decoded_frame->linesize[0]);
    yuv_frame.linesize[1] = static_cast<uint32_t>(decoded_frame->linesize[1]);
    if (can_output_i420) {
      yuv_frame.data[2] = decoded_frame->data[2];
      yuv_frame.linesize[2] = static_cast<uint32_t>(decoded_frame->linesize[2]);
    }

    const video_colorspace colorspace = obs_colorspace_for_frame(decoded_frame);
    if (!video_format_get_parameters_for_format(colorspace,
                                                yuv_frame.range,
                                                yuv_frame.format,
                                                yuv_frame.color_matrix,
                                                yuv_frame.color_range_min,
                                                yuv_frame.color_range_max)) {
      blog(LOG_WARNING, "[OpenStream] Could not calculate OBS YUV color parameters");
      return false;
    }

    obs_source_output_video2(ctx->source, &yuv_frame);
    const uint64_t frames_output = ++ctx->frames_output;
    if (frames_output == 1 || frames_output % 300 == 0) {
      const char *format_name = av_get_pix_fmt_name(source_format);
      blog(LOG_INFO,
           "[OpenStream] Output %" PRIu64 " decoded YUV frame(s) to OBS (%dx%d, source format=%s)",
           frames_output,
           width,
           height,
           format_name ? format_name : "unknown");
    }
    return true;
  }

  SwsContext *current_sws = sws_ctx->get();
  SwsContext *scaled = sws_getCachedContext(
      current_sws,
      width,
      height,
      source_format,
      width,
      height,
      AV_PIX_FMT_BGRA,
      SWS_FAST_BILINEAR,
      nullptr,
      nullptr,
      nullptr);
  if (!scaled) {
    blog(LOG_WARNING, "[OpenStream] Could not create BGRA converter");
    return false;
  }
  if (scaled != current_sws) {
    sws_ctx->reset(scaled);
  }

  const int linesize = width * 4;
  const size_t buffer_size = static_cast<size_t>(linesize) * height;
  if (bgra_buffer->size() != buffer_size) {
    bgra_buffer->assign(buffer_size, 0);
  }

  uint8_t *dst_data[4] = {bgra_buffer->data(), nullptr, nullptr, nullptr};
  int dst_linesize[4] = {linesize, 0, 0, 0};
  const int scaled_rows = sws_scale(scaled,
                                   decoded_frame->data,
                                   decoded_frame->linesize,
                                   0,
                                   height,
                                   dst_data,
                                   dst_linesize);
  if (scaled_rows != height) {
    blog(LOG_WARNING, "[OpenStream] Incomplete frame conversion");
    return false;
  }

  (void)stream;

  struct obs_source_frame obs_frame = {};
  obs_frame.format = VIDEO_FORMAT_BGRA;
  obs_frame.width = static_cast<uint32_t>(width);
  obs_frame.height = static_cast<uint32_t>(height);
  obs_frame.timestamp = os_gettime_ns();
  obs_frame.data[0] = bgra_buffer->data();
  obs_frame.linesize[0] = static_cast<uint32_t>(linesize);
  obs_frame.flip = false;
  obs_frame.full_range = false;

  if (decoder_ctx->color_range == AVCOL_RANGE_JPEG) {
    obs_frame.full_range = true;
  }

  obs_source_output_video(ctx->source, &obs_frame);
  const uint64_t frames_output = ++ctx->frames_output;
  if (frames_output == 1 || frames_output % 300 == 0) {
    const char *format_name = av_get_pix_fmt_name(source_format);
    blog(LOG_INFO,
         "[OpenStream] Output %" PRIu64 " decoded BGRA frame(s) to OBS (%dx%d, source format=%s)",
         frames_output,
         width,
         height,
         format_name ? format_name : "unknown");
  }
  return true;
}

void decode_packets(OpenStreamSource *ctx,
                    AVFormatContext *format_ctx,
                    int video_stream_index,
                    AVCodecContext *decoder_ctx) {
  PacketPtr packet(av_packet_alloc());
  FramePtr frame(av_frame_alloc());
  if (!packet || !frame) {
    blog(LOG_WARNING, "[OpenStream] Could not allocate decode packet/frame");
    return;
  }

  SwsContextPtr sws_ctx(nullptr);
  std::vector<uint8_t> bgra_buffer;
  AVStream *video_stream = format_ctx->streams[video_stream_index];

  while (!ctx->stop_requested.load()) {
    const int read_result = av_read_frame(format_ctx, packet.get());
    if (read_result == AVERROR_EXIT && ctx->stop_requested.load()) {
      break;
    }
    if (read_result == AVERROR(EAGAIN)) {
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
      continue;
    }
    if (read_result < 0) {
      blog(LOG_INFO,
           "[OpenStream] SRT input ended or disconnected: %s",
           av_error(read_result).c_str());
      break;
    }

    if (packet->stream_index != video_stream_index) {
      av_packet_unref(packet.get());
      continue;
    }

    int result = avcodec_send_packet(decoder_ctx, packet.get());
    av_packet_unref(packet.get());
    if (result < 0) {
      blog(LOG_WARNING,
           "[OpenStream] Could not send packet to decoder: %s",
           av_error(result).c_str());
      continue;
    }

    while (!ctx->stop_requested.load()) {
      result = avcodec_receive_frame(decoder_ctx, frame.get());
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
        break;
      }
      if (result < 0) {
        blog(LOG_WARNING,
             "[OpenStream] Could not decode frame: %s",
             av_error(result).c_str());
        break;
      }

      output_decoded_frame(
          ctx, video_stream, decoder_ctx, frame.get(), &sws_ctx, &bgra_buffer);
      av_frame_unref(frame.get());
    }
  }
}

void openstream_worker(OpenStreamSource *ctx, std::string srt_url) {
  avformat_network_init();

  while (!ctx->stop_requested.load()) {
    if (srt_url == "openstream:auto") {
      std::optional<PhoneDevice> phone;
      while (!ctx->stop_requested.load()) {
        phone = ctx->phone_discovery.latest();
        if (phone.has_value()) {
          break;
        }
        blog(LOG_INFO, "[OpenStream] Waiting for Android phone discovery beacon");
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
      }
      if (!phone.has_value()) {
        break;
      }
      srt_url = "srt://" + phone->host + ":" + std::to_string(phone->port) +
                "?mode=caller&latency=" + std::to_string(phone->latency_ms);
      blog(LOG_INFO,
           "[OpenStream] Connecting to discovered phone %s at %s",
           phone->name.c_str(),
           srt_url.c_str());
    }

    AVFormatContext *raw_format_ctx = avformat_alloc_context();
    if (!raw_format_ctx) {
      blog(LOG_WARNING, "[OpenStream] Could not allocate FFmpeg format context");
      break;
    }
    raw_format_ctx->interrupt_callback.callback = ffmpeg_interrupt_callback;
    raw_format_ctx->interrupt_callback.opaque = ctx;

    AVDictionary *options = nullptr;
    av_dict_set(&options, "fflags", "nobuffer", 0);
    av_dict_set(&options, "flags", "low_delay", 0);
    av_dict_set(&options, "probesize", "1048576", 0);
    av_dict_set(&options, "analyzeduration", "1000000", 0);

    blog(LOG_INFO,
         "[OpenStream] Opening Android stream at %s",
         srt_url.c_str());
    const AVInputFormat *mpegts_input = av_find_input_format("mpegts");
    int result =
        avformat_open_input(&raw_format_ctx, srt_url.c_str(), mpegts_input, &options);
    av_dict_free(&options);
    if (result < 0) {
      if (!ctx->stop_requested.load()) {
        blog(LOG_WARNING,
             "[OpenStream] Could not open SRT input: %s",
             av_error(result).c_str());
        if (raw_format_ctx) {
          avformat_free_context(raw_format_ctx);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        continue;
      }
      if (raw_format_ctx) {
        avformat_free_context(raw_format_ctx);
      }
      break;
    }

    FormatContextPtr format_ctx(raw_format_ctx);
    ctx->phone_connected = true;
    ctx->frames_output = 0;

    int video_stream_index = -1;
    CodecContextPtr decoder_ctx;
    if (!open_video_decoder(format_ctx.get(), &video_stream_index, &decoder_ctx)) {
      ctx->phone_connected = false;
      if (!ctx->stop_requested.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        continue;
      }
      break;
    }

    const AVCodecParameters *codecpar =
        format_ctx->streams[video_stream_index]->codecpar;
    blog(LOG_INFO,
         "[OpenStream] Receiving %dx%d video stream codec=%s",
         codecpar->width,
         codecpar->height,
         avcodec_get_name(codecpar->codec_id));

    decode_packets(ctx, format_ctx.get(), video_stream_index, decoder_ctx.get());
    ctx->phone_connected = false;
    if (!ctx->stop_requested.load()) {
      blog(LOG_INFO, "[OpenStream] Waiting for stream reconnect");
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
  }
  ctx->listener_running = false;
  ctx->phone_connected = false;
  blog(LOG_INFO, "[OpenStream] Listener worker exited");
}

void openstream_start_worker(OpenStreamSource *ctx) {
  std::string srt_url;
  int listener_port = kDefaultListenerPort;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  std::string source_name;
  std::string instance_id;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    srt_url = ctx->srt_url;
    listener_port = ctx->listener_port;
    latency_ms = ctx->latency_ms;
    bitrate_mbps = ctx->bitrate_mbps;
    source_name = ctx->device_name.empty() ? kOpenStreamSourceName : ctx->device_name;
    instance_id = ctx->instance_id;
  }

  const bool same_active_config =
      ctx->active_srt_url == srt_url &&
      ctx->active_listener_port == listener_port &&
      ctx->active_latency_ms == latency_ms &&
      ctx->active_bitrate_mbps == bitrate_mbps &&
      ctx->active_device_name == source_name;
  if (same_active_config && ctx->listener_running.load() && ctx->worker.joinable() &&
      !ctx->stop_requested.load()) {
    return;
  }

  openstream_stop_worker(ctx);

  ctx->active_srt_url = srt_url;
  ctx->active_listener_port = listener_port;
  ctx->active_latency_ms = latency_ms;
  ctx->active_bitrate_mbps = bitrate_mbps;
  ctx->active_device_name = source_name;
  ctx->stop_requested = false;
  ctx->listener_running = true;
  ctx->phone_connected = false;
  ctx->worker = std::thread(openstream_worker, ctx, srt_url);
  (void)listener_port;
  (void)latency_ms;
  (void)bitrate_mbps;
  (void)source_name;
  (void)instance_id;
}

void openstream_update(void *data, obs_data_t *settings) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  bool should_start = false;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    ctx->listener_enabled = obs_data_get_bool(settings, "listener_enabled");
    ctx->device_name = obs_data_get_string(settings, "device_name");
    int requested_port = static_cast<int>(obs_data_get_int(settings, "listener_port"));
    if (requested_port <= 0) {
      requested_port = kDefaultListenerPort;
      obs_data_set_int(settings, "listener_port", requested_port);
    }
    ctx->listener_port = requested_port;
    ctx->latency_ms = static_cast<int>(obs_data_get_int(settings, "latency_ms"));
    ctx->bitrate_mbps = static_cast<int>(obs_data_get_int(settings, "bitrate_mbps"));
    ctx->srt_url = "openstream:auto";
    obs_data_set_string(settings, "srt_url", ctx->srt_url.c_str());
    ctx->phone_target_hint = "Waiting for Android app beacon on UDP 51515";
    if (const auto phone = ctx->phone_discovery.latest()) {
      ctx->phone_target_hint = phone->name + "  " + phone->host + ":" + std::to_string(phone->port) +
                               "  " + std::to_string(phone->width) + "x" +
                               std::to_string(phone->height) + "@" + std::to_string(phone->fps);
    }
    ctx->pairing_url = "Open the Android app; OBS will connect to the first discovered phone.";
    should_start = ctx->listener_enabled;
  }
  if (should_start) {
    openstream_start_worker(ctx);
  } else {
    openstream_stop_worker(ctx);
  }
}

void *openstream_create(obs_data_t *settings, obs_source_t *source) {
  auto *ctx = new OpenStreamSource();
  ctx->source = source;
  ctx->instance_id = make_instance_id(source);
  ctx->phone_discovery.start();
  openstream_update(ctx, settings);
  return ctx;
}

void openstream_destroy(void *data) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  openstream_stop_worker(ctx);
  ctx->phone_discovery.stop();
  delete ctx;
}

void openstream_defaults(obs_data_t *settings) {
  obs_data_set_default_bool(settings, "listener_enabled", true);
  obs_data_set_default_string(settings, "device_name", kOpenStreamSourceName);
  obs_data_set_default_string(settings, "srt_url", "openstream:auto");
  obs_data_set_default_string(settings, "phone_target_hint", "Waiting for Android app beacon on UDP 51515");
  obs_data_set_default_string(settings, "pairing_url", "Open the Android app; OBS will connect to the first discovered phone.");
  obs_data_set_default_int(settings, "listener_port", kDefaultListenerPort);
  obs_data_set_default_int(settings, "latency_ms", 120);
  obs_data_set_default_int(settings, "bitrate_mbps", 12);
}

obs_properties_t *openstream_properties(void *) {
  obs_properties_t *props = obs_properties_create();
  obs_properties_add_bool(props, "listener_enabled", "Auto-connect discovered phone");
  obs_properties_add_text(props, "device_name", "Device label", OBS_TEXT_DEFAULT);
  obs_properties_add_int(props, "listener_port", "Phone SRT port", 1024, 65535, 1);
  obs_properties_add_text(props, "srt_url", "SRT mode", OBS_TEXT_INFO);
  obs_properties_add_text(
      props,
      "phone_target_hint",
      "Discovered phone",
      OBS_TEXT_INFO);
  obs_properties_add_text(
      props,
      "pairing_url",
      "Workflow",
      OBS_TEXT_INFO);
  obs_properties_add_int_slider(props, "latency_ms", "SRT latency (ms)", 80, 200, 10);
  obs_properties_add_int_slider(props, "bitrate_mbps", "Expected bitrate (Mbps)", 8, 35, 1);
  obs_properties_add_button(props, "connect", "Connect discovered phone", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_start_worker(ctx);
    if (const auto phone = ctx->phone_discovery.latest()) {
      blog(LOG_INFO, "[OpenStream] Selected Android phone: %s %s:%d", phone->name.c_str(), phone->host.c_str(), phone->port);
    } else {
      blog(LOG_INFO, "[OpenStream] No Android phone discovered yet");
    }
    return true;
  });
  obs_properties_add_button(props, "disconnect", "Disconnect phone", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_stop_worker(ctx);
    blog(LOG_INFO, "[OpenStream] Listener stopped");
    return true;
  });
  return props;
}

obs_source_info openstream_source_info = {
    .id = "openstream_phone_v4_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_ASYNC_VIDEO,
    .get_name = openstream_get_name,
    .create = openstream_create,
    .destroy = openstream_destroy,
    .get_defaults = openstream_defaults,
    .get_properties = openstream_properties,
    .update = openstream_update,
};
}  // namespace

bool obs_module_load(void) {
#ifdef _WIN32
  WSADATA data = {};
  if (WSAStartup(MAKEWORD(2, 2), &data) == 0) {
    g_winsock_started = true;
  } else {
    blog(LOG_WARNING, "[OpenStream] WSAStartup failed; discovery may not advertise");
  }
#endif
  obs_register_source(&openstream_source_info);
  blog(LOG_INFO, "[OpenStream] OBS plugin loaded: phone-discovery V4 YUV output");
  return true;
}

void obs_module_unload(void) {
#ifdef _WIN32
  if (g_winsock_started) {
    WSACleanup();
    g_winsock_started = false;
  }
#endif
}
