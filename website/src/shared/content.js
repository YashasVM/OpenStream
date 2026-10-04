// Facts shared by every site variant. Keep in sync with the Android app and OBS plugin.
const release = "https://github.com/YashasVM/OpenStream/releases/latest/download";

export const links = {
  apk: `${release}/openstream-android.apk`,
  apkSha: `${release}/openstream-android.apk.sha256`,
  installer: `${release}/openstream-obs-plugin-installer-windows-x64.exe`,
  zip: `${release}/openstream-obs-windows-x64.zip`,
  release: "https://github.com/YashasVM/OpenStream/releases/latest",
  repo: "https://github.com/YashasVM/OpenStream",
  issues: "https://github.com/YashasVM/OpenStream/issues",
  author: "https://yash0.in",
};

export const specs = [
  { value: 120, unit: "ms", label: "SRT latency buffer" },
  { value: 1080, unit: "p", label: "up to 60 fps" },
  { value: 0, unit: "", label: "cloud servers involved", display: "0" },
  { value: 50, unit: "Mbps", label: "max bitrate" },
];

export const pipeline = [
  { key: "capture", title: "Camera2", body: "The app opens your phone's real camera hardware — every lens it exposes, plus the front camera and microphone." },
  { key: "encode", title: "Hardware encode", body: "The phone's own encoder compresses to H.264 video and AAC audio. Your computer does zero encoding work." },
  { key: "transmit", title: "SRT over Wi-Fi", body: "Packed into MPEG-TS and sent over SRT — a protocol built for live video on imperfect networks." },
  { key: "receive", title: "Native OBS source", body: "The plugin decodes straight into an OBS source that behaves like any webcam in your scenes." },
];

export const ports = [
  { name: "Media", value: "SRT :9100" },
  { name: "Discovery", value: "UDP :51615" },
  { name: "Camera control", value: "HTTP :9101" },
];

export const features = [
  { key: "lens", title: "Every lens", body: "Ultra-wide, main, telephoto and front. Switch on the phone or from OBS." },
  { key: "dock", title: "Control from OBS", body: "A Camera Control dock inside OBS for lens, zoom and torch. Hands off the phone." },
  { key: "discover", title: "Finds your PC", body: "OBS computers on your network appear in the app. Tap one. No IP addresses." },
  { key: "reconnect", title: "Reconnects itself", body: "Brief Wi-Fi drops recover on their own. The source stays put in your scene." },
  { key: "audio", title: "Real audio", body: "AAC audio from the phone mic, synced with the video and on its own track." },
  { key: "local", title: "Local only", body: "No accounts, no relay, no telemetry. Video never leaves your network." },
];

export const lenses = ["0.6×", "1×", "3×", "5×", "Front"];

export const presets = [
  { name: "Data saver", res: "720p", fps: 30, rate: "8 Mbps", note: "Crowded or 2.4 GHz Wi-Fi" },
  { name: "Balanced", res: "1080p", fps: 30, rate: "12 Mbps", note: "The default. Right for most setups", isDefault: true },
  { name: "High", res: "1080p", fps: 60, rate: "50 Mbps", note: "Strong 5 GHz or Wi-Fi 6" },
];

export const platforms = {
  android: {
    label: "Android",
    side: "Phone",
    need: "Android 10 or newer",
    files: [
      { name: "OpenStream for Android", desc: "Signed APK", ext: "apk", href: links.apk, primary: true },
      { name: "SHA-256 checksum", desc: "Verify your download", ext: "sha256", href: links.apkSha },
    ],
    steps: [
      { title: "Download the APK", body: "Open this page on your phone and tap download, or copy the file across." },
      { title: "Allow the install", body: "Android asks once to allow installs from your browser or file manager." },
      { title: "Grant camera and mic", body: "Open OpenStream and accept both permissions. The viewfinder appears." },
    ],
  },
  windows: {
    label: "Windows",
    side: "OBS",
    need: "Windows 10/11 x64 · OBS Studio 30+",
    files: [
      { name: "OBS plugin installer", desc: "Recommended — finds OBS for you", ext: "exe", href: links.installer, primary: true },
      { name: "Manual ZIP", desc: "For custom OBS install paths", ext: "zip", href: links.zip },
    ],
    steps: [
      { title: "Close OBS", body: "The plugin can't be replaced while OBS has it loaded." },
      { title: "Run the installer", body: "It asks for admin rights once and installs into your OBS folder." },
      { title: "Reopen OBS", body: "The OpenStream source and Camera Control dock are ready." },
    ],
    note: "Using the ZIP? Extract everything, then right-click install-openstream-plugin.bat → Run as administrator.",
  },
  linux: {
    label: "Linux",
    side: "OBS",
    need: "OBS Studio 30+ · build from source",
    files: [{ name: "Source code", desc: "Build script and per-user installer", ext: "git", href: links.repo, primary: true, external: true }],
    steps: [
      {
        title: "Install build dependencies",
        body: "Debian / Ubuntu shown — use your distro's equivalents.",
        cmds: ["sudo apt install cmake g++ pkg-config libobs-dev libavformat-dev libavcodec-dev libavutil-dev libswscale-dev qt6-base-dev"],
      },
      {
        title: "Build and install",
        body: "Builds the plugin and installs it for your user. No root needed.",
        cmds: ["git clone https://github.com/YashasVM/OpenStream && cd OpenStream", "./build_plugin_linux.sh --install-user"],
      },
      { title: "Restart OBS", body: "The OpenStream source and dock load on next start." },
    ],
  },
};

export const golive = [
  { title: "Same network", body: "Phone and computer on the same Wi-Fi. 5 GHz or Wi-Fi 6 if you have it." },
  { title: "Add the source", body: "In OBS: Sources → + → OpenStream. One source per phone." },
  { title: "Pick your computer", body: "Open the app — your PC appears under “Choose your OBS computer”. Tap it." },
  { title: "Frame the shot", body: "Choose a lens and quality. Turn on Stay to keep the screen awake." },
  { title: "Go live", body: "Stream or record as usual. Fine-tune from the Camera Control dock." },
];

export const faq = [
  { q: "My computer doesn't show up in the app.", a: "Both devices need the same network and subnet. Guest networks and “client isolation” block discovery. Allow UDP 51615 through your computer's firewall and turn off VPNs for the first pairing." },
  { q: "The picture stutters or freezes.", a: "Drop to Balanced or Data saver, move closer to the router, or switch to 5 GHz. A wired PC with the phone on 5 GHz is the most stable setup." },
  { q: "Does it work over the internet?", a: "No — on purpose. OpenStream is built for your local network. That's what keeps latency low and your video private." },
  { q: "Is there an iPhone app or a macOS plugin?", a: "Not yet. Today it's Android on the phone side, and Windows or Linux for OBS." },
  { q: "Windows warns me about the installer.", a: "SmartScreen flags newer apps. Choose More info → Run anyway, or use the ZIP. Every file is built from the public source on GitHub." },
  { q: "What does it cost?", a: "Nothing. It's open source, with no accounts, no ads and no telemetry." },
];
