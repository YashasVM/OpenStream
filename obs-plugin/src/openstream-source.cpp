#include <obs-module.h>

#include <chrono>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

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

struct OpenStreamSource {
  obs_source_t *source = nullptr;
  std::string srt_url;
  std::string device_name;
  std::string phone_target_hint;
  int listener_port = 9000;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  std::atomic<bool> connected = false;
  std::atomic<bool> stop_requested = false;
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
  return "OpenStream Phone";
}

void openstream_stop_worker(OpenStreamSource *ctx) {
  ctx->stop_requested = true;
  ctx->connected = false;
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

  AVFormatContext *raw_format_ctx = avformat_alloc_context();
  if (!raw_format_ctx) {
    blog(LOG_WARNING, "[OpenStream] Could not allocate FFmpeg format context");
    ctx->connected = false;
    return;
  }
  raw_format_ctx->interrupt_callback.callback = ffmpeg_interrupt_callback;
  raw_format_ctx->interrupt_callback.opaque = ctx;

  AVDictionary *options = nullptr;
  av_dict_set(&options, "fflags", "nobuffer", 0);
  av_dict_set(&options, "flags", "low_delay", 0);
  av_dict_set(&options, "probesize", "32768", 0);
  av_dict_set(&options, "analyzeduration", "100000", 0);

  blog(LOG_INFO, "[OpenStream] Listening for Android stream at %s", srt_url.c_str());
  int result = avformat_open_input(&raw_format_ctx, srt_url.c_str(), nullptr, &options);
  av_dict_free(&options);
  if (result < 0) {
    if (!ctx->stop_requested.load()) {
      blog(LOG_WARNING,
           "[OpenStream] Could not open SRT input: %s",
           av_error(result).c_str());
    }
    avformat_free_context(raw_format_ctx);
    ctx->connected = false;
    return;
  }

  FormatContextPtr format_ctx(raw_format_ctx);

  int video_stream_index = -1;
  CodecContextPtr decoder_ctx;
  if (!open_video_decoder(format_ctx.get(), &video_stream_index, &decoder_ctx)) {
    ctx->connected = false;
    return;
  }

  const AVCodecParameters *codecpar =
      format_ctx->streams[video_stream_index]->codecpar;
  blog(LOG_INFO,
       "[OpenStream] Receiving %dx%d video stream codec=%s",
       codecpar->width,
       codecpar->height,
       avcodec_get_name(codecpar->codec_id));

  decode_packets(ctx, format_ctx.get(), video_stream_index, decoder_ctx.get());
  ctx->connected = false;
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
  ctx->connected = true;
  ctx->worker = std::thread(openstream_worker, ctx, srt_url);
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
}

void *openstream_create(obs_data_t *settings, obs_source_t *source) {
  auto *ctx = new OpenStreamSource();
  ctx->source = source;
  openstream_update(ctx, settings);
  return ctx;
}

void openstream_destroy(void *data) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  openstream_stop_worker(ctx);
  delete ctx;
}

void openstream_defaults(obs_data_t *settings) {
  obs_data_set_default_string(settings, "device_name", "Android Phone");
  obs_data_set_default_string(settings, "srt_url", "srt://0.0.0.0:9000?mode=listener&latency=120");
  obs_data_set_default_string(settings, "phone_target_hint", "srt://<OBS-PC-IP>:9000?mode=caller&latency=120");
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

void openstream_tick(void *data, float) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  if (!ctx || !ctx->connected) {
    return;
  }
}

obs_source_info openstream_source_info = {
    .id = "openstream_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_VIDEO,
    .get_name = openstream_get_name,
    .create = openstream_create,
    .destroy = openstream_destroy,
    .get_defaults = openstream_defaults,
    .get_properties = openstream_properties,
    .update = openstream_update,
    .video_tick = openstream_tick,
};
}  // namespace

bool obs_module_load(void) {
  obs_register_source(&openstream_source_info);
  blog(LOG_INFO, "[OpenStream] OBS plugin loaded");
  return true;
}
