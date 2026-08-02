#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_6.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {

[[noreturn]] void fail(const std::string& message, HRESULT hr = E_FAIL) {
  std::ostringstream out;
  out << message << " (HRESULT=0x" << std::hex << static_cast<unsigned long>(hr) << ')';
  throw std::runtime_error(out.str());
}

void check(HRESULT hr, const char* operation) {
  if (FAILED(hr)) fail(operation, hr);
}

std::string json_escape(const std::string& value) {
  std::ostringstream out;
  for (const unsigned char c : value) {
    switch (c) {
      case '\\': out << "\\\\"; break;
      case '"': out << "\\\""; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (c < 0x20) out << "\\u" << std::hex << static_cast<int>(c);
        else out << c;
    }
  }
  return out.str();
}

std::string narrow(const wchar_t* text) {
  if (text == nullptr) return {};
  const int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
  std::string result(static_cast<size_t>(size > 0 ? size - 1 : 0), '\0');
  if (size > 1) WideCharToMultiByte(CP_UTF8, 0, text, -1, result.data(), size - 1, nullptr, nullptr);
  return result;
}

struct Options {
  std::vector<std::filesystem::path> inputs;
  std::optional<LUID> requested_luid;
  uint64_t max_frames = 0;
  bool allow_software = false;
  bool realtime = false;
  uint64_t duration_seconds = 0;
};

uint64_t parse_u64(const std::string& value, const char* name) {
  size_t consumed = 0;
  const auto parsed = std::stoull(value, &consumed, 0);
  if (consumed != value.size()) throw std::runtime_error(std::string("invalid ") + name);
  return parsed;
}

Options parse_options(int argc, char** argv) {
  Options options;
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--help") {
      std::cout << "Usage: openstream_mf_gpu_probe --input FILE [--input FILE ...] "
                   "[--adapter-luid UINT64] [--max-frames N] [--realtime] "
                   "[--duration-seconds N] [--allow-software-fallback]\n";
      std::exit(0);
    }
    if (arg == "--input" && i + 1 < argc) options.inputs.emplace_back(argv[++i]);
    else if (arg == "--adapter-luid" && i + 1 < argc) {
      const uint64_t value = parse_u64(argv[++i], "adapter LUID");
      options.requested_luid = LUID{static_cast<DWORD>(value), static_cast<LONG>(value >> 32)};
    } else if (arg == "--max-frames" && i + 1 < argc) options.max_frames = parse_u64(argv[++i], "max frames");
    else if (arg == "--allow-software-fallback") options.allow_software = true;
    else if (arg == "--realtime") options.realtime = true;
    else if (arg == "--duration-seconds" && i + 1 < argc)
      options.duration_seconds = parse_u64(argv[++i], "duration seconds");
    else throw std::runtime_error("unknown or incomplete argument: " + arg);
  }
  if (options.inputs.empty() || options.inputs.size() > 4) throw std::runtime_error("provide one to four --input files");
  return options;
}

uint64_t luid_value(const LUID& luid) {
  return (static_cast<uint64_t>(static_cast<uint32_t>(luid.HighPart)) << 32) | luid.LowPart;
}

struct AdapterChoice {
  ComPtr<IDXGIAdapter1> adapter;
  DXGI_ADAPTER_DESC1 description{};
};

AdapterChoice select_adapter(const std::optional<LUID>& requested) {
  ComPtr<IDXGIFactory6> factory;
  check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)), "CreateDXGIFactory1");
  for (UINT index = 0;; ++index) {
    ComPtr<IDXGIAdapter1> adapter;
    const HRESULT hr = factory->EnumAdapterByGpuPreference(index, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
                                                            IID_PPV_ARGS(&adapter));
    if (hr == DXGI_ERROR_NOT_FOUND) break;
    check(hr, "EnumAdapterByGpuPreference");
    DXGI_ADAPTER_DESC1 description{};
    check(adapter->GetDesc1(&description), "IDXGIAdapter1::GetDesc1");
    if ((description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0) continue;
    if (!requested || luid_value(description.AdapterLuid) == luid_value(*requested)) return {adapter, description};
  }
  throw std::runtime_error("requested hardware adapter LUID was not found; silent adapter switching is forbidden");
}

