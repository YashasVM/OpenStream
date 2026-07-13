export const product = {
  name: "OpenStream",
  version: "2.1.0-beta",
  displayVersion: "V2.1 beta",
  releaseDate: "July 13, 2026",
  requirements: {
    android: "Android 10+ with Camera2 and hardware MediaCodec",
    windows: "Windows 10 or 11, x64",
    obs: "OBS Studio 30+",
    network: "Same local network; 5 GHz or Wi-Fi 6 recommended",
  },
};

const releaseRoot = "https://github.com/YashasVM/OpenStream/releases/latest";

export const links = {
  apk: `${releaseRoot}/download/openstream-android.apk`,
  installer: `${releaseRoot}/download/openstream-obs-plugin-installer-windows-x64.exe`,
  zip: `${releaseRoot}/download/openstream-obs-windows-x64.zip`,
  release: releaseRoot,
  repo: "https://github.com/YashasVM/OpenStream",
  issues: "https://github.com/YashasVM/OpenStream/issues",
  setup: "https://github.com/YashasVM/OpenStream/blob/main/docs/set-up.md",
  protocol: "https://github.com/YashasVM/OpenStream/blob/main/docs/protocol.md",
};

export const setupSteps = [
  {
    id: "download",
    number: "01",
    title: "Download the release",
    short: "Get both installers",
    description: "Download the signed Android APK and the Windows OBS plugin installer from the same verified release.",
    image: "/setup/release-downloads.svg",
    alt: "OpenStream GitHub release page showing Android and Windows downloads",
  },
  {
    id: "windows",
    number: "02",
    title: "Install the OBS plugin",
    short: "Run the Windows installer",
    description: "Close OBS, run the installer, and reopen OBS Studio. The installer handles upgrades and removes known stale plugin copies.",
    image: "/setup/windows-installer.svg",
    alt: "OpenStream OBS Plugin Windows installer",
  },
  {
    id: "obs",
    number: "03",
    title: "Add an OpenStream source",
    short: "Create a camera slot",
    description: "Add OpenStream from the OBS Sources menu and name the slot for your camera position, such as CAM A or CAM B.",
    image: "/setup/obs-source.svg",
    alt: "OBS Studio source list containing an OpenStream source",
  },
  {
    id: "android",
    number: "04",
    title: "Connect your Android phone",
    short: "Choose the OBS slot",
    description: "Open the Android app on the same Wi-Fi network and choose the discovered OBS slot. Manual IP entry remains available when discovery is blocked.",
    image: "/setup/android-connect.svg",
    alt: "OpenStream Android app connecting to a discovered OBS camera slot",
  },
];
