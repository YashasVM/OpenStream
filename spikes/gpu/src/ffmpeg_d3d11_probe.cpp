#include <Windows.h>
#include <d3d11.h>
#include <dxgi1_6.h>
#include <psapi.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
}

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <limits>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

struct ComReleaser {
  template <typename T>
  void operator()(T* value) const noexcept {
    if (value != nullptr) {
      value->Release();
    }
  }
};

template <typename T>
using ComPtr = std::unique_ptr<T, ComReleaser>;

struct AvFormatCloser {
  void operator()(AVFormatContext* value) const noexcept {
    if (value != nullptr) {
      avformat_close_input(&value);
    }
  }
};

struct AvCodecCloser {
  void operator()(AVCodecContext* value) const noexcept {
    avcodec_free_context(&value);
  }
};

struct AvPacketCloser {
  void operator()(AVPacket* value) const noexcept {
    av_packet_free(&value);
  }
};

struct AvFrameCloser {
  void operator()(AVFrame* value) const noexcept {
    av_frame_free(&value);
  }
};

struct AvBufferCloser {
  void operator()(AVBufferRef* value) const noexcept {
    av_buffer_unref(&value);
  }
};

using FormatPtr = std::unique_ptr<AVFormatContext, AvFormatCloser>;
using CodecPtr = std::unique_ptr<AVCodecContext, AvCodecCloser>;
using PacketPtr = std::unique_ptr<AVPacket, AvPacketCloser>;
using FramePtr = std::unique_ptr<AVFrame, AvFrameCloser>;
using BufferPtr = std::unique_ptr<AVBufferRef, AvBufferCloser>;

[[nodiscard]] std::string AvError(const int error) {
  char buffer[AV_ERROR_MAX_STRING_SIZE]{};
  av_strerror(error, buffer, sizeof(buffer));
  return buffer;
}

[[nodiscard]] std::string HexLuid(const LUID& luid) {
  std::ostringstream value;
  value << "0x" << std::hex << std::uppercase << static_cast<std::uint32_t>(luid.HighPart)
        << ":0x" << static_cast<std::uint32_t>(luid.LowPart);
  return value.str();
}

[[nodiscard]] bool SameLuid(const LUID& left, const LUID& right) noexcept {
  return left.HighPart == right.HighPart && left.LowPart == right.LowPart;
}

[[nodiscard]] std::optional<LUID> ParseLuid(const std::string_view text) {
  const std::size_t separator = text.find(':');
  if (separator == std::string_view::npos || text.find(':', separator + 1) != std::string_view::npos) {
    return std::nullopt;
  }
  try {
    std::size_t parsed = 0;
    const unsigned long high = std::stoul(std::string(text.substr(0, separator)), &parsed, 0);
    if (parsed != separator) {
      return std::nullopt;
    }
    const std::string low_text(text.substr(separator + 1));
    const unsigned long low = std::stoul(low_text, &parsed, 0);
    if (parsed != low_text.size() || high > std::numeric_limits<std::uint32_t>::max() ||
        low > std::numeric_limits<std::uint32_t>::max()) {
      return std::nullopt;
    }
    return LUID{static_cast<DWORD>(low), static_cast<LONG>(static_cast<std::uint32_t>(high))};
  } catch (const std::exception&) {
    return std::nullopt;
  }
}

[[nodiscard]] std::string JsonEscape(const std::string_view value) {
  std::ostringstream result;
  for (const unsigned char character : value) {
    switch (character) {
      case '\\': result << "\\\\"; break;
      case '"': result << "\\\""; break;
      case '\n': result << "\\n"; break;
      case '\r': result << "\\r"; break;
      case '\t': result << "\\t"; break;
      default:
        if (character < 0x20U) {
          result << "\\u" << std::setw(4) << std::setfill('0') << std::hex
                 << static_cast<unsigned int>(character) << std::dec << std::setfill(' ');
        } else {
          result << static_cast<char>(character);
        }
    }
  }
  return result.str();
}

struct Options {
  std::vector<std::string> inputs;
  LUID adapter_luid{};
  std::size_t output_queue_capacity = 4;
  std::string metrics_path;
  bool list_adapters = false;
  bool realtime = false;
  std::uint64_t duration_seconds = 0;
};

