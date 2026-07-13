import React from "react";
import { createRoot } from "react-dom/client";
import { AndroidIcon, Icon, WindowsIcon } from "./icons";
import { Header, Logo } from "./components/Header";
import { ProductDemo } from "./components/ProductDemo";
import { SetupStepper } from "./components/SetupStepper";
import { links, product } from "./product";
import "./styles.css";

const features = [
  { icon: "sliders", title: "Control from OBS", copy: "Switch lenses, toggle the torch, identify a phone, and frame the shot from a dock that stays beside your preview." },
  { icon: "target", title: "Real-time zoom", copy: "Zoom tracks every slider movement continuously, so the framing changes while you drag—not seconds later." },
  { icon: "slots", title: "Stable camera slots", copy: "Reserve CAM A, CAM B, and other production positions so reconnecting phones return to the right source." },
  { icon: "audio", title: "A clean audio channel", copy: "Phone microphone audio arrives as AAC in its own OBS mixer channel, independent of desktop audio." },
];

function Hero() {
  return (
    <section className="hero" id="top">
      <div className="hero-grid">
        <div className="hero-copy">
          <p className="eyebrow"><span /> OPEN-SOURCE CAMERA LINK FOR OBS</p>
          <h1>Your phone is already a great camera. <em>OpenStream brings it into OBS.</em></h1>
          <p className="hero-summary">Low-latency video up to 1080p60 on supported hardware, clean AAC audio, and live camera control over your local network.</p>
          <div className="hero-actions"><a className="button button-primary" href={links.apk}><AndroidIcon size={19} /> Download Android app</a><a className="button button-ghost" href={links.installer}><WindowsIcon size={18} /> Install OBS plugin</a></div>
          <p className="hero-note"><Icon name="shield" size={16} /> Free, open source, and LAN-first. No account required.</p>
        </div>
        <div className="hero-version"><span>{product.displayVersion}</span><small>RELEASED {product.releaseDate.toUpperCase()}</small></div>
      </div>
      <ProductDemo />
      <div className="status-rail"><span><small>CAMERA SLOT</small><strong>CAM A</strong></span><span><small>CONNECTION</small><strong className="positive"><i /> Connected</strong></span><span><small>TRANSPORT</small><strong>SRT · 120 ms default</strong></span><span><small>AUDIO</small><strong>AAC · 48 kHz</strong></span></div>
    </section>
  );
}

function Features() {
  return (
    <section className="light-section features-section" id="how-it-works">
      <div className="section-heading"><p className="eyebrow dark"><span /> THE PRODUCTION LOOP</p><h2>Built for the way you shoot.</h2><p>OpenStream treats your phone like a camera source, not a mirrored screen. The app handles capture; the native plugin handles OBS.</p></div>
      <div className="feature-story">
        <div className="feature-list">{features.map((feature, index) => <article key={feature.title}><span className="feature-number">0{index + 1}</span><Icon name={feature.icon} size={24} /><div><h3>{feature.title}</h3><p>{feature.copy}</p></div></article>)}</div>
        <div className="pipeline-card"><div className="pipeline-head"><span>LIVE SIGNAL PATH</span><strong>LOCAL NETWORK ONLY</strong></div><div className="pipeline-nodes"><span><Icon name="camera" /><b>ANDROID</b><small>Camera2 + MediaCodec</small></span><i /><span><Icon name="wifi" /><b>SRT STREAM</b><small>HEVC/H.264 + AAC</small></span><i /><span><Icon name="video" /><b>OBS SOURCE</b><small>Native plugin + mixer</small></span></div><dl><div><dt>Media</dt><dd>SRT :9000</dd></div><div><dt>Discovery</dt><dd>UDP :51515</dd></div><div><dt>Control</dt><dd>HTTP :9001</dd></div></dl></div>
      </div>
    </section>
  );
}

