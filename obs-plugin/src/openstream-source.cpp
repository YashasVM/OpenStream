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

#include "openstream-ui-api.hpp"

#include <chrono>
#include <atomic>
#include <cmath>
#include <charconv>
#include <cstdint>
#include <inttypes.h>
#include <cstdlib>
#include <cstring>
#include <future>
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
OBS_MODULE_USE_DEFAULT_LOCALE("openstream-beta-obs", "en-US")

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
constexpr auto kReconnectReservationWindow = std::chrono::seconds(45);
constexpr const char *kOpenStreamSourceName = "OpenStream Beta Camera";
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

std::mutex g_slot_registry_mutex;
std::map<const void *, std::string> g_source_slots;

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

std::optional<double> json_number_value(const std::string &json, const std::string &key) {
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) return std::nullopt;
  const size_t colon = json.find(':', key_pos + quoted_key.size());
  if (colon == std::string::npos) return std::nullopt;
  const size_t start = json.find_first_of("-0123456789", colon + 1);
  if (start == std::string::npos) return std::nullopt;
  char *end = nullptr;
  const double value = std::strtod(json.c_str() + start, &end);
  if (end == json.c_str() + start) return std::nullopt;
  return value;
}

std::optional<uint64_t> json_uint64_value(const std::string &json, const std::string &key) {
  const auto value = json_number_value(json, key);
  if (!value || *value < 0.0) return std::nullopt;
  return static_cast<uint64_t>(*value);
}

std::vector<std::string> json_string_array_value(const std::string &json,
                                                 const std::string &key) {
  std::vector<std::string> values;
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) return values;
  const size_t open = json.find('[', key_pos + quoted_key.size());
  const size_t close = open == std::string::npos ? std::string::npos : json.find(']', open + 1);
  if (close == std::string::npos) return values;
  size_t cursor = open + 1;
  while (cursor < close) {
    const size_t begin = json.find('"', cursor);
    if (begin == std::string::npos || begin >= close) break;
    const size_t end = json.find('"', begin + 1);
    if (end == std::string::npos || end > close) break;
    values.push_back(json.substr(begin + 1, end - begin - 1));
    cursor = end + 1;
  }
  return values;
}

std::vector<double> json_number_array_value(const std::string &json,
                                            const std::string &key) {
  std::vector<double> values;
  const std::string quoted_key = "\"" + key + "\"";
  const size_t key_pos = json.find(quoted_key);
  if (key_pos == std::string::npos) return values;
  const size_t open = json.find('[', key_pos + quoted_key.size());
  const size_t close = open == std::string::npos ? std::string::npos : json.find(']', open + 1);
  if (close == std::string::npos) return values;
  const char *cursor = json.c_str() + open + 1;
  const char *finish = json.c_str() + close;
  while (cursor < finish) {
    while (cursor < finish && (*cursor == ' ' || *cursor == ',' || *cursor == '\t')) ++cursor;
    if (cursor >= finish) break;
    char *end = nullptr;
    const double value = std::strtod(cursor, &end);
    if (end == cursor || end > finish) break;
    values.push_back(value);
    cursor = end;
  }
  return values;
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
  int bitrate_mbps = 50;
  bool busy = false;
  std::string reserved_by;
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
                                    const std::string &source_instance_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    pruneExpiredLocked();
    if (selected_id.empty() || selected_id == kAutoPhoneId) {
      for (const auto &entry : devices_) {
        if (!entry.second.busy || entry.second.reserved_by == source_instance_id) {
          return entry.second;
        }
      }
      return std::nullopt;
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
      blog(LOG_WARNING, "[OpenStream Beta] Could not create phone discovery socket");
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
      blog(LOG_WARNING, "[OpenStream Beta] Could not bind phone discovery UDP port");
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
      // The UDP source address is authoritative. Trusting the JSON host lets a
      // spoofed beacon redirect OBS to an arbitrary address or inject SRT query
      // parameters into the URL assembled below.
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
      device.bitrate_mbps = std::clamp(json_int_value(json, "bitrateMbps").value_or(12), 1, 200);
      device.busy = json_bool_value(json, "busy").value_or(false);
      device.reserved_by = json_string_value(json, "reservedBy").value_or("");
      device.last_seen = std::chrono::steady_clock::now();
      {
        std::lock_guard<std::mutex> lock(mutex_);
        pruneExpiredLocked();
        devices_[device.instance_id] = device;
      }
      blog(LOG_INFO,
           "[OpenStream Beta] Discovered phone %s at %s:%d%s",
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
      blog(LOG_WARNING, "[OpenStream Beta] Could not create discovery UDP socket");
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
  int bitrate_mbps_ = 50;
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
  int bitrate_mbps = 50;
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
  double last_cam_zoom = 1.0;
  std::string control_token;
  OpenStreamCameraCapabilities capabilities;
  OpenStreamCameraState camera_state;
  OpenStreamZoomPresetConfig zoom_presets;
  std::string last_control_error;
  bool control_request_pending = false;
};

OpenStreamZoomPresetConfig sanitize_zoom_presets(const OpenStreamZoomPresetConfig &input) {
  OpenStreamZoomPresetConfig result;
  result.version = 1;
  result.show_buttons = input.show_buttons;
  result.duration_ms = std::clamp(input.duration_ms, 250, 10000);
  result.duration_ms = (result.duration_ms / 250) * 250;
  if (result.duration_ms < 250) result.duration_ms = 250;
  result.ratios.clear();
  for (double ratio : input.ratios) {
    if (std::isfinite(ratio) && ratio > 0.0 && result.ratios.size() < 8) result.ratios.push_back(ratio);
  }
  if (result.ratios.empty()) result.ratios = {0.5, 1.0, 3.0, 5.0};
  return result;
}

OpenStreamZoomPresetConfig parse_zoom_presets(const char *json) {
  OpenStreamZoomPresetConfig parsed;
  if (!json || !*json) return parsed;
  const std::string value(json);
  parsed.show_buttons = json_bool_value(value, "showButtons").value_or(parsed.show_buttons);
  parsed.duration_ms = static_cast<int>(json_number_value(value, "durationMs").value_or(parsed.duration_ms));
  parsed.ratios = json_number_array_value(value, "ratios");
  return sanitize_zoom_presets(parsed);
}

std::string zoom_presets_json(const OpenStreamZoomPresetConfig &input) {
  const auto config = sanitize_zoom_presets(input);
  std::ostringstream json;
  json << "{\"version\":1,\"showButtons\":" << (config.show_buttons ? "true" : "false")
       << ",\"durationMs\":" << config.duration_ms << ",\"ratios\":[";
  for (size_t index = 0; index < config.ratios.size(); ++index) {
    if (index) json << ',';
    json << config.ratios[index];
  }
  json << "]}";
  return json.str();
}

void notify_camera_changed(const std::string &instance_id);

void set_slot_status(OpenStreamSource *ctx, std::string status) {
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    ctx->slot_status = std::move(status);
  }
  notify_camera_changed(ctx->instance_id);
}

struct ControlHttpResponse {
  bool transport_ok = false;
  int status_code = 0;
  std::string body;
};

ControlHttpResponse send_control_request(const std::string &host,
                                         int port,
                                         const std::string &method,
                                         const std::string &path,
                                         const std::string &body,
                                         const std::string &bearer_token = {}) {
  ControlHttpResponse result;
  if (port < 1 || port > 65535 || body.size() > 8192 || path.empty() || path.front() != '/') {
    return result;
  }
  SocketHandle sock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (sock == kInvalidSocket) return result;

#ifdef _WIN32
  DWORD timeout = 3000;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char *>(&timeout), sizeof(timeout));
#else
  timeval timeout = {};
  timeout.tv_sec = 3;
  setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
  setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
#endif

  sockaddr_in addr = {};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
    close_socket(sock);
    return result;
  }

  if (connect(sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
    close_socket(sock);
    return result;
  }

  std::ostringstream request;
  request << method << " " << path << " HTTP/1.1\r\n"
          << "Host: " << host << ":" << port << "\r\n"
          << "Accept: application/json\r\n";
  if (!bearer_token.empty()) {
    request << "Authorization: Bearer " << bearer_token << "\r\n";
  }
  if (!body.empty()) {
    request << "Content-Type: application/json\r\n"
            << "Content-Length: " << body.size() << "\r\n";
  }
  request << "Connection: close\r\n\r\n" << body;
  const std::string req = request.str();
  size_t sent_total = 0;
  while (sent_total < req.size()) {
    const int sent = send(sock,
                          req.data() + sent_total,
                          static_cast<int>(req.size() - sent_total),
                          0);
    if (sent <= 0) {
      close_socket(sock);
      return result;
    }
    sent_total += static_cast<size_t>(sent);
  }

  constexpr size_t kMaxResponseBytes = 8192;
  std::string response;
  response.reserve(1024);
  char response_chunk[512];
  while (response.size() < kMaxResponseBytes) {
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
  if (header_end == std::string::npos || response.rfind("HTTP/", 0) != 0) {
    return result;
  }

  const size_t status_start = response.find(' ');
  if (status_start == std::string::npos) return result;
  result.status_code = std::atoi(response.c_str() + status_start + 1);
  result.transport_ok = true;

  const size_t length_header = response.find("Content-Length:");
  if (length_header == std::string::npos || length_header > header_end) {
    result.body = response.substr(header_end + 4);
    return result;
  }
  const size_t length_start = response.find_first_of("0123456789", length_header + 15);
  if (length_start == std::string::npos || length_start > header_end) {
    return result;
  }
  const size_t length_end = response.find_first_not_of("0123456789", length_start);
  size_t content_length = 0;
  const char *length_begin_ptr = response.data() + length_start;
  const char *length_end_ptr = response.data() +
      (length_end == std::string::npos ? response.size() : length_end);
  const auto parsed_length = std::from_chars(length_begin_ptr, length_end_ptr, content_length);
  if (parsed_length.ec != std::errc{} || parsed_length.ptr != length_end_ptr ||
      content_length > kMaxResponseBytes) {
    return result;
  }
  const size_t body_start = header_end + 4;
  if (body_start > response.size() || response.size() - body_start < content_length) {
    return result;
  }
  result.body = response.substr(body_start, content_length);
  return result;
}

bool send_control_command(const std::string &host, int port,
                          const std::string &path, const std::string &body,
                          const std::string &bearer_token = {}) {
  const auto response = send_control_request(host, port, "POST", path, body, bearer_token);
  return response.transport_ok && response.status_code >= 200 && response.status_code < 300 &&
         json_bool_value(response.body, "ok").value_or(false);
}

void set_active_phone(OpenStreamSource *ctx, std::optional<PhoneDevice> phone) {
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    ctx->slot_busy = phone.has_value();
    ctx->active_phone = std::move(phone);
  }
  notify_camera_changed(ctx->instance_id);
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

bool reserve_phone(OpenStreamSource *ctx, const PhoneDevice &phone) {
  std::ostringstream body;
  std::string token;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    body << "{\"sourceInstanceId\":\"" << json_escape(ctx->instance_id) << "\","
         << "\"slotId\":\"" << json_escape(ctx->slot_id) << "\","
         << "\"slotLabel\":\"" << json_escape(ctx->slot_label) << "\","
         << "\"bitrateMbps\":" << ctx->bitrate_mbps << "}";
    token = ctx->control_token;
  }
  return send_control_command(phone.host, phone.control_port, "/reserve", body.str(), token);
}

