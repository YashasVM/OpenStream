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

#include "async-control-client.hpp"
#include "media-clock.hpp"
#include "openstream-control-api.hpp"
#include <util/platform.h>

#include <chrono>
#include <atomic>
#include <charconv>
#include <cstdint>
#include <inttypes.h>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
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
#include <libavutil/samplefmt.h>
#include <libswscale/swscale.h>
}

OBS_DECLARE_MODULE()

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
constexpr int kDefaultBitrateMbps = 12;
constexpr int kMinBitrateMbps = 8;
constexpr int kMaxBitrateMbps = 50;
constexpr uint64_t kMaximumMediaBacklogNs = 250'000'000;
constexpr int64_t kSrtIoTimeoutUs = 4'500'000;
constexpr int64_t kSrtConnectTimeoutMs = 2'000;
constexpr auto kReconnectReservationWindow = std::chrono::seconds(45);
constexpr const char *kOpenStreamSourceName = "OpenStream V8";
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

std::string url_query_escape(const std::string &value) {
  static constexpr char kHex[] = "0123456789ABCDEF";
  std::string escaped;
  for (const unsigned char ch : value) {
    if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
        (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.' ||
        ch == '~') {
      escaped += static_cast<char>(ch);
    } else {
      escaped += '%';
      escaped += kHex[(ch >> 4) & 0x0f];
      escaped += kHex[ch & 0x0f];
    }
  }
  return escaped;
}

std::string make_instance_id(const void *source) {
  std::ostringstream stream;
  stream << "openstream-" << source << "-" << os_gettime_ns();
  return stream.str();
}

std::string cam_label_for_index(size_t index) {
  std::string suffix;
  do {
    const char letter = static_cast<char>('A' + (index % 26));
    suffix.insert(suffix.begin(), letter);
    index = index / 26;
    if (index > 0) {
      --index;
    }
  } while (index > 0);
  return "CAM " + suffix;
}

struct OpenStreamSource;
std::mutex g_slot_registry_mutex;
std::map<const void *, std::string> g_source_slots;
std::map<obs_source_t *, OpenStreamSource *> g_source_contexts;

std::string next_available_slot_label_locked() {
  for (size_t index = 0; index < 256; ++index) {
    const std::string candidate = cam_label_for_index(index);
    bool used = false;
    for (const auto &entry : g_source_slots) {
      if (entry.second == candidate) {
        used = true;
        break;
      }
    }
    if (!used) {
      return candidate;
    }
  }
  return cam_label_for_index(g_source_slots.size());
}

std::string pairing_url_for_slot(const std::string &host,
                                 int listener_port,
                                 int latency_ms,
                                 int bitrate_mbps,
                                 const std::string &slot_id,
                                 const std::string &slot_label,
                                 const std::string &source_instance_id) {
  std::ostringstream url;
  url << "openstream://connect"
      << "?slotId=" << url_query_escape(slot_id)
      << "&slotLabel=" << url_query_escape(slot_label)
      << "&sourceInstanceId=" << url_query_escape(source_instance_id)
      << "&host=" << url_query_escape(host)
      << "&port=" << listener_port
      << "&latency=" << latency_ms
      << "&bitrateMbps=" << bitrate_mbps
      << "&name=" << url_query_escape(slot_label);
  return url.str();
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
  const char *begin_ptr = json.data() + first_digit;
  const char *end_ptr = json.data() + (end == std::string::npos ? json.size() : end);
  int value = 0;
  const auto parsed = std::from_chars(begin_ptr, end_ptr, value);
  if (parsed.ec != std::errc{} || parsed.ptr != end_ptr) {
    return std::nullopt;
  }
  return value;
}

std::optional<bool> json_bool_value(const std::string &json, const std::string &key) {
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) {
    return std::nullopt;
  }
  const size_t colon = json.find(':', key_pos + quoted_key.size());
  if (colon == std::string::npos) {
    return std::nullopt;
  }
  const size_t value_start = json.find_first_not_of(" \t\r\n", colon + 1);
  if (value_start == std::string::npos) {
    return std::nullopt;
  }
  if (json.compare(value_start, 4, "true") == 0) {
    return true;
  }
  if (json.compare(value_start, 5, "false") == 0) {
    return false;
  }
  return std::nullopt;
}

struct PhoneDevice {
  std::string name;
  std::string instance_id;
  std::string host;
  int port = 9000;
  int control_port = 9001;
  int latency_ms = 120;
  int width = 1920;
  int height = 1080;
  int fps = 30;
  int bitrate_mbps = kDefaultBitrateMbps;
  bool busy = false;
  std::string reserved_by;
  std::string reservation_token;
  std::chrono::steady_clock::time_point last_seen = std::chrono::steady_clock::now();
};

class PhoneDiscoveryReceiver {
 public:
  static constexpr const char *kAutoPhoneId = "auto";

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

  std::vector<PhoneDevice> devices() {
    std::lock_guard<std::mutex> lock(mutex_);
    pruneExpiredLocked();
    std::vector<PhoneDevice> snapshot;
    snapshot.reserve(devices_.size());
    for (const auto &entry : devices_) {
      snapshot.push_back(entry.second);
    }
    std::sort(snapshot.begin(), snapshot.end(), [](const PhoneDevice &lhs, const PhoneDevice &rhs) {
      if (lhs.busy != rhs.busy) {
        return !lhs.busy;
      }
      if (lhs.name != rhs.name) {
        return lhs.name < rhs.name;
      }
      return lhs.instance_id < rhs.instance_id;
    });
    return snapshot;
  }

  std::optional<PhoneDevice> select(const std::string &selected_id,
                                    const std::string &source_instance_id,
                                    const std::string &deprioritized_id = "") {
    std::lock_guard<std::mutex> lock(mutex_);
    pruneExpiredLocked();
    if (selected_id.empty() || selected_id == kAutoPhoneId) {
      std::optional<PhoneDevice> deprioritized;
      for (const auto &entry : devices_) {
        if (entry.second.busy && entry.second.reserved_by != source_instance_id) {
          continue;
        }
        if (!deprioritized_id.empty() && entry.second.instance_id == deprioritized_id) {
          deprioritized = entry.second;
          continue;
        }
        return entry.second;
      }
      return deprioritized;
    }

    const auto found = devices_.find(selected_id);
    if (found == devices_.end() ||
        (found->second.busy && found->second.reserved_by != source_instance_id)) {
      return std::nullopt;
    }
    return found->second;
  }

 private:
  static constexpr auto kDeviceTtl = std::chrono::seconds(5);