[[noreturn]] void Usage(const char* const executable, const std::string_view reason = {}) {
  if (!reason.empty()) {
    std::cerr << "error: " << reason << '\n';
  }
  std::cerr << "Usage:\n  " << executable
            << " --list-adapters\n  " << executable
            << " --adapter-luid 0xHIGH:0xLOW --input corpus-1.mkv [--input corpus-2.mkv ...]"
               " [--queue-capacity 4] --metrics metrics.jsonl\n"
               "\nThe adapter LUID is mandatory for decoding; the probe never chooses a fallback adapter.\n";
  std::exit(reason.empty() ? EXIT_SUCCESS : EXIT_FAILURE);
}

[[nodiscard]] Options ParseOptions(const int argc, char** argv) {
  Options options;
  bool has_luid = false;
  for (int index = 1; index < argc; ++index) {
    const std::string_view argument(argv[index]);
    const auto require_value = [&]() -> std::string_view {
      if (++index >= argc) {
        Usage(argv[0], "missing value for " + std::string(argument));
      }
      return argv[index];
    };
    if (argument == "--list-adapters") {
      options.list_adapters = true;
    } else if (argument == "--adapter-luid") {
      const auto luid = ParseLuid(require_value());
      if (!luid.has_value()) {
        Usage(argv[0], "adapter LUID must be formatted as 0xHIGH:0xLOW");
      }
      options.adapter_luid = *luid;
      has_luid = true;
    } else if (argument == "--input") {
      options.inputs.emplace_back(require_value());
    } else if (argument == "--metrics") {
      options.metrics_path = require_value();
    } else if (argument == "--queue-capacity") {
      try {
        const auto parsed = std::stoull(std::string(require_value()));
        if (parsed == 0 || parsed > 1024) {
          Usage(argv[0], "queue capacity must be between 1 and 1024");
        }
        options.output_queue_capacity = static_cast<std::size_t>(parsed);
      } catch (const std::exception&) {
        Usage(argv[0], "queue capacity must be an integer");
      }
    } else if (argument == "--realtime") {
      options.realtime = true;
    } else if (argument == "--duration-seconds") {
      options.duration_seconds = std::stoull(std::string(require_value()));
    } else if (argument == "--help" || argument == "-h") {
      Usage(argv[0]);
    } else {
      Usage(argv[0], "unknown argument " + std::string(argument));
    }
  }
  if (!options.list_adapters && (!has_luid || options.inputs.empty() || options.metrics_path.empty())) {
    Usage(argv[0], "--adapter-luid, at least one --input, and --metrics are required");
  }
  return options;
}

void ListAdapters() {
  IDXGIFactory6* raw_factory = nullptr;
  const HRESULT result = CreateDXGIFactory1(IID_PPV_ARGS(&raw_factory));
  if (FAILED(result)) {
    throw std::runtime_error("CreateDXGIFactory1 failed");
  }
  ComPtr<IDXGIFactory6> factory(raw_factory);
  for (UINT index = 0;; ++index) {
    IDXGIAdapter1* raw_adapter = nullptr;
    const HRESULT enumerated = factory->EnumAdapterByGpuPreference(
        index, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE, IID_PPV_ARGS(&raw_adapter));
    if (enumerated == DXGI_ERROR_NOT_FOUND) {
      return;
    }
    if (FAILED(enumerated)) {
      throw std::runtime_error("EnumAdapterByGpuPreference failed");
    }
    ComPtr<IDXGIAdapter1> adapter(raw_adapter);
    DXGI_ADAPTER_DESC1 description{};
    if (FAILED(adapter->GetDesc1(&description))) {
      throw std::runtime_error("IDXGIAdapter1::GetDesc1 failed");
    }
    std::cout << "adapter=" << index << " luid=" << HexLuid(description.AdapterLuid) << '\n';
    std::wcout << L"  name=" << description.Description << L'\n';
  }
}

struct Device {
  ComPtr<ID3D11Device> d3d_device;
  ComPtr<ID3D11DeviceContext> d3d_context;
  BufferPtr ffmpeg_device;
  LUID luid{};

