#include <obs-module.h>
#include <util/platform.h>

#include <chrono>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

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

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
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
constexpr const char *kOpenStreamSourceName = "OpenStream Phone Link";

#ifdef _WIN32
using SocketHandle = SOCKET;
constexpr SocketHandle kInvalidSocket = INVALID_SOCKET;
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

std::string first_pairing_host() {
  for (const std::string &address : local_ipv4_addresses()) {
    if (address != "0.0.0.0") {
      return address;
    }
  }
  return "<OBS-PC-IP>";
}

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
    std::ostringstream payload;
    payload << "OPENSTREAM/1 {"
            << "\"type\":\"dev.openstream.listener\","
            << "\"version\":1,"
            << "\"name\":\"" << json_escape(source_name_) << "\","
            << "\"instanceId\":\"" << json_escape(instance_id_) << "\","
            << "\"listenerPort\":" << listener_port_ << ","
            << "\"latencyMs\":" << latency_ms_ << ","
            << "\"bitrateMbps\":" << bitrate_mbps_ << ","
            << "\"busy\":" << ((busy_ && busy_->load()) ? "true" : "false")
            << "}";
    return payload.str();
  }

  void run() {
#ifdef _WIN32
    WSADATA data = {};
    WSAStartup(MAKEWORD(2, 2), &data);
#endif
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

    sockaddr_in destination = {};
    destination.sin_family = AF_INET;
    destination.sin_port = htons(kDiscoveryPort);
    destination.sin_addr.s_addr = INADDR_BROADCAST;

    while (!stop_requested_.load()) {
      const std::string payload = beacon_payload();
      sendto(socket,
             payload.c_str(),
             static_cast<int>(payload.size()),
             0,
             reinterpret_cast<sockaddr *>(&destination),
             sizeof(destination));
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    close_socket(socket);
#ifdef _WIN32
    WSACleanup();
#endif
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
  int listener_port = 9000;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  std::atomic<bool> listener_running = false;
  std::atomic<bool> phone_connected = false;
  std::atomic<bool> stop_requested = false;
  DiscoveryAdvertiser discovery;
  std::thread worker;
  std::mutex settings_mutex;
};

std::string av_error(int error) {
  char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
  av_strerror(error, buffer, sizeof(buffer));
  return buffer;
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

  SwsContext *scaled = sws_getCachedContext(
      sws_ctx->get(),
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
  sws_ctx->reset(scaled);

  const int linesize = width * 4;
  const size_t buffer_size = static_cast<size_t>(linesize) * height;
  if (bgra_buffer->size() != buffer_size) {
    bgra_buffer->assign(buffer_size, 0);
  }

  uint8_t *dst_data[4] = {bgra_buffer->data(), nullptr, nullptr, nullptr};
  int dst_linesize[4] = {linesize, 0, 0, 0};
  const int scaled_rows = sws_scale(sws_ctx->get(),
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

  const int64_t best_timestamp = decoded_frame->best_effort_timestamp;
  const AVRational nanosecond_time_base = {1, 1000000000};
  uint64_t timestamp_ns = 0;
  if (best_timestamp != AV_NOPTS_VALUE) {
    timestamp_ns = static_cast<uint64_t>(
        av_rescale_q(best_timestamp, stream->time_base, nanosecond_time_base));
  } else if (decoded_frame->pts != AV_NOPTS_VALUE) {
    timestamp_ns = static_cast<uint64_t>(
        av_rescale_q(decoded_frame->pts, stream->time_base, nanosecond_time_base));
  } else {
    timestamp_ns = os_gettime_ns();
  }

  struct obs_source_frame obs_frame = {};
  obs_frame.format = VIDEO_FORMAT_BGRA;
  obs_frame.width = static_cast<uint32_t>(width);
  obs_frame.height = static_cast<uint32_t>(height);
  obs_frame.timestamp = timestamp_ns;
  obs_frame.data[0] = bgra_buffer->data();
  obs_frame.linesize[0] = static_cast<uint32_t>(linesize);
  obs_frame.flip = false;
  obs_frame.full_range = false;

  if (decoder_ctx->color_range == AVCOL_RANGE_JPEG) {
    obs_frame.full_range = true;
  }

  obs_source_output_video(ctx->source, &obs_frame);
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
    av_dict_set(&options, "probesize", "32768", 0);
    av_dict_set(&options, "analyzeduration", "100000", 0);

    blog(LOG_INFO,
         "[OpenStream] Listening for Android stream at %s",
         srt_url.c_str());
    int result =
        avformat_open_input(&raw_format_ctx, srt_url.c_str(), nullptr, &options);
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
  openstream_stop_worker(ctx);

  std::string srt_url;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    srt_url = ctx->srt_url;
  }

  ctx->stop_requested = false;
  int listener_port = 9000;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  std::string source_name;
  std::string instance_id;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    listener_port = ctx->listener_port;
    latency_ms = ctx->latency_ms;
    bitrate_mbps = ctx->bitrate_mbps;
    source_name = ctx->device_name.empty() ? kOpenStreamSourceName : ctx->device_name;
    instance_id = ctx->instance_id;
  }

  ctx->listener_running = true;
  ctx->phone_connected = false;
  ctx->worker = std::thread(openstream_worker, ctx, srt_url);
  ctx->discovery.start(listener_port,
                       latency_ms,
                       bitrate_mbps,
                       source_name,
                       instance_id,
                       &ctx->phone_connected);
}

void openstream_update(void *data, obs_data_t *settings) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  std::lock_guard<std::mutex> lock(ctx->settings_mutex);
  ctx->srt_url = obs_data_get_string(settings, "srt_url");
  ctx->device_name = obs_data_get_string(settings, "device_name");
  ctx->listener_port = static_cast<int>(obs_data_get_int(settings, "listener_port"));
  ctx->latency_ms = static_cast<int>(obs_data_get_int(settings, "latency_ms"));
  ctx->bitrate_mbps = static_cast<int>(obs_data_get_int(settings, "bitrate_mbps"));
  if (ctx->srt_url.empty()) {
    ctx->srt_url = "srt://0.0.0.0:" + std::to_string(ctx->listener_port) +
                   "?mode=listener&latency=" + std::to_string(ctx->latency_ms);
  }
  ctx->phone_target_hint = "srt://<OBS-PC-IP>:" + std::to_string(ctx->listener_port) +
                           "?mode=caller&latency=" + std::to_string(ctx->latency_ms);
  ctx->pairing_url = "openstream://connect?host=" + first_pairing_host() +
                     "&port=" + std::to_string(ctx->listener_port) +
                     "&latency=" + std::to_string(ctx->latency_ms) +
                     "&name=OpenStream%20Phone%20Link";
}