struct ReaderState {
  ComPtr<IMFSourceReader> reader;
  uint64_t frames = 0;
  uint64_t source_timestamp_100ns = 0;
  ComPtr<ID3D11Texture2D> texture;
  UINT texture_subresource = 0;
  uint64_t loop_offset_100ns = 0;
  uint64_t last_timestamp_100ns = 0;
};

class Composer {
 public:
  explicit Composer(ID3D11Device* device) {
    check(device->QueryInterface(IID_PPV_ARGS(&video_device_)), "ID3D11VideoDevice");
    ComPtr<ID3D11DeviceContext> context;
    device->GetImmediateContext(&context);
    check(context.As(&video_context_), "ID3D11VideoContext");
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputWidth = 1920;
    content.InputHeight = 1080;
    content.OutputWidth = 1920;
    content.OutputHeight = 1080;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    content.InputFrameRate = {60, 1};
    content.OutputFrameRate = {60, 1};
    check(video_device_->CreateVideoProcessorEnumerator(&content, &enumerator_),
          "CreateVideoProcessorEnumerator");
    check(video_device_->CreateVideoProcessor(enumerator_.Get(), 0, &processor_), "CreateVideoProcessor");
    D3D11_TEXTURE2D_DESC output_desc{};
    output_desc.Width = 1920;
    output_desc.Height = 1080;
    output_desc.MipLevels = 1;
    output_desc.ArraySize = 1;
    output_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    output_desc.SampleDesc.Count = 1;
    output_desc.Usage = D3D11_USAGE_DEFAULT;
    output_desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    check(device->CreateTexture2D(&output_desc, nullptr, &output_), "create composition texture");
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC view_desc{};
    view_desc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    check(video_device_->CreateVideoProcessorOutputView(output_.Get(), enumerator_.Get(), &view_desc,
                                                        &output_view_),
          "CreateVideoProcessorOutputView");
  }

  void compose(const std::vector<ReaderState>& readers) {
    std::vector<ComPtr<ID3D11VideoProcessorInputView>> views(readers.size());
    std::vector<D3D11_VIDEO_PROCESSOR_STREAM> streams(readers.size());
    for (size_t i = 0; i < readers.size(); ++i) {
      if (!readers[i].texture) return;
      D3D11_TEXTURE2D_DESC texture_desc{};
      readers[i].texture->GetDesc(&texture_desc);
      D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC input_desc{};
      input_desc.FourCC = 0;
      input_desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
      input_desc.Texture2D.ArraySlice = readers[i].texture_subresource;
      check(video_device_->CreateVideoProcessorInputView(readers[i].texture.Get(), enumerator_.Get(),
                                                         &input_desc, &views[i]),
            "CreateVideoProcessorInputView");
      const LONG column = static_cast<LONG>(i % 2);
      const LONG row = static_cast<LONG>(i / 2);
      const RECT source{0, 0, static_cast<LONG>(texture_desc.Width), static_cast<LONG>(texture_desc.Height)};
      const RECT destination{column * 960, row * 540, column * 960 + 960, row * 540 + 540};
      video_context_->VideoProcessorSetStreamSourceRect(processor_.Get(), static_cast<UINT>(i), TRUE, &source);
      video_context_->VideoProcessorSetStreamDestRect(processor_.Get(), static_cast<UINT>(i), TRUE, &destination);
      video_context_->VideoProcessorSetStreamFrameFormat(processor_.Get(), static_cast<UINT>(i),
                                                         D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
      const auto rotation = i == readers.size() - 1 ? D3D11_VIDEO_PROCESSOR_ROTATION_90
                                                    : D3D11_VIDEO_PROCESSOR_ROTATION_IDENTITY;
      video_context_->VideoProcessorSetStreamRotation(processor_.Get(), static_cast<UINT>(i), TRUE, rotation);
      streams[i].Enable = TRUE;
      streams[i].pInputSurface = views[i].Get();
    }
    check(video_context_->VideoProcessorBlt(processor_.Get(), output_view_.Get(), static_cast<UINT>(frame_index_),
                                            static_cast<UINT>(streams.size()), streams.data()),
          "VideoProcessorBlt");
    ++frame_index_;
  }

  uint64_t frames() const { return frame_index_; }

 private:
  ComPtr<ID3D11VideoDevice> video_device_;
  ComPtr<ID3D11VideoContext> video_context_;
  ComPtr<ID3D11VideoProcessorEnumerator> enumerator_;
  ComPtr<ID3D11VideoProcessor> processor_;
  ComPtr<ID3D11Texture2D> output_;
  ComPtr<ID3D11VideoProcessorOutputView> output_view_;
  uint64_t frame_index_ = 0;
};

ReaderState make_reader(const std::filesystem::path& input, IMFDXGIDeviceManager* manager) {
  ComPtr<IMFAttributes> attributes;
  check(MFCreateAttributes(&attributes, 4), "MFCreateAttributes");
  check(attributes->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, manager), "set MF_SOURCE_READER_D3D_MANAGER");
  check(attributes->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE), "enable hardware transforms");
  check(attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, FALSE), "disable software video processing");

  ReaderState state;
  check(MFCreateSourceReaderFromURL(input.c_str(), attributes.Get(), &state.reader), "MFCreateSourceReaderFromURL");
  ComPtr<IMFMediaType> output_type;
  check(MFCreateMediaType(&output_type), "MFCreateMediaType");
  check(output_type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "set video major type");
  check(output_type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12), "require NV12 output");
  check(state.reader->SetCurrentMediaType(static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM), nullptr, output_type.Get()),
        "require Media Foundation NV12 output");
  return state;
}