  explicit Device(const LUID& requested_luid) : luid(requested_luid) {
    ComPtr<IDXGIAdapter1> adapter;
    { // COM out parameters are easier with a raw temporary, then transferred to RAII.
      IDXGIFactory1* raw_factory = nullptr;
      if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&raw_factory)))) {
        throw std::runtime_error("CreateDXGIFactory1 failed");
      }
      ComPtr<IDXGIFactory1> factory(raw_factory);
      for (UINT index = 0;; ++index) {
        IDXGIAdapter1* raw_adapter = nullptr;
        const HRESULT enumerated = factory->EnumAdapters1(index, &raw_adapter);
        if (enumerated == DXGI_ERROR_NOT_FOUND) {
          break;
        }
        if (FAILED(enumerated)) {
          throw std::runtime_error("IDXGIFactory1::EnumAdapters1 failed");
        }
        ComPtr<IDXGIAdapter1> candidate(raw_adapter);
        DXGI_ADAPTER_DESC1 description{};
        if (FAILED(candidate->GetDesc1(&description))) {
          throw std::runtime_error("IDXGIAdapter1::GetDesc1 failed");
        }
        if (SameLuid(description.AdapterLuid, requested_luid)) {
          adapter = std::move(candidate);
          break;
        }
      }
    }
    if (!adapter) {
      throw std::runtime_error("requested adapter LUID is not present; refusing silent adapter switch");
    }

    ID3D11Device* raw_device = nullptr;
    ID3D11DeviceContext* raw_context = nullptr;
    D3D_FEATURE_LEVEL feature_level{};
    const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    const HRESULT created = D3D11CreateDevice(adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT, levels,
        static_cast<UINT>(std::size(levels)), D3D11_SDK_VERSION, &raw_device, &feature_level, &raw_context);
    if (FAILED(created)) {
      throw std::runtime_error("D3D11CreateDevice on selected LUID failed");
    }
    d3d_device.reset(raw_device);
    d3d_context.reset(raw_context);

    AVBufferRef* raw_hw_device = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
    if (raw_hw_device == nullptr) {
      throw std::runtime_error("av_hwdevice_ctx_alloc(D3D11VA) failed");
    }
    ffmpeg_device.reset(raw_hw_device);
    auto* const hw_device = reinterpret_cast<AVHWDeviceContext*>(ffmpeg_device->data);
    auto* const d3d11_device = reinterpret_cast<AVD3D11VADeviceContext*>(hw_device->hwctx);
    // FFmpeg takes ownership of this reference during av_hwdevice_ctx_init.
    d3d_device->AddRef();
    d3d_context->AddRef();
    d3d11_device->device = d3d_device.get();
    d3d11_device->device_context = d3d_context.get();
    const int initialized = av_hwdevice_ctx_init(ffmpeg_device.get());
    if (initialized < 0) {
      d3d11_device->device = nullptr;
      d3d11_device->device_context = nullptr;
      d3d_device->Release();
      d3d_context->Release();
      throw std::runtime_error("av_hwdevice_ctx_init(D3D11VA): " + AvError(initialized));
    }
  }
};

struct Counters {
  std::uint64_t packets_read = 0;
  std::uint64_t packets_sent = 0;
  std::uint64_t frames_decoded = 0;
  std::uint64_t texture_frames = 0;
  std::uint64_t software_frames_rejected = 0;
  std::uint64_t hwframe_transfers = 0;
  std::uint64_t cpu_uploads = 0;
  std::uint64_t cpu_readbacks = 0;
  std::uint64_t cpu_frame_copies = 0;
  std::uint64_t queue_overflows = 0;
  std::uint64_t packet_timestamp_evictions = 0;
  std::uint64_t device_removed = 0;
  std::uint64_t device_reset = 0;
  std::uint64_t device_hung = 0;
  std::uint64_t device_failure = 0;
};

struct FrameRecord {
  FramePtr frame;
  Clock::time_point packet_submit_time;
  std::int64_t pts = AV_NOPTS_VALUE;
};

class BoundedFrameQueue {
 public:
  explicit BoundedFrameQueue(const std::size_t capacity) : capacity_(capacity) {}

