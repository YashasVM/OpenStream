import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

const repository = "https://github.com/YashasVM/OpenStream";
const latestReleaseApi = "https://api.github.com/repos/YashasVM/OpenStream/releases/latest";
const release = `${repository}/releases/latest`;
const releaseDownload = `${release}/download`;
const links = {
  apk: `${releaseDownload}/openstream-android.apk`,
  installer: `${releaseDownload}/openstream-obs-plugin-installer-windows-x64.exe`,
  zip: `${releaseDownload}/openstream-obs-windows-x64.zip`,
  release,
  repo: repository,
  issues: `${repository}/issues`,
  setup: `${repository}/blob/main/docs/set-up.md`,
};

const expectedReleaseAssets = [
  "openstream-android.apk",
  "openstream-obs-windows-x64.zip",
  "openstream-obs-plugin-installer-windows-x64.exe",
  "shin-obs-linux-x86_64.tar.gz",
];

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function loadPublishedRelease() {
  const response = await fetch(latestReleaseApi, {
    headers: { Accept: "application/vnd.github+json" },
  });
  if (!response.ok) throw new Error("Latest release metadata is unavailable");

  const releaseData = await response.json();
  if (!isRecord(releaseData) || typeof releaseData.tag_name !== "string" ||
      typeof releaseData.html_url !== "string" || !Array.isArray(releaseData.assets)) {
    throw new Error("Latest release metadata is invalid");
  }

  const publishedAssets = new Map(
    releaseData.assets
      .filter((asset) => isRecord(asset) && typeof asset.name === "string")
      .map((asset) => [asset.name, asset]),
  );
  const manifestAsset = publishedAssets.get("release-manifest.json");
  if (!isRecord(manifestAsset) || typeof manifestAsset.browser_download_url !== "string") {
    throw new Error("Latest release has no manifest");
  }

  const manifestResponse = await fetch(manifestAsset.browser_download_url);
  if (!manifestResponse.ok) throw new Error("Published release manifest is unavailable");
  const manifest = await manifestResponse.json();
  if (!isRecord(manifest) || manifest.schemaVersion !== 1 ||
      typeof manifest.productVersion !== "string" ||
      !/^\d+\.\d+\.\d+$/.test(manifest.productVersion) ||
      manifest.tag !== releaseData.tag_name || manifest.tag !== `v${manifest.productVersion}` ||
      !isRecord(manifest.artifacts)) {
    throw new Error("Published release manifest is invalid");
  }

  const matchedAssets = {};
  for (const name of expectedReleaseAssets) {
    const expected = manifest.artifacts[name];
    const actual = publishedAssets.get(name);
    const checksum = publishedAssets.get(`${name}.sha256`);
    if (!isRecord(expected) || !Number.isSafeInteger(expected.size) || expected.size <= 0 ||
        typeof expected.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(expected.sha256) ||
        !isRecord(actual) || actual.size !== expected.size ||
        typeof actual.browser_download_url !== "string" || !isRecord(checksum) ||
        typeof checksum.browser_download_url !== "string") {
      throw new Error(`Published release asset does not match the manifest: ${name}`);
    }

    const checksumResponse = await fetch(checksum.browser_download_url);
    if (!checksumResponse.ok) throw new Error(`Published checksum is unavailable: ${name}`);
    const checksumText = (await checksumResponse.text()).trim();
    if (checksumText !== `${expected.sha256}  ${name}`) {
      throw new Error(`Published checksum does not match the manifest: ${name}`);
    }
    matchedAssets[name] = actual.browser_download_url;
  }

  return {
    version: manifest.productVersion,
    releaseUrl: releaseData.html_url,
    assets: matchedAssets,
  };
}