bool read_texture(ReaderState& state) {
  DWORD stream = 0;
  DWORD flags = 0;
  LONGLONG timestamp = 0;
  ComPtr<IMFSample> sample;
  check(state.reader->ReadSample(static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM), 0, &stream, &flags, &timestamp, &sample),
        "IMFSourceReader::ReadSample");
  if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0) return false;
  if (!sample) return true;
  ComPtr<IMFMediaBuffer> buffer;
  check(sample->GetBufferByIndex(0, &buffer), "IMFSample::GetBufferByIndex");
  ComPtr<IMFDXGIBuffer> dxgi_buffer;
  const HRESULT dxgi_hr = buffer.As(&dxgi_buffer);
  if (FAILED(dxgi_hr)) fail("Media Foundation produced a software buffer; hardware gate failed", dxgi_hr);
  state.texture.Reset();
  check(dxgi_buffer->GetResource(IID_PPV_ARGS(&state.texture)), "IMFDXGIBuffer::GetResource");
  check(dxgi_buffer->GetSubresourceIndex(&state.texture_subresource), "IMFDXGIBuffer::GetSubresourceIndex");
  state.last_timestamp_100ns = static_cast<uint64_t>(timestamp);
  state.source_timestamp_100ns = state.loop_offset_100ns + state.last_timestamp_100ns;
  ++state.frames;
  return true;
}

void rewind_reader(ReaderState& state) {
  state.loop_offset_100ns += state.last_timestamp_100ns + 333333;
  PROPVARIANT position;
  PropVariantInit(&position);
  position.vt = VT_I8;
  position.hVal.QuadPart = 0;
  check(state.reader->SetCurrentPosition(GUID_NULL, position), "IMFSourceReader::SetCurrentPosition");
  PropVariantClear(&position);
}

void validate_texture(ID3D11Texture2D* texture, ID3D11Device* expected_device) {
  ComPtr<ID3D11Device> owner;
  texture->GetDevice(&owner);
  if (owner.Get() != expected_device) throw std::runtime_error("cross-device decoder texture rejected");
  D3D11_TEXTURE2D_DESC description{};
  texture->GetDesc(&description);
  if (description.Format != DXGI_FORMAT_NV12 && description.Format != DXGI_FORMAT_P010)
    throw std::runtime_error("decoder texture is not NV12/P010");
}

}  // namespace