  [[nodiscard]] bool TryPush(FrameRecord value) {
    if (frames_.size() >= capacity_) {
      ++overflows_;
      return false;
    }
    frames_.push_back(std::move(value));
    high_water_ = std::max(high_water_, frames_.size());
    return true;
  }

  [[nodiscard]] std::optional<FrameRecord> TryPop() {
    if (frames_.empty()) {
      return std::nullopt;
    }
    FrameRecord result = std::move(frames_.front());
    frames_.pop_front();
    return result;
  }

  [[nodiscard]] std::size_t capacity() const noexcept { return capacity_; }
  [[nodiscard]] std::size_t depth() const noexcept { return frames_.size(); }
  [[nodiscard]] std::size_t high_water() const noexcept { return high_water_; }
  [[nodiscard]] std::size_t overflows() const noexcept { return overflows_; }

 private:
  std::size_t capacity_;
  std::size_t high_water_ = 0;
  std::size_t overflows_ = 0;
  std::deque<FrameRecord> frames_;
};

struct Session {
  std::string input;
  FormatPtr format;
  CodecPtr codec;
  int stream_index = -1;
  PacketPtr packet{av_packet_alloc()};
  FramePtr frame{av_frame_alloc()};
  BoundedFrameQueue queue;
  std::deque<std::pair<std::int64_t, Clock::time_point>> packet_times;
  bool input_finished = false;
  bool flushed = false;
  bool failed = false;
  bool realtime = false;
  std::int64_t first_pts = AV_NOPTS_VALUE;
  Clock::time_point playback_started{};

  Session(std::string input_path, const std::size_t queue_capacity)
      : input(std::move(input_path)), queue(queue_capacity) {
    if (!packet || !frame) {
      throw std::runtime_error("FFmpeg packet/frame allocation failed");
    }
  }
};

[[nodiscard]] AVPixelFormat RequireD3D11Format(AVCodecContext* context, const AVPixelFormat* formats) {
  for (const AVPixelFormat* current = formats; *current != AV_PIX_FMT_NONE; ++current) {
    if (*current == AV_PIX_FMT_D3D11) {
      return *current;
    }
  }
  std::cerr << "decoder " << (context->codec == nullptr ? "unknown" : context->codec->name)
            << " did not offer AV_PIX_FMT_D3D11; rejecting software fallback\n";
  return AV_PIX_FMT_NONE;
}

