// Exercise the production settings adapter without starting OBS or media I/O.
// The dock is not needed for this test; libobs data/properties APIs are.
#include "../src/openstream-source.cpp"
#include <cstdlib>
#include <iostream>

void openstream_register_dock() {}
void openstream_unregister_dock() {}

static void require(bool ok, const char *message) {
  if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}

int main() {
  obs_data_t *settings = obs_data_create();
  openstream_defaults(settings);
  auto context = std::make_shared<OpenStreamSource>();
  context->instance_id = "test-source";
  // No registration: update cannot schedule a worker or open a network socket.
  openstream_update(context.get(), settings);
  require(context->srt_url == "openstream:auto", "automatic pairing default changed");
  require(context->slot_label == "Phone Camera", "new source has a production slot label");
  obs_data_set_bool(settings, "manual_receive", true);
  obs_data_set_int(settings, "listener_port", 9876);
  obs_data_set_int(settings, "latency_ms", 160);
  openstream_update(context.get(), settings);
  require(context->srt_url == "srt://0.0.0.0:9876?mode=listener&latency=160",
          "manual caller has no corresponding OBS listener");
  obs_data_set_string(settings, "slot_label", "Legacy CAM B");
  obs_data_set_string(settings, "slot_id", "saved-legacy-id");
  obs_data_set_bool(settings, "manual_receive", false);
  openstream_update(context.get(), settings);
  require(context->srt_url == "openstream:auto", "automatic pairing cannot be restored");
  require(context->slot_label == "Legacy CAM B" && context->slot_id == "saved-legacy-id",
          "legacy scene identity was lost");
  int existing_camera = 0;
  require(g_camera_lease.acquire(&existing_camera), "could not simulate existing camera");
  openstream_start_worker(context.get());
  require(!context->worker.joinable() && !context->listener_running.load(),
          "production start bypassed the one-camera gate");
  require(context->slot_status.find("Another OpenStream camera") != std::string::npos,
          "blocked camera has no explanation");
  g_camera_lease.release(&existing_camera);
  auto *properties = openstream_properties(context.get());
  require(obs_properties_get(properties, "manual_receive") != nullptr,
          "manual receive has no user-facing control");
  obs_properties_destroy(properties);
  obs_data_release(settings);
  std::cout << "Production source settings tests passed\n";
}
