// Exercise the production settings adapter without starting OBS or media I/O.
// The dock is not needed for this test; libobs data/properties APIs are.
#include "../src/openstream-source.cpp"
#include "contract_helpers.hpp"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <chrono>
#include <cstdlib>
#include <future>
#include <iostream>
#include <string>
#include <thread>

using openstream_test::require;

void openstream_register_dock() {}
void openstream_unregister_dock() {}

// Capture the OBS output boundary without starting its graphics runtime.
static uint32_t captured_video_width = 0;
void obs_source_output_video(obs_source_t *, const struct obs_source_frame *frame) {
  require(frame != nullptr && frame->format == VIDEO_FORMAT_BGRA,
          "converter did not deliver a BGRA frame to OBS");
  captured_video_width = frame->width;
}

struct ReleaseProbe {
  int listener = -1;
  int port = 0;
  std::string response_body = "{\"ok\":true}";
  std::promise<std::string> request;
  std::thread worker;
};

void start_control_probe(ReleaseProbe &probe,
                         std::function<std::string(const std::string &)> response_builder) {
  probe.listener = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  require(probe.listener >= 0, "could not create release probe socket");

  timeval timeout = {};
  timeout.tv_sec = 2;
  require(::setsockopt(probe.listener,
                       SOL_SOCKET,
                       SO_RCVTIMEO,
                       &timeout,
                       sizeof(timeout)) == 0,
          "could not bound release probe accept");

  sockaddr_in address = {};
  address.sin_family = AF_INET;
  address.sin_port = htons(0);
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  require(::bind(probe.listener,
                 reinterpret_cast<sockaddr *>(&address),
                 sizeof(address)) == 0,
          "could not bind release probe socket");
  socklen_t address_length = sizeof(address);
  require(::getsockname(probe.listener,
                        reinterpret_cast<sockaddr *>(&address),
                        &address_length) == 0,
          "could not read release probe port");
  probe.port = ntohs(address.sin_port);
  require(::listen(probe.listener, 1) == 0, "could not listen for release probe");

  const int listener = probe.listener;
  probe.worker = std::thread([listener, &request = probe.request, builder = std::move(response_builder)] {
    sockaddr_in peer = {};
    socklen_t peer_length = sizeof(peer);
    const int client = ::accept(listener,
                                reinterpret_cast<sockaddr *>(&peer),
                                &peer_length);
    if (client < 0) {
      request.set_value({});
      return;
    }

    std::string received;
    char buffer[1024] = {};
    while (received.size() < 16 * 1024) {
      const ssize_t count = ::recv(client, buffer, sizeof(buffer), 0);
      if (count <= 0) break;
      received.append(buffer, static_cast<size_t>(count));
      const size_t header_end = received.find("\r\n\r\n");
      if (header_end == std::string::npos) continue;
      const size_t length_header = received.find("Content-Length:");
      if (length_header == std::string::npos) break;
      const size_t length_start = received.find_first_of("0123456789", length_header + 15);
      if (length_start == std::string::npos) break;
      const size_t length_end = received.find_first_not_of("0123456789", length_start);
      const size_t content_length = std::stoul(received.substr(
          length_start,
          length_end == std::string::npos ? std::string::npos : length_end - length_start));
      if (received.size() >= header_end + 4 + content_length) break;
    }

    const std::string body = builder(received);
    const std::string response =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: " + std::to_string(body.size()) + "\r\n"
        "Connection: close\r\n"
        "\r\n" + body;
    ::send(client, response.data(), response.size(), 0);
    ::close(client);
    request.set_value(std::move(received));
  });
}

void start_release_probe(ReleaseProbe &probe) {
  const std::string body = probe.response_body;
  start_control_probe(probe, [body](const std::string &) { return body; });
}