void OpenSession(Session& session, Device& device) {
  AVFormatContext* raw_format = nullptr;
  int result = avformat_open_input(&raw_format, session.input.c_str(), nullptr, nullptr);
  if (result < 0) {
    throw std::runtime_error("avformat_open_input(" + session.input + "): " + AvError(result));
  }
  session.format.reset(raw_format);
  result = avformat_find_stream_info(session.format.get(), nullptr);
  if (result < 0) {
    throw std::runtime_error("avformat_find_stream_info(" + session.input + "): " + AvError(result));
  }
  result = av_find_best_stream(session.format.get(), AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (result < 0) {
    throw std::runtime_error("no video stream in " + session.input + ": " + AvError(result));
  }
  session.stream_index = result;
  const AVCodecParameters* const parameters = session.format->streams[session.stream_index]->codecpar;
  const AVCodec* const decoder = avcodec_find_decoder(parameters->codec_id);
  if (decoder == nullptr) {
    throw std::runtime_error("no FFmpeg decoder for " + std::string(avcodec_get_name(parameters->codec_id)));
  }
  session.codec.reset(avcodec_alloc_context3(decoder));
  if (!session.codec) {
    throw std::runtime_error("avcodec_alloc_context3 failed");
  }
  result = avcodec_parameters_to_context(session.codec.get(), parameters);
  if (result < 0) {
    throw std::runtime_error("avcodec_parameters_to_context: " + AvError(result));
  }
  session.codec->get_format = RequireD3D11Format;
  session.codec->hw_device_ctx = av_buffer_ref(device.ffmpeg_device.get());
  if (session.codec->hw_device_ctx == nullptr) {
    throw std::runtime_error("av_buffer_ref(D3D11VA device) failed");
  }
  result = avcodec_open2(session.codec.get(), decoder, nullptr);
  if (result < 0) {
    throw std::runtime_error("avcodec_open2(" + session.input + "): " + AvError(result));
  }
}

[[nodiscard]] std::optional<Clock::time_point> FindPacketTime(Session& session, const std::int64_t pts) {
  if (pts == AV_NOPTS_VALUE) {
    return std::nullopt;
  }
  const auto found = std::find_if(session.packet_times.begin(), session.packet_times.end(),
      [pts](const auto& value) { return value.first == pts; });
  if (found == session.packet_times.end()) {
    return std::nullopt;
  }
  const Clock::time_point result = found->second;
  session.packet_times.erase(found);
  return result;
}

[[nodiscard]] bool TextureIsOnSelectedAdapter(ID3D11Texture2D* texture, const LUID& selected_luid) {
  ID3D11Device* raw_device = nullptr;
  texture->GetDevice(&raw_device);
  ComPtr<ID3D11Device> texture_device(raw_device);
  IDXGIDevice* raw_dxgi_device = nullptr;
  if (FAILED(texture_device->QueryInterface(IID_PPV_ARGS(&raw_dxgi_device)))) {
    return false;
  }
  ComPtr<IDXGIDevice> dxgi_device(raw_dxgi_device);
  IDXGIAdapter* raw_adapter = nullptr;
  if (FAILED(dxgi_device->GetAdapter(&raw_adapter))) {
    return false;
  }
  ComPtr<IDXGIAdapter> adapter(raw_adapter);
  DXGI_ADAPTER_DESC description{};
  return SUCCEEDED(adapter->GetDesc(&description)) && SameLuid(description.AdapterLuid, selected_luid);
}

void InspectFrame(Session& session, Device& device, Counters& counters) {
  ++counters.frames_decoded;
  if (session.frame->format != AV_PIX_FMT_D3D11 || session.frame->data[0] == nullptr) {
    ++counters.software_frames_rejected;
    throw std::runtime_error("decoder returned a non-D3D11 frame; software fallback is rejected");
  }
  auto* const texture = reinterpret_cast<ID3D11Texture2D*>(session.frame->data[0]);
  D3D11_TEXTURE2D_DESC description{};
  texture->GetDesc(&description);
  if (description.Width == 0 || description.Height == 0 || !TextureIsOnSelectedAdapter(texture, device.luid)) {
    throw std::runtime_error("decoder texture is not valid on the selected adapter; cross-adapter path rejected");
  }
  ++counters.texture_frames;

  const std::int64_t pts = session.frame->best_effort_timestamp;
  if (session.realtime && pts != AV_NOPTS_VALUE) {
    if (session.first_pts == AV_NOPTS_VALUE) {
      session.first_pts = pts;
      session.playback_started = Clock::now();
    }
    const AVRational time_base = session.format->streams[session.stream_index]->time_base;
    const auto target = session.playback_started + std::chrono::nanoseconds(
        av_rescale_q(pts - session.first_pts, time_base, AVRational{1, 1000000000}));
    if (target > Clock::now()) std::this_thread::sleep_until(target);
  }
  const auto submitted = FindPacketTime(session, pts).value_or(Clock::now());
  FramePtr retained(av_frame_clone(session.frame.get()));
  if (!retained) {
    throw std::runtime_error("av_frame_clone failed");
  }
  if (!session.queue.TryPush(FrameRecord{std::move(retained), submitted, pts})) {
    ++counters.queue_overflows;
  }
  av_frame_unref(session.frame.get());
}

void DrainFrames(Session& session, Device& device, Counters& counters) {
  for (;;) {
    const int result = avcodec_receive_frame(session.codec.get(), session.frame.get());
    if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
      return;
    }
    if (result < 0) {
      throw std::runtime_error("avcodec_receive_frame(" + session.input + "): " + AvError(result));
    }
    InspectFrame(session, device, counters);
  }
}

