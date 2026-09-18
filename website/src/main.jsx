import React from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";

const release = "https://github.com/YashasVM/OpenStream/releases/latest/download";
const links = {
  apk: `${release}/openstream-android.apk`,
  installer: `${release}/openstream-obs-plugin-installer-windows-x64.exe`,
  zip: `${release}/openstream-obs-windows-x64.zip`,
  release: "https://github.com/YashasVM/OpenStream/releases/latest",
  repo: "https://github.com/YashasVM/OpenStream",
  issues: "https://github.com/YashasVM/OpenStream/issues",
  setup: "https://github.com/YashasVM/OpenStream/blob/main/docs/set-up.md",
};

function Header() {
  return (
    <header className="site-header">
      <a className="brand" href="#top" aria-label="OpenStream home"><span className="mark" aria-hidden="true">Os</span><strong>OPENSTREAM</strong><span>V1.0.1</span></a>
      <nav aria-label="Main navigation">
        <a href="#features">Features</a>
        <a href="#downloads">Downloads</a>
        <a href="#setup">Setup</a>
        <a href={links.repo}>GitHub</a>
      </nav>
      <a className="button button-dark header-cta" href="#downloads">Download V1.0.1</a>
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
          <p>Low-latency 1080p30 video, AAC audio, live camera controls, and reliable reconnects over local Wi-Fi.</p>
          <div className="actions">
            <a className="button button-dark" href={links.apk}><span className="icon" aria-hidden="true">◆</span>Download for Android</a>
            <a className="button button-light" href={links.installer}><span className="icon" aria-hidden="true">✚</span>Install OBS plugin</a>
          </div>
          <p className="requirements">Android 10+ · Windows x64 · OBS Studio · Same local network</p>
        </div>
        <div className="hero-visual"><PhonePreview /><ObsPreview /></div>
      </section>
      <div className="signal-strip" aria-label="OpenStream media defaults"><span>▣ <b>1080p30</b></span><span>⌁ <b>SRT 120 ms</b></span><span>▥ <b>AAC audio</b></span><span>⌁ <b>Local Wi-Fi</b></span></div>
    </>
  );
}

const benefits = [
  ["⌕", "Smooth live zoom", "Zoom responds continuously while you drag the OBS slider, without stale commands piling up."],
  ["▤", "One phone camera", "Connect one phone to OBS and reuse its source across your scenes."],
  ["↻", "Fast reconnects", "Discovery, bounded queues, and reservation-aware recovery keep brief Wi-Fi drops manageable."],
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
      <div className="section-lead"><h2>Control the shot<br/>without leaving OBS.</h2><p>The native dock keeps the controls you touch most beside your preview—not buried in source properties.</p><ControlDock /></div>
      <div className="benefit-list">{benefits.map(([icon, title, copy]) => <article key={title}><span className="icon" aria-hidden="true">{icon}</span><div><h3>{title}</h3><p>{copy}</p></div></article>)}</div>
    </section>
  );
}

function Downloads() {
  return (
    <section className="section downloads" id="downloads">
      <div><h2>Install both sides.<br/>Start shooting.</h2><p>Install OpenStream on your phone and the native plugin on your OBS computer from the same verified V1.0.1 release.</p><a className="text-link" href={links.release}>View V1.0.1 release notes →</a></div>
      <div className="download-stack">
        <article className="download-row"><span className="icon" aria-hidden="true">◆</span><div><h3>OpenStream for Android</h3><p>Android 10+ · Signed release APK</p></div><div className="download-actions"><a className="button button-dark" href={links.apk}>Download signed APK</a></div></article>
        <article className="download-row"><span className="icon" aria-hidden="true">⊞</span><div><h3>OpenStream OBS Plugin</h3><p>Windows x64 · OBS Studio</p></div><div className="download-actions"><a className="button button-dark" href={links.installer}>Download installer</a><a className="button button-light" href={links.zip}>Manual ZIP</a></div></article>
        <div className="trust-note"><span className="icon" aria-hidden="true">◇</span><p><strong>Release integrity included.</strong><br/>SHA-256 checksums are published alongside the V1.0.1 release.</p></div>
      </div>
    </section>
  );
}

const steps = [
  ["01", "Install the Android app", "Download the signed APK, install it, and grant camera and microphone access."],
  ["02", "Install the OBS plugin", "Close OBS, run the Windows installer once, then reopen OBS Studio."],
  ["03", "Add your camera", "Add one OpenStream source in OBS. Use Add Existing to reuse it in other scenes."],
  ["04", "Pair on local Wi-Fi", "Choose your OBS computer on the phone. Enable manual receive in OBS if discovery is blocked."],
  ["05", "Frame and go live", "Open the Camera Control dock, verify audio, adjust zoom or lens, and stream."],
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

function Compatibility() {
  return (
    <section className="section compatibility">
      <div><h2>Built for a strong LAN.</h2><p>Use 5 GHz or Wi-Fi 6, keep both devices on the same subnet, and disable VPN or client isolation during first setup.</p></div>
      <ul><li><strong>Phone</strong><span>Android 10+ with Camera2 and hardware MediaCodec</span></li><li><strong>Computer</strong><span>Windows x64 with OBS Studio</span></li><li><strong>Network</strong><span>Same LAN; guest networks may block discovery</span></li><li><strong>Current limits</strong><span>V1.0.1 supports Android and Windows; macOS/Linux packages and adaptive bitrate are not included</span></li></ul>
    </section>
  );
}

function Footer() {
  return (
    <footer><a className="brand" href="#top"><span className="mark" aria-hidden="true">Os</span><strong>OPENSTREAM</strong></a><p>Open-source phone camera streaming for OBS.</p><nav aria-label="Footer links"><a href={links.repo}>Source</a><a href={links.release}>Releases</a><a href={links.issues}>Issues</a><a href={links.setup}>Setup</a></nav><p className="copyright">Made by YashasVM · V1.0.1</p></footer>
  );
}

function App() {
  return <><a className="skip-link" href="#main">Skip to content</a><Header /><main id="main"><Hero /><Features /><Downloads /><Setup /><Pipeline /><Compatibility /></main><Footer /></>;
}

createRoot(document.getElementById("root")).render(<React.StrictMode><App /></React.StrictMode>);
