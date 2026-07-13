import React, { useEffect, useRef, useState } from "react";
import { Icon } from "../icons";
import { links, product } from "../product";

export function Logo({ compact = false }) {
  return (
    <span className="logo-lockup">
      <img src="/brand/openstream-logo.png" alt="" width="36" height="36" />
      {!compact && <><strong>{product.name}</strong><span>{product.displayVersion}</span></>}
    </span>
  );
}

export function Header() {
  const [open, setOpen] = useState(false);
  const menuButtonRef = useRef(null);
  const navRef = useRef(null);
  useEffect(() => {
    const close = (event) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      menuButtonRef.current?.focus();
    };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, []);

  const toggleMenu = () => {
    const next = !open;
    setOpen(next);
    if (next) requestAnimationFrame(() => navRef.current?.querySelector("a")?.focus());
  };

  const closeMenu = () => setOpen(false);
  return (
    <header className="site-header">
      <a className="brand" href="#top" aria-label="OpenStream home" onClick={closeMenu}><Logo /></a>
      <nav ref={navRef} className={open ? "nav-links is-open" : "nav-links"} aria-label="Main navigation">
        <a href="#product" onClick={closeMenu}>Product</a>
        <a href="#how-it-works" onClick={closeMenu}>How it works</a>
        <a href="#downloads" onClick={closeMenu}>Downloads</a>
        <a href={links.setup} onClick={closeMenu}>Docs</a>
        <a href={links.repo} onClick={closeMenu}>GitHub <Icon name="external" size={14} /></a>
      </nav>
      <a className="button button-primary header-cta" href="#downloads">Get OpenStream</a>
      <button ref={menuButtonRef} className="menu-button" type="button" aria-label={open ? "Close navigation" : "Open navigation"} aria-expanded={open} onClick={toggleMenu}>
        <Icon name={open ? "close" : "menu"} />
      </button>
    </header>
  );
}
