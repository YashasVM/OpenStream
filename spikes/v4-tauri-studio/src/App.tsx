import { Moon, PlugZap, RefreshCw, Sun } from "lucide-react";
import { useEffect, useState, useTransition } from "react";
import { Button } from "./components/ui/button";
import { connectBridge, type BridgeStatus, type PreviewDescriptor } from "./bridge";

function Preview({ preview }: { preview: PreviewDescriptor }) {
  return <article className="preview" aria-label={`${preview.label}, ${preview.health}`}>
    <div className="preview-pattern" aria-hidden="true"><span>{preview.label.slice(-1)}</span></div>
    <div className="preview-meta"><h2>{preview.label}</h2><span className={`health health-${preview.health}`}>{preview.health}</span></div>
    <dl><div><dt>Descriptor</dt><dd>{preview.id}</dd></div><div><dt>Surface</dt><dd>{preview.width}×{preview.height}</dd></div><div><dt>Generation</dt><dd>{preview.generation}</dd></div></dl>
  </article>;
}

export function App() {
  const [status, setStatus] = useState<BridgeStatus>({ connected: false, pipeName: "pending", detail: "Not connected" });
  const [dark, setDark] = useState(() => matchMedia("(prefers-color-scheme: dark)").matches);
  const [pending, startTransition] = useTransition();
  const connect = () => startTransition(() => { void connectBridge().then(setStatus); });
  useEffect(connect, []);
  useEffect(() => { document.documentElement.classList.toggle("dark", dark); }, [dark]);
  const previews = status.snapshot?.previews ?? [];
  return <main>
    <header><div><p className="product">OpenStream Studio</p><h1>Windows shell feasibility</h1></div><div className="actions">
      <Button variant="outline" onClick={() => setDark(value => !value)} aria-label={`Use ${dark ? "light" : "dark"} theme`}>{dark ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />} Theme</Button>
      <Button onClick={connect} disabled={pending}>{pending ? <RefreshCw className="spin" aria-hidden="true" /> : <PlugZap aria-hidden="true" />} Reconnect</Button>
    </div></header>
    <section className="statusbar" aria-live="polite"><strong>{status.connected ? "Connected" : "Simulation"}</strong><span>{status.detail}</span><code>{status.pipeName}</code></section>
    <section className="grid" aria-label="Simulated shared texture previews">{previews.map(preview => <Preview key={preview.id} preview={preview} />)}</section>
    <footer><span>Typed descriptors only</span><span>No media sessions in Studio</span><span>Reduced-motion aware</span></footer>
  </main>;
}