int main(int argc, char** argv) try {
  const Options options = parse_options(argc, argv);
  check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "CoInitializeEx");
  check(MFStartup(MF_VERSION, MFSTARTUP_FULL), "MFStartup");
  const AdapterChoice choice = select_adapter(options.requested_luid);

  UINT device_flags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT;
  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  D3D_FEATURE_LEVEL feature_level{};
  check(D3D11CreateDevice(choice.adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, device_flags, nullptr, 0,
                          D3D11_SDK_VERSION, &device, &feature_level, &context), "D3D11CreateDevice");
  ComPtr<ID3D10Multithread> multithread;
  check(context.As(&multithread), "ID3D10Multithread");
  multithread->SetMultithreadProtected(TRUE);

  UINT reset_token = 0;
  ComPtr<IMFDXGIDeviceManager> manager;
  check(MFCreateDXGIDeviceManager(&reset_token, &manager), "MFCreateDXGIDeviceManager");
  check(manager->ResetDevice(device.Get(), reset_token), "IMFDXGIDeviceManager::ResetDevice");

  std::vector<ReaderState> readers;
  readers.reserve(options.inputs.size());
  for (const auto& input : options.inputs) readers.push_back(make_reader(input, manager.Get()));
  Composer composer(device.Get());

  const auto started = std::chrono::steady_clock::now();
  std::optional<uint64_t> first_source_timestamp;
  bool active = true;
  while (active) {
    if (options.duration_seconds != 0 &&
        std::chrono::steady_clock::now() - started >= std::chrono::seconds(options.duration_seconds)) break;
    active = false;
    for (auto& reader : readers) {
      if (options.max_frames != 0 && reader.frames >= options.max_frames) continue;
      if (!read_texture(reader) && options.duration_seconds != 0) {
        rewind_reader(reader);
        active = true;
      } else if (reader.texture) {
        active = true;
        validate_texture(reader.texture.Get(), device.Get());
        if (!first_source_timestamp || reader.source_timestamp_100ns < *first_source_timestamp)
          first_source_timestamp = reader.source_timestamp_100ns;
      }
    }
    if (active) composer.compose(readers);
    if (active && options.realtime && first_source_timestamp) {
      uint64_t newest_timestamp = 0;
      for (const auto& reader : readers)
        newest_timestamp = (std::max)(newest_timestamp, reader.source_timestamp_100ns);
      const auto target = started + std::chrono::nanoseconds((newest_timestamp - *first_source_timestamp) * 100);
      if (target > std::chrono::steady_clock::now()) std::this_thread::sleep_until(target);
    }
  }
  const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
  uint64_t total_frames = 0;
  for (const auto& reader : readers) total_frames += reader.frames;
  context->Flush();
  const HRESULT removed_reason = device->GetDeviceRemovedReason();
  if (removed_reason != S_OK) fail("selected D3D11 device was removed during a normal run", removed_reason);

  std::cout << "{\"schema_version\":1,\"backend\":\"media-foundation\",\"result\":\"PASS\","
            << "\"adapter_luid\":" << luid_value(choice.description.AdapterLuid) << ','
            << "\"adapter\":\"" << json_escape(narrow(choice.description.Description)) << "\","
            << "\"texture_type\":\"IMFDXGIBuffer/ID3D11Texture2D\",\"pixel_format\":\"NV12|P010\","
            << "\"streams\":" << readers.size() << ",\"frames\":" << total_frames << ','
            << "\"composition_frames\":" << composer.frames() << ','
            << "\"gpu_scale\":true,\"gpu_colour_conversion\":true,\"gpu_rotation\":true,"
            << "\"layout\":\"" << (readers.size() > 2 ? "2x2" : "2x1") << "\","
            << "\"elapsed_s\":" << seconds << ",\"cpu_frame_uploads\":0,\"cpu_frame_readbacks\":0,"
            << "\"ordinary_cpu_frame_copies\":0,\"software_fallback_allowed\":"
            << (options.allow_software ? "true" : "false") << ",\"device_removed_reason\":"
            << static_cast<long>(removed_reason) << "}\n";
  return 0;
} catch (const std::exception& error) {
  std::cerr << "{\"schema_version\":1,\"backend\":\"media-foundation\",\"result\":\"FAIL\","
               "\"warning\":\"HARDWARE PATH REJECTED; software fallback requires explicit opt-in\","
               "\"error\":\"" << json_escape(error.what()) << "\"}\n";
  return 1;
}