  void pruneExpiredLocked() {
    const auto cutoff = std::chrono::steady_clock::now() - kDeviceTtl;
    for (auto it = devices_.begin(); it != devices_.end();) {
      if (it->second.last_seen < cutoff) {
        it = devices_.erase(it);
      } else {
        ++it;
      }
    }
  }

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
#ifdef _WIN32
      int source_len = sizeof(source);
#else
      socklen_t source_len = sizeof(source);
#endif
      const int received =
          recvfrom(socket,
                   buffer,
                   static_cast<int>(sizeof(buffer) - 1),
                   0,
                   reinterpret_cast<sockaddr *>(&source),
                   &source_len);
      if (received <= 0) {
        std::lock_guard<std::mutex> lock(mutex_);
        pruneExpiredLocked();
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
      if (device.name.size() > 128) {
        device.name.resize(128);
      }
      device.instance_id = json_string_value(json, "instanceId").value_or("");
      if (device.instance_id.size() > 256) {
        continue;
      }
      device.host = packet_host;
      device.port = json_int_value(json, "listenerPort").value_or(-1);
      device.control_port = json_int_value(json, "controlPort").value_or(-1);
      if (device.port < 1 || device.port > 65535 ||
          device.control_port < 1 || device.control_port > 65535) {
        continue;
      }
      if (device.instance_id.empty()) {
        device.instance_id = device.host + ":" + std::to_string(device.port);
      }
      device.latency_ms = std::clamp(json_int_value(json, "latencyMs").value_or(120), 80, 200);
      device.width = std::clamp(json_int_value(json, "width").value_or(1920), 16, 8192);
      device.height = std::clamp(json_int_value(json, "height").value_or(1080), 16, 8192);
      device.fps = std::clamp(json_int_value(json, "fps").value_or(30), 1, 240);
      device.bitrate_mbps = std::clamp(
          json_int_value(json, "bitrateMbps").value_or(kDefaultBitrateMbps),
          kMinBitrateMbps,
          kMaxBitrateMbps);
      device.busy = json_bool_value(json, "busy").value_or(false);
      device.reserved_by = json_string_value(json, "reservedBy").value_or("");
      device.last_seen = std::chrono::steady_clock::now();
      {
        std::lock_guard<std::mutex> lock(mutex_);
        pruneExpiredLocked();
        devices_[device.instance_id] = device;
      }
      blog(LOG_INFO,
           "[OpenStream] Discovered phone %s at %s:%d%s",
           device.name.c_str(),
           device.host.c_str(),
           device.port,
           device.busy ? " (busy)" : "");
    }

    close_socket(socket);
  }

  std::atomic<bool> running_ = false;
  std::thread worker_;
  mutable std::mutex mutex_;
  std::map<std::string, PhoneDevice> devices_;
};

class DiscoveryAdvertiser {
 public:
  void start(int listener_port,
             int latency_ms,
             int bitrate_mbps,
             std::string source_name,
             std::string instance_id,
             std::string slot_id,
             std::string slot_label,
             std::string pairing_url,
             std::atomic<bool> *busy) {
    stop();
    listener_port_ = listener_port;
    latency_ms_ = latency_ms;
    bitrate_mbps_ = bitrate_mbps;
    source_name_ = std::move(source_name);
    instance_id_ = std::move(instance_id);
    slot_id_ = std::move(slot_id);
    slot_label_ = std::move(slot_label);
    pairing_url_ = std::move(pairing_url);
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
            << "\"sourceInstanceId\":\"" << json_escape(instance_id_) << "\","
            << "\"slotId\":\"" << json_escape(slot_id_) << "\","
            << "\"slotLabel\":\"" << json_escape(slot_label_) << "\","
            << "\"host\":\"" << json_escape(host) << "\","
            << "\"listenerPort\":" << listener_port_ << ","
            << "\"latencyMs\":" << latency_ms_ << ","
            << "\"bitrateMbps\":" << bitrate_mbps_ << ","
            << "\"pairingUrl\":\"" << json_escape(pairing_url_) << "\","
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
  int bitrate_mbps_ = kDefaultBitrateMbps;
  std::string source_name_ = kOpenStreamSourceName;
  std::string instance_id_;
  std::string slot_id_;
  std::string slot_label_ = "CAM A";
  std::string pairing_url_;
  std::atomic<bool> *busy_ = nullptr;
};

struct OpenStreamSource {
  obs_source_t *source = nullptr;
  std::string srt_url;
  std::string device_name;
  std::string phone_target_hint;
  std::string pairing_hint;
  std::string pairing_url;
  std::string instance_id;
  std::string slot_id;
  std::string slot_label = "CAM A";
  std::string slot_status = "Empty Slot";
  std::string selected_phone_id = PhoneDiscoveryReceiver::kAutoPhoneId;
  int listener_port = 0;
  int latency_ms = 120;
  int bitrate_mbps = kDefaultBitrateMbps;
  bool listener_enabled = true;
  std::atomic<bool> listener_running = false;
  std::atomic<bool> phone_connected = false;
  std::atomic<bool> slot_busy = false;
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
  std::string active_slot_id;
  std::string active_slot_label;
  std::string active_selected_phone_id = PhoneDiscoveryReceiver::kAutoPhoneId;
  std::optional<PhoneDevice> active_phone;
  uint64_t frames_output = 0;
  uint64_t stale_video_frames = 0;
  uint64_t stale_audio_frames = 0;
  double last_cam_zoom = 1.0;
  std::shared_ptr<AsyncControlClient> camera_controls =
      std::make_shared<AsyncControlClient>();
};

bool send_control_command(const std::string &host, int port,
                          const std::string &path, const std::string &body);
std::optional<PhoneDevice> control_phone(OpenStreamSource *ctx);

bool queue_control_command(OpenStreamSource *ctx, const std::string &path,
                           const std::string &body) {
  if (!ctx || path.empty() || path.front() != '/' || body.size() > 8192) return false;
  const auto phone = control_phone(ctx);
  if (!phone.has_value()) return false;
  const auto client = ctx->camera_controls;
  const std::string host = phone->host;
  const int port = phone->control_port;
  const bool queued = client->post([host, port, path, body] {
    if (!send_control_command(host, port, path, body)) {
      blog(LOG_WARNING, "[OpenStream] Camera command %s failed", path.c_str());
    }
  });
  if (!queued) {
    blog(LOG_WARNING,
         "[OpenStream] Camera command %s dropped: control queue is full or stopping",
         path.c_str());
  }
  return queued;
}

void set_slot_status(OpenStreamSource *ctx, std::string status) {
  std::lock_guard<std::mutex> lock(ctx->settings_mutex);
  ctx->slot_status = std::move(status);
}

bool send_control_command(const std::string &host, int port,
                          const std::string &path, const std::string &body) {
  if (port < 1 || port > 65535 || body.size() > 8192 || path.empty() || path.front() != '/') {
    return false;
  }
  SocketHandle sock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (sock == kInvalidSocket) return false;

#ifdef _WIN32
  DWORD timeout = 1000;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
#else
  timeval timeout = {};
  timeout.tv_sec = 1;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
#endif

  sockaddr_in addr = {};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
    close_socket(sock);
    return false;
  }

