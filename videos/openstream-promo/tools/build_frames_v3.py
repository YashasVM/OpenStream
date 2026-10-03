#!/usr/bin/env python3
"""Generate the 10 OpenStream 2 promo frames (v3: minimal monochrome, 40s, 120 BPM).

Dark (logo black) for the quiet open and the close, warm paper for the product
section. Inter only. CSS-3D stage under a perspective camera in every frame.
Beat B = 0.5s; every frame is 4.0s (two bars). Run from the project root:
    python3 tools/build_frames_v3.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "compositions" / "frames"
B = 0.5
D = 4.0

DARK = "#0B0B0C"
PAPER = "#F3EFE8"
INK = "#0E0E0F"
WARM = "#5E5A54"      # secondary text on paper
DIM = "#8C877F"       # secondary text on dark
CARD = "#FBF9F5"
HAIR = "rgba(14,14,15,0.12)"

FACES = "\n".join(
    f'    @font-face {{ font-family: "Inter"; src: url("assets/fonts/Inter-{w}.woff2") format("woff2"); font-weight: {w}; }}'
    for w in (400, 500, 600, 700)
)

O_PATH = "M 110.6 78.6 A 29.6 29.6 0 1 0 108.6 112.6"
S_PATH = "M 137 87 L 103.5 87 A 8.7 8.7 0 0 0 103.5 104.4 L 127.6 104.4 A 8.7 8.7 0 0 1 127.6 121.8 L 107.5 121.8"
GH = ('<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>')


def logo(cls, color, oid=None, sid=None, width=5.4):
    a = f' id="{oid}"' if oid else ""
    b = f' id="{sid}"' if sid else ""
    return (f'<svg class="{cls}" viewBox="52 60 90 68" aria-label="OpenStream logo"><g fill="none" stroke="{color}" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round">'
            f'<path{a} pathLength="1" d="{O_PATH}"/><path{b} pathLength="1" d="{S_PATH}"/></g></svg>')


def tile(p, tid, x, y, s, depth=10):
    layers = "".join(f'<i class="tl-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (f'<div class="{p}-tile" id="{tid}" style="left: {x}px; top: {y}px; width: {s}px; height: {s}px;">{layers}'
            f'<div class="tl-f">{logo("tl-logo", "#F3EFE8")}</div></div>')


def phone(p, pid, x, y, w, h, depth=14):
    layers = "".join(f'<i class="ph-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (f'<div class="{p}-phone" id="{pid}" style="left: {x}px; top: {y}px; width: {w}px; height: {h}px;">'
            f'{layers}<i class="ph-back" style="transform: translateZ(-{depth + 1}px);"></i>'
            f'<div class="ph-front"><div class="ph-screen"><img src="assets/phone-app.jpg" alt="OpenStream Android app camera screen" /></div><i class="ph-hole"></i></div></div>')


def base_css(p, dark):
    bg = (f"radial-gradient(ellipse 70% 60% at 50% 42%, #1A1A1C 0%, {DARK} 70%)" if dark
          else f"radial-gradient(ellipse 75% 65% at 50% 40%, #F8F5F0 0%, {PAPER} 60%, #E9E3D8 100%)")
    return f"""
    #root {{ position: absolute; inset: 0; overflow: hidden; color: {PAPER if dark else INK}; font-family: "Inter", sans-serif; }}
    .{p}-bg {{ position: absolute; inset: 0; background: {bg}; }}
    .{p}-cam {{ position: absolute; inset: 0; perspective: 1700px; perspective-origin: 50% 45%; }}
    .{p}-stage {{ position: absolute; inset: 0; transform-style: preserve-3d; }}
    .{p}-h {{ display: block; font-weight: 600; letter-spacing: -0.04em; line-height: 1.04; white-space: nowrap; }}
    .{p}-kick {{ display: block; font-size: 24px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; color: {DIM if dark else WARM}; white-space: nowrap; }}
    .{p}-sub {{ display: block; font-weight: 500; letter-spacing: -0.01em; color: {DIM if dark else WARM}; white-space: nowrap; }}
    .{p}-card {{ background: {CARD}; border-radius: 28px; box-shadow: 0 0 0 1px {HAIR}, 0 2px 6px rgba(14,14,15,0.04), 0 30px 70px rgba(40,30,15,0.12); }}
    .{p}-pill {{ position: absolute; display: flex; align-items: center; gap: 12px; height: 60px; padding: 0 26px; border-radius: 9999px; background: {CARD}; font-size: 25px; font-weight: 600; white-space: nowrap; box-shadow: 0 0 0 1px {HAIR}, 0 14px 34px rgba(40,30,15,0.14); }}
    .{p}-pill b {{ display: block; width: 10px; height: 10px; border-radius: 50%; background: {INK}; }}
    .{p}-tile {{ position: absolute; transform-style: preserve-3d; }}
    .{p}-tile .tl-l {{ position: absolute; inset: 0; border-radius: 24%; background: #2A2A2C; }}
    .{p}-tile .tl-f {{ position: absolute; inset: 0; border-radius: 24%; background: linear-gradient(160deg, #1C1C1E, {DARK}); display: flex; align-items: center; justify-content: center; box-shadow: inset 0 0 0 1px rgba(255,255,255,0.08); }}
    .{p}-tile .tl-logo {{ display: block; width: 62%; height: auto; }}
    .{p}-phone {{ position: absolute; transform-style: preserve-3d; }}
    .{p}-phone .ph-l {{ position: absolute; inset: 0; border-radius: 46px; background: #2B2B2E; }}
    .{p}-phone .ph-back {{ position: absolute; inset: 0; border-radius: 46px; background: linear-gradient(140deg, #4A4A4F, #1C1C1E); }}
    .{p}-phone .ph-front {{ position: absolute; inset: 0; border-radius: 46px; background: #0E0E10; box-shadow: inset 0 0 0 2px #3A3A3E; }}
    .{p}-phone .ph-screen {{ position: absolute; inset: 11px; border-radius: 36px; overflow: hidden; background: #000; }}
    .{p}-phone .ph-screen img {{ display: block; width: 100%; height: 100%; object-fit: cover; }}
    .{p}-phone .ph-hole {{ position: absolute; left: 50%; top: 22px; width: 16px; height: 16px; margin-left: -8px; border-radius: 50%; background: #050505; box-shadow: 0 0 0 2px #1d1d20; }}
    .{p}-shadow {{ position: absolute; border-radius: 50%; background: radial-gradient(closest-side, rgba(40,30,15,0.26), transparent); }}
"""


def frame(fid, p, dark, css, body, js, overlay=""):
    return f"""<template>
  <style>
{FACES}
{base_css(p, dark)}{css}
  </style>

  <div id="root" data-composition-id="{fid}" data-width="1920" data-height="1080">
    <div id="{p}-bg" class="{p}-bg clip" data-start="0" data-duration="{D}" data-track-index="0"></div>
    <div id="{p}-cam" class="{p}-cam clip" data-start="0" data-duration="{D}" data-track-index="1">
      <div class="{p}-stage" id="{p}-stage">
{body}
      </div>
    </div>{overlay}
  </div>

  <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
  <script>
    (function () {{
      const B = {B};
      const D = {D};
      const tl = gsap.timeline({{ paused: true }});
{js}
      window.__timelines["{fid}"] = tl;
    }})();
  </script>
</template>
"""


frames = {}

# ---------------------------------------------------------------- 01 intro (quiet)
p = "f01"
body = """        <div class="f01-lines">
          <span class="f01-h" id="f01-a" style="color: #8C877F;">Your phone has</span>
          <span class="f01-h" id="f01-b">a great camera.</span>
        </div>"""
css = """
    .f01-lines { position: absolute; left: 0; right: 0; top: 380px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .f01-h { font-size: 112px; }
"""
js = """      tl.fromTo("#f01-stage", { z: -140 }, { z: 0, duration: D, ease: "sine.inOut" }, 0);
      tl.fromTo("#f01-a", { opacity: 0, y: 30, filter: "blur(12px)" }, { opacity: 1, y: 0, filter: "blur(0px)", duration: 1.4, ease: "power3.out" }, 0.3);
      tl.fromTo("#f01-b", { opacity: 0, y: 30, filter: "blur(12px)" }, { opacity: 1, y: 0, filter: "blur(0px)", duration: 1.4, ease: "power3.out" }, 1.3);
"""
frames["01-intro"] = frame("01-intro", p, True, css, body, js)

# ---------------------------------------------------------------- 02 problem
p = "f02"
items = ["Capture cards", "Webcam apps", "Cloud relays"]
body = """        <div class="f02-lines">
          <span class="f02-h" id="f02-a">Getting it into OBS</span>
          <span class="f02-h" id="f02-b" style="color: #8C877F;">shouldn't be this hard.</span>
        </div>
        <div class="f02-items">""" + "".join(
    f'<span class="f02-it" id="f02-i{i}">{t}<i class="f02-strike" id="f02-s{i}"></i></span>' for i, t in enumerate(items)
) + "</div>"
css = f"""
    .f02-lines {{ position: absolute; left: 0; right: 0; top: 330px; display: flex; flex-direction: column; align-items: center; gap: 8px; }}
    .f02-h {{ font-size: 96px; }}
    .f02-items {{ position: absolute; left: 0; right: 0; top: 700px; display: flex; justify-content: center; gap: 64px; }}
    .f02-it {{ position: relative; display: block; font-size: 34px; font-weight: 500; color: {DIM}; white-space: nowrap; }}
    .f02-strike {{ position: absolute; left: -6px; right: -6px; top: 52%; height: 2px; background: rgba(243,239,232,0.7); }}
"""
js = """      tl.fromTo("#f02-stage", { z: -60, rotationX: 4 }, { z: 60, rotationX: 0, duration: D, ease: "sine.inOut" }, 0);
      tl.fromTo("#f02-a", { opacity: 0, y: 30, filter: "blur(10px)" }, { opacity: 1, y: 0, filter: "blur(0px)", duration: 1.1, ease: "power3.out" }, 0.1);
      tl.fromTo("#f02-b", { opacity: 0, y: 30, filter: "blur(10px)" }, { opacity: 1, y: 0, filter: "blur(0px)", duration: 1.1, ease: "power3.out" }, 0.7);
      for (let i = 0; i < 3; i++) {
        const t = 1.6 + i * B;
        tl.fromTo("#f02-i" + i, { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, t);
        tl.fromTo("#f02-s" + i, { scaleX: 0 }, { scaleX: 1, duration: 0.45, ease: "power2.inOut", transformOrigin: "0% 50%" }, t + 0.5);
        tl.to("#f02-i" + i, { opacity: 0.45, duration: 0.3 }, t + 0.8);
      }
"""
frames["02-problem"] = frame("02-problem", p, True, css, body, js)

# ---------------------------------------------------------------- 03 turn (phone from darkness)
p = "f03"
body = f"""        <div class="f03-glow" id="f03-glow"></div>
        {phone(p, "f03-ph", 430, 170, 350, 724)}
        <div class="f03-copy"><span class="f03-h" style="color: {DIM};" id="f03-a">There's a</span><span class="f03-h" id="f03-b">simpler way.</span></div>"""
css = """
    .f03-glow { position: absolute; left: 230px; top: 140px; width: 760px; height: 780px; border-radius: 50%; background: radial-gradient(closest-side, rgba(243,239,232,0.16), transparent); }
    .f03-copy { position: absolute; left: 960px; top: 400px; display: flex; flex-direction: column; gap: 6px; }
    .f03-h { font-size: 104px; }
"""
js = """      tl.fromTo("#f03-stage", { rotationY: 8, z: -80 }, { rotationY: -4, z: 40, duration: D, ease: "sine.inOut" }, 0);
      tl.fromTo("#f03-ph", { opacity: 0, z: -700, rotationY: -55, rotationX: 14 }, { opacity: 1, z: 0, rotationY: -18, rotationX: 4, duration: 2.2, ease: "power3.out" }, 0);
      tl.to("#f03-ph", { rotationY: 10, rotationX: 0, duration: D - 2.2, ease: "sine.inOut" }, 2.2);
      tl.fromTo("#f03-glow", { opacity: 0, scale: 0.6 }, { opacity: 1, scale: 1, duration: 2.4, ease: "power2.out" }, 0.2);
      tl.fromTo("#f03-a", { opacity: 0, x: 40 }, { opacity: 1, x: 0, duration: 1.0, ease: "power3.out" }, 1.2);
      tl.fromTo("#f03-b", { opacity: 0, x: 40 }, { opacity: 1, x: 0, duration: 1.0, ease: "power3.out" }, 1.7);
"""
frames["03-turn"] = frame("03-turn", p, True, css, body, js)

# ---------------------------------------------------------------- 04 logo draw (the build)
p = "f04"
body = f"""        <div class="f04-mark" id="f04-mark">{logo("f04-logo", "#F3EFE8", "f04-o", "f04-s", 4.6)}</div>
        <div class="f04-word" id="f04-word">OpenStream</div>"""
css = """
    .f04-mark { position: absolute; left: 720px; top: 210px; width: 480px; height: 363px; }
    .f04-logo { display: block; width: 100%; height: 100%; }
    .f04-logo path { stroke-dasharray: 1 2; }
    .f04-word { position: absolute; left: 0; right: 0; top: 650px; text-align: center; font-size: 76px; font-weight: 600; letter-spacing: -0.03em; white-space: nowrap; }
    .f04-flash { position: absolute; inset: 0; background: #FFFFFF; }
"""
overlay = '\n    <div id="f04-flash" class="f04-flash clip" data-start="3.6" data-duration="0.4" data-track-index="2"></div>'
js = """      tl.fromTo("#f04-stage", { z: -120 }, { z: 0, duration: 3.4, ease: "sine.inOut" }, 0);
      tl.fromTo("#f04-o", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.8, ease: "power2.inOut" }, 0.1);
      tl.fromTo("#f04-s", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.4, ease: "power2.inOut" }, 1.0);
      tl.fromTo("#f04-word", { opacity: 0, y: 24, filter: "blur(8px)" }, { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.9, ease: "power3.out" }, 2.2);
      // the last half-bar: rush toward the mark into the drop
      tl.to("#f04-stage", { z: 900, duration: 0.6, ease: "power3.in" }, 3.4);
      tl.fromTo("#f04-flash", { opacity: 0 }, { opacity: 1, duration: 0.35, ease: "power2.in" }, 3.6);
"""
frames["04-logo"] = frame("04-logo", p, True, css, body, js, overlay)

# ---------------------------------------------------------------- 05 reveal (the drop)
p = "f05"
body = f"""        {tile(p, "f05-tile", 860, 150, 200)}
        <div class="f05-kick" id="f05-k" style="top: 382px;">Introducing</div>
        <div class="f05-title" id="f05-t">OpenStream <span class="f05-two">2</span></div>
        <div class="f05-sub" id="f05-s">Your Android phone. A native OBS camera.</div>
        <div class="f05-tag" id="f05-g">Works out of the box.</div>"""
css = f"""
    .f05-kick {{ position: absolute; left: 0; right: 0; text-align: center; font-size: 24px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; color: {WARM}; white-space: nowrap; }}
    .f05-title {{ position: absolute; left: 0; right: 0; top: 462px; text-align: center; font-size: 176px; font-weight: 700; letter-spacing: -0.05em; line-height: 1; white-space: nowrap; }}
    .f05-two {{ display: inline-block; color: {WARM}; font-weight: 500; }}
    .f05-sub {{ position: absolute; left: 0; right: 0; top: 670px; text-align: center; font-size: 44px; font-weight: 500; letter-spacing: -0.02em; color: {WARM}; white-space: nowrap; }}
    .f05-tag {{ position: absolute; left: 50%; top: 770px; width: 520px; margin-left: -260px; height: 76px; border-radius: 9999px; background: {INK}; color: {PAPER}; display: flex; align-items: center; justify-content: center; font-size: 30px; font-weight: 600; white-space: nowrap; }}
    .f05-flash {{ position: absolute; inset: 0; background: #FFFFFF; }}
"""
overlay = '\n    <div id="f05-flash" class="f05-flash clip" data-start="0" data-duration="0.6" data-track-index="2"></div>'
js = """      tl.fromTo("#f05-flash", { opacity: 1 }, { opacity: 0, duration: 0.55, ease: "power2.out" }, 0);
      tl.fromTo("#f05-stage", { rotationX: 8, rotationY: -10, z: -200 }, { rotationX: 0, rotationY: 6, z: 0, duration: D, ease: "power2.out" }, 0);
      tl.fromTo("#f05-tile", { rotationY: -200, rotationX: 20, z: 500, opacity: 0 }, { rotationY: 0, rotationX: 0, z: 0, opacity: 1, duration: 1.0, ease: "expo.out" }, 0);
      tl.fromTo("#f05-k", { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.5, ease: "power3.out" }, 0.25);
      tl.fromTo("#f05-t", { opacity: 0, z: 400, filter: "blur(16px)" }, { opacity: 1, z: 0, filter: "blur(0px)", duration: 0.7, ease: "expo.out" }, 0.1);
      tl.fromTo("#f05-s", { opacity: 0, y: 24 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 2 * B);
      tl.fromTo("#f05-g", { opacity: 0, scale: 0.85 }, { opacity: 1, scale: 1, duration: 0.6, ease: "back.out(1.6)" }, 4 * B);
      for (let k = 1; k < 8; k++) {
        tl.fromTo("#f05-tile", { z: 40 }, { z: 0, duration: 0.35, ease: "power2.out", immediateRender: false }, k * B);
      }
"""
frames["05-reveal"] = frame("05-reveal", p, False, css, body, js, overlay)

# ---------------------------------------------------------------- 06 steps
p = "f06"
steps = [("01", "Install the app", "Open it, grant the camera."),
         ("02", "Add the OBS plugin", "Your phone shows up on its own."),
         ("03", "Go live", "No accounts. No cloud.")]
cards = "".join(
    f'<div class="f06-c f06-card" id="f06-c{i}" style="left: {160 + i * 560}px;">'
    f'<span class="f06-n">{n}</span><span class="f06-t">{t}</span><span class="f06-d">{d}</span>'
    f'<i class="f06-ok" id="f06-ok{i}"><i></i></i></div>'
    for i, (n, t, d) in enumerate(steps)
)
body = f"""        <div class="f06-head"><span class="f06-kick">Out of the box</span><span class="f06-h" style="font-size: 84px;">Three steps. That's it.</span></div>
        {cards}"""
css = f"""
    .f06-head {{ position: absolute; left: 160px; top: 150px; display: flex; flex-direction: column; gap: 18px; }}
    .f06-c {{ position: absolute; top: 420px; width: 500px; height: 400px; padding: 48px; box-sizing: border-box; display: flex; flex-direction: column; }}
    .f06-n {{ display: block; font-size: 30px; font-weight: 600; color: {WARM}; font-variant-numeric: tabular-nums; }}
    .f06-t {{ display: block; margin-top: 108px; font-size: 46px; font-weight: 600; letter-spacing: -0.035em; white-space: nowrap; }}
    .f06-d {{ display: block; margin-top: 12px; font-size: 26px; font-weight: 500; color: {WARM}; white-space: nowrap; }}
    .f06-ok {{ position: absolute; right: 44px; top: 40px; width: 56px; height: 56px; border-radius: 50%; background: {INK}; display: block; }}
    .f06-ok i {{ position: absolute; left: 19px; top: 13px; width: 13px; height: 24px; border: solid {PAPER}; border-width: 0 4px 4px 0; transform: rotate(45deg); display: block; }}
"""
js = """      tl.fromTo("#f06-stage", { rotationY: 12, rotationX: 8, z: -120 }, { rotationY: -6, rotationX: 2, z: 0, duration: D, ease: "power2.inOut" }, 0);
      tl.fromTo(".f06-head", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 0);
      for (let i = 0; i < 3; i++) {
        const t = 0.3 + i * 2 * B;
        tl.fromTo("#f06-c" + i, { opacity: 0, z: -500, rotationX: 25, y: 60 }, { opacity: 1, z: 0, rotationX: 0, y: 0, duration: 0.8, ease: "expo.out" }, t);
        tl.fromTo("#f06-ok" + i, { scale: 0, rotation: -45 }, { scale: 1, rotation: 0, duration: 0.45, ease: "back.out(2.2)" }, t + B);
      }
"""
frames["06-steps"] = frame("06-steps", p, False, css, body, js)

# ---------------------------------------------------------------- 07 proof
p = "f07"
body = f"""        <div class="f07-head"><span class="f07-kick">Real footage</span><span class="f07-h" style="font-size: 84px;">Live in OBS.</span></div>
        <div class="f07-win f07-card" id="f07-win"><div class="f07-bar"><i></i><i></i><i></i><span>OBS Studio</span></div><img src="assets/obs-live.png" alt="OBS Studio showing the OpenStream phone camera live" /></div>
        <div class="f07-shadow" id="f07-sh" style="left: 220px; top: 920px; width: 400px; height: 54px;"></div>
        {phone(p, "f07-ph", 270, 320, 290, 600)}
        <div class="f07-pill" id="f07-p0" style="left: 1080px; top: 330px;"><b></b>Native OBS source</div>
        <div class="f07-pill" id="f07-p1" style="left: 470px; top: 880px;"><b></b>Found on your Wi-Fi automatically</div>"""
css = """
    .f07-head { position: absolute; left: 160px; top: 120px; display: flex; flex-direction: column; gap: 16px; }
    .f07-win { position: absolute; left: 640px; top: 300px; width: 1100px; height: 649px; overflow: hidden; border-radius: 22px; background: #1E1E20; }
    .f07-bar { height: 52px; display: flex; align-items: center; gap: 10px; padding: 0 20px; background: #2A2A2D; }
    .f07-bar i { display: block; width: 14px; height: 14px; border-radius: 50%; background: rgba(255,255,255,0.22); }
    .f07-bar span { display: block; margin-left: 14px; font-size: 20px; font-weight: 600; color: rgba(255,255,255,0.7); }
    .f07-win img { display: block; width: 1100px; height: 597px; }
"""
js = """      tl.fromTo("#f07-stage", { rotationY: 20, rotationX: 8, z: -200 }, { rotationY: -6, rotationX: 2, z: 80, duration: D, ease: "power2.inOut" }, 0);
      tl.fromTo(".f07-head", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 0);
      tl.fromTo("#f07-win", { opacity: 0, z: -900, rotationY: 24 }, { opacity: 1, z: 0, rotationY: 0, duration: 1.0, ease: "expo.out" }, 0.05);
      tl.fromTo("#f07-ph", { x: -500, z: 300, rotationY: -70 }, { x: 0, z: 120, rotationY: 16, duration: 0.9, ease: "expo.out" }, 2 * B);
      tl.to("#f07-ph", { rotationY: 4, duration: D - 2 * B - 0.9, ease: "sine.inOut" }, 2 * B + 0.9);
      tl.fromTo("#f07-sh", { opacity: 0 }, { opacity: 1, duration: 0.6 }, 2 * B + 0.2);
      tl.fromTo("#f07-p0", { opacity: 0, scale: 0.6, z: 160 }, { opacity: 1, scale: 1, z: 160, duration: 0.5, ease: "back.out(2)" }, 4 * B);
      tl.fromTo("#f07-p1", { opacity: 0, scale: 0.6, z: 160 }, { opacity: 1, scale: 1, z: 160, duration: 0.5, ease: "back.out(2)" }, 5 * B);
"""
frames["07-proof"] = frame("07-proof", p, False, css, body, js)

# ---------------------------------------------------------------- 08 control
p = "f08"
rows = ["Front & rear lenses", "Zoom", "Torch on / off", "Identify the phone"]
body = f"""        <div class="f08-head"><span class="f08-kick">Remote control</span><span class="f08-h" style="font-size: 84px;">Run the camera</span><span class="f08-h" style="font-size: 84px; color: {WARM};">from OBS.</span></div>
        <div class="f08-list">""" + "".join(
    f'<div class="f08-row" id="f08-r{i}"><i></i>{t}</div>' for i, t in enumerate(rows)
) + f"""</div>
        <div class="f08-shadow" id="f08-sh" style="left: 1150px; top: 930px; width: 620px; height: 60px;"></div>
        <div class="f08-dock f08-card" id="f08-dock"><img src="assets/obs-dock-panel.png" alt="OpenStream Camera Control dock in OBS" /></div>"""
css = f"""
    .f08-head {{ position: absolute; left: 160px; top: 150px; display: flex; flex-direction: column; gap: 14px; }}
    .f08-list {{ position: absolute; left: 160px; top: 520px; width: 760px; }}
    .f08-row {{ display: flex; align-items: center; gap: 20px; height: 84px; border-top: 1px solid {HAIR}; font-size: 36px; font-weight: 500; letter-spacing: -0.02em; white-space: nowrap; }}
    .f08-row i {{ display: block; width: 10px; height: 10px; border-radius: 50%; background: {INK}; }}
    .f08-dock {{ position: absolute; left: 1180px; top: 150px; width: 560px; height: 800px; overflow: hidden; border-radius: 26px; background: #1E1E20; }}
    .f08-dock img {{ display: block; width: 560px; height: 800px; }}
"""
js = """      tl.fromTo("#f08-stage", { rotationY: -10, rotationX: 6, z: -100 }, { rotationY: 6, rotationX: 0, z: 40, duration: D, ease: "power2.inOut" }, 0);
      tl.fromTo(".f08-head", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 0);
      tl.fromTo("#f08-dock", { opacity: 0, z: -600, rotationY: -40, x: 200 }, { opacity: 1, z: 0, rotationY: -14, x: 0, duration: 1.1, ease: "expo.out" }, 0.1);
      tl.to("#f08-dock", { rotationY: -4, duration: D - 1.2, ease: "sine.inOut" }, 1.2);
      tl.fromTo("#f08-sh", { opacity: 0 }, { opacity: 1, duration: 0.8 }, 0.4);
      for (let i = 0; i < 4; i++) {
        tl.fromTo("#f08-r" + i, { opacity: 0, x: -30 }, { opacity: 1, x: 0, duration: 0.5, ease: "power3.out" }, (2 + i) * B);
      }
"""
frames["08-control"] = frame("08-control", p, False, css, body, js)

# ---------------------------------------------------------------- 09 specs
p = "f09"
specs = [("Connection", "Local Wi-Fi"), ("Transport", "SRT"), ("Video", "H.264 + AAC"),
         ("Bitrate", "12 Mbps default"), ("Cloud", "None"), ("Price", "Free")]
body = """        <div class="f09-head"><span class="f09-kick">Under the hood</span><span class="f09-h" style="font-size: 84px;">Simple on purpose.</span></div>
        <div class="f09-table" id="f09-table">""" + "".join(
    f'<div class="f09-row" id="f09-r{i}"><span class="f09-l">{l}</span><span class="f09-v">{v}</span></div>' for i, (l, v) in enumerate(specs)
) + "</div>"
css = f"""
    .f09-head {{ position: absolute; left: 160px; top: 150px; display: flex; flex-direction: column; gap: 18px; }}
    .f09-table {{ position: absolute; left: 160px; right: 160px; top: 380px; transform-style: preserve-3d; }}
    .f09-row {{ display: flex; justify-content: space-between; align-items: center; height: 92px; border-top: 1px solid {HAIR}; }}
    .f09-l {{ display: block; font-size: 30px; font-weight: 500; color: {WARM}; white-space: nowrap; }}
    .f09-v {{ display: block; font-size: 44px; font-weight: 600; letter-spacing: -0.03em; white-space: nowrap; }}
"""
js = """      tl.fromTo("#f09-stage", { z: -80 }, { z: 40, duration: D, ease: "sine.inOut" }, 0);
      tl.fromTo(".f09-head", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 0);
      tl.fromTo("#f09-table", { rotationX: 28, y: 80 }, { rotationX: 0, y: 0, duration: 1.6, ease: "power3.out", transformOrigin: "50% 0%" }, 0.1);
      for (let i = 0; i < 6; i++) {
        tl.fromTo("#f09-r" + i, { opacity: 0, y: 20 }, { opacity: 1, y: 0, duration: 0.5, ease: "power3.out" }, 0.4 + i * B);
      }
"""
frames["09-specs"] = frame("09-specs", p, False, css, body, js)

# ---------------------------------------------------------------- 10 cta (dark, final)
p = "f10"
body = f"""        <div class="f10-mark">{logo("f10-logo", "#F3EFE8", "f10-o", "f10-s", 5)}</div>
        <div class="f10-title" id="f10-t">OpenStream <span style="color: {DIM}; font-weight: 500;">2</span></div>
        <div class="f10-sub" id="f10-s2">Free and open source.</div>
        <div class="f10-btnwrap"><div class="f10-btn" id="f10-btn">{GH}<span>Star on GitHub</span></div></div>
        <div class="f10-url" id="f10-url">github.com/YashasVM/OpenStream</div>"""
css = f"""
    .f10-mark {{ position: absolute; left: 870px; top: 150px; width: 180px; height: 136px; }}
    .f10-logo {{ display: block; width: 100%; height: 100%; }}
    .f10-logo path {{ stroke-dasharray: 1; }}
    .f10-title {{ position: absolute; left: 0; right: 0; top: 340px; text-align: center; font-size: 140px; font-weight: 700; letter-spacing: -0.05em; line-height: 1; white-space: nowrap; }}
    .f10-sub {{ position: absolute; left: 0; right: 0; top: 520px; text-align: center; font-size: 42px; font-weight: 500; color: {DIM}; white-space: nowrap; }}
    .f10-btnwrap {{ position: absolute; left: 0; right: 0; top: 640px; display: flex; justify-content: center; }}
    .f10-btn {{ display: flex; align-items: center; gap: 20px; height: 104px; padding: 0 52px; border-radius: 9999px; background: {PAPER}; color: {INK}; font-size: 40px; font-weight: 600; letter-spacing: -0.02em; white-space: nowrap; }}
    .f10-btn svg {{ display: block; width: 46px; height: 46px; }}
    .f10-url {{ position: absolute; left: 0; right: 0; top: 790px; text-align: center; font-size: 30px; font-weight: 500; color: {DIM}; white-space: nowrap; }}
"""
js = """      tl.fromTo("#f10-stage", { z: -260, rotationX: 6 }, { z: 0, rotationX: 0, duration: 2.0, ease: "power3.out" }, 0);
      tl.to("#f10-stage", { z: 50, duration: D - 2.0, ease: "sine.inOut" }, 2.0);
      tl.fromTo("#f10-o", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.9, ease: "power2.inOut" }, 0);
      tl.fromTo("#f10-s", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.7, ease: "power2.inOut" }, 0.35);
      tl.fromTo("#f10-t", { opacity: 0, z: 300, filter: "blur(14px)" }, { opacity: 1, z: 0, filter: "blur(0px)", duration: 0.7, ease: "expo.out" }, 0.1);
      tl.fromTo("#f10-s2", { opacity: 0, y: 20 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 2 * B);
      tl.fromTo("#f10-btn", { opacity: 0, rotationX: -80, z: 80 }, { opacity: 1, rotationX: 0, z: 0, duration: 0.7, ease: "back.out(1.4)", transformOrigin: "50% 100%" }, 3 * B);
      tl.fromTo("#f10-url", { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" }, 4 * B);
      // final frame: settle into black
      tl.to("#f10-stage", { opacity: 0, duration: 0.6, ease: "power2.in" }, D - 0.6);
"""
frames["10-cta"] = frame("10-cta", p, True, css, body, js)

for old in OUT.glob("*.html"):
    old.unlink()
for fid, html in frames.items():
    (OUT / f"{fid}.html").write_text(html)
    print("wrote", fid)
