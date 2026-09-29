# OpenStream

OpenStream is a multi-platform streaming plugin that provides robust, low-latency streaming capabilities for desktop applications and mobile platforms.

**Key Features:**
- 🔴 **Live Streaming**: Stream directly from your application
- 🎯 **Multi-Platform**: Desktop (Windows, macOS, Linux) and mobile support
- ⚡ **Low Latency**: Optimized for real-time streaming
- 🔧 **Plugin Architecture**: Easy integration with existing applications
- 📱 **Mobile Support**: Full Android support

## Getting Started

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

## OBS Integration

OpenStream provides a comprehensive OBS Studio plugin for streaming integration. Install the plugin to enable streaming features within OBS.

### Plugin Installation

1. Build the plugin using the scripts above
2. Copy the plugin to your OBS plugins directory
3. Restart OBS to load the plugin

## Android Application

The Android version provides native streaming capabilities with minimal latency.

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

## Performance

OpenStream is optimized for:
- Minimal CPU usage
- Sub-100ms latency
- Support for 1080p @ 60fps streaming
- Concurrent stream handling

## License

MIT License - See LICENSE file for details

## Support

For issues, feature requests, or questions:
- Open an issue on GitHub
- Check existing documentation in `/website`
- Review the contributing guidelines

## Acknowledgments

OpenStream is built with community support and contributions from developers worldwide.