  if (connect(sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
    close_socket(sock);
    return false;
  }

  std::ostringstream request;
  request << "POST " << path << " HTTP/1.1\r\n"
          << "Host: " << host << ":" << port << "\r\n"
          << "Content-Type: application/json\r\n"
          << "Content-Length: " << body.size() << "\r\n"
          << "Connection: close\r\n"
          << "\r\n"
          << body;
  const std::string req = request.str();
  size_t sent_total = 0;
  while (sent_total < req.size()) {
    const int sent = send(sock,
                          req.data() + sent_total,
                          static_cast<int>(req.size() - sent_total),
                          0);
    if (sent <= 0) {
      close_socket(sock);
      return false;
    }
    sent_total += static_cast<size_t>(sent);
  }

  constexpr size_t kMaxResponseBytes = 8192;
  std::string response;
  response.reserve(1024);
  char response_chunk[512];
  const auto response_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
  while (response.size() < kMaxResponseBytes) {
    if (std::chrono::steady_clock::now() >= response_deadline) break;
    const size_t remaining_capacity = kMaxResponseBytes - response.size();
    const size_t receive_capacity =
        remaining_capacity < sizeof(response_chunk) ? remaining_capacity : sizeof(response_chunk);
    const int received = recv(sock,
                              response_chunk,
                              static_cast<int>(receive_capacity),
                              0);
    if (received <= 0) {
      break;
    }
    response.append(response_chunk, static_cast<size_t>(received));
  }
  close_socket(sock);

  const size_t header_end = response.find("\r\n\r\n");
  if (header_end == std::string::npos ||
      (response.rfind("HTTP/1.1 200 ", 0) != 0 && response.rfind("HTTP/1.0 200 ", 0) != 0)) {
    return false;
  }

  const size_t length_header = response.find("Content-Length:");
  if (length_header == std::string::npos || length_header > header_end) {
    return false;
  }
  const size_t length_start = response.find_first_of("0123456789", length_header + 15);
  if (length_start == std::string::npos || length_start > header_end) {
    return false;
  }
  const size_t length_end = response.find_first_not_of("0123456789", length_start);
  size_t content_length = 0;
  const char *length_begin_ptr = response.data() + length_start;
  const char *length_end_ptr = response.data() +
      (length_end == std::string::npos ? response.size() : length_end);
  const auto parsed_length = std::from_chars(length_begin_ptr, length_end_ptr, content_length);
  if (parsed_length.ec != std::errc{} || parsed_length.ptr != length_end_ptr ||
      content_length > kMaxResponseBytes) {
    return false;
  }
  const size_t body_start = header_end + 4;
  if (body_start > response.size() || response.size() - body_start < content_length) {
    return false;
  }
  const std::string response_body = response.substr(body_start, content_length);
  return json_bool_value(response_body, "ok").value_or(false);
}

void set_active_phone(OpenStreamSource *ctx, std::optional<PhoneDevice> phone) {
  std::lock_guard<std::mutex> lock(ctx->settings_mutex);
  ctx->slot_busy = phone.has_value();
  ctx->active_phone = std::move(phone);
}

std::optional<PhoneDevice> control_phone(OpenStreamSource *ctx) {
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    if (ctx->active_phone.has_value()) {
      return ctx->active_phone;
    }
    if (!ctx->selected_phone_id.empty() &&
        ctx->selected_phone_id != PhoneDiscoveryReceiver::kAutoPhoneId) {
      return ctx->phone_discovery.select(ctx->selected_phone_id, ctx->instance_id);
    }
  }
  return std::nullopt;
}

std::string phone_label(const PhoneDevice &phone) {
  std::ostringstream label;
  label << phone.name << "  "
        << phone.width << "x" << phone.height << "@" << phone.fps;
  if (phone.busy) {
    label << "  Busy";
  } else {
    label << "  Available";
  }
  return label.str();
}

bool reserve_phone(OpenStreamSource *ctx, PhoneDevice &phone) {
  std::ostringstream body;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    phone.reservation_token =
        ctx->instance_id + "-" + std::to_string(os_gettime_ns());
    body << "{\"sourceInstanceId\":\"" << json_escape(ctx->instance_id) << "\","
         << "\"reservationToken\":\"" << json_escape(phone.reservation_token) << "\","
         << "\"slotId\":\"" << json_escape(ctx->slot_id) << "\","
         << "\"slotLabel\":\"" << json_escape(ctx->slot_label) << "\","
         << "\"bitrateMbps\":" << ctx->bitrate_mbps << "}";
  }
  return send_control_command(phone.host, phone.control_port, "/reserve", body.str());
}

void queue_release_phone(OpenStreamSource *ctx, const PhoneDevice &phone) {
  if (phone.reservation_token.empty()) return;
  const auto client = ctx->camera_controls;
  const std::string host = phone.host;
  const int port = phone.control_port;
  const std::string source_instance_id = ctx->instance_id;
  const std::string reservation_token = phone.reservation_token;
  const bool queued = client->post_urgent(
      [host, port, source_instance_id, reservation_token] {
        const std::string body =
            "{\"sourceInstanceId\":\"" + json_escape(source_instance_id) +
            "\",\"reservationToken\":\"" + json_escape(reservation_token) + "\"}";
        if (send_control_command(host, port, "/release", body)) {
          return true;
        }
        blog(LOG_WARNING, "[OpenStream] Camera reservation release failed; retrying");
        return false;
      });
  if (!queued) {
    blog(LOG_WARNING,
         "[OpenStream] Camera reservation release could not be queued: control executor stopped");
  }
}

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
  std::optional<PhoneDevice> reserved_phone;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    reserved_phone = ctx->active_phone;
  }
  if (reserved_phone.has_value()) {
    queue_release_phone(ctx, *reserved_phone);
  }
  set_active_phone(ctx, std::nullopt);
  set_slot_status(ctx, "Offline");
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

bool open_audio_decoder(AVFormatContext *format_ctx,
                        int *audio_stream_index,
                        CodecContextPtr *decoder_ctx) {
  const int best_stream = av_find_best_stream(
      format_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
  if (best_stream < 0) {
    blog(LOG_INFO, "[OpenStream] No audio stream found (video-only mode)");
    *audio_stream_index = -1;
    return false;
  }

  AVStream *stream = format_ctx->streams[best_stream];
  const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!decoder) {
    blog(LOG_WARNING,
         "[OpenStream] No audio decoder found for codec id %d",
         av_error(best_stream).c_str());
    *audio_stream_index = -1;
    return false;
  }

  CodecContextPtr codec_ctx(avcodec_alloc_context3(decoder));
  if (!codec_ctx) {
    *audio_stream_index = -1;
    return false;
  }

  int result = avcodec_parameters_to_context(codec_ctx.get(), stream->codecpar);
  if (result < 0) {
    *audio_stream_index = -1;
    return false;
  }

  result = avcodec_open2(codec_ctx.get(), decoder, nullptr);
  if (result < 0) {
    blog(LOG_WARNING,
         "[OpenStream] Could not open audio decoder: %s",
         av_error(result).c_str());
    *audio_stream_index = -1;
    return false;
  }

  *audio_stream_index = best_stream;
  *decoder_ctx = std::move(codec_ctx);
  blog(LOG_INFO,
       "[OpenStream] Opened audio decoder: %s, %d Hz, %d channels",
       avcodec_get_name(stream->codecpar->codec_id),
       stream->codecpar->sample_rate,
       stream->codecpar->ch_layout.nb_channels);
  return true;
}