[[nodiscard]] bool PumpSession(Session& session, Device& device, Counters& counters) {
  if (session.input_finished) {
    return false;
  }
  av_packet_unref(session.packet.get());
  const int read_result = av_read_frame(session.format.get(), session.packet.get());
  if (read_result == AVERROR_EOF) {
    session.input_finished = true;
    return false;
  }
  if (read_result < 0) {
    throw std::runtime_error("av_read_frame(" + session.input + "): " + AvError(read_result));
  }
  ++counters.packets_read;
  if (session.packet->stream_index != session.stream_index) {
    return true;
  }
  const std::int64_t packet_timestamp = session.packet->pts == AV_NOPTS_VALUE
      ? session.packet->dts : session.packet->pts;
  if (packet_timestamp != AV_NOPTS_VALUE) {
    constexpr std::size_t kPacketTimestampCapacity = 512;
    if (session.packet_times.size() == kPacketTimestampCapacity) {
      session.packet_times.pop_front();
      ++counters.packet_timestamp_evictions;
    }
    session.packet_times.emplace_back(packet_timestamp, Clock::now());
  }
  const int sent = avcodec_send_packet(session.codec.get(), session.packet.get());
  if (sent == AVERROR(EAGAIN)) {
    DrainFrames(session, device, counters);
    const int resent = avcodec_send_packet(session.codec.get(), session.packet.get());
    if (resent < 0) {
      throw std::runtime_error("avcodec_send_packet retry(" + session.input + "): " + AvError(resent));
    }
  } else if (sent < 0) {
    throw std::runtime_error("avcodec_send_packet(" + session.input + "): " + AvError(sent));
  }
  ++counters.packets_sent;
  DrainFrames(session, device, counters);
  return true;
}

void FlushSession(Session& session, Device& device, Counters& counters) {
  if (session.flushed) {
    return;
  }
  const int result = avcodec_send_packet(session.codec.get(), nullptr);
  if (result < 0 && result != AVERROR_EOF) {
    throw std::runtime_error("avcodec_send_packet flush(" + session.input + "): " + AvError(result));
  }
  DrainFrames(session, device, counters);
  session.flushed = true;
}

[[nodiscard]] double ProcessCpuPercent(const Clock::time_point started, const std::uint64_t initial_cpu_100ns) {
  FILETIME created{};
  FILETIME exited{};
  FILETIME kernel{};
  FILETIME user{};
  if (!GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user)) {
    return -1.0;
  }
  ULARGE_INTEGER kernel_value{};
  kernel_value.LowPart = kernel.dwLowDateTime;
  kernel_value.HighPart = kernel.dwHighDateTime;
  ULARGE_INTEGER user_value{};
  user_value.LowPart = user.dwLowDateTime;
  user_value.HighPart = user.dwHighDateTime;
  const std::uint64_t cpu_100ns = kernel_value.QuadPart + user_value.QuadPart;
  const double elapsed_100ns = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - started).count()) / 100.0;
  const DWORD logical_processors = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
  return elapsed_100ns <= 0.0 || logical_processors == 0 ? 0.0
      : (100.0 * static_cast<double>(cpu_100ns - initial_cpu_100ns) /
          (elapsed_100ns * static_cast<double>(logical_processors)));
}

[[nodiscard]] std::uint64_t ProcessCpu100ns() {
  FILETIME created{};
  FILETIME exited{};
  FILETIME kernel{};
  FILETIME user{};
  if (!GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user)) {
    throw std::runtime_error("GetProcessTimes failed");
  }
  ULARGE_INTEGER kernel_value{};
  kernel_value.LowPart = kernel.dwLowDateTime;
  kernel_value.HighPart = kernel.dwHighDateTime;
  ULARGE_INTEGER user_value{};
  user_value.LowPart = user.dwLowDateTime;
  user_value.HighPart = user.dwHighDateTime;
  return kernel_value.QuadPart + user_value.QuadPart;
}

[[nodiscard]] std::uint64_t PrivateBytes() {
  PROCESS_MEMORY_COUNTERS_EX counters{};
  if (!GetProcessMemoryInfo(GetCurrentProcess(), reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&counters), sizeof(counters))) {
    return 0;
  }
  return static_cast<std::uint64_t>(counters.PrivateUsage);
}

