import { useEffect, useState } from "react";
import Lenis from "lenis";

export const prefersReducedMotion = () =>
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Inertial smooth scrolling; also smooths in-page #anchor jumps. */
export function useSmoothScroll(options = {}) {
  useEffect(() => {
    if (prefersReducedMotion()) return;
    const lenis = new Lenis({ duration: 1.1, ...options });
    let raf = requestAnimationFrame(function loop(t) {
      lenis.raf(t);
      raf = requestAnimationFrame(loop);
    });
    const onClick = (e) => {
      const a = e.target.closest('a[href^="#"]');
      if (!a) return;
      const target = document.querySelector(a.getAttribute("href"));
      if (!target) return;
      e.preventDefault();
      lenis.scrollTo(target, { offset: -24 });
    };
    document.addEventListener("click", onClick);
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener("click", onClick);
      lenis.destroy();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}

export function detectPlatform() {
  const ua = navigator.userAgent || "";
  if (/android/i.test(ua)) return "android";
  if (/linux/i.test(ua) && !/cros/i.test(ua)) return "linux";
  return "windows";
}

export function usePlatform() {
  const [platform, setPlatform] = useState("windows");
  useEffect(() => setPlatform(detectPlatform()), []);
  return [platform, setPlatform];
}

export function useCopy() {
  const [copied, setCopied] = useState(null);
  const copy = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(text);
      setTimeout(() => setCopied((c) => (c === text ? null : c)), 1500);
    } catch {
      /* clipboard blocked; text stays selectable */
    }
  };
  return [copied, copy];
}

/** Cycles 0..count-1 on an interval (paused for reduced motion). */
export function useTicker(count, ms = 2400) {
  const [i, setI] = useState(0);
  useEffect(() => {
    if (prefersReducedMotion()) return;
    const id = setInterval(() => setI((v) => (v + 1) % count), ms);
    return () => clearInterval(id);
  }, [count, ms]);
  return i;
}