std::optional<int64_t> source_timestamp_ns(const AVFrame *frame,
                                           const AVStream *stream) {
  if (!frame || !stream) return std::nullopt;
  const int64_t timestamp = frame->best_effort_timestamp != AV_NOPTS_VALUE
                                ? frame->best_effort_timestamp
                                : frame->pts;
  if (timestamp == AV_NOPTS_VALUE) return std::nullopt;
  return av_rescale_q(timestamp, stream->time_base, AVRational{1, 1'000'000'000});
}

bool stream_timestamp_is_stale(uint64_t timestamp_ns) {
  const uint64_t now_ns = os_gettime_ns();
  return timestamp_ns < now_ns && now_ns - timestamp_ns > kMaximumMediaBacklogNs;
}

bool output_decoded_frame(OpenStreamSource *ctx,
                          AVCodecContext *decoder_ctx,
                          AVFrame *decoded_frame,
                          uint64_t timestamp_ns,
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
    yuv_frame.timestamp = timestamp_ns;
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

audio_format obs_audio_format_for_sample_format(AVSampleFormat sample_format) {
  switch (sample_format) {
    case AV_SAMPLE_FMT_U8:
      return AUDIO_FORMAT_U8BIT;
    case AV_SAMPLE_FMT_S16:
      return AUDIO_FORMAT_16BIT;
    case AV_SAMPLE_FMT_S32:
      return AUDIO_FORMAT_32BIT;
    case AV_SAMPLE_FMT_FLT:
      return AUDIO_FORMAT_FLOAT;
    case AV_SAMPLE_FMT_U8P:
      return AUDIO_FORMAT_U8BIT_PLANAR;
    case AV_SAMPLE_FMT_S16P:
      return AUDIO_FORMAT_16BIT_PLANAR;
    case AV_SAMPLE_FMT_S32P:
      return AUDIO_FORMAT_32BIT_PLANAR;
    case AV_SAMPLE_FMT_FLTP:
      return AUDIO_FORMAT_FLOAT_PLANAR;
    default:
      return AUDIO_FORMAT_UNKNOWN;
  }
}

speaker_layout obs_speaker_layout_for_channels(int channels) {
  switch (channels) {
    case 1:
      return SPEAKERS_MONO;
    case 2:
      return SPEAKERS_STEREO;
    default:
      return SPEAKERS_UNKNOWN;
  }
}

void decode_packets(OpenStreamSource *ctx,
                    AVFormatContext *format_ctx,
                    int video_stream_index,
                    AVCodecContext *video_decoder_ctx,
                    int audio_stream_index,
                    AVCodecContext *audio_decoder_ctx) {
  PacketPtr packet(av_packet_alloc());
  FramePtr frame(av_frame_alloc());
  FramePtr audio_frame(av_frame_alloc());
  if (!packet || !frame || !audio_frame) {
    blog(LOG_WARNING, "[OpenStream] Could not allocate decode packet/frame");
    return;
  }

  SwsContextPtr sws_ctx(nullptr);
  std::vector<uint8_t> bgra_buffer;
  AVStream *video_stream = format_ctx->streams[video_stream_index];
  AVStream *audio_stream = audio_stream_index >= 0
                               ? format_ctx->streams[audio_stream_index]
                               : nullptr;
  MediaClock media_clock;
  uint64_t audio_frames_output = 0;

  const auto drain_video = [&]() -> int {
    while (!ctx->stop_requested.load()) {
      const int result = avcodec_receive_frame(video_decoder_ctx, frame.get());
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
        return 0;
      }
      if (result < 0) {
        blog(LOG_WARNING,
             "[OpenStream] Could not decode frame: %s",
             av_error(result).c_str());
        return result;
      }
      const auto source_ns = source_timestamp_ns(frame.get(), video_stream);
      const auto timestamp_ns = source_ns
                                    ? media_clock.map(*source_ns, os_gettime_ns())
                                    : std::nullopt;
      if (!timestamp_ns) {
        blog(LOG_WARNING,
             "[OpenStream] Dropping video frame without a usable source timestamp (media gap surfaced)");
        av_frame_unref(frame.get());
        continue;
      }
      if (stream_timestamp_is_stale(*timestamp_ns)) {
        const uint64_t dropped = ++ctx->stale_video_frames;
        if (dropped == 1 || dropped % 60 == 0) {
          blog(LOG_WARNING,
               "[OpenStream] Dropped stale video frame(s): %" PRIu64
               " (receiver backlog exceeded %u ms; media gap surfaced)",
               dropped,
               static_cast<unsigned>(kMaximumMediaBacklogNs / 1'000'000));
        }
        av_frame_unref(frame.get());
        continue;
      }
      output_decoded_frame(ctx,
                           video_decoder_ctx,
                           frame.get(),
                           *timestamp_ns,
                           &sws_ctx,
                           &bgra_buffer);
      av_frame_unref(frame.get());
    }
    return AVERROR_EXIT;
  };

  const auto drain_audio = [&]() -> int {
    while (!ctx->stop_requested.load()) {
      const int result = avcodec_receive_frame(audio_decoder_ctx, audio_frame.get());
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
        return 0;
      }
      if (result < 0) {
        return result;
      }

      const AVSampleFormat sample_format =
          static_cast<AVSampleFormat>(audio_frame->format);
      const audio_format obs_format = obs_audio_format_for_sample_format(sample_format);
      const int sample_rate =
          audio_frame->sample_rate > 0 ? audio_frame->sample_rate : audio_decoder_ctx->sample_rate;
      const int channels =
          audio_frame->ch_layout.nb_channels > 0
              ? audio_frame->ch_layout.nb_channels
              : audio_decoder_ctx->ch_layout.nb_channels;
      const speaker_layout speakers = obs_speaker_layout_for_channels(channels);
      if (obs_format == AUDIO_FORMAT_UNKNOWN || speakers == SPEAKERS_UNKNOWN ||
          sample_rate <= 0 || audio_frame->nb_samples <= 0) {
        av_frame_unref(audio_frame.get());
        return AVERROR(EINVAL);
      }

      const auto source_ns = source_timestamp_ns(audio_frame.get(), audio_stream);
      const auto timestamp_ns = source_ns
                                    ? media_clock.map(*source_ns, os_gettime_ns())
                                    : std::nullopt;
      if (!timestamp_ns) {
        blog(LOG_WARNING,
             "[OpenStream] Dropping audio frame without a usable source timestamp (media gap surfaced)");
        av_frame_unref(audio_frame.get());
        continue;
      }
      if (stream_timestamp_is_stale(*timestamp_ns)) {
        const uint64_t dropped = ++ctx->stale_audio_frames;
        if (dropped == 1 || dropped % 100 == 0) {
          blog(LOG_WARNING,
               "[OpenStream] Dropped stale audio frame(s): %" PRIu64
               " (audio gap surfaced)",
               dropped);
        }
        av_frame_unref(audio_frame.get());
        continue;
      }

      struct obs_source_audio obs_audio = {};
      obs_audio.samples_per_sec = sample_rate;
      obs_audio.frames = static_cast<uint32_t>(audio_frame->nb_samples);
      obs_audio.timestamp = *timestamp_ns;
      obs_audio.format = obs_format;
      obs_audio.speakers = speakers;
      for (int i = 0; i < MAX_AV_PLANES && audio_frame->data[i]; i++) {
        obs_audio.data[i] = audio_frame->data[i];
      }

      obs_source_output_audio(ctx->source, &obs_audio);
      ++audio_frames_output;
      if (audio_frames_output == 1 || audio_frames_output % 1000 == 0) {
        blog(LOG_INFO,
             "[OpenStream] Output %" PRIu64 " decoded audio frame(s) (%d Hz, %d ch)",
             audio_frames_output,
             sample_rate,
             channels);
      }
      av_frame_unref(audio_frame.get());
    }
    return AVERROR_EXIT;
  };

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

    if (packet->stream_index == video_stream_index) {
      int result = avcodec_send_packet(video_decoder_ctx, packet.get());
      if (result == AVERROR(EAGAIN)) {
        const int drain_result = drain_video();
        if (drain_result >= 0) {
          result = avcodec_send_packet(video_decoder_ctx, packet.get());
        } else {
          result = drain_result;
        }
      }
      av_packet_unref(packet.get());
      if (result < 0) {
        blog(LOG_WARNING,
             "[OpenStream] Could not send packet to decoder: %s",
             av_error(result).c_str());
        continue;
      }
      drain_video();
    } else if (audio_decoder_ctx && packet->stream_index == audio_stream_index) {
      int result = avcodec_send_packet(audio_decoder_ctx, packet.get());
      if (result == AVERROR(EAGAIN)) {
        const int drain_result = drain_audio();
        if (drain_result >= 0) {
          result = avcodec_send_packet(audio_decoder_ctx, packet.get());
        } else {
          result = drain_result;
        }
      }
      av_packet_unref(packet.get());
      if (result < 0) {
        continue;
      }
      drain_audio();
    } else {
      av_packet_unref(packet.get());
    }
  }
}