[[nodiscard]] std::uint64_t LocalVideoMemoryBytes(Device& device) {
  IDXGIDevice* raw_dxgi_device = nullptr;
  if (FAILED(device.d3d_device->QueryInterface(IID_PPV_ARGS(&raw_dxgi_device)))) {
    return 0;
  }
  ComPtr<IDXGIDevice> dxgi_device(raw_dxgi_device);
  IDXGIAdapter* raw_adapter = nullptr;
  if (FAILED(dxgi_device->GetAdapter(&raw_adapter))) {
    return 0;
  }
  ComPtr<IDXGIAdapter> adapter(raw_adapter);
  IDXGIAdapter3* raw_adapter3 = nullptr;
  if (FAILED(adapter->QueryInterface(IID_PPV_ARGS(&raw_adapter3)))) {
    return 0;
  }
  ComPtr<IDXGIAdapter3> adapter3(raw_adapter3);
  DXGI_QUERY_VIDEO_MEMORY_INFO information{};
  return SUCCEEDED(adapter3->QueryVideoMemoryInfo(0, DXGI_MEMORY_SEGMENT_GROUP_LOCAL, &information))
      ? information.CurrentUsage : 0;
}

void WriteMetric(std::ofstream& metrics, const Clock::time_point started, const std::uint64_t initial_cpu_100ns,
    Device& device, const std::vector<std::unique_ptr<Session>>& sessions, const Counters& counters,
    const std::string_view event, const std::optional<double> frame_latency_ms = std::nullopt) {
  std::size_t queue_depth = 0;
  std::size_t queue_high_water = 0;
  std::size_t queue_overflows = 0;
  for (const auto& session : sessions) {
    queue_depth += session->queue.depth();
    queue_high_water += session->queue.high_water();
    queue_overflows += session->queue.overflows();
  }
  const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
  metrics << std::fixed << std::setprecision(3)
          << "{\"schema_version\":1,\"event\":\"" << JsonEscape(event)
          << "\",\"elapsed_ms\":" << elapsed_ms
          << ",\"adapter_luid\":\"" << HexLuid(device.luid)
          << "\",\"cpu_process_pct\":" << ProcessCpuPercent(started, initial_cpu_100ns)
          << ",\"private_bytes\":" << PrivateBytes()
          << ",\"gpu_local_memory_bytes\":" << LocalVideoMemoryBytes(device)
          << ",\"gpu_decode_utilisation_pct\":null,\"gpu_utilisation_source\":\"ETW_required\""
          << ",\"packets_read\":" << counters.packets_read
          << ",\"packets_sent\":" << counters.packets_sent
          << ",\"frames_decoded\":" << counters.frames_decoded
          << ",\"texture_frames\":" << counters.texture_frames
          << ",\"software_frames_rejected\":" << counters.software_frames_rejected
          << ",\"av_hwframe_transfer_data_calls\":" << counters.hwframe_transfers
          << ",\"cpu_uploads\":" << counters.cpu_uploads
          << ",\"cpu_readbacks\":" << counters.cpu_readbacks
          << ",\"cpu_frame_copies\":" << counters.cpu_frame_copies
          << ",\"queue_capacity_per_session\":" << (sessions.empty() ? 0 : sessions.front()->queue.capacity())
          << ",\"queue_depth\":" << queue_depth
          << ",\"queue_high_water_sum\":" << queue_high_water
          << ",\"queue_overflows\":" << queue_overflows
          << ",\"packet_timestamp_evictions\":" << counters.packet_timestamp_evictions
          << ",\"device_removed\":" << counters.device_removed
          << ",\"device_reset\":" << counters.device_reset
          << ",\"device_hung\":" << counters.device_hung
          << ",\"device_failure\":" << counters.device_failure
          << ",\"fallback\":\"disabled\",\"texture_format\":\"AV_PIX_FMT_D3D11\"";
  if (frame_latency_ms.has_value()) {
    metrics << ",\"packet_to_texture_latency_ms\":" << *frame_latency_ms;
  }
  metrics << "}\n";
  metrics.flush();
}

