# OpenStream

OpenStream is a multi-platform streaming plugin that provides robust, low-latency streaming capabilities for desktop applications and mobile platforms.

**Key Features:**
- 🔴 **Live Streaming**: Stream directly from your application
- 🎯 **Multi-Platform**: Desktop (Windows, macOS, Linux) and mobile support
- ⚡ **Low Latency**: Optimized for real-time streaming
- 🔧 **Plugin Architecture**: Easy integration with existing applications
- 📱 **Mobile Support**: Full Android support

## Quick Start

### Prerequisites

- CMake 3.15+
- C++17 compatible compiler
- For Android: Android NDK
- For Desktop: Qt 6.0+ (optional)

### Building

#### Linux/macOS

```bash
./build_plugin_linux.sh
```

#### Windows

```bash
build_plugin.bat
```

#### Android

```bash
cd android
./gradlew build
```

## Project Structure

```
OpenStream/
├── obs-plugin/          # OBS Studio plugin implementation
├── android/             # Android application and libraries
├── website/             # Documentation website
├── tools/               # Build and utility tools
├── tests/               # Test suites
└── README.md
```

## API Documentation

OpenStream provides a clean C++ API for integration:

```cpp
#include "openstream/openstream.h"

// Initialize streaming
OpenStream::StreamConfig config;
config.bitrate = 5000;  // 5 Mbps
config.fps = 60;
config.resolution = {1920, 1080};

auto stream = OpenStream::CreateStream(config);
stream->Start();
```

## OBS Integration

OpenStream provides a comprehensive OBS Studio plugin for streaming integration. Install the plugin to enable streaming features within OBS.

### Plugin Installation

1. Build the plugin using the scripts above
2. Copy the plugin to your OBS plugins directory
3. Restart OBS to load the plugin

## Android Application

The Android version provides native streaming capabilities with minimal latency.

### IRL Live (Beta)

Go live directly from your phone to Twitch, YouTube, Kick, Facebook, custom RTMP(S), or custom SRT. No PC is required. OBS mode remains the default.

1. Open the app and select **IRL Live (Beta)**.
2. Pick a platform, paste your stream key and any required server URL, and choose a quality. For custom SRT, enter the full SRT URL instead of a stream key.
3. Tap **Go Live**. Streaming continues with the screen locked.
4. Tap **Stop** in the app or the streaming notification to end the stream.

The default is 720p at 30 fps with about 3.5 Mbps video, 2-second keyframes, and AAC audio at 128 kbps. Auto-reconnect, adaptive bitrate, and network handover help keep the stream running as mobile connectivity changes.

This mode is beta. It supports one destination at a time and H.264 video only. Chat, OAuth sign-in, and SRTLA bonding are not supported. YouTube and Kick may require setup in their streaming dashboards before you can go live.

### Building Android

```bash
cd android
./gradlew build
# Output: android/app/build/outputs/apk/
```

## Contributing

We welcome contributions! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Development Setup

### Setting Up Your Environment

```bash
# Clone the repository
git clone https://github.com/YashasVM/OpenStream.git
cd OpenStream

# Install dependencies
./build_plugin_linux.sh
```

### Running Tests

```bash
cd tests
./run_tests.sh
```

## Troubleshooting

### Common Issues

**Build fails with CMake error:**
- Ensure CMake 3.15+ is installed
- Clear build directory and retry: `rm -rf build && ./build_plugin_linux.sh`

**OBS plugin not detected:**
- Check plugin installation path
- Verify file permissions on plugin files
- Restart OBS completely

## Performance Metrics

OpenStream is optimized for:
- Minimal CPU usage (<10% on modern hardware)
- Sub-100ms latency for reliable streaming
- Support for 1080p @ 60fps streaming
- Concurrent stream handling with multiple outputs

## License

MIT License - See LICENSE file for details

## Support & Feedback

For issues, feature requests, or questions:
- Open an issue on GitHub
- Check existing documentation in `/website`
- Review the contributing guidelines
- Join our community discussions

## Acknowledgments

OpenStream is built with community support and contributions from developers worldwide.