void openstream_worker(OpenStreamSource *ctx, std::string base_srt_url, std::string selected_phone_id) {
  avformat_network_init();

  const bool auto_phone_selection =
      selected_phone_id.empty() || selected_phone_id == PhoneDiscoveryReceiver::kAutoPhoneId;
  std::string reconnect_phone_id;
  std::string expired_reconnect_phone_id;
  auto reconnect_deadline = std::chrono::steady_clock::time_point{};
  bool reconnect_hold_exhausted = false;
  const auto hold_phone_for_reconnect = [&](const std::optional<PhoneDevice> &phone) {
    if (!auto_phone_selection || !phone.has_value() || reconnect_hold_exhausted) {
      return;
    }
    if (!reconnect_phone_id.empty()) {
      return;
    }
    reconnect_phone_id = phone->instance_id;
    reconnect_deadline = std::chrono::steady_clock::now() + kReconnectReservationWindow;
  };
  const auto reset_reconnect_episode = [&]() {
    reconnect_phone_id.clear();
    expired_reconnect_phone_id.clear();
    reconnect_deadline = std::chrono::steady_clock::time_point{};
    reconnect_hold_exhausted = false;
  };

  while (!ctx->stop_requested.load()) {
    std::string srt_url = base_srt_url;
    std::optional<PhoneDevice> reserved_phone;
    if (srt_url == "openstream:auto") {
      std::optional<PhoneDevice> phone;
      set_slot_status(ctx, "Waiting");
      while (!ctx->stop_requested.load()) {
        std::string effective_phone_id = selected_phone_id;
        if (auto_phone_selection && !reconnect_phone_id.empty()) {
          if (std::chrono::steady_clock::now() < reconnect_deadline) {
            effective_phone_id = reconnect_phone_id;
          } else {
            blog(LOG_INFO,
                 "[OpenStream] Reconnect hold expired; allowing %s to choose another phone",
                 ctx->slot_label.c_str());
            expired_reconnect_phone_id = reconnect_phone_id;
            reconnect_phone_id.clear();
            reconnect_hold_exhausted = true;
          }
        }
        const std::string deprioritized_phone_id =
            auto_phone_selection && reconnect_hold_exhausted
                ? expired_reconnect_phone_id
                : std::string{};
        phone = ctx->phone_discovery.select(
            effective_phone_id, ctx->instance_id, deprioritized_phone_id);
        if (phone.has_value() && reserve_phone(ctx, *phone)) {
          break;
        }
        if (auto_phone_selection && !reconnect_phone_id.empty()) {
          blog(LOG_INFO, "[OpenStream] Waiting for previously connected Android phone");
        } else if (auto_phone_selection) {
          blog(LOG_INFO, "[OpenStream] Waiting for available Android phone");
        } else {
          blog(LOG_INFO, "[OpenStream] Waiting for selected Android phone");
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
      }
      if (!phone.has_value()) {
        break;
      }
      reserved_phone = phone;
      set_slot_status(ctx, "Reserved");
      set_active_phone(ctx, phone);
      srt_url = "srt://" + phone->host + ":" + std::to_string(phone->port) +
                "?mode=caller&latency=" + std::to_string(phone->latency_ms);
      blog(LOG_INFO,
           "[OpenStream] Connecting source to phone %s at %s",
           phone->name.c_str(),
           srt_url.c_str());
    } else {
      set_active_phone(ctx, std::nullopt);
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
    av_dict_set(&options, "probesize", "262144", 0);
    av_dict_set(&options, "analyzeduration", "250000", 0);
    av_dict_set_int(&options, "timeout", kSrtIoTimeoutUs, 0);
    av_dict_set_int(&options, "connect_timeout", kSrtConnectTimeoutMs, 0);

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
        if (reserved_phone.has_value()) {
          hold_phone_for_reconnect(reserved_phone);
          set_slot_status(ctx, "Reconnecting");
          set_active_phone(ctx, reserved_phone);
        } else {
          set_active_phone(ctx, std::nullopt);
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
    set_slot_status(ctx, "Live");
    ctx->frames_output = 0;
    ctx->stale_video_frames = 0;
    ctx->stale_audio_frames = 0;

    int video_stream_index = -1;
    CodecContextPtr video_decoder_ctx;
    if (!open_video_decoder(format_ctx.get(), &video_stream_index, &video_decoder_ctx)) {
      ctx->phone_connected = false;
      if (reserved_phone.has_value()) {
        hold_phone_for_reconnect(reserved_phone);
        set_slot_status(ctx, "Reconnecting");
        set_active_phone(ctx, reserved_phone);
      } else {
        set_active_phone(ctx, std::nullopt);
      }
      if (!ctx->stop_requested.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        continue;
      }
      break;
    }

    int audio_stream_index = -1;
    CodecContextPtr audio_decoder_ctx;
    open_audio_decoder(format_ctx.get(), &audio_stream_index, &audio_decoder_ctx);

    const AVCodecParameters *codecpar =
        format_ctx->streams[video_stream_index]->codecpar;
    blog(LOG_INFO,
         "[OpenStream] Receiving %dx%d video stream codec=%s%s",
         codecpar->width,
         codecpar->height,
         avcodec_get_name(codecpar->codec_id),
         audio_stream_index >= 0 ? " + audio" : "");

    decode_packets(ctx, format_ctx.get(), video_stream_index, video_decoder_ctx.get(),
                   audio_stream_index, audio_decoder_ctx.get());
    ctx->phone_connected = false;
    if (ctx->frames_output > 0) {
      reset_reconnect_episode();
    }
    if (!ctx->stop_requested.load()) {
      hold_phone_for_reconnect(reserved_phone);
      set_slot_status(ctx, "Reconnecting");
      blog(LOG_INFO, "[OpenStream] Holding %s for reconnect",
           ctx->slot_label.c_str());
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
      continue;
    }
    if (reserved_phone.has_value()) {
      queue_release_phone(ctx, *reserved_phone);
    }
    set_active_phone(ctx, std::nullopt);
  }
  ctx->listener_running = false;
  ctx->phone_connected = false;
  set_slot_status(ctx, "Offline");
  set_active_phone(ctx, std::nullopt);
  blog(LOG_INFO, "[OpenStream] Listener worker exited");
}

void openstream_start_worker(OpenStreamSource *ctx) {
  std::string srt_url;
  int listener_port = kDefaultListenerPort;
  int latency_ms = 120;
  int bitrate_mbps = kDefaultBitrateMbps;
  std::string source_name;
  std::string instance_id;
  std::string slot_id;
  std::string slot_label;
  std::string pairing_url;
  std::string selected_phone_id;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    srt_url = ctx->srt_url;
    listener_port = ctx->listener_port;
    latency_ms = ctx->latency_ms;
    bitrate_mbps = ctx->bitrate_mbps;
    source_name = ctx->device_name.empty() ? kOpenStreamSourceName : ctx->device_name;
    instance_id = ctx->instance_id;
    slot_id = ctx->slot_id;
    slot_label = ctx->slot_label;
    pairing_url = ctx->pairing_url;
    selected_phone_id = ctx->selected_phone_id.empty()
                            ? PhoneDiscoveryReceiver::kAutoPhoneId
                            : ctx->selected_phone_id;
  }

  const bool same_active_config =
      ctx->active_srt_url == srt_url &&
      ctx->active_listener_port == listener_port &&
      ctx->active_latency_ms == latency_ms &&
      ctx->active_bitrate_mbps == bitrate_mbps &&
      ctx->active_device_name == source_name &&
      ctx->active_slot_id == slot_id &&
      ctx->active_slot_label == slot_label &&
      ctx->active_selected_phone_id == selected_phone_id;
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
  ctx->active_slot_id = slot_id;
  ctx->active_slot_label = slot_label;
  ctx->active_selected_phone_id = selected_phone_id;
  ctx->active_phone.reset();
  ctx->stop_requested = false;
  ctx->listener_running = true;
  ctx->phone_connected = false;
  ctx->discovery.start(listener_port,
                       latency_ms,
                       bitrate_mbps,
                       source_name,
                       instance_id,
                       slot_id,
                       slot_label,
                       pairing_url,
                       &ctx->slot_busy);
  ctx->worker = std::thread(openstream_worker, ctx, srt_url, selected_phone_id);
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
    ctx->latency_ms = std::clamp(
        static_cast<int>(obs_data_get_int(settings, "latency_ms")), 80, 200);
    ctx->bitrate_mbps = std::clamp(
        static_cast<int>(obs_data_get_int(settings, "bitrate_mbps")),
        kMinBitrateMbps,
        kMaxBitrateMbps);
    const char *slot_id = obs_data_get_string(settings, "slot_id");
    if (slot_id && slot_id[0] != '\0') {
      ctx->slot_id = slot_id;
    }
    const char *slot_label = obs_data_get_string(settings, "slot_label");
    if (slot_label && slot_label[0] != '\0') {
      ctx->slot_label = slot_label;
    }
    if (ctx->slot_id.empty()) {
      ctx->slot_id = ctx->instance_id;
      obs_data_set_string(settings, "slot_id", ctx->slot_id.c_str());
    }
    obs_data_set_string(settings, "source_instance_id", ctx->instance_id.c_str());
    if (ctx->slot_label.empty()) {
      ctx->slot_label = "CAM A";
      obs_data_set_string(settings, "slot_label", ctx->slot_label.c_str());
    }
    {
      std::lock_guard<std::mutex> registry_lock(g_slot_registry_mutex);
      g_source_slots[ctx] = ctx->slot_label;
    }
    const char *selected_phone = obs_data_get_string(settings, "selected_phone_id");
    ctx->selected_phone_id =
        (selected_phone && selected_phone[0] != '\0') ? selected_phone : PhoneDiscoveryReceiver::kAutoPhoneId;
    ctx->srt_url = "openstream:auto";
    obs_data_set_string(settings, "srt_url", ctx->srt_url.c_str());
    ctx->pairing_url = pairing_url_for_slot(first_pairing_host(),
                                            ctx->listener_port,
                                            ctx->latency_ms,
                                            ctx->bitrate_mbps,
                                            ctx->slot_id,
                                            ctx->slot_label,
                                            ctx->instance_id);
    ctx->pairing_hint = "Open OpenStream on your phone, choose " + ctx->slot_label +
                        ", and keep both devices on the same Wi-Fi. Pairing URL is in Advanced.";
    const std::vector<PhoneDevice> phones = ctx->phone_discovery.devices();
    ctx->phone_target_hint = "Waiting for a phone to choose " + ctx->slot_label;
    if (ctx->selected_phone_id == PhoneDiscoveryReceiver::kAutoPhoneId) {
      int available = 0;
      for (const PhoneDevice &phone : phones) {
        if (!phone.busy || phone.reserved_by == ctx->instance_id) {
          ++available;
        }
      }
      if (!phones.empty()) {
        ctx->phone_target_hint = ctx->slot_label + ": " + std::to_string(available) + " available / " +
                                 std::to_string(phones.size()) + " discovered";
      }
    } else {
      for (const PhoneDevice &phone : phones) {
        if (phone.instance_id == ctx->selected_phone_id) {
          ctx->phone_target_hint = phone_label(phone);
          break;
        }
      }
    }
    obs_data_set_string(settings, "slot_status", ctx->slot_status.c_str());
    obs_data_set_string(settings, "phone_target_hint", ctx->phone_target_hint.c_str());
    obs_data_set_string(settings, "pairing_hint", ctx->pairing_hint.c_str());
    obs_data_set_string(settings, "pairing_url", ctx->pairing_url.c_str());
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
  const char *saved_source_instance_id = obs_data_get_string(settings, "source_instance_id");
  ctx->instance_id =
      (saved_source_instance_id && saved_source_instance_id[0] != '\0')
          ? saved_source_instance_id
          : make_instance_id(source);
  const char *saved_slot_id = obs_data_get_string(settings, "slot_id");
  const char *saved_slot_label = obs_data_get_string(settings, "slot_label");
  ctx->slot_id = (saved_slot_id && saved_slot_id[0] != '\0') ? saved_slot_id : ctx->instance_id;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    ctx->slot_label =
        (saved_slot_label && saved_slot_label[0] != '\0') ? saved_slot_label : next_available_slot_label_locked();
    g_source_slots[ctx] = ctx->slot_label;
    g_source_contexts[source] = ctx;
  }
  obs_data_set_string(settings, "source_instance_id", ctx->instance_id.c_str());
  obs_data_set_string(settings, "slot_id", ctx->slot_id.c_str());
  obs_data_set_string(settings, "slot_label", ctx->slot_label.c_str());
  ctx->phone_discovery.start();
  openstream_update(ctx, settings);
  return ctx;
}

void openstream_destroy(void *data) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  openstream_stop_worker(ctx);
  ctx->phone_discovery.stop();
  ctx->camera_controls->stop();
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    g_source_slots.erase(ctx);
    g_source_contexts.erase(ctx->source);
  }
  delete ctx;
}

