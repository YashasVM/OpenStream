import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  motion, AnimatePresence, useScroll, useTransform, useSpring, useVelocity, useMotionValue,
  useAnimationFrame, useInView, animate,
} from "motion/react";
import "@fontsource-variable/unbounded";
import "@fontsource-variable/inter";
import { links, specs, pipeline, ports, features, presets, platforms, golive, faq } from "./shared/content.js";
import { useSmoothScroll, usePlatform, useCopy, prefersReducedMotion } from "./shared/hooks.js";
import "./styles.css";

const ease = [0.76, 0, 0.24, 1];
const soft = [0.16, 1, 0.3, 1];

/* ───────── Kinetic primitives ───────── */

function SplitLine({ text, delay = 0, className }) {
  let n = 0;
  return (
    <span className={`split ${className || ""}`} aria-label={text}>
      {text.split(" ").map((word, wi) => (
        <span key={wi} className="sw" aria-hidden="true">
          {[...word].map((ch, i) => (
            <span key={i} className="mask">
              <motion.span initial={{ y: "110%" }} animate={{ y: 0 }} transition={{ duration: 1, delay: delay + n++ * 0.035, ease }}>
                {ch}
              </motion.span>
            </span>
          ))}
        </span>
      ))}
    </span>
  );
}

function wrap(min, max, v) {
  const r = max - min;
  return ((((v - min) % r) + r) % r) + min;
}

/** Marquee whose speed and direction respond to scroll velocity. */
function Marquee({ items, base = -2, className }) {
  const x = useMotionValue(0);
  const { scrollY } = useScroll();
  const vel = useSpring(useVelocity(scrollY), { damping: 50, stiffness: 400 });
  const factor = useTransform(vel, [0, 1000], [0, 5], { clamp: false });
  const skew = useTransform(vel, [-2000, 2000], [8, -8]);
  const dir = useRef(1);
  const pct = useTransform(x, (v) => `${wrap(-50, 0, v)}%`);
  useAnimationFrame((_, delta) => {
    if (prefersReducedMotion()) return;
    let move = dir.current * base * (delta / 1000);
    const f = factor.get();
    if (f < 0) dir.current = -1; else if (f > 0) dir.current = 1;
    move += dir.current * move * f;
    x.set(x.get() + move);
  });
  return (
    <div className={`marquee ${className || ""}`}>
      <motion.div className="mq-track" style={{ x: pct, skewX: skew }}>
        {[0, 1].map((k) => (
          <div key={k} className="mq-set" aria-hidden={k === 1}>
            {items.map((it, i) => <span key={i}>{it}<i>✦</i></span>)}
          </div>
        ))}
      </motion.div>
    </div>
  );
}

function ScrollWords({ text }) {
  const ref = useRef(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start 85%", "end 45%"] });
  const words = text.split(" ");
  return (
    <p className="scrollwords" ref={ref}>
      {words.map((w, i) => (
        <Word key={i} p={scrollYProgress} range={[i / words.length, (i + 1) / words.length]} hot={/OBS|phone|Wi-Fi|camera/.test(w)}>{w}</Word>
      ))}
    </p>
  );
}
function Word({ children, p, range, hot }) {
  const o = useTransform(p, range, [0.12, 1]);
  return <motion.span style={{ opacity: o }} className={hot ? "hot" : ""}>{children} </motion.span>;
}

function Count({ to, display }) {
  const ref = useRef(null);
  const inView = useInView(ref, { once: true });
  const [n, setN] = useState(0);
  useEffect(() => {
    if (!inView) return;
    const c = animate(0, to, { duration: 1.8, ease: soft, onUpdate: (v) => setN(Math.round(v)) });
    return () => c.stop();
  }, [inView, to]);
  return <span ref={ref}>{display ?? n}</span>;
}

function Up({ children, delay = 0, className }) {
  return (
    <motion.div className={className} initial={{ opacity: 0, y: 50 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, margin: "-60px" }} transition={{ duration: 1, delay, ease: soft }}>
      {children}
    </motion.div>
  );
}

/* ───────── Sections ───────── */

function Hero() {
  const ref = useRef(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end start"] });
  const y1 = useTransform(scrollYProgress, [0, 1], [0, -160]);
  const y2 = useTransform(scrollYProgress, [0, 1], [0, -60]);
  const rot = useTransform(scrollYProgress, [0, 1], [0, 180]);
  return (
    <header className="hero" ref={ref}>
      <div className="hero-top">
        <span className="logo"><img src="/logo.png" alt="" width="34" height="34" />OpenStream</span>
        <span className="hero-tag">Android → OBS Studio</span>
        <a className="hero-gh" href={links.repo} target="_blank" rel="noreferrer">GitHub ↗</a>
      </div>

      <h1 className="hero-title" aria-label="Your phone is now an OBS camera.">
        <motion.span className="hl" style={{ y: y1 }}><SplitLine text="YOUR PHONE" /></motion.span>
        <motion.span className="hl hl-2" style={{ y: y2 }}>
          <SplitLine text="IS NOW AN" delay={0.25} />
          <motion.span className="badge" aria-hidden="true" style={{ rotate: rot }} initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ duration: 1, delay: 0.9, ease: soft }}>
            <svg viewBox="0 0 120 120" aria-hidden="true">
              <defs><path id="circ" d="M60 60 m-46 0 a46 46 0 1 1 92 0 a46 46 0 1 1 -92 0" /></defs>
              <text><textPath href="#circ">FREE · OPEN SOURCE · LOCAL ONLY · </textPath></text>
            </svg>
            <img src="/logo.png" alt="" width="54" height="54" />
          </motion.span>
        </motion.span>
        <span className="hl hl-3"><SplitLine text="OBS CAMERA." delay={0.5} className="orange" /></span>
      </h1>

      <motion.div className="hero-foot" initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 1, delay: 1.1, ease: soft }}>
        <p>Stream any Android camera into OBS over local Wi-Fi. Every lens, hardware-encoded, zero cloud.</p>
        <div className="hero-btns">
          <a href="#install" className="btn-o">Download <span>↓</span></a>
          <a href="#how" className="btn-k">How it works</a>
        </div>
      </motion.div>

      <Marquee items={["1080p60", "Every lens", "H.264 + AAC", "SRT over Wi-Fi", "Camera Control dock", "Auto-discovery", "Zero cloud", "Open source"]} />
    </header>
  );
}