void release_phone(OpenStreamSource *ctx, const PhoneDevice &phone) {
  std::ostringstream body;
  std::string token;
  body << "{\"sourceInstanceId\":\"" << json_escape(ctx->instance_id) << "\"}";
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    token = ctx->control_token;
  }
  send_control_command(phone.host, phone.control_port, "/release", body.str(), token);
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
  const auto phone = control_phone(ctx);
  ctx->stop_requested = true;
  ctx->listener_running = false;
  ctx->phone_connected = false;
  ctx->discovery.stop();
  if (ctx->worker.joinable()) {
    ctx->worker.join();
  }
  if (phone.has_value()) {
    release_phone(ctx, *phone);
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
         "[OpenStream Beta] Could not read stream info: %s",
         av_error(stream_result).c_str());
    return false;
  }

  const int best_stream = av_find_best_stream(
      format_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (best_stream < 0) {
    blog(LOG_WARNING,
         "[OpenStream Beta] No video stream found in SRT input: %s",
         av_error(best_stream).c_str());
    return false;
  }

  AVStream *stream = format_ctx->streams[best_stream];
  const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!decoder) {
    blog(LOG_WARNING,
         "[OpenStream Beta] No FFmpeg decoder found for codec id %d",
         stream->codecpar->codec_id);
    return false;
  }

  CodecContextPtr codec_ctx(avcodec_alloc_context3(decoder));
  if (!codec_ctx) {
    blog(LOG_WARNING, "[OpenStream Beta] Could not allocate decoder context");
    return false;
  }

  int result = avcodec_parameters_to_context(codec_ctx.get(), stream->codecpar);
  if (result < 0) {
    blog(LOG_WARNING,
         "[OpenStream Beta] Could not copy decoder parameters: %s",
         av_error(result).c_str());
    return false;
  }

  codec_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
  result = avcodec_open2(codec_ctx.get(), decoder, nullptr);
  if (result < 0) {
    blog(LOG_WARNING,
         "[OpenStream Beta] Could not open decoder: %s",
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
    blog(LOG_INFO, "[OpenStream Beta] No audio stream found (video-only mode)");
    *audio_stream_index = -1;
    return false;
  }

  AVStream *stream = format_ctx->streams[best_stream];
  const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!decoder) {
    blog(LOG_WARNING,
         "[OpenStream Beta] No audio decoder found for codec id %d",
         stream->codecpar->codec_id);
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
         "[OpenStream Beta] Could not open audio decoder: %s",
         av_error(result).c_str());
    *audio_stream_index = -1;
    return false;
  }

  *audio_stream_index = best_stream;
  *decoder_ctx = std::move(codec_ctx);
  blog(LOG_INFO,
       "[OpenStream Beta] Opened audio decoder: %s, %d Hz, %d channels",
       avcodec_get_name(stream->codecpar->codec_id),
       stream->codecpar->sample_rate,
       stream->codecpar->ch_layout.nb_channels);
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
      blog(LOG_WARNING, "[OpenStream Beta] Could not calculate OBS YUV color parameters");
      return false;
    }

    obs_source_output_video2(ctx->source, &yuv_frame);
    const uint64_t frames_output = ++ctx->frames_output;
    if (frames_output == 1 || frames_output % 300 == 0) {
      const char *format_name = av_get_pix_fmt_name(source_format);
      blog(LOG_INFO,
           "[OpenStream Beta] Output %" PRIu64 " decoded YUV frame(s) to OBS (%dx%d, source format=%s)",
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
    blog(LOG_WARNING, "[OpenStream Beta] Could not create BGRA converter");
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
    blog(LOG_WARNING, "[OpenStream Beta] Incomplete frame conversion");
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
         "[OpenStream Beta] Output %" PRIu64 " decoded BGRA frame(s) to OBS (%dx%d, source format=%s)",
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
    blog(LOG_WARNING, "[OpenStream Beta] Could not allocate decode packet/frame");
    return;
  }

  SwsContextPtr sws_ctx(nullptr);
  std::vector<uint8_t> bgra_buffer;
  AVStream *video_stream = format_ctx->streams[video_stream_index];
  uint64_t audio_frames_output = 0;

  const auto drain_video = [&]() -> int {
    while (!ctx->stop_requested.load()) {
      const int result = avcodec_receive_frame(video_decoder_ctx, frame.get());
      if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
        return 0;
      }
      if (result < 0) {
        blog(LOG_WARNING,
             "[OpenStream Beta] Could not decode frame: %s",
             av_error(result).c_str());
        return result;
      }
      output_decoded_frame(
          ctx, video_stream, video_decoder_ctx, frame.get(), &sws_ctx, &bgra_buffer);
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

      struct obs_source_audio obs_audio = {};
      obs_audio.samples_per_sec = sample_rate;
      obs_audio.frames = static_cast<uint32_t>(audio_frame->nb_samples);
      obs_audio.timestamp = os_gettime_ns();
      obs_audio.format = obs_format;
      obs_audio.speakers = speakers;
      for (int i = 0; i < MAX_AV_PLANES && audio_frame->data[i]; i++) {
        obs_audio.data[i] = audio_frame->data[i];
      }

      obs_source_output_audio(ctx->source, &obs_audio);
      ++audio_frames_output;
      if (audio_frames_output == 1 || audio_frames_output % 1000 == 0) {
        blog(LOG_INFO,
             "[OpenStream Beta] Output %" PRIu64 " decoded audio frame(s) (%d Hz, %d ch)",
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
           "[OpenStream Beta] SRT input ended or disconnected: %s",
           av_error(read_result).c_str());
      break;
    }

    // Handle video packets
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
             "[OpenStream Beta] Could not send packet to decoder: %s",
             av_error(result).c_str());
        continue;
      }
      drain_video();
    }
    // Handle audio packets
    else if (audio_decoder_ctx && packet->stream_index == audio_stream_index) {
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

  while (!ctx->stop_requested.load()) {
    std::string srt_url = base_srt_url;
    std::optional<PhoneDevice> reserved_phone;
    if (srt_url == "openstream:auto") {
      std::optional<PhoneDevice> phone;
      set_slot_status(ctx, "Waiting");
      while (!ctx->stop_requested.load()) {
        phone = ctx->phone_discovery.select(selected_phone_id, ctx->instance_id);
        if (phone.has_value() && reserve_phone(ctx, *phone)) {
          break;
        }
        if (selected_phone_id.empty() || selected_phone_id == PhoneDiscoveryReceiver::kAutoPhoneId) {
          blog(LOG_INFO, "[OpenStream Beta] Waiting for available Android phone");
        } else {
          blog(LOG_INFO, "[OpenStream Beta] Waiting for selected Android phone");
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
           "[OpenStream Beta] Connecting source to phone %s at %s",
           phone->name.c_str(),
           srt_url.c_str());
    } else {
      set_active_phone(ctx, std::nullopt);
    }

    AVFormatContext *raw_format_ctx = avformat_alloc_context();
    if (!raw_format_ctx) {
      blog(LOG_WARNING, "[OpenStream Beta] Could not allocate FFmpeg format context");
      break;
    }
    raw_format_ctx->interrupt_callback.callback = ffmpeg_interrupt_callback;
    raw_format_ctx->interrupt_callback.opaque = ctx;

    AVDictionary *options = nullptr;
    av_dict_set(&options, "fflags", "nobuffer", 0);
    av_dict_set(&options, "flags", "low_delay", 0);
    av_dict_set(&options, "probesize", "1048576", 0);
    av_dict_set(&options, "analyzeduration", "1000000", 0);
    std::string stream_passphrase;
    {
      std::lock_guard<std::mutex> lock(ctx->settings_mutex);
      stream_passphrase = ctx->control_token;
    }
    if (!stream_passphrase.empty()) {
      av_dict_set(&options, "passphrase", stream_passphrase.c_str(), 0);
      av_dict_set(&options, "pbkeylen", "32", 0);
    }

    blog(LOG_INFO,
         "[OpenStream Beta] Opening Android stream at %s",
         srt_url.c_str());
    const AVInputFormat *mpegts_input = av_find_input_format("mpegts");
    int result =
        avformat_open_input(&raw_format_ctx, srt_url.c_str(), mpegts_input, &options);
    av_dict_free(&options);
    if (result < 0) {
      if (!ctx->stop_requested.load()) {
        blog(LOG_WARNING,
             "[OpenStream Beta] Could not open SRT input: %s",
             av_error(result).c_str());
        if (raw_format_ctx) {
          avformat_free_context(raw_format_ctx);
        }
        if (reserved_phone.has_value()) {
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

    int video_stream_index = -1;
    CodecContextPtr video_decoder_ctx;
    if (!open_video_decoder(format_ctx.get(), &video_stream_index, &video_decoder_ctx)) {
      ctx->phone_connected = false;
      if (reserved_phone.has_value()) {
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

    // Try to open audio decoder (optional — video-only is fine)
    int audio_stream_index = -1;
    CodecContextPtr audio_decoder_ctx;
    open_audio_decoder(format_ctx.get(), &audio_stream_index, &audio_decoder_ctx);

    const AVCodecParameters *codecpar =
        format_ctx->streams[video_stream_index]->codecpar;
    blog(LOG_INFO,
         "[OpenStream Beta] Receiving %dx%d video stream codec=%s%s",
         codecpar->width,
         codecpar->height,
         avcodec_get_name(codecpar->codec_id),
         audio_stream_index >= 0 ? " + audio" : "");

    decode_packets(ctx, format_ctx.get(), video_stream_index, video_decoder_ctx.get(),
                   audio_stream_index, audio_decoder_ctx.get());
    ctx->phone_connected = false;
    if (!ctx->stop_requested.load()) {
      set_slot_status(ctx, "Reconnecting");
      blog(LOG_INFO, "[OpenStream Beta] Holding %s for reconnect",
           ctx->slot_label.c_str());
      std::this_thread::sleep_for(std::chrono::milliseconds(500));
      continue;
    }
    if (reserved_phone.has_value()) {
      release_phone(ctx, *reserved_phone);
    }
    set_active_phone(ctx, std::nullopt);
  }
  ctx->listener_running = false;
  ctx->phone_connected = false;
  set_slot_status(ctx, "Offline");
  set_active_phone(ctx, std::nullopt);
  blog(LOG_INFO, "[OpenStream Beta] Listener worker exited");
}

void openstream_start_worker(OpenStreamSource *ctx) {
  std::string srt_url;
  int listener_port = kDefaultListenerPort;
  int latency_ms = 120;
  int bitrate_mbps = 50;
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
    ctx->zoom_presets = parse_zoom_presets(obs_data_get_string(settings, "zoom_presets"));
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
    const char *control_token = obs_data_get_string(settings, "control_token");
    if (control_token && control_token[0] != '\0') {
      ctx->control_token = control_token;
    }
    ctx->srt_url = "openstream:auto";
    obs_data_set_string(settings, "srt_url", ctx->srt_url.c_str());
    ctx->pairing_url = pairing_url_for_slot(first_pairing_host(),
                                            ctx->listener_port,
                                            ctx->latency_ms,
                                            ctx->bitrate_mbps,
                                            ctx->slot_id,
                                            ctx->slot_label,
                                            ctx->instance_id);
    ctx->pairing_hint = "Open OpenStream Beta on your phone, choose " + ctx->slot_label +
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
  }
  obs_data_set_string(settings, "source_instance_id", ctx->instance_id.c_str());
  obs_data_set_string(settings, "slot_id", ctx->slot_id.c_str());
  obs_data_set_string(settings, "slot_label", ctx->slot_label.c_str());
  ctx->phone_discovery.start();
  openstream_update(ctx, settings);
  notify_camera_changed(ctx->instance_id);
  return ctx;
}

void openstream_destroy(void *data) {
  auto *ctx = static_cast<OpenStreamSource *>(data);
  const std::string instance_id = ctx->instance_id;
  openstream_stop_worker(ctx);
  ctx->phone_discovery.stop();
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    g_source_slots.erase(ctx);
  }
  delete ctx;
  notify_camera_changed(instance_id);
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
  obs_data_set_default_string(settings, "pairing_hint", "Open OpenStream Beta on your phone, choose CAM A, and keep both devices on the same Wi-Fi.");
  obs_data_set_default_string(settings, "pairing_url", "openstream://connect");
  obs_data_set_default_bool(settings, "show_advanced", false);
  obs_data_set_default_int(settings, "listener_port", kDefaultListenerPort);
  obs_data_set_default_int(settings, "latency_ms", 120);
  obs_data_set_default_int(settings, "bitrate_mbps", 50);
  obs_data_set_default_double(settings, "cam_zoom", 1.0);
  obs_data_set_default_string(settings, "zoom_presets",
                              "{\"version\":1,\"showButtons\":true,\"durationMs\":2000,\"ratios\":[0.5,1,3,5]}");
  obs_data_set_default_string(settings, "control_token", "");
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
      "Camera status",
      OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(slot_summary, true);
  obs_property_set_long_description(
      slot_summary,
      "Shows whether this OBS source has an available phone or is waiting for one.");

  obs_property_t *slot_label =
      obs_properties_add_text(slot_group, "slot_label", "Camera name", OBS_TEXT_DEFAULT);
  obs_property_set_long_description(
      slot_label,
      "The short name operators use in the Control Room, such as Wide or Stage Left.");

  obs_property_t *camera_label =
      obs_properties_add_text(slot_group, "device_name", "Phone display label", OBS_TEXT_DEFAULT);
  obs_property_set_long_description(
      camera_label,
      "Shown on the phone when the orchestrator uses Identify.");

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
      "Setup guidance",
      OBS_TEXT_INFO);
  obs_property_text_set_info_word_wrap(pairing_hint, true);

  obs_property_t *phone_list = obs_properties_add_list(
      slot_group, "selected_phone_id", "Phone", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
  obs_property_set_long_description(
      phone_list,
      "Choose a specific Android phone, or let any available phone connect from the app.");
  obs_property_list_add_string(phone_list, "Any available phone", PhoneDiscoveryReceiver::kAutoPhoneId);
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
      obs_properties_add_button(slot_group, "refresh_devices", "Refresh phone list", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (ctx) {
      blog(LOG_INFO,
           "[OpenStream Beta] Refreshing discovered phones for %s",
           ctx->slot_label.c_str());
    }
    return true;
  });
  obs_property_set_long_description(
      refresh_button,
      "Refreshes the discovered phone list without closing this properties window.");

  obs_property_t *connect_button =
      obs_properties_add_button(slot_group, "connect", "Connect / Retry", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_start_worker(ctx);
    if (const auto phone = ctx->phone_discovery.select(ctx->selected_phone_id, ctx->instance_id)) {
      blog(LOG_INFO,
           "[OpenStream Beta] Selected Android phone for %s: %s",
           ctx->slot_label.c_str(),
           phone->name.c_str());
    } else {
      blog(LOG_INFO,
           "[OpenStream Beta] No available Android phone for %s yet",
           ctx->slot_label.c_str());
    }
    return true;
  });
  obs_property_set_long_description(
      connect_button,
      "Starts listening for the selected phone, or retries the current camera slot.");

  obs_property_t *disconnect_button =
      obs_properties_add_button(slot_group, "disconnect", "Stop camera", [](obs_properties_t *, obs_property_t *, void *data) {
    auto *ctx = static_cast<OpenStreamSource *>(data);
    if (!ctx) {
      return false;
    }
    openstream_stop_worker(ctx);
    blog(LOG_INFO, "[OpenStream Beta] Listener stopped");
    return true;
  });
  obs_property_set_long_description(
      disconnect_button,
      "Stops this OBS source from listening without removing it from the scene.");

  obs_properties_add_group(props, "slot_setup", "Camera setup", OBS_GROUP_NORMAL, slot_group);

  // ── Camera remote controls ──
  obs_properties_t *advanced_group = obs_properties_create();
  obs_properties_add_bool(advanced_group, "listener_enabled", "Enable this camera source");
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
      obs_properties_add_int_slider(advanced_group, "bitrate_mbps", "Expected bitrate (Mbps)", 8, 120, 1);
  obs_property_int_set_suffix(bitrate, " Mbps");
  obs_property_set_long_description(bitrate, "Used in discovery so the phone can tune stream quality for this slot.");
  obs_properties_add_group(props, "show_advanced", "Troubleshooting", OBS_GROUP_CHECKABLE, advanced_group);

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

namespace {
std::mutex g_ui_command_mutex;
std::vector<std::future<void>> g_ui_command_tasks;
std::mutex g_camera_subscription_mutex;
std::map<uint64_t, OpenStreamCameraChangedCallback> g_camera_subscriptions;
std::atomic<uint64_t> g_next_camera_subscription_id = 1;

void notify_camera_changed(const std::string &instance_id) {
  std::vector<OpenStreamCameraChangedCallback> callbacks;
  {
    std::lock_guard<std::mutex> lock(g_camera_subscription_mutex);
    for (const auto &[unused_id, callback] : g_camera_subscriptions) {
      (void)unused_id;
      callbacks.push_back(callback);
    }
  }
  for (const auto &callback : callbacks) callback(instance_id);
}

OpenStreamSource *find_camera_source(const std::string &instance_id,
                                     obs_source_t **source_ref) {
  std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
  for (const auto &[key, unused_label] : g_source_slots) {
    (void)unused_label;
    auto *ctx = static_cast<OpenStreamSource *>(const_cast<void *>(key));
    if (ctx->instance_id == instance_id && ctx->source) {
      *source_ref = obs_source_get_ref(ctx->source);
      return ctx;
    }
  }
  return nullptr;
}

bool data_has_type(obs_data_t *data, const char *name, obs_data_type type) {
  if (!data) return false;
  obs_data_item_t *item = obs_data_item_byname(data, name);
  if (!item) return false;
  const bool matches = obs_data_item_gettype(item) == type;
  obs_data_item_release(&item);
  return matches;
}

std::optional<double> data_number(obs_data_t *data, const char *name) {
  if (!data_has_type(data, name, OBS_DATA_NUMBER)) return std::nullopt;
  return obs_data_get_double(data, name);
}

std::optional<bool> data_bool(obs_data_t *data, const char *name) {
  if (!data_has_type(data, name, OBS_DATA_BOOLEAN)) return std::nullopt;
  return obs_data_get_bool(data, name);
}

std::optional<std::string> data_string(obs_data_t *data, const char *name) {
  if (!data_has_type(data, name, OBS_DATA_STRING)) return std::nullopt;
  return std::string(obs_data_get_string(data, name));
}

OpenStreamNumberRange read_range(obs_data_t *root, const char *name,
                                 double scale = 1.0) {
  OpenStreamNumberRange range;
  if (!data_has_type(root, name, OBS_DATA_OBJECT)) return range;
  obs_data_t *object = obs_data_get_obj(root, name);
  if (!object) return range;
  range.minimum = data_number(object, "min").value_or(0.0) * scale;
  range.maximum = data_number(object, "max").value_or(0.0) * scale;
  range.step = data_number(object, "step").value_or(0.0) * scale;
  range.available = range.maximum > range.minimum;
  obs_data_release(object);
  return range;
}

OpenStreamCameraCapabilities parse_capabilities(const std::string &json) {
  OpenStreamCameraCapabilities caps;
  obs_data_t *root = obs_data_create_from_json(json.c_str());
  if (!root || data_number(root, "protocolVersion").value_or(0.0) != 2.0) {
    if (root) obs_data_release(root);
    return caps;
  }

  caps.focus_modes = json_string_array_value(json, "focusModes");
  caps.white_balance_modes = json_string_array_value(json, "whiteBalanceModes");
  caps.stabilization_modes = json_string_array_value(json, "stabilizationModes");
  const auto contains = [](const std::vector<std::string> &values, const char *value) {
    return std::find(values.begin(), values.end(), value) != values.end();
  };
  caps.autofocus = contains(caps.focus_modes, "continuous") || contains(caps.focus_modes, "single");
  caps.tap_to_focus = data_bool(root, "supportsTapFocus").value_or(false);
  caps.manual_focus = contains(caps.focus_modes, "manual");
  caps.auto_exposure = true;
  caps.manual_exposure = data_bool(root, "manualSensor").value_or(false);
  caps.auto_white_balance = contains(caps.white_balance_modes, "auto");
  caps.manual_white_balance = data_bool(root, "manualWhiteBalance").value_or(false);
  caps.zoom = data_bool(root, "supportsZoomRatio").value_or(false);
  caps.zoom_transition = data_bool(root, "supportsZoomTransition").value_or(false);
  caps.torch = data_bool(root, "supportsTorch").value_or(false);
  caps.lens_selection = false;
  caps.stabilization = contains(caps.stabilization_modes, "video") ||
                       contains(caps.stabilization_modes, "optical");
  caps.iso = read_range(root, "isoRange");
  caps.shutter_us = read_range(root, "shutterRangeNs", 0.001);
  caps.exposure_compensation = read_range(root, "exposureCompensationRange");
  caps.focus_distance = read_range(root, "focusDistanceRange");
  caps.zoom_ratio = read_range(root, "zoomRange");
  if (caps.manual_white_balance) {
    caps.white_balance_kelvin = {2000.0, 12000.0, 50.0, true};
    caps.white_balance_tint = {-100.0, 100.0, 1.0, true};
  }

  if (data_has_type(root, "fpsRanges", OBS_DATA_ARRAY)) {
    obs_data_array_t *ranges = obs_data_get_array(root, "fpsRanges");
    if (ranges) {
      for (size_t index = 0; index < obs_data_array_count(ranges); ++index) {
        obs_data_t *range = obs_data_array_item(ranges, index);
        if (!range) continue;
        const auto minimum = data_number(range, "min");
        const auto maximum = data_number(range, "max");
        if (minimum) caps.frame_rates.push_back(*minimum);
        if (maximum && (!minimum || *maximum != *minimum)) caps.frame_rates.push_back(*maximum);
        obs_data_release(range);
      }
      obs_data_array_release(ranges);
    }
  }
  std::sort(caps.frame_rates.begin(), caps.frame_rates.end());
  caps.frame_rates.erase(std::unique(caps.frame_rates.begin(), caps.frame_rates.end()),
                         caps.frame_rates.end());
  caps.loaded = true;
  obs_data_release(root);
  return caps;
}

OpenStreamCameraState parse_camera_state_object(obs_data_t *root) {
  OpenStreamCameraState state;
  if (!root || data_number(root, "protocolVersion").value_or(0.0) != 2.0 ||
      !data_has_type(root, "settings", OBS_DATA_OBJECT) ||
      !data_has_type(root, "telemetry", OBS_DATA_OBJECT) ||
      !data_has_type(root, "tally", OBS_DATA_OBJECT)) {
    return state;
  }
  obs_data_t *settings = obs_data_get_obj(root, "settings");
  obs_data_t *telemetry = obs_data_get_obj(root, "telemetry");
  obs_data_t *tally = obs_data_get_obj(root, "tally");
  if (!settings || !telemetry || !tally) {
    if (settings) obs_data_release(settings);
    if (telemetry) obs_data_release(telemetry);
    if (tally) obs_data_release(tally);
    return state;
  }

  state.revision = static_cast<uint64_t>(data_number(root, "revision").value_or(0.0));
  state.authority = data_string(root, "authority").value_or("collaborative");
  state.exposure_mode = data_string(settings, "exposureMode").value_or("auto");
  state.focus_mode = data_string(settings, "focusMode").value_or("continuous");
  state.white_balance_mode = data_string(settings, "whiteBalanceMode").value_or("auto");
  state.focus_status = data_string(telemetry, "focusStatus").value_or("inactive");
  state.iso = data_number(telemetry, "actualIso").value_or(
      data_number(settings, "iso").value_or(0.0));
  state.shutter_us = data_number(telemetry, "actualShutterNs").value_or(
      data_number(settings, "shutterNs").value_or(0.0)) * 0.001;
  state.exposure_compensation = data_number(settings, "exposureCompensation").value_or(0.0);
  state.frame_rate = data_number(settings, "fps").value_or(0.0);
  state.focus_distance = data_number(telemetry, "actualFocusDistanceDiopters").value_or(
      data_number(settings, "focusDistanceDiopters").value_or(0.0));
  state.white_balance_kelvin = data_number(telemetry, "actualWhiteBalanceKelvin").value_or(
      data_number(settings, "whiteBalanceKelvin").value_or(0.0));
  state.white_balance_tint = data_number(settings, "whiteBalanceTint").value_or(0.0);
  state.white_balance_lock = data_bool(settings, "whiteBalanceLock").value_or(false);
  state.zoom_ratio = data_number(telemetry, "actualZoomRatio").value_or(
      data_number(settings, "zoomRatio").value_or(1.0));
  if (data_has_type(root, "zoomTransition", OBS_DATA_OBJECT)) {
    obs_data_t *transition = obs_data_get_obj(root, "zoomTransition");
    if (transition) {
      state.zoom_transition_active = data_bool(transition, "active").value_or(false);
      state.zoom_transition_target_ratio = data_number(transition, "targetRatio").value_or(state.zoom_ratio);
      state.zoom_transition_duration_ms = static_cast<int>(data_number(transition, "durationMs").value_or(0.0));
      obs_data_release(transition);
    }
  }
  state.torch = data_bool(settings, "torch").value_or(false);
  state.stabilization_mode = data_string(settings, "stabilizationMode").value_or("off");
  state.program_tally = data_bool(tally, "program").value_or(false);
  state.preview_tally = data_bool(tally, "preview").value_or(false);
  state.valid = true;
  obs_data_release(settings);
  obs_data_release(telemetry);
  obs_data_release(tally);
  return state;
}

OpenStreamCameraState parse_camera_state(const std::string &json) {
  obs_data_t *root = obs_data_create_from_json(json.c_str());
  if (!root) return {};
  const auto state = parse_camera_state_object(root);
  obs_data_release(root);
  return state;
}

OpenStreamCameraState parse_camera_state_response(const std::string &json) {
  obs_data_t *root = obs_data_create_from_json(json.c_str());
  if (!root) return {};
  OpenStreamCameraState state;
  obs_data_t *nested = data_has_type(root, "state", OBS_DATA_OBJECT)
                           ? obs_data_get_obj(root, "state") : nullptr;
  state = parse_camera_state_object(nested ? nested : root);
  if (nested) obs_data_release(nested);
  obs_data_release(root);
  return state;
}

void append_json_string(std::ostringstream &body, bool &first, const char *key,
                        const std::optional<std::string> &value) {
  if (!value) return;
  if (!first) body << ',';
  body << '"' << key << "\":\"" << json_escape(*value) << '"';
  first = false;
}

void append_json_number(std::ostringstream &body, bool &first, const char *key,
                        const std::optional<double> &value) {
  if (!value) return;
  if (!first) body << ',';
  body << '"' << key << "\":" << *value;
  first = false;
}

void append_json_bool(std::ostringstream &body, bool &first, const char *key,
                      const std::optional<bool> &value) {
  if (!value) return;
  if (!first) body << ',';
  body << '"' << key << "\":" << (*value ? "true" : "false");
  first = false;
}

std::string settings_body(const OpenStreamCommand &command) {
  std::ostringstream body;
  body << "{\"expectedRevision\":" << command.expected_revision << ",\"settings\":{";
  bool first = true;
  append_json_string(body, first, "exposureMode", command.settings.exposure_mode);
  append_json_number(body, first, "iso", command.settings.iso);
  std::optional<double> shutter_ns;
  if (command.settings.shutter_us) shutter_ns = *command.settings.shutter_us * 1000.0;
  append_json_number(body, first, "shutterNs", shutter_ns);
  append_json_number(body, first, "exposureCompensation", command.settings.exposure_compensation);
  append_json_number(body, first, "fps", command.settings.frame_rate);
  append_json_string(body, first, "focusMode", command.settings.focus_mode);
  append_json_number(body, first, "focusDistanceDiopters", command.settings.focus_distance);
  append_json_string(body, first, "whiteBalanceMode", command.settings.white_balance_mode);
  append_json_number(body, first, "whiteBalanceKelvin", command.settings.white_balance_kelvin);
  append_json_number(body, first, "whiteBalanceTint", command.settings.white_balance_tint);
  append_json_bool(body, first, "whiteBalanceLock", command.settings.white_balance_lock);
  append_json_number(body, first, "zoomRatio", command.settings.zoom_ratio);
  append_json_bool(body, first, "torch", command.settings.torch);
  append_json_string(body, first, "stabilizationMode", command.settings.stabilization_mode);
  body << "}}";
  return body.str();
}
}

std::vector<OpenStreamCameraSnapshot> openstream_camera_snapshots() {
  std::vector<OpenStreamCameraSnapshot> snapshots;
  std::vector<std::pair<OpenStreamSource *, obs_source_t *>> sources;
  {
    std::lock_guard<std::mutex> registry_lock(g_slot_registry_mutex);
    sources.reserve(g_source_slots.size());
    for (const auto &[key, unused_label] : g_source_slots) {
      (void)unused_label;
      auto *ctx = static_cast<OpenStreamSource *>(const_cast<void *>(key));
      if (ctx->source) sources.emplace_back(ctx, obs_source_get_ref(ctx->source));
    }
  }
  snapshots.reserve(sources.size());
  for (const auto &[ctx, source] : sources) {
    OpenStreamCameraSnapshot item;
    {
      std::lock_guard<std::mutex> settings_lock(ctx->settings_mutex);
      item.instance_id = ctx->instance_id;
      item.source_name = obs_source_get_name(source);
      item.slot_label = ctx->slot_label;
      item.production_label = ctx->device_name;
      item.status = ctx->slot_status;
      item.phone_label = ctx->phone_target_hint;
      item.listener_enabled = ctx->listener_enabled;
      item.phone_available = ctx->active_phone.has_value();
      item.live = ctx->phone_connected.load();
      item.paired = !ctx->control_token.empty();
      item.request_pending = ctx->control_request_pending;
      item.last_control_error = ctx->last_control_error;
      item.capabilities = ctx->capabilities;
      item.state = ctx->camera_state;
      item.zoom_presets = ctx->zoom_presets;
    }
    snapshots.push_back(std::move(item));
    obs_source_release(source);
  }
  std::sort(snapshots.begin(), snapshots.end(), [](const auto &left, const auto &right) {
    if (left.slot_label == right.slot_label) return left.source_name < right.source_name;
    return left.slot_label < right.slot_label;
  });
  return snapshots;
}

OpenStreamZoomPresetConfig openstream_zoom_presets(const std::string &instance_id) {
  obs_source_t *source = nullptr;
  OpenStreamSource *ctx = find_camera_source(instance_id, &source);
  if (!ctx || !source) return {};
  OpenStreamZoomPresetConfig config;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    config = ctx->zoom_presets;
  }
  obs_source_release(source);
  return config;
}

bool openstream_save_zoom_presets(const std::string &instance_id,
                                  const OpenStreamZoomPresetConfig &input) {
  obs_source_t *source = nullptr;
  OpenStreamSource *ctx = find_camera_source(instance_id, &source);
  if (!ctx || !source) return false;
  const auto config = sanitize_zoom_presets(input);
  obs_data_t *settings = obs_source_get_settings(source);
  obs_data_set_string(settings, "zoom_presets", zoom_presets_json(config).c_str());
  obs_source_update(source, settings);
  obs_data_release(settings);
  // `openstream_update` performs the same assignment, but retain this explicit
  // update so the dock model is immediately consistent on OBS versions which
  // defer source callbacks.
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    ctx->zoom_presets = config;
  }
  obs_source_release(source);
  notify_camera_changed(instance_id);
  return true;
}

void openstream_run_command_async(const std::string &instance_id,
                                  OpenStreamCommand command,
                                  OpenStreamCommandResult completion) {
  obs_source_t *source = nullptr;
  OpenStreamSource *ctx = find_camera_source(instance_id, &source);
  if (!ctx || !source) {
    if (completion) completion({false, "Camera source is no longer available", 0});
    return;
  }
  const bool remote_request = command.type != OpenStreamCommandType::Start &&
                              command.type != OpenStreamCommandType::Stop &&
                              command.type != OpenStreamCommandType::RefreshDiscovery;
  bool command_already_running = false;
  {
    std::lock_guard<std::mutex> lock(ctx->settings_mutex);
    if (remote_request && ctx->control_request_pending) {
      command_already_running = true;
    } else {
      if (remote_request) ctx->control_request_pending = true;
      ctx->last_control_error.clear();
    }
  }
  if (command_already_running) {
    obs_source_release(source);
    if (completion) completion({false, "Wait for the current camera command to finish", 0});
    return;
  }
  notify_camera_changed(instance_id);

  auto worker = std::async(std::launch::async, [source, ctx, instance_id, remote_request, command = std::move(command),
                      completion = std::move(completion)]() mutable {
    OpenStreamCommandResponse result;
    if (command.type == OpenStreamCommandType::Start ||
        command.type == OpenStreamCommandType::RefreshDiscovery) {
      openstream_start_worker(ctx);
      result = {true,
                command.type == OpenStreamCommandType::RefreshDiscovery
                    ? "Discovery refreshed" : "Listening for phone",
                0};
    } else if (command.type == OpenStreamCommandType::Stop) {
      openstream_stop_worker(ctx);
      result = {true, "Camera stopped", 0};
    } else {
      const auto phone = control_phone(ctx);
      if (!phone) {
        result.message = "Connect a phone before using camera controls";
      } else {
        std::string token;
        uint64_t revision = command.expected_revision;
        {
          std::lock_guard<std::mutex> lock(ctx->settings_mutex);
          token = ctx->control_token;
          if (revision == 0) revision = ctx->camera_state.revision;
        }

        if (command.type == OpenStreamCommandType::Pair) {
          std::ostringstream body;
          body << "{\"sourceInstanceId\":\"" << json_escape(instance_id)
               << "\",\"sourceName\":\"" << json_escape(obs_source_get_name(source)) << '"';
          if (!command.pairing_code.empty()) {
            body << ",\"pairingCode\":\"" << json_escape(command.pairing_code) << '"';
          }
          body << '}';
          const auto response = send_control_request(phone->host, phone->control_port,
                                                     "POST", "/v2/pair", body.str());
          const std::string paired_token = json_string_value(response.body, "token").value_or(
              json_string_value(response.body, "accessToken").value_or(""));
          result.ok = response.transport_ok && response.status_code >= 200 &&
                      response.status_code < 300 && !paired_token.empty();
          result.message = result.ok ? "Phone paired with this OBS source" :
              json_string_value(response.body, "message").value_or(
                  "Pairing failed; check the code shown on the phone");
          if (result.ok) {
            {
              std::lock_guard<std::mutex> lock(ctx->settings_mutex);
              ctx->control_token = paired_token;
            }
            obs_data_t *settings = obs_source_get_settings(source);
            obs_data_set_string(settings, "control_token", paired_token.c_str());
            obs_source_update(source, settings);
            obs_data_release(settings);
            token = paired_token;
          }
        } else if (token.empty()) {
          result.message = "Pair this phone before using professional controls";
        } else if (command.type == OpenStreamCommandType::RefreshRemoteState) {
          bool caps_loaded = false;
          {
            std::lock_guard<std::mutex> lock(ctx->settings_mutex);
            caps_loaded = ctx->capabilities.loaded;
          }
          bool caps_ok = caps_loaded;
          if (!caps_loaded) {
            const auto response = send_control_request(phone->host, phone->control_port,
                                                       "GET", "/v2/capabilities", {}, token);
            caps_ok = response.transport_ok && response.status_code >= 200 &&
                      response.status_code < 300;
            if (caps_ok) {
              const auto capabilities = parse_capabilities(response.body);
              caps_ok = capabilities.loaded;
              if (caps_ok) {
                std::lock_guard<std::mutex> lock(ctx->settings_mutex);
                ctx->capabilities = capabilities;
              }
            }
          }
          const auto response = send_control_request(phone->host, phone->control_port,
                                                     "GET", "/v2/state", {}, token);
          const bool state_ok = response.transport_ok && response.status_code >= 200 &&
                                response.status_code < 300;
          result.ok = caps_ok && state_ok;
          result.message = result.ok ? "Camera state updated" : "Camera control channel unavailable";
          if (state_ok) {
            const auto state = parse_camera_state(response.body);
            if (state.valid) {
              result.revision = state.revision;
              std::lock_guard<std::mutex> lock(ctx->settings_mutex);
              ctx->camera_state = state;
            } else {
              result.ok = false;
              result.message = "Camera returned an incompatible state payload";
            }
          }
        } else {
          std::string path;
          std::string body;
          bool returns_camera_state = true;
          command.expected_revision = revision;
          switch (command.type) {
            case OpenStreamCommandType::ApplySettings:
              path = "/v2/settings";
              body = settings_body(command);
              break;
            case OpenStreamCommandType::ZoomTransition: {
              path = "/v2/zoom-transition";
              std::ostringstream payload;
              payload << "{\"expectedRevision\":" << revision
                      << ",\"zoomRatio\":" << command.zoom_ratio
                      << ",\"durationMs\":" << command.zoom_duration_ms << '}';
              body = payload.str();
              break;
            }
            case OpenStreamCommandType::FocusAt: {
              path = "/v2/focus";
              std::ostringstream payload;
              payload << "{\"expectedRevision\":" << revision
                      << ",\"x\":" << std::clamp(command.focus_x, 0.0, 1.0)
                      << ",\"y\":" << std::clamp(command.focus_y, 0.0, 1.0)
                      << ",\"mode\":\"" << json_escape(command.focus_mode) << "\"}";
              body = payload.str();
              break;
            }
            case OpenStreamCommandType::SetAuthority:
              path = "/v2/authority";
              body = "{\"expectedRevision\":" + std::to_string(revision) +
                     ",\"mode\":\"" + json_escape(command.authority) + "\"}";
              break;
            case OpenStreamCommandType::SetTally:
              path = "/v2/tally";
              body = "{\"program\":" + std::string(command.program_tally ? "true" : "false") +
                     ",\"preview\":" + std::string(command.preview_tally ? "true" : "false") + "}";
              break;
            case OpenStreamCommandType::Identify: {
              path = "/identify";
              returns_camera_state = false;
              std::lock_guard<std::mutex> lock(ctx->settings_mutex);
              body = "{\"label\":\"" + json_escape(ctx->slot_label) +
                     "\",\"subtitle\":\"" + json_escape(ctx->device_name) + "\"}";
              break;
            }
            default: break;
          }
          const auto response = send_control_request(phone->host, phone->control_port,
                                                     "POST", path, body, token);
          result.ok = response.transport_ok && response.status_code >= 200 &&
                      response.status_code < 300 &&
                      json_bool_value(response.body, "ok").value_or(true);
          result.revision = json_uint64_value(response.body, "revision").value_or(revision);
          result.message = result.ok ? "Camera accepted the change" :
              json_string_value(response.body, "message").value_or(
                  response.status_code == 409
                      ? "Camera state changed; refresh and try again"
                      : "Phone rejected the control request");
          if (result.ok && returns_camera_state) {
            const auto state = parse_camera_state_response(response.body);
            if (state.valid) {
              result.revision = state.revision;
              std::lock_guard<std::mutex> lock(ctx->settings_mutex);
              ctx->camera_state = state;
            } else {
              result.ok = false;
              result.message = "Camera accepted the command but returned incompatible state";
            }
          }
        }
      }
    }
    {
      std::lock_guard<std::mutex> lock(ctx->settings_mutex);
      if (remote_request) ctx->control_request_pending = false;
      ctx->last_control_error = result.ok ? "" : result.message;
    }
    obs_source_release(source);
    notify_camera_changed(instance_id);
    if (completion) completion(std::move(result));
  });
  std::lock_guard<std::mutex> lock(g_ui_command_mutex);
  g_ui_command_tasks.erase(
      std::remove_if(g_ui_command_tasks.begin(), g_ui_command_tasks.end(), [](auto &task) {
        return task.wait_for(std::chrono::seconds(0)) == std::future_status::ready;
      }),
      g_ui_command_tasks.end());
  g_ui_command_tasks.push_back(std::move(worker));
}

uint64_t openstream_subscribe_camera_changes(OpenStreamCameraChangedCallback callback) {
  const uint64_t id = g_next_camera_subscription_id.fetch_add(1);
  std::lock_guard<std::mutex> lock(g_camera_subscription_mutex);
  g_camera_subscriptions.emplace(id, std::move(callback));
  return id;
}

void openstream_unsubscribe_camera_changes(uint64_t subscription_id) {
  std::lock_guard<std::mutex> lock(g_camera_subscription_mutex);
  g_camera_subscriptions.erase(subscription_id);
}

void openstream_run_command_async(const std::string &instance_id,
                                  OpenStreamUiCommand command,
                                  std::function<void(bool, std::string)> completion) {
  obs_source_t *source = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_slot_registry_mutex);
    for (const auto &[key, unused_label] : g_source_slots) {
      (void)unused_label;
      auto *ctx = static_cast<OpenStreamSource *>(const_cast<void *>(key));
      if (ctx->instance_id == instance_id && ctx->source) {
        source = obs_source_get_ref(ctx->source);
        break;
      }
    }
  }
  if (!source) {
    if (completion) completion(false, "Camera source is no longer available");
    return;
  }

  auto worker = std::async(std::launch::async, [source, instance_id, command, completion = std::move(completion)]() mutable {
    bool ok = false;
    std::string message;
    OpenStreamSource *ctx = nullptr;
    {
      std::lock_guard<std::mutex> registry_lock(g_slot_registry_mutex);
      for (const auto &[key, unused_label] : g_source_slots) {
        (void)unused_label;
        auto *candidate = static_cast<OpenStreamSource *>(const_cast<void *>(key));
        if (candidate->instance_id == instance_id) {
          ctx = candidate;
          break;
        }
      }
    }
    if (!ctx) {
      message = "Camera source was removed";
    } else if (command == OpenStreamUiCommand::Start || command == OpenStreamUiCommand::Refresh) {
        openstream_start_worker(ctx);
        ok = true;
        message = command == OpenStreamUiCommand::Refresh ? "Discovery refreshed" : "Listening for phone";
      } else if (command == OpenStreamUiCommand::Stop) {
        openstream_stop_worker(ctx);
        ok = true;
        message = "Camera slot stopped";
      } else {
        const auto phone = control_phone(ctx);
        if (!phone) {
          message = "Connect a phone before using camera controls";
        } else {
          std::string path;
          std::string body;
          std::string slot_label;
          std::string device_name;
          std::string token;
          double zoom = 1.0;
          {
            std::lock_guard<std::mutex> lock(ctx->settings_mutex);
            slot_label = ctx->slot_label;
            device_name = ctx->device_name;
            token = ctx->control_token;
            if (command == OpenStreamUiCommand::ZoomIn || command == OpenStreamUiCommand::ZoomOut) {
              const double delta = command == OpenStreamUiCommand::ZoomIn ? 0.25 : -0.25;
              ctx->last_cam_zoom = std::clamp(ctx->last_cam_zoom + delta, 1.0, 10.0);
            }
            zoom = ctx->last_cam_zoom;
          }
          switch (command) {
            case OpenStreamUiCommand::Identify:
              path = "/identify";
              body = "{\"label\":\"" + json_escape(slot_label) +
                     "\",\"subtitle\":\"" + json_escape(device_name) + "\"}";
              break;
            case OpenStreamUiCommand::TorchOn: path = "/torch"; body = "{\"enabled\":true}"; break;
            case OpenStreamUiCommand::TorchOff: path = "/torch"; body = "{\"enabled\":false}"; break;
            case OpenStreamUiCommand::RearCamera: path = "/lens"; body = "{\"lens\":\"1×\"}"; break;
            case OpenStreamUiCommand::FrontCamera: path = "/lens"; body = "{\"lens\":\"Front\"}"; break;
            case OpenStreamUiCommand::ZoomIn:
            case OpenStreamUiCommand::ZoomOut: {
              std::ostringstream value;
              value << "{\"value\":" << zoom << "}";
              path = "/zoom";
              body = value.str();
              break;
            }
            default: break;
          }
          ok = !path.empty() &&
               send_control_command(phone->host, phone->control_port, path, body, token);
          message = ok ? "Command sent" : "Phone did not accept the command";
        }
    }
    obs_source_release(source);
    if (completion) completion(ok, std::move(message));
  });
  std::lock_guard<std::mutex> lock(g_ui_command_mutex);
  g_ui_command_tasks.erase(
      std::remove_if(g_ui_command_tasks.begin(), g_ui_command_tasks.end(), [](auto &task) {
        return task.wait_for(std::chrono::seconds(0)) == std::future_status::ready;
      }),
      g_ui_command_tasks.end());
  g_ui_command_tasks.push_back(std::move(worker));
}

void openstream_wait_for_commands() {
  std::vector<std::future<void>> commands;
  {
    std::lock_guard<std::mutex> lock(g_ui_command_mutex);
    commands.swap(g_ui_command_tasks);
  }
  for (auto &command : commands) {
    command.wait();
  }
}

bool obs_module_load(void) {
#ifdef _WIN32
  WSADATA data = {};
  if (WSAStartup(MAKEWORD(2, 2), &data) == 0) {
    g_winsock_started = true;
  } else {
    blog(LOG_WARNING, "[OpenStream Beta] WSAStartup failed; discovery may not advertise");
  }
#endif
  obs_register_source(&openstream_source_info);
  obs_register_source(&openstream_legacy_source_info);
  openstream_dock_create();
  blog(LOG_INFO, "[OpenStream Beta] OBS plugin loaded: video + audio + remote controls (Made by @yashas.vm)");
  return true;
}

void obs_module_unload(void) {
  // Stop the dock timer and prevent new UI commands before draining workers.
  openstream_dock_destroy();
  openstream_wait_for_commands();
#ifdef _WIN32
  if (g_winsock_started) {
    WSACleanup();
    g_winsock_started = false;
  }
#endif
}