void openstream_defaults(obs_data_t *settings) {
  obs_data_set_default_bool(settings, "listener_enabled", true);
  obs_data_set_default_string(settings, "device_name", "Close-up");
  obs_data_set_default_string(settings, "slot_id", "");
  obs_data_set_default_string(settings, "source_instance_id", "");
  obs_data_set_default_string(settings, "slot_label", "");
  obs_data_set_default_string(settings, "slot_status", "Empty Slot");
  obs_data_set_default_string(settings, "srt_url", "openstream:auto");
  obs_data_set_default_string(settings, "selected_phone_id", PhoneDiscoveryReceiver::kAutoPhoneId);
  obs_data_set_default_string(settings, "phone_target_hint", "Waiting for a phone to choose CAM A");
  obs_data_set_default_string(settings, "pairing_hint", "Open OpenStream on your phone, choose CAM A, and keep both devices on the same Wi-Fi.");
  obs_data_set_default_string(settings, "pairing_url", "openstream://connect");
  obs_data_set_default_bool(settings, "show_advanced", false);
  obs_data_set_default_int(settings, "listener_port", kDefaultListenerPort);
  obs_data_set_default_int(settings, "latency_ms", 120);
  obs_data_set_default_int(settings, "bitrate_mbps", kDefaultBitrateMbps);
  obs_data_set_default_double(settings, "cam_zoom", 1.0);
}