function Manifesto() {
  return (
    <section className="manifesto">
      <span className="label">(01) The idea</span>
      <ScrollWords text="Your phone already has a better camera than your webcam. OpenStream sends it straight into OBS over your own Wi-Fi — no capture card, no screen mirroring, no account, no cloud." />
    </section>
  );
}

function Numbers() {
  return (
    <section className="numbers">
      {specs.map((s, i) => (
        <Up key={s.label} delay={i * 0.08} className={`num${i === 0 ? " num-o" : ""}`}>
          <strong><Count to={s.value} display={s.display} /><small>{s.unit}</small></strong>
          <span>{s.label}</span>
        </Up>
      ))}
    </section>
  );
}

function StackCard({ p, i, total, progress }) {
  const target = 1 - (total - i) * 0.04;
  const scale = useTransform(progress, [i / total, 1], [1, target]);
  return (
    <div className="stack-slot">
      <motion.article className={`stack-card sc-${i}`} style={{ scale, top: `calc(-6vh + ${i * 28}px)` }}>
        <div className="sc-head">
          <span className="sc-n">0{i + 1}</span>
          <span className="sc-k">{p.key}</span>
        </div>
        <h3>{p.title}</h3>
        <p>{p.body}</p>
        <div className={`sc-art art-${p.key}`}>{Array.from({ length: 12 }, (_, k) => <i key={k} style={{ "--n": k }} />)}</div>
      </motion.article>
    </div>
  );
}