function Header({ publishedRelease }) {
  const releaseLabel = publishedRelease ? `v${publishedRelease.version}` : "Latest release";
  return (
    <header className="site-header">
      <a className="brand" href="#top" aria-label="OpenStream home"><span className="mark" aria-hidden="true">Os</span><strong>OPENSTREAM</strong><span>{releaseLabel}</span></a>
      <nav aria-label="Main navigation">
        <a href="#features">Features</a>
        <a href="#downloads">Downloads</a>
        <a href="#setup">Setup</a>
        <a href={links.repo}>GitHub</a>
      </nav>
      <a className="button button-dark header-cta" href="#downloads">Download latest</a>
    </header>
  );
}

function PhonePreview() {
  return (
    <div className="phone" aria-label="OpenStream Android camera preview mockup">
      <div className="phone-top"><strong>OpenStream</strong><span className="live"><i /> LIVE</span></div>
      <div className="phone-meta"><span>1080p30</span><span>AAC</span><span>Wi-Fi</span></div>
      <div className="camera-scene"><div className="camera-frame" /><div className="subject">PHONE</div></div>
      <div className="phone-tools"><span>↻</span><span>▦</span><strong>1.8×</strong><span>☼</span></div>
      <div className="phone-slider"><span>ZOOM</span><i><b /></i></div>
      <span className="preview-stop" aria-hidden="true"><i /></span>
    </div>
  );
}

function ObsPreview() {
  return (
    <div className="obs" aria-label="OBS Studio with OpenStream source and control dock mockup">
      <div className="obs-bar"><span className="obs-dot">●</span> OBS Studio <span>— □ ×</span></div>
      <div className="obs-menu">File &nbsp; Edit &nbsp; View &nbsp; Docks &nbsp; Profile &nbsp; Scene Collection</div>
      <div className="obs-video"><div className="signal-corners" /><strong>OPENSTREAM / PHONE</strong><span>LIVE · 30 FPS</span></div>
      <div className="obs-panels"><div>Scenes<br/><b>Camera</b><br/>Overlay</div><div>Sources<br/><b>OpenStream</b><br/>Audio</div><div className="mixer">Audio Mixer<br/><b>▮▮▮▮▮▯▯</b><br/>OpenStream AAC</div></div>
      <div className="dock-mini"><div className="dock-title"><span className="mark" aria-hidden="true">Os</span> OpenStream Camera Control <span>● Connected</span></div><div className="dock-actions"><span>Rear</span><span>Front</span><span>Torch</span><span>Identify</span></div><div className="dock-zoom">Zoom <i><b /></i><strong>1.8×</strong></div></div>
    </div>
  );
}

function Hero() {
  return (
    <>
      <section className="hero" id="top">
        <div className="hero-copy">
          <h1>Your Android phone.<br/>Now an OBS camera.</h1>
          <p>1080p30 video, AAC audio, and live camera controls over local Wi-Fi.</p>
          <div className="actions">
            <a className="button button-dark" href={links.apk}><span className="icon" aria-hidden="true">◆</span>Download for Android</a>
            <a className="button button-light" href={links.installer}><span className="icon" aria-hidden="true">✚</span>Install OBS plugin</a>
          </div>
          <p className="requirements">Android 10+ · Windows x64 · OBS Studio · Same local network</p>
        </div>
        <div className="hero-visual"><PhonePreview /><ObsPreview /></div>
      </section>
      <div className="signal-strip" aria-label="OpenStream media defaults"><span>▣ <b>1080p30</b></span><span>⌁ <b>SRT transport</b></span><span>▥ <b>AAC audio</b></span><span>⌁ <b>Local Wi-Fi</b></span></div>
    </>
  );
}

const benefits = [
  ["⌕", "Smooth live zoom", "Zoom responds continuously while you drag the OBS slider, without stale commands piling up."],
  ["▤", "One phone camera", "Connect one phone to OBS and reuse its source across your scenes."],
  ["↻", "Connection recovery", "See connection status and reconnect after a network interruption."],
  ["▥", "Separate AAC audio", "Phone microphone audio arrives in its own OBS mixer channel, separate from desktop audio."],
];