function Downloads() {
  return (
    <section className="dark-section download-section" id="downloads">
      <div className="section-heading inverse"><p className="eyebrow"><span /> LATEST RELEASE · {product.displayVersion.toUpperCase()}</p><h2>Install both sides.<br />Start shooting.</h2><p>Use the Android app for capture and the native Windows plugin for OBS. Both ship from the same GitHub release.</p></div>
      <div className="download-grid">
        <article className="download-card featured"><div className="platform-icon"><AndroidIcon size={34} /></div><span className="availability">AVAILABLE NOW</span><h3>OpenStream for Android</h3><p>Capture video and microphone audio, discover camera slots, and control the connection from your phone.</p><dl><div><dt>Requires</dt><dd>Android 10+</dd></div><div><dt>Package</dt><dd>Signed APK</dd></div></dl><a className="button button-primary" href={links.apk}><Icon name="download" /> Download Android app</a></article>
        <article className="download-card"><div className="platform-icon"><WindowsIcon size={34} /></div><span className="availability">AVAILABLE NOW</span><h3>OpenStream OBS plugin</h3><p>Add a native OpenStream source, camera control dock, automatic discovery, audio mixing, and in-app updates.</p><dl><div><dt>Requires</dt><dd>Windows x64 · OBS 30+</dd></div><div><dt>Package</dt><dd>Guided installer</dd></div></dl><a className="button button-light" href={links.installer}><Icon name="download" /> Download Windows installer</a><a className="secondary-link" href={links.zip}>Need the manual ZIP? <Icon name="external" size={14} /></a></article>
      </div>
      <div className="release-proof"><span><Icon name="shield" /><b>Verifiable releases</b><small>SHA-256 checksums and update manifests are published beside every build.</small></span><a href={links.release}>View release notes <Icon name="external" size={15} /></a></div>
    </section>
  );
}

function Setup() {
  return (
    <section className="light-section setup-section" id="setup"><div className="section-heading"><p className="eyebrow dark"><span /> GUIDED SETUP</p><h2>From install to live in minutes.</h2><p>The guided installer handles the plugin files. Then create a slot in OBS and pair your phone over the same local network.</p></div><SetupStepper /></section>
  );
}

function ProductionNotes() {
  return (
    <section className="light-section notes-section">
      <div className="requirements-panel"><p className="eyebrow dark"><span /> BEFORE YOU INSTALL</p><h2>A strong LAN makes a strong camera link.</h2><p>Use 5 GHz or Wi-Fi 6 when possible. Keep both devices on the same subnet, and turn off VPN or client isolation during first setup.</p><ul><li><b>Phone</b><span>{product.requirements.android}</span></li><li><b>Computer</b><span>{product.requirements.windows} · {product.requirements.obs}</span></li><li><b>Network</b><span>{product.requirements.network}</span></li></ul></div>
      <div className="faq-panel"><p className="eyebrow dark"><span /> QUICK ANSWERS</p><h2>Good to know.</h2><details><summary>Does video leave my network?<Icon name="chevron" /></summary><p>No cloud relay is used by OpenStream. The media and control paths are designed to stay on your local network.</p></details><details><summary>What if discovery cannot find OBS?<Icon name="chevron" /></summary><p>Check Windows Firewall and guest-network isolation first. You can also enter the OBS computer’s local IP address manually.</p></details><details><summary>Is 1080p60 guaranteed on every phone?<Icon name="chevron" /></summary><p>No. Available resolution, frame rate, and codec support depend on your phone’s Camera2 and hardware encoder capabilities.</p></details><details><summary>How are plugin updates delivered?<Icon name="chevron" /></summary><p>The plugin checks version metadata from GitHub and opens the latest release page when an update is available. You stay in control of the download and installation.</p></details><a href={links.issues}>Still stuck? Open an issue <Icon name="external" size={14} /></a></div>
    </section>
  );
}

function FinalCta() {
  return <section className="final-cta"><img src="/brand/openstream-logo.png" alt="" /><p className="eyebrow"><span /> OPENSTREAM {product.displayVersion.toUpperCase()}</p><h2>Give your phone<br />a place in the scene.</h2><div><a className="button button-primary" href={links.apk}><AndroidIcon size={19} /> Get the Android app</a><a className="button button-ghost" href={links.installer}><WindowsIcon size={18} /> Install the OBS plugin</a></div></section>;
}

function Footer() {
  return <footer><a className="brand" href="#top"><Logo /></a><p>Open-source phone camera streaming for OBS.</p><nav aria-label="Footer navigation"><a href={links.repo}>Source</a><a href={links.release}>Releases</a><a href={links.issues}>Issues</a><a href={links.setup}>Setup guide</a><a href={links.protocol}>Protocol</a></nav><small>© 2026 OpenStream · {product.displayVersion} · Built by YashasVM</small></footer>;
}

function App() {
  return <><a className="skip-link" href="#main">Skip to content</a><Header /><main id="main"><Hero /><Features /><Downloads /><Setup /><ProductionNotes /><FinalCta /></main><Footer /></>;
}

createRoot(document.getElementById("root")).render(<React.StrictMode><App /></React.StrictMode>);
