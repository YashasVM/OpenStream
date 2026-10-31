#include <obs-module.h>
#include <algorithm>

namespace {
struct RoundedFilter {
  obs_source_t *source;
  gs_effect_t *effect;
  float radius;
};
const char *shader = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform float2 size;
uniform float radius;
sampler_state linearSampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };
struct Vertex { float4 pos : POSITION; float2 uv : TEXCOORD0; };
Vertex VSDefault(Vertex v) { v.pos = mul(float4(v.pos.xyz, 1.0), ViewProj); return v; }
float4 PSDefault(Vertex v) : TARGET {
  float2 q = abs(v.uv * size - size * 0.5) - (size * 0.5 - radius);
  float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  float4 color = image.Sample(linearSampler, v.uv);
  color *= 1.0 - smoothstep(-0.5, 0.5, distance);
  return color;
}
technique Draw { pass { vertex_shader = VSDefault(v); pixel_shader = PSDefault(v); } }
)";
void destroy(void *data) {
  auto *filter = static_cast<RoundedFilter *>(data);
  obs_enter_graphics();
  gs_effect_destroy(filter->effect);
  obs_leave_graphics();
  delete filter;
}
void update(void *data, obs_data_t *settings) {
  static_cast<RoundedFilter *>(data)->radius =
      static_cast<float>(obs_data_get_double(settings, "radius"));
}
void *create(obs_data_t *settings, obs_source_t *source) {
  auto *filter = new RoundedFilter{source, nullptr, 0};
  obs_enter_graphics();
  filter->effect = gs_effect_create(shader, "OpenStream Rounded Corners", nullptr);
  obs_leave_graphics();
  if (!filter->effect) { destroy(filter); return nullptr; }
  update(filter, settings);
  return filter;
}
void render(void *data, gs_effect_t *) {
  auto *filter = static_cast<RoundedFilter *>(data);
  auto *target = obs_filter_get_target(filter->source);
  if (!target) { obs_source_skip_video_filter(filter->source); return; }
  const uint32_t width = obs_source_get_base_width(target);
  const uint32_t height = obs_source_get_base_height(target);
  if (!width || !height || !obs_source_process_filter_begin(filter->source, GS_RGBA, OBS_NO_DIRECT_RENDERING)) return;
  vec2 size;
  vec2_set(&size, static_cast<float>(width), static_cast<float>(height));
  gs_effect_set_vec2(gs_effect_get_param_by_name(filter->effect, "size"), &size);
  gs_effect_set_float(gs_effect_get_param_by_name(filter->effect, "radius"),
      std::clamp(filter->radius, 0.0f, std::min(width, height) * 0.5f));
  obs_source_process_filter_end(filter->source, filter->effect, width, height);
}
}
void openstream_register_rounded_filter() {
  obs_source_info info = {};
  info.id = "openstream_rounded_corners";
  info.type = OBS_SOURCE_TYPE_FILTER;
  info.output_flags = OBS_SOURCE_VIDEO;
  info.get_name = [](void *) { return "OpenStream Rounded Corners"; };
  info.create = create;
  info.destroy = destroy;
  info.update = update;
  info.video_render = render;
  info.get_defaults = [](obs_data_t *settings) { obs_data_set_default_double(settings, "radius", 32.0); };
  info.get_properties = [](void *) {
    auto *properties = obs_properties_create();
    obs_properties_add_float_slider(properties, "radius", "Corner radius (pixels)", 0, 500, 1);
    return properties;
  };
  obs_register_source(&info);
}