function ControlDock() {
  return (
    <div className="control-window">
      <div className="control-title"><span className="mark" aria-hidden="true">Os</span><strong>OpenStream Camera Control</strong><span>•••</span></div>
      <div className="control-body">
        <div className="connection-panel"><small>CONNECTION</small><p><i /> Connected</p><small>SOURCE</small><div className="faux-select">Pixel 8 Pro</div><div className="connection-buttons"><span>Connect / retry</span><span>Stop</span></div></div>
        <div className="camera-panel"><small>CAMERA</small><div className="segmented"><span className="active">Rear</span><span>Front</span></div><div className="camera-actions"><span>Torch on</span><span>Torch off</span><span>Identify</span></div><label>Zoom <span>1.8×</span></label><div className="zoom-track"><i /></div></div>
      </div>
    </div>
  );
}

function Features() {
  return (
    <section className="section control-section" id="features">
      <div className="section-lead"><h2>Control the shot<br/>without leaving OBS.</h2><p>Use source properties to select the phone and control the camera. Linux also includes a dock beside the preview.</p><ControlDock /></div>
      <div className="benefit-list">{benefits.map(([icon, title, copy]) => <article key={title}><span className="icon" aria-hidden="true">{icon}</span><div><h3>{title}</h3><p>{copy}</p></div></article>)}</div>
    </section>
  );
}

function Downloads({ publishedRelease }) {
  const assets = publishedRelease?.assets;
  const releaseUrl = publishedRelease?.releaseUrl ?? links.release;
  const releaseDescription = publishedRelease
    ? `the matched ${publishedRelease.version} release`
    : "the latest published release";
  return (
    <section className="section downloads" id="downloads">
      <div><h2>Install both sides.<br/>Start shooting.</h2><p>Install OpenStream on your phone and the native plugin on your OBS computer from {releaseDescription}.</p><a className="text-link" href={releaseUrl}>View release notes →</a></div>
      <div className="download-stack">
        <article className="download-row"><span className="icon" aria-hidden="true">◆</span><div><h3>OpenStream for Android</h3><p>Android 10+ · Signed release APK</p></div><div className="download-actions"><a className="button button-dark" href={assets?.[expectedReleaseAssets[0]] ?? links.apk}>Download signed APK</a></div></article>
        <article className="download-row"><span className="icon" aria-hidden="true">⊞</span><div><h3>OpenStream OBS Plugin</h3><p>Windows x64 · OBS Studio</p></div><div className="download-actions"><a className="button button-dark" href={assets?.[expectedReleaseAssets[2]] ?? links.installer}>Download installer</a><a className="button button-light" href={assets?.[expectedReleaseAssets[1]] ?? links.zip}>Manual ZIP</a></div></article>
        {assets && <article className="download-row"><span className="icon" aria-hidden="true">⊞</span><div><h3>OpenStream OBS Plugin</h3><p>Linux x86_64 · Host OBS libraries</p></div><div className="download-actions"><a className="button button-dark" href={assets[expectedReleaseAssets[3]]}>Download Linux package</a></div></article>}
        <div className="trust-note"><span className="icon" aria-hidden="true">◇</span><p><strong>Release integrity included.</strong><br/>Published release downloads include SHA-256 checksums.</p></div>
      </div>
    </section>
  );
}

const steps = [
  ["01", "Install the Android app", "Download the signed APK, install it, and grant camera and microphone access."],
  ["02", "Install the OBS plugin", "Close OBS, run the Windows installer once, then reopen OBS Studio."],
  ["03", "Add your camera", "Add one OpenStream source in OBS. Use Add Existing to reuse it in other scenes."],
  ["04", "Pair on local Wi-Fi", "Choose your OBS computer on the phone. Enable manual receive in OBS if discovery is blocked."],
  ["05", "Frame and go live", "Open the source controls, verify audio, adjust zoom or lens, and stream."],
];

function Setup() {
  return (
    <section className="section setup" id="setup">
      <header><h2>Five clean moves.<br/>Then live.</h2><p>No desktop webcam client. No capture card. No screen-mirroring detour.</p></header>
      <div className="step-list">{steps.map(([n, title, copy]) => <article key={n}><span>{n}</span><div><h3>{title}</h3><p>{copy}</p></div></article>)}</div>
      <a className="button button-light" href={links.setup}>Open the illustrated setup guide</a>
    </section>
  );
}