obs_properties_t *openstream_properties(void *data) {
  obs_properties_t *props = obs_properties_create();
  auto *ctx = static_cast<OpenStreamSource *>(data);
  std::string slot_status_snapshot;
  std::string selected_phone_id_snapshot;
  if (ctx) {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    slot_status_snapshot = ctx->slot_status;
    selected_phone_id_snapshot = ctx->selected_phone_id;
  }

  obs_properties_t *slot_group = obs_properties_create();
  obs_property_t *slot_summary = obs_properties_add_text(
      slot_group,
      "phone_target_hint",
      "Slot summary",
      OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(slot_summary, true);
  obs_property_set_long_description(
      slot_summary,
      "Shows whether this OBS source has an available phone or is waiting for one.");

  obs_property_t *slot_label =
      obs_properties_add_text(slot_group, "slot_label", "OBS slot name", OBS_TEXT_DEFAULT);
  obs_property_set_long_description(
      slot_label,
      "The name shown in the Android app, such as CAM A, CAM B, or Close-up.");

  obs_property_t *camera_label =
      obs_properties_add_text(slot_group, "device_name", "Production label", OBS_TEXT_DEFAULT);
  obs_property_set_long_description(
      camera_label,
      "A friendly label for this source in discovery messages and phone identify overlays.");

  obs_property_t *slot_status = obs_properties_add_text(
      slot_group,
      "slot_status",
      "Stream state",
      OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(slot_status, true);
  if (ctx) {
    if (slot_status_snapshot == "Reconnecting" ||
        slot_status_snapshot == "Offline" ||
        slot_status_snapshot == "Empty Slot") {
      obs_property_text_set_info_type(slot_status, OBS_TEXT_INFO_WARNING);
    }
  }

  obs_property_t *pairing_hint = obs_properties_add_text(
      slot_group,
      "pairing_hint",
      "Phone setup",
      OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(pairing_hint, true);

  obs_property_t *phone_list = obs_properties_add_list(
      slot_group, "selected_phone_id", "Discovered phones", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
  obs_property_set_long_description(
      phone_list,
      "Choose a specific Android phone, or let any available phone connect from the app.");
  obs_property_list_add_string(phone_list, "Let the phone choose this slot", PhoneDiscoveryReceiver::kAutoPhoneId);
  if (ctx) {
    bool selected_listed = selected_phone_id_snapshot.empty() ||
                           selected_phone_id_snapshot == PhoneDiscoveryReceiver::kAutoPhoneId;
    for (const PhoneDevice &phone : ctx->phone_discovery.devices()) {
      obs_property_list_add_string(phone_list, phone_label(phone).c_str(), phone.instance_id.c_str());
      if (phone.instance_id == selected_phone_id_snapshot) {
        selected_listed = true;
      }
    }
    if (!selected_listed) {
      obs_property_list_add_string(
          phone_list, "Selected phone unavailable", selected_phone_id_snapshot.c_str());
    }
  }
  obs_property_t *refresh_button =
      obs_properties_add_button(slot_group, "refresh_devices", "Refresh Phones", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (ctx) {
      blog(LOG_INFO,
           "[OpenStream] Refreshing discovered phones for %s",
           ctx->slot_label.c_str());
    }
    return true;
  });
  obs_property_set_long_description(
      refresh_button,
      "Refreshes the discovered phone list without closing this properties window.");

  obs_property_t *connect_button =
      obs_properties_add_button(slot_group, "connect", "Start / Retry Connection", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_start_worker(ctx);
    if (const auto phone = ctx->phone_discovery.select(ctx->selected_phone_id, ctx->instance_id)) {
      blog(LOG_INFO,
           "[OpenStream] Selected Android phone for %s: %s",
           ctx->slot_label.c_str(),
           phone->name.c_str());
    } else {
      blog(LOG_INFO,
           "[OpenStream] No available Android phone for %s yet",
           ctx->slot_label.c_str());
    }
    return true;
  });
  obs_property_set_long_description(
      connect_button,
      "Starts listening for the selected phone, or retries the current camera slot.");

  obs_property_t *disconnect_button =
      obs_properties_add_button(slot_group, "disconnect", "Stop This Slot", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_stop_worker(ctx);
    blog(LOG_INFO, "[OpenStream] Listener stopped");
    return true;
  });
  obs_property_set_long_description(
      disconnect_button,
      "Stops this OBS source from listening without removing it from the scene.");

  obs_properties_add_group(props, "slot_setup", "1. Camera Slot", OBS_GROUP_NORMAL, slot_group);

  obs_properties_t *advanced_group = obs_properties_create();
  obs_properties_add_bool(advanced_group, "listener_enabled", "Listen for this camera slot");
  obs_properties_add_text(advanced_group, "source_instance_id", "Source instance ID", OBS_TEXT_INFO);
  obs_properties_add_text(advanced_group, "slot_id", "Slot ID", OBS_TEXT_INFO);
  obs_property_t *listener_port =
      obs_properties_add_int(advanced_group, "listener_port", "Phone SRT port", 1024, 65535, 1);
  obs_property_set_long_description(listener_port, "The local UDP/SRT port OBS listens on for this camera slot.");
  obs_properties_add_text(advanced_group, "srt_url", "SRT mode", OBS_TEXT_INFO);
  obs_property_t *pairing_url =
      obs_properties_add_text(advanced_group, "pairing_url", "Deep-link pairing URL", OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(pairing_url, true);
  obs_property_t *latency =
      obs_properties_add_int_slider(advanced_group, "latency_ms", "SRT latency (ms)", 80, 200, 10);
  obs_property_int_set_suffix(latency, " ms");
  obs_property_set_long_description(latency, "Higher values are more stable on Wi-Fi; lower values reduce delay.");
  obs_property_t *bitrate =
      obs_properties_add_int_slider(advanced_group,
                                    "bitrate_mbps",
                                    "Expected bitrate (Mbps)",
                                    kMinBitrateMbps,
                                    kMaxBitrateMbps,
                                    1);
  obs_property_int_set_suffix(bitrate, " Mbps");
  obs_property_set_long_description(bitrate, "Used in discovery so the phone can tune stream quality for this slot.");
  obs_properties_add_group(props, "show_advanced", "3. Network & Pairing (Advanced)", OBS_GROUP_CHECKABLE, advanced_group);

  obs_properties_t *camera_group = obs_properties_create();

  obs_property_t *zoom_prop = obs_properties_add_float_slider(camera_group, "cam_zoom", "Zoom", 1.0, 10.0, 0.1);
  obs_property_float_set_suffix(zoom_prop, "x");
  obs_property_set_modified_callback2(zoom_prop, [](void *priv, obs_properties_t *, obs_property_t *, obs_data_t *settings) -> bool {
    auto *ctx = static_cast<OpenStreamSource *>(priv);
    if (!ctx) return false;
    double zoom = obs_data_get_double(settings, "cam_zoom");
    if (std::abs(zoom - ctx->last_cam_zoom) < 0.05) return false;
    ctx->last_cam_zoom = zoom;
    std::ostringstream body;
    body << "{\"value\":" << zoom << "}";
    queue_control_command(ctx, "/zoom", body.str());
    return false;
  }, static_cast<OpenStreamSource *>(data));

  obs_properties_add_button(camera_group, "cam_torch_on", "Torch On", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) return false;
    if (!queue_control_command(ctx, "/torch", "{\"enabled\":true}")) return false;
    blog(LOG_INFO, "[OpenStream] Torch ON");
    return true;
  });

  obs_properties_add_button(camera_group, "cam_torch_off", "Torch Off", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) return false;
    if (!queue_control_command(ctx, "/torch", "{\"enabled\":false}")) return false;
    blog(LOG_INFO, "[OpenStream] Torch OFF");
    return true;
  });

  obs_properties_add_button(camera_group, "cam_lens_back", "Rear Camera", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) return false;
    if (!queue_control_command(ctx, "/lens", "{\"lens\":\"1×\"}")) return false;
    blog(LOG_INFO, "[OpenStream] Switch to back camera");
    return true;
  });

  obs_properties_add_button(camera_group, "cam_lens_front", "Front Camera", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) return false;
    if (!queue_control_command(ctx, "/lens", "{\"lens\":\"Front\"}")) return false;
    blog(LOG_INFO, "[OpenStream] Switch to front camera");
    return true;
  });

  obs_properties_add_button(camera_group, "identify_camera", "Show Slot Label on Phone", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) return false;
    std::ostringstream body;
    body << "{\"label\":\"" << json_escape(ctx->slot_label) << "\","
         << "\"subtitle\":\"" << json_escape(ctx->device_name) << "\"}";
    const bool sent = queue_control_command(ctx, "/identify", body.str());
    blog(LOG_INFO,
         "[OpenStream] Identify %s%s",
         ctx->slot_label.c_str(),
         sent ? "" : " failed");
    return sent;
  });

  obs_properties_add_group(props, "camera_controls", "2. Live Camera Controls", OBS_GROUP_NORMAL, camera_group);
  obs_properties_add_text(props, "credit", "About", OBS_TEXT_INFO);

  return props;
}