function How() {
  const ref = useRef(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end end"] });
  return (
    <section className="how" id="how">
      <div className="how-head">
        <span className="label">(02) How it works</span>
        <Up><h2>FOUR HOPS.<br /><span className="orange">ONE NETWORK.</span></h2></Up>
        <Up delay={0.1} className="how-ports">{ports.map((p) => <span key={p.name}><b>{p.name}</b>{p.value}</span>)}</Up>
      </div>
      <div className="stack" ref={ref}>
        {pipeline.map((p, i) => <StackCard key={p.key} p={p} i={i} total={pipeline.length} progress={scrollYProgress} />)}
      </div>
    </section>
  );
}

function Features() {
  return (
    <section className="features">
      <span className="label">(03) Features</span>
      <div className="frows">
        {features.map((f, i) => (
          <Up key={f.key} delay={0.03 * i}>
            <div className="frow">
              <span className="fn">0{i + 1}</span>
              <h3>{f.title}</h3>
              <p>{f.body}</p>
              <span className="farrow">→</span>
            </div>
          </Up>
        ))}
      </div>
    </section>
  );
}

function Quality() {
  const [sel, setSel] = useState(1);
  return (
    <section className="quality">
      <span className="label">(04) Quality modes</span>
      <div className="qcols">
        {presets.map((p, i) => (
          <button key={p.name} className={`qcol${sel === i ? " on" : ""}`} onMouseEnter={() => setSel(i)} onFocus={() => setSel(i)} onClick={() => setSel(i)}>
            <span className="qn">{p.name}{p.isDefault ? " (default)" : ""}</span>
            <strong>{p.res}</strong>
            <span className="qfps">{p.fps} FPS</span>
            <span className="qrate">{p.rate}</span>
            <span className="qnote">{p.note}</span>
          </button>
        ))}
      </div>
    </section>
  );
}

function Install() {
  const [tab, setTab] = usePlatform();
  const [copied, copy] = useCopy();
  const p = platforms[tab];
  return (
    <section className="install" id="install">
      <div className="inst-in">
        <span className="label dark">(05) Install</span>
        <div className="plats" role="tablist">
          {Object.entries(platforms).map(([id, x]) => (
            <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? "on" : ""} onClick={() => setTab(id)}>
              <span className="plat-name">{x.label}</span>
              <span className="plat-side">{x.side}</span>
              {tab === id && <motion.i layoutId="platline" className="plat-line" transition={{ duration: 0.6, ease }} />}
            </button>
          ))}
        </div>
        <AnimatePresence mode="wait">
          <motion.div key={tab} className="inst-body" initial={{ opacity: 0, y: 30 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -20 }} transition={{ duration: 0.5, ease: soft }}>
            <div className="inst-dl">
              <span className="req">Needs: {p.need}</span>
              {p.files.map((f) => (
                <a key={f.name} href={f.href} className={`dl${f.primary ? " primary" : ""}`} {...(f.external ? { target: "_blank", rel: "noreferrer" } : {})}>
                  <span className="dl-l"><strong>{f.name}</strong><small>.{f.ext} — {f.desc}</small></span>
                  <span className="dl-i">{f.external ? "↗" : "↓"}</span>
                </a>
              ))}
              {p.note && <p className="inst-note">{p.note}</p>}
              <a className="inst-all" href={links.release} target="_blank" rel="noreferrer">All files &amp; release notes ↗</a>
            </div>
            <ol className="inst-steps">
              {p.steps.map((s, i) => (
                <li key={s.title}>
                  <span className="isn">{i + 1}</span>
                  <div>
                    <h4>{s.title}</h4>
                    <p>{s.body}</p>
                    {s.cmds?.map((c) => (
                      <button key={c} className="cmd" onClick={() => copy(c)}>
                        <code>{c}</code><span>{copied === c ? "COPIED" : "COPY"}</span>
                      </button>
                    ))}
                  </div>
                </li>
              ))}
            </ol>
          </motion.div>
        </AnimatePresence>
      </div>
    </section>
  );
}

function GoLive() {
  return (
    <section className="golive">
      <span className="label">(06) Go live</span>
      <Up><h2>FIVE MOVES.<br /><span className="orange">THEN YOU'RE ON.</span></h2></Up>
      <div className="moves">
        {golive.map((g, i) => (
          <Up key={g.title} delay={i * 0.07} className="move">
            <span className="mv-n">{i + 1}</span>
            <h4>{g.title}</h4>
            <p>{g.body}</p>
          </Up>
        ))}
      </div>
    </section>
  );
}

function Faq() {
  const [open, setOpen] = useState(-1);
  return (
    <section className="faq">
      <span className="label">(07) FAQ</span>
      <div className="faq-list">
        {faq.map((f, i) => (
          <div key={f.q} className={`fq${open === i ? " open" : ""}`}>
            <button onClick={() => setOpen(open === i ? -1 : i)} aria-expanded={open === i}>
              <span>{f.q}</span><i>+</i>
            </button>
            <AnimatePresence initial={false}>
              {open === i && (
                <motion.div initial={{ height: 0 }} animate={{ height: "auto" }} exit={{ height: 0 }} transition={{ duration: 0.6, ease }}>
                  <p>{f.a}</p>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        ))}
      </div>
      <p className="faq-more">Still stuck? <a href={links.issues} target="_blank" rel="noreferrer">Open an issue ↗</a></p>
    </section>
  );
}

function Footer() {
  return (
    <footer className="footer">
      <Marquee items={["Point", "Stream", "Repeat"]} base={-3} className="mq-big" />
      <div className="foot-cta">
        <a href="#install" className="btn-o big">Get OpenStream <span>↓</span></a>
      </div>
      <div className="foot-word" aria-hidden="true">
        {[..."OPENSTREAM"].map((c, i) => <span key={i} style={{ "--i": i }}>{c}</span>)}
      </div>
      <div className="foot-row">
        <span>© OpenStream — made by <a href={links.author} target="_blank" rel="noreferrer">yashas</a></span>
        <nav>
          <a href={links.repo} target="_blank" rel="noreferrer">GitHub</a>
          <a href={links.release} target="_blank" rel="noreferrer">Releases</a>
          <a href={links.issues} target="_blank" rel="noreferrer">Issues</a>
        </nav>
      </div>
    </footer>
  );
}

function App() {
  useSmoothScroll({ duration: 1.25 });
  return (
    <main>
      <Hero />
      <Manifesto />
      <Numbers />
      <How />
      <Features />
      <Quality />
      <Install />
      <GoLive />
      <Faq />
      <Footer />
    </main>
  );
}

createRoot(document.getElementById("root")).render(<App />);