function Pipeline() {
  return (
    <section className="pipeline">
      <div><h2>Local network.<br/>Explicit pipes.</h2><p>Your media stays on the LAN. Camera2 and MediaCodec handle capture, MPEG-TS carries hardware AVC/H.264 plus AAC, and SRT delivers it to the native OBS source.</p></div>
      <div className="flow" aria-label="OpenStream media pipeline"><span>ANDROID CAMERA<small>Camera2 + MediaCodec</small></span><b>→</b><span>SRT STREAM<small>MPEG-TS · port 9000</small></span><b>→</b><span>OBS SOURCE<small>FFmpeg decode + mixer</small></span></div>
      <dl><div><dt>Media</dt><dd>SRT :9000</dd></div><div><dt>Discovery</dt><dd>UDP :51515</dd></div><div><dt>Control</dt><dd>HTTP :9001</dd></div><div><dt>Default bitrate</dt><dd>12 Mbps</dd></div></dl>
    </section>
  );
}

function Compatibility({ publishedRelease }) {
  const computerSupport = publishedRelease
    ? "Windows x64 and Linux x86_64 with OBS Studio"
    : "Windows x64 with OBS Studio";
  const currentLimits = publishedRelease
    ? "macOS packages and adaptive bitrate are not included"
    : "Linux and macOS packages and adaptive bitrate are not included in this release";
  return (
    <section className="section compatibility">
      <div><h2>Built for a strong LAN.</h2><p>Use 5 GHz or Wi-Fi 6, keep both devices on the same subnet, and disable VPN or client isolation during first setup.</p></div>
      <ul><li><strong>Phone</strong><span>Android 10+ with Camera2 and hardware MediaCodec</span></li><li><strong>Computer</strong><span>{computerSupport}</span></li><li><strong>Network</strong><span>Same LAN; guest networks may block discovery</span></li><li><strong>Current limits</strong><span>{currentLimits}</span></li></ul>
    </section>
  );
}

function Footer() {
  return (
    <footer><a className="brand" href="#top"><span className="mark" aria-hidden="true">Os</span><strong>OPENSTREAM</strong></a><p>Phone camera streaming for OBS.</p><nav aria-label="Footer links"><a href={links.repo}>Source</a><a href={links.release}>Releases</a><a href={links.issues}>Issues</a><a href={links.setup}>Setup</a></nav><p className="copyright">Made by YashasVM</p></footer>
  );
}

function App() {
  const [publishedRelease, setPublishedRelease] = useState(null);

  useEffect(() => {
    let active = true;
    loadPublishedRelease()
      .then((releaseData) => {
        if (active) setPublishedRelease(releaseData);
      })
      .catch(() => {
        if (active) setPublishedRelease(null);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    const title = publishedRelease
      ? `OpenStream ${publishedRelease.version} | Wireless OBS Camera`
      : "OpenStream | Wireless OBS Camera";
    document.title = title;
    for (const selector of ['meta[property="og:title"]', 'meta[name="twitter:title"]']) {
      document.querySelector(selector)?.setAttribute("content", title);
    }
    const application = document.querySelector('script[type="application/ld+json"]');
    if (application) {
      const data = JSON.parse(application.textContent);
      if (publishedRelease) data.softwareVersion = publishedRelease.version;
      else delete data.softwareVersion;
      application.textContent = JSON.stringify(data);
    }
  }, [publishedRelease]);

  return <><a className="skip-link" href="#main">Skip to content</a><Header publishedRelease={publishedRelease} /><main id="main"><Hero /><Features /><Downloads publishedRelease={publishedRelease} /><Setup /><Pipeline /><Compatibility publishedRelease={publishedRelease} /></main><Footer /></>;
}

createRoot(document.getElementById("root")).render(<React.StrictMode><App /></React.StrictMode>);