obs_source_info openstream_source_info = {
    .id = "openstream_phone_v8_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO,
    .get_name = openstream_get_name,
    .create = openstream_create,
    .destroy = openstream_destroy,
    .get_defaults = openstream_defaults,
    .get_properties = openstream_properties,
    .update = openstream_update,
};

obs_source_info openstream_legacy_source_info = {
    .id = "openstream_phone_v7_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO,
    .get_name = openstream_get_name,
    .create = openstream_create,
    .destroy = openstream_destroy,
    .get_defaults = openstream_defaults,
    .get_properties = openstream_properties,
    .update = openstream_update,
};
}  // namespace

bool openstream_is_camera_source(obs_source_t *source) {
  if (!source) return false;
  const char *id = obs_source_get_id(source);
  return id && (strcmp(id, "openstream_phone_v8_source") == 0 ||
                strcmp(id, "openstream_phone_v7_source") == 0);
}

bool openstream_post_camera_command(obs_source_t *source, const char *path,
                                    const char *json_body) {
  if (!openstream_is_camera_source(source) || !path || !json_body) return false;
  OpenStreamSource *ctx = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    const auto found = g_source_contexts.find(source);
    if (found != g_source_contexts.end()) ctx = found->second;
  }
  return queue_control_command(ctx, path, json_body);
}

bool openstream_start_camera_source(obs_source_t *source) {
  if (!openstream_is_camera_source(source)) return false;
  OpenStreamSource *ctx = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    const auto found = g_source_contexts.find(source);
    if (found != g_source_contexts.end()) ctx = found->second;
  }
  if (!ctx) return false;
  openstream_start_worker(ctx);
  return true;
}

bool openstream_stop_camera_source(obs_source_t *source) {
  if (!openstream_is_camera_source(source)) return false;
  OpenStreamSource *ctx = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    const auto found = g_source_contexts.find(source);
    if (found != g_source_contexts.end()) ctx = found->second;
  }
  if (!ctx) return false;
  openstream_stop_worker(ctx);
  return true;
}

const char *openstream_source_status(obs_source_t *source) {
  thread_local std::string status;
  status = "Camera unavailable";
  if (!openstream_is_camera_source(source)) return status.c_str();
  OpenStreamSource *ctx = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    const auto found = g_source_contexts.find(source);
    if (found != g_source_contexts.end()) ctx = found->second;
  }
  if (!ctx) return status.c_str();
  std::lock_guard<std::mutex> lock(ctx->settings_mutex);
  status = ctx->slot_label + " — " + ctx->slot_status;
  if (ctx->active_phone.has_value()) status += " — " + ctx->active_phone->name;
  return status.c_str();
}

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
  obs_register_source(&openstream_legacy_source_info);
  openstream_register_dock();
  blog(LOG_INFO, "[OpenStream] OBS plugin loaded: V8 — video + audio + remote controls (Made by @yashas.vm)");
  return true;
}

void obs_module_unload(void) {
  openstream_unregister_dock();
#ifdef _WIN32
  if (g_winsock_started) {
    WSACleanup();
    g_winsock_started = false;
  }
#endif
}
