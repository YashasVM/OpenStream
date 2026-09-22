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

struct ReleaseProbe {
  int listener = -1;
  int port = 0;
  std::promise<std::string> request;
  std::thread worker;
};

void start_release_probe(ReleaseProbe &probe) {
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
  probe.worker = std::thread([listener, &request = probe.request] {
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

    constexpr char response[] =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: 11\r\n"
        "Connection: close\r\n"
        "\r\n"
        "{\"ok\":true}";
    ::send(client, response, sizeof(response) - 1, 0);
    ::close(client);
    request.set_value(std::move(received));
  });
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

int main() {
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