void DrainQueues(const std::vector<std::unique_ptr<Session>>& sessions, std::ofstream& metrics,
    const Clock::time_point started, const std::uint64_t initial_cpu_100ns, Device& device, const Counters& counters) {
  for (const auto& session : sessions) {
    while (auto record = session->queue.TryPop()) {
      const double latency = std::chrono::duration<double, std::milli>(Clock::now() - record->packet_submit_time).count();
      WriteMetric(metrics, started, initial_cpu_100ns, device, sessions, counters, "texture_frame", latency);
    }
  }
}

}  // namespace

int main(const int argc, char** argv) {
  try {
    const Options options = ParseOptions(argc, argv);
    if (options.list_adapters) {
      ListAdapters();
      return EXIT_SUCCESS;
    }

    Device device(options.adapter_luid);
    std::ofstream metrics(options.metrics_path, std::ios::out | std::ios::trunc);
    if (!metrics) {
      throw std::runtime_error("cannot create metrics file " + options.metrics_path);
    }
    std::vector<std::unique_ptr<Session>> sessions;
    sessions.reserve(options.inputs.size());
    for (const std::string& input : options.inputs) {
      auto session = std::make_unique<Session>(input, options.output_queue_capacity);
      session->realtime = options.realtime;
      OpenSession(*session, device);
      sessions.push_back(std::move(session));
    }

    Counters counters;
    const Clock::time_point started = Clock::now();
    const std::uint64_t initial_cpu_100ns = ProcessCpu100ns();
    WriteMetric(metrics, started, initial_cpu_100ns, device, sessions, counters, "started");
    Clock::time_point next_sample = started + std::chrono::seconds(1);
    bool made_progress = true;
    const auto deadline = options.duration_seconds == 0 ? Clock::time_point::max()
        : started + std::chrono::seconds(options.duration_seconds);
    while (made_progress && Clock::now() < deadline) {
      made_progress = false;
      for (const auto& session : sessions) {
        made_progress = PumpSession(*session, device, counters) || made_progress;
      }
      DrainQueues(sessions, metrics, started, initial_cpu_100ns, device, counters);
      if (Clock::now() >= next_sample) {
        WriteMetric(metrics, started, initial_cpu_100ns, device, sessions, counters, "sample");
        next_sample += std::chrono::seconds(1);
      }
      if (!made_progress && options.duration_seconds != 0 && Clock::now() < deadline) {
        for (const auto& session : sessions) {
          FlushSession(*session, device, counters);
          if (av_seek_frame(session->format.get(), session->stream_index, 0, AVSEEK_FLAG_BACKWARD) < 0)
            throw std::runtime_error("cannot loop corpus " + session->input);
          avcodec_flush_buffers(session->codec.get());
          session->input_finished = false;
          session->flushed = false;
          session->first_pts = AV_NOPTS_VALUE;
          session->packet_times.clear();
        }
        made_progress = true;
      }
    }
    for (const auto& session : sessions) {
      FlushSession(*session, device, counters);
    }
    DrainQueues(sessions, metrics, started, initial_cpu_100ns, device, counters);
    WriteMetric(metrics, started, initial_cpu_100ns, device, sessions, counters, "completed");
    if (counters.texture_frames == 0 || counters.software_frames_rejected != 0 || counters.hwframe_transfers != 0 || counters.cpu_uploads != 0 ||
        counters.cpu_readbacks != 0 || counters.cpu_frame_copies != 0) {
      throw std::runtime_error("hardware copy gate failed; see JSONL metrics");
    }
    std::cout << "{\"schema_version\":1,\"backend\":\"ffmpeg-d3d11\",\"result\":\"PASS\","
                 "\"adapter_luid\":\"" << HexLuid(device.luid) << "\","
                 "\"texture_type\":\"AV_PIX_FMT_D3D11/ID3D11Texture2D\","
                 "\"ordinary_cpu_frame_copies\":0,\"cpu_frame_uploads\":0,\"cpu_frame_readbacks\":0,"
                 "\"av_hwframe_transfer_data_calls\":0,\"frames\":" << counters.texture_frames << "}\n";
    return EXIT_SUCCESS;
  } catch (const std::exception& error) {
    std::cerr << "FFmpeg D3D11 probe failed: " << error.what() << '\n';
    return EXIT_FAILURE;
  }
}
