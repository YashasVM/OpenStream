import React, { useMemo, useState } from "react";
import { Icon } from "../icons";

const formatZoom = (value) => `${Number(value).toFixed(1)}×`;

export function ProductDemo() {
  const [connected, setConnected] = useState(true);
  const [lens, setLens] = useState("rear");
  const [torch, setTorch] = useState(false);
  const [zoom, setZoom] = useState(1.8);
  const zoomPercent = useMemo(() => ((zoom - 1) / 4) * 100, [zoom]);

  return (
    <div className={`product-demo ${connected ? "is-connected" : "is-disconnected"} ${torch ? "torch-on" : ""}`} id="product">
      <div className="demo-stage">
        <div className="phone-shell" aria-label="Interactive OpenStream Android preview">
          <div className="phone-speaker" />
          <div className="phone-screen">
            <div className="phone-header"><img src="/brand/openstream-logo.png" alt="" /><strong>OpenStream</strong><span className="live-badge"><i /> LIVE</span></div>
            <div className="phone-metadata"><span>1080p60</span><span>HEVC</span><span>AAC</span></div>
            <div className="camera-view">
              <div className={`camera-image lens-${lens}`} style={{ "--zoom": zoom }}>
                <div className="studio-wall" /><div className="studio-light" /><div className="studio-subject"><i /><b /></div>
              </div>
              <span className="frame-corner corner-a" /><span className="frame-corner corner-b" /><span className="phone-slot">CAM A</span>
              {!connected && <div className="offline-overlay"><Icon name="wifi" /><strong>Stream paused</strong></div>}
            </div>
            <div className="phone-bottom"><span>{lens === "rear" ? "REAR CAMERA" : "FRONT CAMERA"}</span><strong>{formatZoom(zoom)}</strong></div>
          </div>
        </div>

        <div className="signal-path" aria-hidden="true"><span>SRT VIDEO + AAC AUDIO</span><i /><b /><i /></div>

        <div className="obs-window" aria-label="Interactive OBS preview">
          <div className="window-bar"><span><i className="window-dot" /> OBS Studio</span><small>— □ ×</small></div>
          <div className="obs-menu">File&nbsp;&nbsp; Edit&nbsp;&nbsp; View&nbsp;&nbsp; Docks&nbsp;&nbsp; Profile&nbsp;&nbsp; Scene Collection</div>
          <div className="obs-canvas">
            <div className={`camera-image lens-${lens}`} style={{ "--zoom": zoom }}><div className="studio-wall" /><div className="studio-light" /><div className="studio-subject"><i /><b /></div></div>
            <span className="obs-source-label">OPENSTREAM / CAM A</span><span className="obs-live">{connected ? "LIVE · 60 FPS" : "NO SIGNAL"}</span>
          </div>
          <div className="obs-bottom"><div><small>SCENES</small><b>Camera</b><span>Starting soon</span></div><div><small>SOURCES</small><b>OpenStream</b><span>Stream overlay</span></div><div className="audio-meter"><small>AUDIO MIXER</small><b>OpenStream AAC</b><i><em /></i></div></div>
        </div>
      </div>

      <div className="control-dock">
        <div className="dock-header"><span><img src="/brand/openstream-logo.png" alt="" /> OpenStream Camera Control</span><small className="connection-state"><i /> {connected ? "Connected" : "Disconnected"}</small></div>
        <div className="dock-grid">
          <div className="source-block"><label htmlFor="camera-source">SOURCE</label><select id="camera-source" defaultValue="cam-a"><option value="cam-a">CAM A — Android phone</option></select><button type="button" className={connected ? "secondary-control" : "connect-control"} onClick={() => setConnected((value) => !value)}><Icon name={connected ? "video" : "refresh"} size={16} />{connected ? "Stop stream" : "Connect / retry"}</button></div>
          <fieldset className="lens-controls"><legend>CAMERA</legend><div className="segmented-control"><button type="button" aria-pressed={lens === "rear"} onClick={() => setLens("rear")}>Rear</button><button type="button" aria-pressed={lens === "front"} onClick={() => setLens("front")}>Front</button></div></fieldset>
          <div className="utility-controls"><span>TOOLS</span><button type="button" aria-pressed={torch} onClick={() => setTorch((value) => !value)}><Icon name="torch" size={17} />Torch</button><button type="button" onClick={() => setZoom(1)}><Icon name="target" size={17} />Reset frame</button></div>
          <div className="zoom-control"><label htmlFor="demo-zoom"><span>ZOOM</span><output htmlFor="demo-zoom" aria-live="polite">{formatZoom(zoom)}</output></label><div className="zoom-input-row"><button type="button" aria-label="Zoom out" onClick={() => setZoom((value) => Math.max(1, Number((value - .1).toFixed(1))))}>−</button><input id="demo-zoom" type="range" min="1" max="5" step="0.1" value={zoom} style={{ "--range-progress": `${zoomPercent}%` }} onInput={(event) => setZoom(Number(event.currentTarget.value))} onChange={(event) => setZoom(Number(event.currentTarget.value))} /><button type="button" aria-label="Zoom in" onClick={() => setZoom((value) => Math.min(5, Number((value + .1).toFixed(1))))}>+</button></div></div>
        </div>
        <p className="sr-only" aria-live="polite">CAM A is {connected ? "connected" : "disconnected"}. {lens} camera, zoom {formatZoom(zoom)}, torch {torch ? "on" : "off"}.</p>
      </div>
    </div>
  );
}