void *openstream_create(obs_data_t *settings, obs_source_t *source) {
  auto *ctx = new OpenStreamSource();
  ctx->source = source;
  ctx->instance_id = make_instance_id(source);
  openstream_update(ctx, settings);
  return ctx;
}

void openstream_destroy(void *data) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  openstream_stop_worker(ctx);
  delete ctx;
}

void openstream_defaults(obs_data_t *settings) {
  obs_data_set_default_string(settings, "device_name", kOpenStreamSourceName);
  obs_data_set_default_string(settings, "srt_url", "srt://0.0.0.0:9000?mode=listener&latency=120");
  obs_data_set_default_string(settings, "phone_target_hint", "srt://<OBS-PC-IP>:9000?mode=caller&latency=120");
  obs_data_set_default_string(settings, "pairing_url", "openstream://connect?host=<OBS-PC-IP>&port=9000&latency=120&name=OpenStream%20Phone%20Link");
  obs_data_set_default_int(settings, "listener_port", 9000);
  obs_data_set_default_int(settings, "latency_ms", 120);
  obs_data_set_default_int(settings, "bitrate_mbps", 12);
}

obs_properties_t *openstream_properties(void *) {
  obs_properties_t *props = obs_properties_create();
  obs_properties_add_text(props, "device_name", "Device label", OBS_TEXT_DEFAULT);
  obs_properties_add_int(props, "listener_port", "OBS listener port", 1024, 65535, 1);
  obs_properties_add_text(props, "srt_url", "SRT listener URL", OBS_TEXT_DEFAULT);
  obs_properties_add_text(
      props,
      "phone_target_hint",
      "Phone target",
      OBS_TEXT_INFO);
  obs_properties_add_text(
      props,
      "pairing_url",
      "QR fallback URL",
      OBS_TEXT_INFO);
  obs_properties_add_int_slider(props, "latency_ms", "SRT latency (ms)", 80, 200, 10);
  obs_properties_add_int_slider(props, "bitrate_mbps", "Expected bitrate (Mbps)", 8, 35, 1);
  obs_properties_add_button(props, "connect", "Start listener", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_start_worker(ctx);
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    blog(LOG_INFO, "[OpenStream] Android target hint: %s", ctx->phone_target_hint.c_str());
    return true;
  });
  obs_properties_add_button(props, "disconnect", "Stop listener", [](obs_properties_t *, obs_property_t *, void *data) {
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
    .id = "openstream_source",
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
  obs_register_source(&openstream_source_info);
  blog(LOG_INFO, "[OpenStream] OBS plugin loaded");
  return true;
}