void test_async_camera_status_loads_phone_lenses_and_zoom_range() {
  ReleaseProbe probe;
  probe.response_body =
      R"({"zoom":0.6,"zoomMin":0.6,"zoomMax":8.0,"currentLens":"0.6×","availableLenses":["0.6×","1×","3×","5×","Front"],"torchAvailable":true,"torchEnabled":false,"cameraControls":{"exposureMin":-12,"exposureMax":12,"aeLock":true,"focusLocked":false}})";
  start_release_probe(probe);
  std::future<std::string> request = probe.request.get_future();

  auto context = std::make_shared<OpenStreamSource>();
  PhoneDevice phone;
  phone.host = "127.0.0.1";
  phone.control_port = probe.port;
  set_active_phone(context.get(), phone);
  require(queue_camera_status_request(context),
          "active phone status request was not queued asynchronously");

  const auto wait_result = request.wait_for(std::chrono::seconds(3));
  const std::string captured = wait_result == std::future_status::ready ? request.get() : "";
  probe.worker.join();
  ::close(probe.listener);
  require(wait_result == std::future_status::ready,
          "async status request did not reach the active phone");
  require(captured.find("GET /status HTTP/1.1") != std::string::npos,
          "lens discovery did not use GET /status");

  const auto cache_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
  while (context->available_camera_lenses.empty() &&
         std::chrono::steady_clock::now() < cache_deadline) {
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  require(context->available_camera_lenses.size() == 5,
          "phone JSON lens array was not loaded into the dynamic lens cache");
  require(context->available_camera_lenses[2] == "3×" &&
              context->current_camera_lens == "0.6×",
          "dynamic lens labels or current lens were parsed incorrectly");
  require(context->camera_zoom == 0.6 && context->camera_zoom_min == 0.6 &&
              context->camera_zoom_max == 8.0,
          "phone zoom value or per-lens limits were not applied");
  require(context->camera_torch_available && !context->camera_torch_enabled,
          "torch availability or state was not parsed from phone status");
  const auto controls = QJsonDocument::fromJson(QByteArray::fromStdString(context->camera_pro_state)).object();
  require(controls.value("exposureMin").toInt() == -12 && controls.value("aeLock").toBool(),
          "pro control capabilities were not preserved in the dock status cache");
  set_active_phone(context.get(), std::nullopt);
  require(context->camera_pro_state == "{}", "pro controls from the prior phone leaked into a new selection");
  context->camera_controls->stop();
}

void test_worker_releases_reservation_on_exit() {
  ReleaseProbe probe;
  start_release_probe(probe);
  std::future<std::string> request = probe.request.get_future();

  OpenStreamSource context;
  context.instance_id = "source-a";
  context.stop_requested = true;

  PhoneDevice phone;
  phone.host = "127.0.0.1";
  phone.control_port = probe.port;
  phone.reservation_token = "source-a-token";
  set_active_phone(&context, phone);

  openstream_worker(&context, "shin:auto", PhoneDiscoveryReceiver::kAutoPhoneId);

  const auto wait_result = request.wait_for(std::chrono::seconds(3));
  const std::string captured = wait_result == std::future_status::ready ? request.get() : "";
  context.camera_controls->stop();
  probe.worker.join();
  ::close(probe.listener);

  require(wait_result == std::future_status::ready,
          "worker exit left the phone reservation without a release request");
  require(captured.find("POST /release HTTP/1.1") != std::string::npos,
          "worker exit sent the wrong reservation control request");
  require(captured.find("sourceInstanceId\":\"source-a\"") != std::string::npos,
          "release request lost the source identity");
  require(captured.find("reservationToken\":\"source-a-token\"") != std::string::npos,
          "release request lost the reservation token");
  require(!context.active_phone.has_value(),
          "worker exit kept a stale active reservation after release");
}

void test_converter_survives_format_changes() {
  OpenStreamSource context;
  CodecContextPtr decoder(avcodec_alloc_context3(nullptr));
  SwsContextPtr converter(nullptr);
  std::vector<uint8_t> pixels;
  for (const auto &[width, format] : std::vector<std::pair<int, AVPixelFormat>>{
           {64, AV_PIX_FMT_RGB24}, {64, AV_PIX_FMT_RGB24},
           {128, AV_PIX_FMT_BGR24}, {32, AV_PIX_FMT_RGB24}, {64, AV_PIX_FMT_BGR24}}) {
    FramePtr frame(av_frame_alloc());
    frame->width = width;
    frame->height = 32;
    frame->format = format;
    require(av_frame_get_buffer(frame.get(), 32) == 0, "could not allocate test frame");
    for (int row = 0; row < frame->height; ++row) {
      std::memset(frame->data[0] + row * frame->linesize[0], 0, width * 3);
    }
    require(output_decoded_frame(&context, decoder.get(), frame.get(), 0,
                                 &converter, &pixels),
            "frame conversion failed after a size change");
    require(captured_video_width == static_cast<uint32_t>(width),
            "OBS received a frame with stale dimensions");
    require(pixels.size() == static_cast<size_t>(width * 32 * 4),
            "converted frame retained the old dimensions");
    require(pixels[0] == 0 && pixels[1] == 0 && pixels[2] == 0 && pixels[3] == 255,
            "converted black frame has incorrect BGRA pixels");
  }
}

void test_usb_route_preference_and_labeling() {
  const auto t0 = std::chrono::steady_clock::now();

  PhoneDevice usb_phone;
  usb_phone.name = "Pixel 8";
  usb_phone.instance_id = "phone-1";
  usb_phone.host = "192.168.42.129";
  usb_phone.transport = "usb";
  usb_phone.last_seen = t0;

  PhoneDevice wifi_phone = usb_phone;
  wifi_phone.host = "192.168.1.50";
  wifi_phone.transport = "wifi";
  wifi_phone.last_seen = t0 + std::chrono::seconds(1);

  require(!merge_discovered_phone(&usb_phone, wifi_phone, wifi_phone.last_seen),
          "Wi-Fi beacon should keep USB primary while retaining Wi-Fi fallback");
  require(usb_phone.fallback_host == "192.168.1.50",
          "USB phone should retain the fresh Wi-Fi fallback host");

  PhoneDevice failover_phone = usb_phone;
  require(apply_fallback_route(failover_phone, wifi_phone.last_seen),
          "Failover helper should switch to the retained Wi-Fi route when USB fails");
  require(failover_phone.host == "192.168.1.50" && !is_usb_phone(failover_phone),
          "Failover route should use Wi-Fi host and transport");

  PhoneDevice usb_refresh = usb_phone;
  usb_refresh.fallback_host.clear();
  usb_refresh.last_seen = t0 + std::chrono::seconds(1);
  require(merge_discovered_phone(&usb_phone, usb_refresh, usb_refresh.last_seen),
          "Fresh USB beacon should refresh an existing USB route");
  require(usb_refresh.fallback_host == "192.168.1.50",
          "USB refresh should carry forward a fresh Wi-Fi fallback host");
  require(merge_discovered_phone(&wifi_phone, usb_refresh, usb_refresh.last_seen),
          "USB beacon should immediately upgrade a Wi-Fi route");

  PhoneDevice wifi_after_hold = wifi_phone;
  wifi_after_hold.last_seen = t0 + kUsbRouteHold + std::chrono::milliseconds(1);
  require(merge_discovered_phone(&usb_phone, wifi_after_hold, wifi_after_hold.last_seen),
          "Wi-Fi fallback should take over once USB hold expires");

  require(phone_label(usb_phone) == "Pixel 8 [USB]  1920x1080@30  Available",
          "USB phone label should include the [USB] badge");
  require(phone_label(wifi_phone) == "Pixel 8  1920x1080@30  Available",
          "Wi-Fi phone label should remain unchanged");

  OpenStreamSource token_ctx;
  const std::string token_a1 = ensure_reservation_token_locked(&token_ctx, "phone-a");
  const std::string token_a2 = ensure_reservation_token_locked(&token_ctx, "phone-a");
  const std::string token_b = ensure_reservation_token_locked(&token_ctx, "phone-b");
  require(token_a1.size() == 32 && token_a1 == token_a2,
          "Same phone should keep its 128-bit reservation token across address changes");
  require(token_b.size() == 32 && token_b != token_a1,
          "Different phone should receive a fresh reservation token");

  const std::string proof_real =
      compute_rebind_proof("0123456789abcdef0123456789abcdef",
                           "192.168.1.50",
                           "fedcba9876543210fedcba9876543210");
  const std::string proof_spoof =
      compute_rebind_proof("0123456789abcdef0123456789abcdef",
                           "192.168.1.99",
                           "fedcba9876543210fedcba9876543210");
  require(proof_real.size() == 64 && proof_spoof.size() == 64 && proof_real != proof_spoof,
          "rebind proof should be a destination-bound 64-char SHA-256 hex digest");
}

void test_reserve_phone_uses_destination_bound_rebind_proof() {
  ReleaseProbe initial_probe;
  start_release_probe(initial_probe);
  std::future<std::string> initial_req = initial_probe.request.get_future();

  OpenStreamSource context;
  context.instance_id = "source-rebind";
  context.slot_id = "slot-1";
  context.slot_label = "CAM A";
  context.bitrate_mbps = 12;

  PhoneDevice phone;
  phone.instance_id = "phone-rebind";
  phone.host = "127.0.0.1";
  phone.control_port = initial_probe.port;

  require(reserve_phone(&context, phone), "initial reserve_phone failed");
  const auto initial_wait = initial_req.wait_for(std::chrono::seconds(3));
  const std::string initial_captured =
      initial_wait == std::future_status::ready ? initial_req.get() : "";
  initial_probe.worker.join();
  ::close(initial_probe.listener);

  require(initial_wait == std::future_status::ready, "initial reserve probe timed out");
  require(initial_captured.find("rebindProof") == std::string::npos,
          "initial reserve should not include a rebindProof");
  const std::string first_token = context.reservation_token;
  require(first_token.size() == 32 && context.reservation_token_host == "127.0.0.1",
          "initial reserve should commit token and bound host");

  // Simulate switching from a previously bound USB IP to a new host (127.0.0.1).
  context.reservation_token_host = "192.168.42.129";

  // A failed rebind attempt must preserve the existing active token and host.
  PhoneDevice unreachable_phone = phone;
  unreachable_phone.control_port = 1;
  require(!reserve_phone(&context, unreachable_phone),
          "reserve_phone to unreachable port should fail");
  require(context.reservation_token == first_token &&
              context.reservation_token_host == "192.168.42.129",
          "failed rebind must not rotate or clobber the active token");

  // A spoofed endpoint replying 200 OK without a valid rebindAck must be rejected
  // without discarding the active token that the real phone still holds.
  ReleaseProbe spoof_probe;
  start_release_probe(spoof_probe);
  std::future<std::string> spoof_req = spoof_probe.request.get_future();

  phone.control_port = spoof_probe.port;
  require(!reserve_phone(&context, phone),
          "unauthenticated 200 OK rebind reply must be rejected");
  const auto spoof_wait = spoof_req.wait_for(std::chrono::seconds(3));
  const std::string spoof_captured =
      spoof_wait == std::future_status::ready ? spoof_req.get() : "";
  spoof_probe.worker.join();
  ::close(spoof_probe.listener);

  require(spoof_wait == std::future_status::ready, "spoof probe timed out");
  require(spoof_captured.find(first_token) == std::string::npos,
          "rebind attempt to spoofed host leaked the active token");
  require(context.reservation_token == first_token &&
              context.reservation_token_host == "192.168.42.129",
          "unauthenticated 200 OK must not rotate or clobber the active token");

  // Returning to the original host while a rebind ACK was lost must still send
  // a destination-bound rebindProof instead of a plain reserve.
  context.reservation_token_host = "127.0.0.1";
  require(context.reservation_rebind_pending,
          "unacknowledged rebind attempt should keep reservation_rebind_pending set");

  // A legitimate phone returning a matching rebindAck succeeds and rotates state.
  ReleaseProbe rebind_probe;
  start_control_probe(rebind_probe, [first_token](const std::string &raw_request) {
    const size_t body_pos = raw_request.find("\r\n\r\n");
    const std::string req_body =
        body_pos == std::string::npos ? "" : raw_request.substr(body_pos + 4);
    const std::string next_token = json_string_value(req_body, "reservationToken").value_or("");
    const std::string ack = compute_rebind_ack(first_token, "127.0.0.1", next_token);
    return "{\"ok\":true,\"reservedBy\":\"source-rebind\",\"rebindAck\":\"" + ack + "\"}";
  });
  std::future<std::string> rebind_req = rebind_probe.request.get_future();

  phone.control_port = rebind_probe.port;
  require(reserve_phone(&context, phone), "authenticated rebind reserve_phone failed");
  const auto rebind_wait = rebind_req.wait_for(std::chrono::seconds(3));
  const std::string rebind_captured =
      rebind_wait == std::future_status::ready ? rebind_req.get() : "";
  rebind_probe.worker.join();
  ::close(rebind_probe.listener);

  require(rebind_wait == std::future_status::ready, "rebind probe timed out");
  require(!context.reservation_rebind_pending,
          "authenticated rebind should clear reservation_rebind_pending");
  require(context.reservation_token.size() == 32 && context.reservation_token != first_token,
          "successful rebind should rotate to a fresh token");
  require(context.reservation_token_host == "127.0.0.1",
          "successful rebind should update bound host");
  require(rebind_captured.find(first_token) == std::string::npos,
          "rebind request leaked the previous active token to the new host");
  const std::string expected_proof =
      compute_rebind_proof(first_token, "127.0.0.1", context.reservation_token);
  require(rebind_captured.find("\"rebindProof\":\"" + expected_proof + "\"") != std::string::npos,
          "rebind request missing expected destination-bound SHA-256 proof");
}

int main() {
  test_usb_route_preference_and_labeling();
  test_reserve_phone_uses_destination_bound_rebind_proof();
  test_converter_survives_format_changes();
  test_worker_releases_reservation_on_exit();

  require(std::string(openstream_source_info.id) == "shin_phone_source",
          "shin source identity changed");
  obs_data_t *settings = obs_data_create();
  openstream_defaults(settings);
  auto context = std::make_shared<OpenStreamSource>();
  context->instance_id = "test-source";
  // No registration: update cannot schedule a worker or open a network socket.
  openstream_update(context.get(), settings);
  require(context->srt_url == "shin:auto", "automatic pairing default changed");
  require(context->slot_label == "Phone Camera", "new source has a production slot label");
  require(!context->enabled, "new source unexpectedly starts enabled");

  obs_data_t *legacy_settings = obs_data_create();
  obs_data_set_bool(legacy_settings, "listener_enabled", true);
  openstream_update(context.get(), legacy_settings);
  require(context->enabled, "legacy listener_enabled setting was not migrated");
  require(obs_data_get_bool(legacy_settings, "enabled"),
          "migrated enabled setting was not persisted");

  obs_data_set_bool(legacy_settings, "enabled", false);
  openstream_update(context.get(), legacy_settings);
  require(!context->enabled,
          "explicit disabled value was overridden by the legacy setting");
  obs_data_release(legacy_settings);

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
  require(context->srt_url == "shin:auto", "automatic pairing cannot be restored");
  require(context->slot_label == "Legacy CAM B" && context->slot_id == "saved-legacy-id",
          "legacy scene identity was lost");
  auto *properties = openstream_properties(context.get());
  require(obs_properties_get(properties, "manual_receive") != nullptr,
          "manual receive has no user-facing control");
  obs_properties_destroy(properties);
  obs_data_release(settings);
  std::cout << "Production source settings tests passed\n";
}
