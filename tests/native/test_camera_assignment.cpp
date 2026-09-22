// Production discovery, ownership, and control integration smoke test.
// This deliberately includes the implementation so it can exercise the
// private source seams without adding test-only hooks to the plugin.
#include "../../obs-plugin/src/openstream-source.cpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <atomic>
#include <cerrno>
#include <sys/time.h>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <iostream>

#include "../../obs-plugin/tests/contract_helpers.hpp"

using openstream_test::require;

void openstream_register_dock() {}
void openstream_unregister_dock() {}

class FakePhone {
 public:
  int port() const { return port_; }

  void start() {
    const int listener = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    require(listener >= 0, "fake phone socket creation failed");
    listener_.store(listener);
    sockaddr_in address = {};
    address.sin_family = AF_INET;
    address.sin_port = htons(0);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    require(::bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0,
            "fake phone bind failed");
    socklen_t length = sizeof(address);
    require(::getsockname(listener_, reinterpret_cast<sockaddr *>(&address), &length) == 0,
            "fake phone port lookup failed");
    port_ = ntohs(address.sin_port);
    require(::listen(listener, 8) == 0, "fake phone listen failed");
    timeval timeout = {0, 100 * 1000};
    require(::setsockopt(listener, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0,
            "fake phone listener timeout failed");
    worker_ = std::thread([this] { run(); });
  }

  void stop() {
    const int listener = listener_.exchange(-1);
    if (listener >= 0) {
      ::shutdown(listener, SHUT_RDWR);
      ::close(listener);
    }
    if (worker_.joinable()) worker_.join();
  }

  ~FakePhone() { stop(); }

  bool saw(const std::string &path) const {
    std::lock_guard<std::mutex> lock(mutex_);
    for (const std::string &request : requests_) {
      if (request.find("POST " + path + " ") != std::string::npos) return true;
    }
    return false;
  }

  bool wait_for(const std::string &path) {
    std::unique_lock<std::mutex> lock(mutex_);
    return changed_.wait_for(lock, std::chrono::seconds(3), [&] {
      for (const std::string &request : requests_) {
        if (request.find("POST " + path + " ") != std::string::npos) return true;
      }
      return false;
    });
  }

 private:
  void run() {
    while (listener_.load() >= 0) {
      const int client = ::accept(listener_.load(), nullptr, nullptr);
      if (client < 0) {
        if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
        break;
      }
      timeval timeout = {1, 0};
      ::setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
      std::string request;
      char buffer[1024] = {};
      constexpr size_t kMaxRequestBytes = 16 * 1024;
      bool complete = false;
      while (!complete && request.size() <= kMaxRequestBytes) {
        const ssize_t count = ::recv(client, buffer, sizeof(buffer), 0);
        if (count <= 0) break;
        request.append(buffer, static_cast<size_t>(count));
        if (request.size() > kMaxRequestBytes) break;
        const size_t header_end = request.find("\r\n\r\n");
        if (header_end != std::string::npos) {
          const size_t marker = request.find("Content-Length:");
          const size_t start = marker == std::string::npos
                                   ? std::string::npos
                                   : request.find_first_of("0123456789", marker);
          if (start == std::string::npos || start > header_end) break;
          const size_t end = request.find_first_not_of("0123456789", start);
          size_t body_length = 0;
          try {
            body_length = std::stoul(request.substr(start, end - start));
          } catch (...) {
            break;
          }
          if (body_length > kMaxRequestBytes || header_end + 4 > kMaxRequestBytes - body_length) break;
          complete = request.size() >= header_end + 4 + body_length;
        }
      }
      constexpr char response[] =
          "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
          "Content-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}";
      if (complete) ::send(client, response, sizeof(response) - 1, 0);
      ::close(client);
      {
        std::lock_guard<std::mutex> lock(mutex_);
        if (requests_.size() == 64) requests_.erase(requests_.begin());
        requests_.push_back(std::move(request));
      }
      changed_.notify_all();
    }
  }

  std::atomic<int> listener_{-1};
  int port_ = 0;
  std::thread worker_;
  mutable std::mutex mutex_;
  std::condition_variable changed_;
  std::vector<std::string> requests_;
};

void advertise(const std::string &id, int control_port,
               const std::string &reserved_by = "", bool busy = false) {
  const int socket_fd = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  require(socket_fd >= 0, "discovery sender socket failed");
  int ttl = 1;
  ::setsockopt(socket_fd, IPPROTO_IP, IP_MULTICAST_TTL, &ttl, sizeof(ttl));
  sockaddr_in destination = {};
  destination.sin_family = AF_INET;
  destination.sin_port = htons(kDiscoveryPort);
  destination.sin_addr.s_addr = inet_addr(kDiscoveryMulticastAddress);
  const std::string payload =
      "SHIN_PHONE/1 {\"type\":\"dev.shin.phone\",\"name\":\"" + id +
      "\",\"instanceId\":\"" + id + "\",\"listenerPort\":9100,\"controlPort\":" +
      std::to_string(control_port) + ",\"busy\":" + (busy ? "true" : "false") +
      ",\"reservedBy\":\"" + reserved_by + "\"}";
  // Discovery startup is asynchronous. Repeat a bounded number of beacons so
  // the test does not depend on which receiver joined the multicast group first.
  for (int attempt = 0; attempt < 3; ++attempt) {
    ::sendto(socket_fd, payload.data(), payload.size(), 0,
             reinterpret_cast<sockaddr *>(&destination), sizeof(destination));
    // The production receiver binds INADDR_ANY. Loopback makes this test
    // deterministic on CI hosts whose multicast route is absent.
    destination.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ::sendto(socket_fd, payload.data(), payload.size(), 0,
             reinterpret_cast<sockaddr *>(&destination), sizeof(destination));
    destination.sin_addr.s_addr = inet_addr(kDiscoveryMulticastAddress);
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
  }
  ::close(socket_fd);
}

int main() {
  FakePhone phone_a;
  FakePhone phone_b;
  FakePhone phone_c;
  phone_a.start();
  phone_b.start();
  phone_c.start();

  OpenStreamSource source_a;
  OpenStreamSource source_b;
  source_a.instance_id = "source-a";
  source_b.instance_id = "source-b";
  source_a.slot_id = "slot-a";
  source_b.slot_id = "slot-b";
  source_a.slot_label = "CAM A";
  source_b.slot_label = "CAM B";
  source_a.phone_discovery.start();
  source_b.phone_discovery.start();
  advertise("phone-a", phone_a.port());
  advertise("phone-b", phone_b.port());
  advertise("phone-c", phone_c.port(), "other-source", true);

  std::optional<PhoneDevice> discovered_a;
  std::optional<PhoneDevice> discovered_b;
  std::optional<PhoneDevice> discovered_c;
  for (int attempt = 0; attempt < 40 && (!discovered_a || !discovered_b || !discovered_c); ++attempt) {
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    for (const PhoneDevice &phone : source_a.phone_discovery.devices()) {
      if (phone.instance_id == "phone-a") discovered_a = phone;
      if (phone.instance_id == "phone-b") discovered_b = phone;
      if (phone.instance_id == "phone-c") discovered_c = phone;
    }
  }
  require(discovered_a.has_value() && discovered_b.has_value() && discovered_c.has_value(),
          "fake phones were not discovered");

  require(!source_a.phone_discovery.select("phone-c", source_a.instance_id).has_value(),
          "a phone reserved by another source was selectable");

  source_b.selected_phone_id = "phone-b";
  const auto selected = control_phone(&source_b);
  require(!selected.has_value(),
          "selected but unreserved phone was exposed to camera controls");
  require(reserve_phone(&source_a, *discovered_a), "source A could not reserve phone A");
  require(!reserve_phone(&source_b, *discovered_a), "source B stole phone A ownership");
  require(!control_phone(&source_b).has_value(), "source B exposed a foreign active phone");
  require(reserve_phone(&source_b, *discovered_b), "source B could not reserve phone B");
  set_active_phone(&source_a, discovered_a);
  set_active_phone(&source_b, discovered_b);
  require(control_phone(&source_a)->instance_id == "phone-a" &&
              control_phone(&source_b)->instance_id == "phone-b",
          "active ownership did not select each source's own phone");
  require(queue_control_command(&source_a, "/zoom", "{\"value\":2}"),
          "source A control was not queued");
  require(queue_control_command(&source_b, "/identify", "{\"label\":\"CAM B\"}"),
          "source B control was not queued");
  require(phone_a.wait_for("/zoom") && !phone_b.saw("/zoom"), "source A control crossed phone boundary");
  require(phone_b.wait_for("/identify") && !phone_a.saw("/identify"), "source B control crossed phone boundary");

  queue_release_phone(&source_a, *discovered_a);
  require(phone_a.wait_for("/release"), "source A release was not delivered");
  openstream_stop_worker(&source_a);
  require(!source_a.active_phone.has_value(), "disconnect did not clear active phone");
  obs_data_t *settings = obs_data_create();
  openstream_defaults(settings);
  obs_data_set_string(settings, "selected_phone_id", "phone-a");
  obs_data_set_string(settings, "slot_id", "slot-a");
  obs_data_set_string(settings, "slot_label", "CAM A");
  obs_data_set_bool(settings, "listener_enabled", false);
  openstream_update(&source_a, settings);
  require(!source_a.active_phone.has_value() && source_a.selected_phone_id == "phone-a" &&
              source_a.slot_id == "slot-a" && source_a.slot_label == "CAM A",
          "unrelated settings update lost camera selection or slot identity");
  obs_data_release(settings);
  require(!source_a.active_phone.has_value(), "settings update revived a disconnected session");
  require(reserve_phone(&source_b, *discovered_a), "released phone A remained owned by source A");

  source_a.phone_discovery.stop();
  source_b.phone_discovery.stop();
  source_a.camera_controls->stop();
  source_b.camera_controls->stop();
  std::cout << "Camera assignment integration test passed\n";
}
