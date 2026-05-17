#include <obs-module.h>

#include <atomic>
#include <string>

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("openstream-obs", "en-US")

namespace {
struct OpenStreamSource {
  obs_source_t *source = nullptr;
  std::string srt_url;
  std::string device_name;
  std::string phone_target_hint;
  int listener_port = 9000;
  int latency_ms = 120;
  int bitrate_mbps = 12;
  std::atomic<bool> connected = false;
};

const char *openstream_get_name(void *) {
  return "OpenStream Phone";
}

void openstream_update(void *data, obs_data_t *settings) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
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
    ctx->connected = true;
    blog(LOG_INFO, "[OpenStream] Listener requested for %s", ctx->srt_url.c_str());
    blog(LOG_INFO, "[OpenStream] Android target hint: %s", ctx->phone_target_hint.c_str());
    return true;
  });
  obs_properties_add_button(props, "disconnect", "Stop listener", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    ctx->connected = false;
    blog(LOG_INFO, "[OpenStream] Listener stopped");
    return true;
  });
  return props;
}

void openstream_tick(void *data, float) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  if (!ctx->connected) {
    return;
  }
  // The next milestone wires this state to an FFmpeg/libsrt decode worker and
  // submits frames through obs_source_output_video and obs_source_output_audio.
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
