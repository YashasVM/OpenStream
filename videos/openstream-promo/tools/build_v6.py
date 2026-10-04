#!/usr/bin/env python3
"""OpenStream v6 promo — energetic SaaS-demo film, one continuous composition (46s).

Inspired by (not copied from) the site: ink + warm paper + one orange, Unbounded
display + Inter. 120 BPM bed ("Car Ad Energy", HeyGen catalog) starts at 10s so
its drop lands on "Meet OpenStream" at 6.0s and it ends naturally at 46s.
Every scene is a 2D zoomer (exact camera targeting) > perspective cam > 3D world.
VO: HeyGen "Ewan – Bright & Energetic", word timestamps in assets/vo-v6/*.json.
Writes index.html. Run from the project root:  python3 tools/build_v6.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
K, K2, C, O, DIM = "#0b0b0a", "#171614", "#f2ede4", "#ff5b14", "#8a857b"
LINE = "rgba(242,237,228,0.13)"
TOTAL = 46.0
VO_START = [0.2, 2.9, 5.75, 7.4, 10.9, 15.9, 21.4, 26.0, 32.2, 40.3]
FEED = "assets/feed-creator.png"

# word -> absolute time, from the HeyGen timestamps
W = {}
for i, base in enumerate(VO_START):
    d = json.loads((ROOT / f"assets/vo-v6/l{i + 1}.json").read_text())["data"]
    for w in d["word_timestamps"]:
        if not w["word"].startswith("<"):
            W[f"{i + 1}:{w['word'].strip('.,?!').lower()}"] = round(base + w["start"], 3)
VO_DUR = [json.loads((ROOT / f"assets/vo-v6/l{i + 1}.json").read_text())["data"]["duration"] for i in range(10)]

O_PATH = "M 110.6 78.6 A 29.6 29.6 0 1 0 108.6 112.6"
S_PATH = "M 137 87 L 103.5 87 A 8.7 8.7 0 0 0 103.5 104.4 L 127.6 104.4 A 8.7 8.7 0 0 1 127.6 121.8 L 107.5 121.8"


def logo(cls, color=C, oid="", sid="", width=5.4):
    a = f' id="{oid}"' if oid else ""
    b = f' id="{sid}"' if sid else ""
    return (f'<svg class="{cls}" viewBox="52 60 90 68" aria-label="OpenStream logo"><g fill="none" stroke="{color}" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round">'
            f'<path{a} pathLength="1" d="{O_PATH}"/><path{b} pathLength="1" d="{S_PATH}"/></g></svg>')


def zpose(px, py, s):
    """2D zoomer (transform-origin 0 0) pose that puts world point (px,py) at frame centre."""
    return dict(x=round(960 - s * px, 1), y=round(540 - s * py, 1), scale=s)


def phone(pid, x, y, screen, w=320, h=660, depth=16):
    slabs = "".join(f'<i class="ph-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth))
    lenses = ('<div class="ph-mod"><i class="lens l1"></i><i class="lens l2"></i><i class="lens l3"></i><i class="flash"></i>'
              '<i class="glint" id="' + pid + '-glint"></i></div>')
    return (f'<div class="phone" id="{pid}" style="left: {x}px; top: {y}px; width: {w}px; height: {h}px;">'
            f'<div class="ph-back" style="transform: translateZ(-{depth}px) rotateY(180deg);">{lenses}</div>{slabs}'
            f'<div class="ph-front"><div class="ph-screen">{screen}</div><i class="ph-hole"></i></div></div>')


def app_screen(p):
    return f"""<img class="vf" src="{FEED}" alt="" />
          <div class="app-top"><span>{logo("app-logo")}OpenStream</span><b>1080p60</b></div>
          <div class="app-pill" id="{p}-pill"><i></i><span class="st0">Tap to start</span><span class="st1" id="{p}-st1">Ready · waiting for OBS</span><span class="st2" id="{p}-st2">Connected · LIVE</span></div>
          <div class="app-lens"><span>0.6×</span><span class="on">1×</span><span>3×</span><span>5×</span></div>
          <div class="app-go" id="{p}-go">Start</div><i class="tap" id="{p}-tap"></i>"""


def feed(fid, extra_cls=""):
    return f'<div class="feed {extra_cls}" id="{fid}"><div class="feed-z" id="{fid}-z"><img src="{FEED}" alt="Creator on camera" /></div></div>'


CUR = (f'<svg viewBox="0 0 28 36" aria-hidden="true"><path d="M3 2 L3 29 L10.5 22 L15.5 33 L20 31 L15 20.5 L25 20.5 Z" '
       f'fill="{C}" stroke="{K}" stroke-width="2.2" stroke-linejoin="round"/></svg>')


def cursor(cid):
    return f'<div class="cur" id="{cid}"><i class="cur-ring" id="{cid}-ring"></i>{CUR}</div>'


def obs(wid, menu=False, modal=False):
    extra = ""
    if menu:
        extra += (f'<div class="obs-menu" id="{wid}-menu"><span>Image</span><span>Browser</span>'
                  f'<span class="hl" id="{wid}-mi">{logo("obs-mini", O)}OpenStream</span><span>Video Capture</span></div>')
    if modal:
        extra += (f'<div class="obs-modal" id="{wid}-modal"><span class="om-t">Pick your phone</span>'
                  f'<span class="om-dev" id="{wid}-dev"><b></b><span><strong>Pixel 9 Pro</strong><em>Found on Wi-Fi · ready</em></span><i id="{wid}-chk">✓</i></span>'
                  f'<span class="om-dev dim"><b></b><span><strong>Galaxy S24</strong><em>Found on Wi-Fi</em></span></span></div>')
    return f"""<div class="obs" id="{wid}">
          <div class="obs-bar"><i></i><i></i><i></i><span>OBS Studio</span></div>
          <div class="obs-side"><span class="obs-h">Scenes</span><span class="obs-row on">Stream</span>
            <span class="obs-h" style="margin-top: 150px;">Sources</span>
            <span class="obs-row src" id="{wid}-src">{logo("obs-mini", O)}OpenStream</span>
            <span class="obs-plus" id="{wid}-plus">+</span></div>
          <div class="obs-prev"><span class="obs-empty" id="{wid}-empty">No signal</span>
            <div class="obs-feedwrap" id="{wid}-fw">{feed(wid + "-feed")}</div>
            <span class="obs-live" id="{wid}-live"><b></b>LIVE</span></div>
          <div class="obs-bot"><div class="obs-meters" id="{wid}-meters">{"".join(f'<i style="height:{h}%"></i>' for h in (60, 78, 52, 70, 64))}</div>
            <span class="obs-stream" id="{wid}-stream"><em>Start Streaming</em><em class="on" id="{wid}-on"><b></b>LIVE 00:00:01</em></span></div>
          {extra}</div>"""


CSS = f"""
    @font-face {{ font-family: "Unbounded"; src: url("assets/fonts/Unbounded-var.woff2") format("woff2"); font-weight: 200 900; }}
""" + "".join(f'    @font-face {{ font-family: "Inter"; src: url("assets/fonts/Inter-{w}.woff2") format("woff2"); font-weight: {w}; }}\n' for w in (400, 500, 600, 700, 800)) + f"""
    * {{ margin: 0; padding: 0; box-sizing: border-box; }}
    html, body {{ width: 1920px; height: 1080px; overflow: hidden; background: #000; }}
    #root {{ position: relative; width: 1920px; height: 1080px; overflow: hidden; background: {K}; color: {C}; font-family: "Inter", sans-serif; }}
    .bgl {{ position: absolute; inset: 0; background: radial-gradient(ellipse 60% 55% at 50% 48%, #1d1a16 0%, {K} 72%); }}
    .glow {{ position: absolute; left: 50%; top: 50%; width: 1400px; height: 1400px; margin: -700px 0 0 -700px; border-radius: 50%; background: radial-gradient(closest-side, rgba(255,91,20,0.22), rgba(255,91,20,0) 70%); opacity: 0; }}
    .grain {{ position: absolute; inset: 0; opacity: 0.07; mix-blend-mode: overlay; background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='240'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' seed='7'/></filter><rect width='240' height='240' filter='url(%23n)'/></svg>"); }}
    .scene {{ position: absolute; inset: 0; overflow: hidden; }}
    .zoomer {{ position: absolute; inset: 0; transform-origin: 0 0; }}
    .cam {{ position: absolute; inset: 0; perspective: 1700px; perspective-origin: 50% 46%; }}
    .world {{ position: absolute; inset: 0; transform-style: preserve-3d; transform-origin: 960px 540px; }}
    .disp {{ font-family: "Unbounded", sans-serif; font-weight: 800; letter-spacing: -0.05em; line-height: 0.92; text-transform: uppercase; white-space: nowrap; }}
    .o {{ color: {O}; }}
    .feed {{ position: absolute; inset: 0; overflow: hidden; background: #000; }}
    .feed-z {{ position: absolute; inset: 0; transform-origin: 52% 34%; }}
    .feed img {{ position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; display: block; }}
    .flash {{ position: absolute; inset: 0; background: #fff4ea; opacity: 0; pointer-events: none; }}
    /* phone */
    .phone {{ position: absolute; transform-style: preserve-3d; }}
    .phone .ph-l {{ position: absolute; inset: 0; border-radius: 50px; background: #262522; }}
    .phone .ph-back {{ position: absolute; inset: 0; border-radius: 50px; background: linear-gradient(150deg, #3b3934 0%, #1a1917 55%, #262420 100%); box-shadow: inset 0 0 0 2px #45423c; backface-visibility: hidden; }}
    .ph-mod {{ position: absolute; left: 26px; top: 26px; width: 150px; height: 150px; border-radius: 40px; background: linear-gradient(150deg, #2c2b28, #121110); box-shadow: inset 0 0 0 2px #4a4740, 0 10px 20px rgba(0,0,0,0.4); overflow: hidden; }}
    .lens {{ position: absolute; width: 56px; height: 56px; border-radius: 50%; background: radial-gradient(circle at 38% 35%, #4b5a73 0%, #0d1018 38%, #000 62%); box-shadow: 0 0 0 5px #1b1a18, 0 0 0 7px #55524a; display: block; }}
    .lens.l1 {{ left: 16px; top: 16px; }} .lens.l2 {{ left: 78px; top: 16px; }} .lens.l3 {{ left: 16px; top: 78px; }}
    .ph-mod .flash {{ left: 94px; top: 94px; width: 22px; height: 22px; border-radius: 50%; background: #e9dcc5; opacity: 0.85; inset: auto; position: absolute; }}
    .glint {{ position: absolute; left: -120px; top: -40px; width: 60px; height: 260px; background: linear-gradient(90deg, rgba(255,255,255,0), rgba(255,255,255,0.55), rgba(255,255,255,0)); transform: rotate(25deg); display: block; }}
    .phone .ph-front {{ position: absolute; inset: 0; border-radius: 50px; background: #0c0c0b; box-shadow: inset 0 0 0 2px #3c3a35; backface-visibility: hidden; }}
    .phone .ph-screen {{ position: absolute; inset: 12px; border-radius: 40px; overflow: hidden; background: #000; }}
    .phone .ph-hole {{ position: absolute; left: 50%; top: 24px; width: 16px; height: 16px; margin-left: -8px; border-radius: 50%; background: #050505; box-shadow: 0 0 0 2px #1c1b19; display: block; }}
    .vf {{ position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; object-position: 50% 30%; display: block; }}
    .app-top {{ position: absolute; left: 0; right: 0; top: 46px; display: flex; justify-content: space-between; padding: 0 22px; font: 600 15px "Inter", sans-serif; }}
    .app-top span {{ display: flex; align-items: center; gap: 7px; }}
    .app-top b {{ font-weight: 700; padding: 3px 9px; border-radius: 99px; background: rgba(11,11,10,0.6); }}
    .app-logo {{ width: 22px; height: 17px; display: block; }}
    .app-pill {{ position: absolute; left: 20px; right: 20px; top: 440px; height: 44px; border-radius: 99px; background: rgba(11,11,10,0.72); display: flex; align-items: center; gap: 10px; padding: 0 16px; font: 600 15px "Inter", sans-serif; overflow: hidden; }}
    .app-pill i {{ width: 9px; height: 9px; border-radius: 50%; background: {O}; display: block; flex: none; }}
    .app-pill span {{ position: absolute; left: 40px; white-space: nowrap; }}
    .app-pill .st1, .app-pill .st2 {{ opacity: 0; }}
    .app-lens {{ position: absolute; left: 0; right: 0; top: 500px; display: flex; justify-content: center; gap: 8px; }}
    .app-lens span {{ width: 50px; height: 50px; border-radius: 50%; background: rgba(11,11,10,0.6); display: flex; align-items: center; justify-content: center; font: 600 14px "Inter", sans-serif; }}
    .app-lens .on {{ background: {C}; color: {K}; }}
    .app-go {{ position: absolute; left: 50%; top: 566px; width: 120px; height: 40px; margin-left: -60px; border-radius: 99px; background: {O}; color: {K}; display: flex; align-items: center; justify-content: center; font: 700 16px "Inter", sans-serif; }}
    .tap {{ position: absolute; left: 148px; top: 586px; width: 70px; height: 70px; margin: -35px 0 0 -35px; border-radius: 50%; background: rgba(242,237,228,0.5); opacity: 0; display: block; }}
    /* cursor */
    .cur {{ position: absolute; left: 0; top: 0; width: 40px; height: 50px; z-index: 60; filter: drop-shadow(0 10px 16px rgba(0,0,0,0.55)); }}
    .cur svg {{ position: absolute; left: 0; top: 0; width: 40px; height: 50px; transform-origin: 6px 4px; }}
    .cur-ring {{ position: absolute; left: -26px; top: -28px; width: 60px; height: 60px; border-radius: 50%; border: 3px solid {O}; opacity: 0; display: block; }}
    /* obs window */
    .obs {{ position: absolute; width: 1180px; height: 700px; border-radius: 22px; background: {K2}; box-shadow: 0 0 0 1px {LINE}, 0 60px 140px rgba(0,0,0,0.65); overflow: hidden; }}
    .obs-bar {{ position: absolute; left: 0; right: 0; top: 0; height: 44px; display: flex; align-items: center; gap: 9px; padding-left: 18px; border-bottom: 1px solid {LINE}; }}
    .obs-bar i {{ width: 12px; height: 12px; border-radius: 50%; background: #3a3934; display: block; }}
    .obs-bar span {{ margin-left: 14px; font: 500 17px "Inter", sans-serif; color: {DIM}; }}
    .obs-side {{ position: absolute; left: 16px; top: 60px; width: 240px; height: 624px; border-radius: 14px; background: {K}; padding: 18px 14px; }}
    .obs-h {{ display: block; font: 600 14px "Inter", sans-serif; color: {DIM}; letter-spacing: 0.08em; text-transform: uppercase; margin-bottom: 10px; }}
    .obs-row {{ display: flex; align-items: center; gap: 10px; height: 42px; padding: 0 12px; border-radius: 10px; font: 500 18px "Inter", sans-serif; }}
    .obs-row.on {{ background: rgba(242,237,228,0.08); }}
    .obs-row.src {{ background: rgba(255,91,20,0.14); box-shadow: inset 0 0 0 1.5px {O}; opacity: 0; }}
    .obs-mini {{ width: 26px; height: 20px; display: block; }}
    .obs-plus {{ position: absolute; left: 14px; bottom: 14px; width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font: 500 28px "Inter", sans-serif; background: rgba(242,237,228,0.08); }}
    .obs-prev {{ position: absolute; left: 272px; top: 60px; width: 892px; height: 502px; border-radius: 14px; background: #050505; overflow: hidden; }}
    .obs-empty {{ position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font: 500 20px "Inter", sans-serif; color: {DIM}; letter-spacing: 0.1em; text-transform: uppercase; }}
    .obs-feedwrap {{ position: absolute; inset: 0; clip-path: inset(0 100% 0 0); }}
    .obs-live {{ position: absolute; left: 20px; top: 18px; display: flex; align-items: center; gap: 9px; height: 38px; padding: 0 16px; border-radius: 99px; background: {O}; color: {K}; font: 700 17px "Inter", sans-serif; letter-spacing: 0.08em; opacity: 0; }}
    .obs-live b, .obs-stream b {{ width: 9px; height: 9px; border-radius: 50%; background: {K}; display: block; }}
    .obs-bot {{ position: absolute; left: 272px; top: 576px; width: 892px; height: 108px; border-radius: 14px; background: {K}; }}
    .obs-meters {{ position: absolute; left: 22px; top: 20px; bottom: 20px; display: flex; gap: 8px; align-items: flex-end; }}
    .obs-meters i {{ width: 10px; border-radius: 3px; background: linear-gradient(0deg, {O} 0%, {C} 100%); opacity: 0.6; display: block; transform-origin: 50% 100%; }}
    .obs-stream {{ position: absolute; right: 20px; top: 26px; width: 260px; height: 56px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {C}; font: 600 19px "Inter", sans-serif; overflow: hidden; }}
    .obs-stream em {{ position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 10px; font-style: normal; }}
    .obs-stream em.on {{ background: {O}; color: {K}; font-weight: 700; opacity: 0; }}
    .obs-menu {{ position: absolute; left: 30px; top: 440px; width: 290px; padding: 10px; border-radius: 14px; background: #24221e; box-shadow: 0 0 0 1px {LINE}, 0 24px 50px rgba(0,0,0,0.5); display: flex; flex-direction: column; gap: 2px; opacity: 0; transform-origin: 0% 100%; }}
    .obs-menu span {{ display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 14px; border-radius: 9px; font: 500 19px "Inter", sans-serif; color: {DIM}; }}
    .obs-menu .hl {{ color: {C}; }}
    .obs-modal {{ position: absolute; left: 420px; top: 140px; width: 620px; padding: 28px; border-radius: 20px; background: #24221e; box-shadow: 0 0 0 1px {LINE}, 0 30px 70px rgba(0,0,0,0.6); opacity: 0; display: flex; flex-direction: column; gap: 12px; }}
    .om-t {{ font: 700 25px "Unbounded", sans-serif; letter-spacing: -0.03em; margin-bottom: 6px; }}
    .om-dev {{ display: flex; align-items: center; gap: 18px; height: 86px; padding: 0 22px; border-radius: 14px; box-shadow: inset 0 0 0 1.5px {LINE}; }}
    .om-dev b {{ width: 14px; height: 14px; border-radius: 50%; background: {O}; display: block; box-shadow: 0 0 14px {O}; }}
    .om-dev.dim {{ opacity: 0.45; }} .om-dev.dim b {{ background: {DIM}; box-shadow: none; }}
    .om-dev > span {{ display: flex; flex-direction: column; gap: 4px; flex: 1; }}
    .om-dev strong {{ font: 600 22px "Inter", sans-serif; }}
    .om-dev em {{ font: 500 17px "Inter", sans-serif; font-style: normal; color: {DIM}; }}
    .om-dev i {{ font: 700 28px "Inter", sans-serif; font-style: normal; color: {O}; opacity: 0; }}
"""

# ============================================================== scenes (HTML) + choreography (JS)
H, J = [], []  # html chunks, js lines


def scene(sid, start, end, inner, track):
    H.append(f'    <div id="{sid}" class="scene clip" data-start="{start}" data-duration="{round(end - start, 3)}" data-track-index="{track}">\n{inner}\n    </div>')


def js(s):
    J.append(s)


# ---------------------------------------------------------------- S1 problem: the laptop webcam (0–3.3)
scene("s1", 0, 3.3, f"""      <div class="cam"><div class="world" id="w1">
        <div class="s1-win" id="s1-win">
          <div class="s1-bar"><i></i><i></i><i></i><span>FaceTime HD Camera</span><b>720p</b></div>
          <div class="s1-shot"><img src="{FEED}" alt="Blurry laptop webcam image" /><i class="s1-noise" id="s1-noise"></i></div>
        </div>
        <span class="s1-tag" id="s1-t0" style="left: 300px; top: 300px;">grainy</span>
        <span class="s1-tag" id="s1-t1" style="left: 1460px; top: 420px;">washed out</span>
        <span class="s1-tag" id="s1-t2" style="left: 380px; top: 760px;">stuck at 720p</span>
      </div></div>""", 1)
CSS += f"""
    .s1-win {{ position: absolute; left: 520px; top: 230px; width: 880px; height: 560px; border-radius: 20px; background: {K2}; box-shadow: 0 0 0 1px {LINE}, 0 40px 100px rgba(0,0,0,0.6); overflow: hidden; }}
    .s1-bar {{ position: absolute; left: 0; right: 0; top: 0; height: 44px; display: flex; align-items: center; gap: 9px; padding: 0 18px; font: 500 16px "Inter", sans-serif; color: {DIM}; }}
    .s1-bar i {{ width: 12px; height: 12px; border-radius: 50%; background: #3a3934; display: block; }}
    .s1-bar span {{ margin-left: 12px; flex: 1; }}
    .s1-bar b {{ font-weight: 700; color: {DIM}; }}
    .s1-shot {{ position: absolute; left: 0; right: 0; top: 44px; bottom: 0; overflow: hidden; }}
    .s1-shot img {{ position: absolute; left: 0; top: 0; width: 220px; height: 129px; transform: scale(4); transform-origin: 0 0; image-rendering: pixelated; filter: grayscale(0.6) contrast(0.7) brightness(0.62) blur(0.6px); }}
    .s1-noise {{ position: absolute; inset: -40px; display: block; opacity: 0.35; background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='1.4' numOctaves='1' seed='3'/></filter><rect width='200' height='200' filter='url(%23n)'/></svg>"); }}
    .s1-tag {{ position: absolute; font: 600 30px "Inter", sans-serif; color: {DIM}; padding: 10px 20px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {LINE}; white-space: nowrap; opacity: 0; }}
"""
js(f"""
      // S1 — slow push on a sad webcam, annotations on the words, yanked "back"
      tl.fromTo("#w1", {{ z: -200, rotationX: 6, rotationY: -6 }}, {{ z: 120, rotationX: 2, rotationY: 3, duration: {W['1:back']}, ease: "sine.inOut" }}, 0);
      tl.fromTo("#s1-win", {{ opacity: 0, y: 30 }}, {{ opacity: 1, y: 0, duration: 0.6, ease: "power2.out" }}, 0);
      tl.fromTo("#s1-noise", {{ x: 0, y: 0 }}, {{ keyframes: {{ x: [0, -18, 12, -6, 20, 0], y: [0, 10, -14, 8, -4, 0] }}, duration: 0.3, ease: "none", repeat: 9 }}, 0);
      [{W['1:laptop']}, {W['1:webcam']}, {W['1:holding']}].forEach((t, i) => tl.fromTo("#s1-t" + i, {{ opacity: 0, scale: 0.7, z: 200 }}, {{ opacity: 1, scale: 1, z: 0, duration: 0.35, ease: "back.out(2)" }}, t));
      tl.to("#w1", {{ z: -1400, rotationX: 28, duration: 0.75, ease: "power3.in" }}, {W['1:back']});
      tl.to("#s1", {{ opacity: 0, duration: 0.45, ease: "power2.in" }}, {W['1:back'] + 0.35});
""")

# ---------------------------------------------------------------- S2 the phone: back, lenses, dive into the lens (2.6–6.15)
LENS = (810 + 320 - 26 - 16 - 28, 230 + 26 + 16 + 28)  # back-facing: module mirrored to the right edge
scene("s2", 2.6, 6.15, f"""      <div class="zoomer" id="z2"><div class="glow" id="s2-glow"></div><div class="cam"><div class="world" id="w2">
        {phone("s2-ph", 810, 230, app_screen("s2"))}
      </div></div></div>
      <div class="s2-cap" id="s2-cap"><span class="mk"><span id="s2-c0">The best camera you own</span></span><span class="mk"><span id="s2-c1">is already <em class="o">in your pocket.</em></span></span></div>""", 2)
CSS += """
    .s2-cap { position: absolute; left: 0; right: 0; top: 905px; text-align: center; font: 600 44px "Inter", sans-serif; letter-spacing: -0.02em; }
    .s2-cap em { font-style: normal; }
    .mk { display: block; overflow: hidden; padding-bottom: 0.08em; }
    .mk > span { display: block; }
"""
LX, LY = 810 + 26 + 16 + 28, 230 + 26 + 16 + 28  # main lens centre on screen when the phone shows its back flat
js(f"""
      // S2 — phone rises showing its camera, glint across the lenses, then we dive INTO the lens
      tl.fromTo("#s2-ph", {{ y: 900, rotationX: 55, rotationY: 230, rotationZ: -10 }}, {{ y: 0, rotationX: 8, rotationY: 196, rotationZ: 0, duration: 1.3, ease: "expo.out" }}, 2.9);
      tl.to("#s2-ph", {{ rotationX: 0, rotationY: 180, duration: 1.15, ease: "power2.inOut" }}, 4.2);
      tl.fromTo("#s2-glow", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 1.4, ease: "expo.out" }}, 2.95);
      tl.fromTo("#s2-ph-glint", {{ x: 0 }}, {{ x: 360, duration: 0.6, ease: "power2.inOut" }}, {W['2:camera']});
      tl.fromTo("#s2-c0", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.7, ease: "power4.out" }}, {W['2:the']});
      tl.fromTo("#s2-c1", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.7, ease: "power4.out" }}, {W['2:is']});
      tl.to("#s2-cap", {{ opacity: 0, y: 30, duration: 0.3, ease: "power2.in" }}, 5.35);
      tl.fromTo("#z2", {{ x: 0, y: 0, scale: 1 }}, {{ x: {960 - 22 * LX}, y: {540 - 22 * LY}, scale: 22, duration: 0.7, ease: "power4.in" }}, 5.38);
""")

# ---------------------------------------------------------------- S3 DROP: Meet OpenStream (5.95–7.95)
shards = "".join(f'<i class="shard" id="sh{i}"></i>' for i in range(14))
scene("s3", 6.0, 7.95, f"""      <div class="zoomer" id="z3"><div class="cam"><div class="world" id="w3">
        <i class="s3-ring" id="s3-ring"></i><i class="s3-ring r2" id="s3-ring2"></i>{shards}
        <div class="s3-mark" id="s3-mark">{logo("s3-logo", C, "s3-o", "s3-s", 5.2)}</div>
        <div class="s3-word disp" id="s3-word">{"".join(f'<span class="s3-ch">{ch}</span>' for ch in "OpenStream")}</div>
      </div></div></div>
      <div class="flash" id="s3-flash"></div>""", 3)
CSS += f"""
    #s3 {{ background: {K}; }}
    .s3-mark {{ position: absolute; left: 820px; top: 230px; width: 280px; height: 212px; }}
    .s3-logo {{ width: 100%; height: 100%; display: block; overflow: visible; }}
    .s3-logo path {{ stroke-dasharray: 1 2; }}
    .s3-word {{ position: absolute; left: 0; right: 0; top: 520px; text-align: center; font-size: 170px; display: flex; justify-content: center; transform-style: preserve-3d; }}
    .s3-ch {{ display: block; will-change: transform; backface-visibility: hidden; }}
    .s3-ring {{ position: absolute; left: 960px; top: 340px; width: 300px; height: 300px; margin: -150px 0 0 -150px; border-radius: 50%; border: 6px solid {O}; opacity: 0; display: block; }}
    .s3-ring.r2 {{ border-width: 2px; border-color: {C}; }}
    .shard {{ position: absolute; left: 958px; top: 338px; width: 4px; height: 40px; border-radius: 2px; background: {O}; opacity: 0; display: block; }}
"""
js(f"""
      // S3 — the drop: flash out of the lens, mark draws with a shock ring, letters slam from depth
      tl.fromTo("#s3-flash", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.45, ease: "power2.out" }}, 6.0);
      tl.fromTo("#s3-o", {{ strokeDashoffset: 1 }}, {{ strokeDashoffset: 0, duration: 0.5, ease: "power3.out" }}, 6.0);
      tl.fromTo("#s3-s", {{ strokeDashoffset: 1 }}, {{ strokeDashoffset: 0, duration: 0.5, ease: "power3.out" }}, 6.12);
      tl.fromTo("#s3-mark", {{ scale: 1.8, z: 300 }}, {{ scale: 1, z: 0, duration: 0.6, ease: "expo.out" }}, 6.0);
      tl.fromTo("#s3-ring", {{ opacity: 1, scale: 0.3 }}, {{ opacity: 0, scale: 4.2, duration: 0.9, ease: "expo.out" }}, 6.0);
      tl.fromTo("#s3-ring2", {{ opacity: 0.8, scale: 0.3 }}, {{ opacity: 0, scale: 6, duration: 1.2, ease: "expo.out" }}, 6.05);
      for (let i = 0; i < 14; i++) {{
        const a = i / 14 * Math.PI * 2 + 0.2, r = 340 + (i % 3) * 120;
        tl.fromTo("#sh" + i, {{ opacity: 1, x: 0, y: 0, rotation: a * 180 / Math.PI + 90, scaleY: 1.6 }}, {{ opacity: 0, x: Math.cos(a) * r, y: Math.sin(a) * r, scaleY: 0.3, duration: 0.8, ease: "expo.out" }}, 6.02);
      }}
      tl.fromTo("#s3-word .s3-ch", {{ opacity: 0, z: 700, y: 40, rotationX: -50 }}, {{ opacity: 1, z: 0, y: 0, rotationX: 0, duration: 0.55, ease: "expo.out", stagger: 0.035 }}, {W['3:openstream'] - 0.08});
      tl.fromTo("#w3", {{ rotationY: -10, z: -60 }}, {{ rotationY: 8, z: 80, duration: 1.95, ease: "sine.inOut" }}, 6.0);
      [6.5, 7.0, 7.5].forEach(t => tl.fromTo("#z3", {{ scale: 1.03 }}, {{ scale: 1, duration: 0.25, ease: "power2.out", immediateRender: false }}, t));
      tl.to("#s3", {{ scale: 2.6, opacity: 0, filter: "blur(14px)", duration: 0.45, ease: "power3.in" }}, 7.5);
""")

# ---------------------------------------------------------------- S4+S5 demo: phone + OBS, cursor workflow, dive into preview (7.5–16.35)
PH = dict(x=200, y=210)      # phone top-left in world
OW = dict(x=640, y=190)      # obs window top-left
BEAM = f"M {PH['x'] + 320} {PH['y'] + 300} C 560 220, 640 180, {OW['x'] + 120} 330"
scene("s5", 7.5, 16.35, f"""      <div class="zoomer" id="z5"><div class="cam"><div class="world" id="w5">
        <svg class="beam" viewBox="0 0 1920 1080" aria-hidden="true"><path id="s5-beam" d="{BEAM}" pathLength="1"/></svg>
        <i class="dot" id="s5-d0"></i><i class="dot" id="s5-d1"></i><i class="dot" id="s5-d2"></i>
        {phone("s5-ph", PH['x'], PH['y'], app_screen("s5"))}
        <div class="s5-win" id="s5-win">{obs("o5", menu=True, modal=True)}{cursor("c5")}</div>
      </div></div></div>
      <div class="s5-tag" id="s5-tag"><span class="mk"><span id="s5-t0">Android</span></span><i id="s5-arrow">→</i><span class="mk"><span id="s5-t1" class="o">OBS camera</span></span></div>""", 4)
CSS += f"""
    .s5-win {{ position: absolute; left: {OW['x']}px; top: {OW['y']}px; width: 1180px; height: 700px; }}
    .s5-win .obs {{ left: 0; top: 0; }}
    .beam {{ position: absolute; inset: 0; width: 1920px; height: 1080px; overflow: visible; }}
    .beam path {{ fill: none; stroke: {O}; stroke-width: 4; stroke-linecap: round; stroke-dasharray: 1 1; stroke-dashoffset: 1; filter: drop-shadow(0 0 10px {O}); }}
    .dot {{ position: absolute; left: {PH['x'] + 310}px; top: {PH['y'] + 290}px; width: 20px; height: 20px; border-radius: 50%; background: {O}; box-shadow: 0 0 24px 6px rgba(255,91,20,0.7); opacity: 0; display: block; }}
    .s5-tag {{ position: absolute; left: 0; right: 0; top: 74px; display: flex; justify-content: center; align-items: center; gap: 26px; font: 800 64px "Unbounded", sans-serif; letter-spacing: -0.05em; text-transform: uppercase; }}
    .s5-tag i {{ font-style: normal; color: {DIM}; opacity: 0; }}
"""
P_PHONE = zpose(PH['x'] + 160, PH['y'] + 330, 1.55)
P_ADD = zpose(OW['x'] + 150, OW['y'] + 520, 1.7)
P_MODAL = zpose(OW['x'] + 730, OW['y'] + 290, 1.45)
P_WIDE = dict(x=0, y=0, scale=1)
PREV = (OW['x'] + 272 + 446, OW['y'] + 60 + 251)
P_DIVE = zpose(PREV[0], PREV[1], 1920 / 892)
t_open, t_add, t_pick, t_live = W['5:open'], W['5:add'], W['5:pick'], W['5:live']


def zp(sel, p, t, d, ease="power3.inOut"):
    return f'      tl.to("{sel}", {{ x: {p["x"]}, y: {p["y"]}, scale: {p["scale"]}, duration: {d}, ease: "{ease}" }}, {t:.3f});\n'


def cur_to(c, x, y, t, d=0.45, ease="power3.inOut"):
    return f'      tl.to("#{c}", {{ x: {x}, y: {y}, duration: {d}, ease: "{ease}" }}, {t:.3f});\n'


def click(c, t):
    return (f'      tl.fromTo("#{c} svg", {{ scale: 1 }}, {{ scale: 0.8, duration: 0.07, ease: "power2.out", yoyo: true, repeat: 1, immediateRender: false }}, {t:.3f});\n'
            f'      tl.fromTo("#{c}-ring", {{ scale: 0.2, opacity: 1 }}, {{ scale: 1.7, opacity: 0, duration: 0.32, ease: "expo.out", immediateRender: false }}, {t:.3f});\n')


j = f"""
      // S4 — phone flies in, OBS window rockets from depth and lands on "OBS", beam connects them
      tl.fromTo("#s5", {{ scale: 0.55, opacity: 0, filter: "blur(16px)" }}, {{ scale: 1, opacity: 1, filter: "blur(0px)", duration: 0.55, ease: "expo.out" }}, 7.5);
      tl.fromTo("#w5", {{ rotationY: 14, rotationX: 6 }}, {{ rotationY: -5, rotationX: 2, duration: 3.2, ease: "power2.inOut" }}, 7.5);
      tl.fromTo("#s5-ph", {{ x: -700, z: -900, rotationY: 70, rotationX: 10 }}, {{ x: 0, z: 0, rotationY: 16, rotationX: 0, duration: 0.9, ease: "expo.out" }}, {W['4:android'] - 0.35});
      tl.fromTo("#s5-win", {{ x: 900, z: -2600, rotationY: -50, opacity: 0 }}, {{ x: 0, z: 0, rotationY: -7, opacity: 1, duration: 0.7, ease: "expo.out" }}, {W['4:obs'] - 0.45});
      tl.fromTo("#s5-t0", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.5, ease: "power4.out" }}, {W['4:android']});
      tl.fromTo("#s5-arrow", {{ opacity: 0, x: -30 }}, {{ opacity: 1, x: 0, duration: 0.35, ease: "power3.out" }}, {W['4:now']});
      tl.fromTo("#s5-t1", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.5, ease: "power4.out" }}, {W['4:obs']});
      tl.to("#s5-beam", {{ strokeDashoffset: 0, duration: 0.45, ease: "power3.out" }}, {W['4:camera']});
      tl.to("#s5-tag", {{ opacity: 0, y: -40, duration: 0.35, ease: "power2.in" }}, {t_open - 0.55:.3f});
      tl.set("#c5", {{ x: 760, y: 820 }}, 7.5);
      // "Open the app" — push to the phone, tap Start
{zp("#z5", P_PHONE, t_open - 0.45, 0.6)}      tl.fromTo("#s5-tap", {{ opacity: 0.9, scale: 0.3 }}, {{ opacity: 0, scale: 1.6, duration: 0.45, ease: "expo.out" }}, {W['5:app']});
      tl.to("#s5-go", {{ scale: 0.9, duration: 0.08, yoyo: true, repeat: 1 }}, {W['5:app']});
      tl.to("#s5-pill .st0", {{ opacity: 0, duration: 0.15 }}, {W['5:app'] + 0.1});
      tl.fromTo("#s5-st1", {{ opacity: 0, y: 10 }}, {{ opacity: 1, y: 0, duration: 0.25 }}, {W['5:app'] + 0.15});
      // "Add the source" — whip to OBS sources, + menu, pick OpenStream
      tl.fromTo("#s5", {{ filter: "blur(0px)" }}, {{ keyframes: [{{ filter: "blur(10px)", duration: 0.15 }}, {{ filter: "blur(0px)", duration: 0.25 }}], ease: "none", immediateRender: false }}, {t_add - 0.42:.3f});
{zp("#z5", P_ADD, t_add - 0.42, 0.42, "power4.inOut")}{cur_to("c5", 36, 650, t_add - 0.4, 0.4)}{click("c5", t_add + 0.02)}      tl.fromTo("#o5-menu", {{ opacity: 0, scale: 0.85, y: 14 }}, {{ opacity: 1, scale: 1, y: 0, duration: 0.25, ease: "back.out(2)" }}, {t_add + 0.06:.3f});
{cur_to("c5", 120, 560, t_add + 0.12, 0.3)}      tl.to("#o5-mi", {{ backgroundColor: "rgba(255,91,20,0.2)", duration: 0.1 }}, {t_add + 0.38:.3f});
{click("c5", W['5:source'] + 0.08)}      tl.to("#o5-menu", {{ opacity: 0, scale: 0.95, duration: 0.15 }}, {W['5:source'] + 0.15:.3f});
      tl.fromTo("#o5-src", {{ opacity: 0, x: -24 }}, {{ opacity: 1, x: 0, duration: 0.3, ease: "back.out(2)" }}, {W['5:source'] + 0.15:.3f});
      // "Pick your phone" — modal springs, click the device
{zp("#z5", P_MODAL, t_pick - 0.3, 0.45)}      tl.fromTo("#o5-modal", {{ opacity: 0, scale: 0.88, y: 30 }}, {{ opacity: 1, scale: 1, y: 0, duration: 0.4, ease: "back.out(1.7)" }}, {t_pick - 0.25:.3f});
{cur_to("c5", 900, 290, t_pick - 0.1, 0.45)}{click("c5", W['5:phone'] + 0.15)}      tl.to("#o5-dev", {{ boxShadow: "inset 0 0 0 2.5px #ff5b14", backgroundColor: "rgba(255,91,20,0.1)", duration: 0.15 }}, {W['5:phone'] + 0.15:.3f});
      tl.fromTo("#o5-chk", {{ opacity: 0, scale: 0.3 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "back.out(3)" }}, {W['5:phone'] + 0.2:.3f});
      tl.to("#o5-modal", {{ opacity: 0, scale: 0.94, duration: 0.25, ease: "power2.in" }}, {W['5:phone'] + 0.5:.3f});
      // "You're live in seconds" — pull wide, beam fires, preview wipes in, LIVE stamps
{zp("#z5", P_WIDE, t_live - 0.55, 0.55)}{cur_to("c5", 1300, 900, t_live - 0.5, 0.6, "power2.in")}      tl.to("#s5-beam", {{ opacity: 1, duration: 0.01 }}, {t_live - 0.3:.3f});
"""
for i in range(3):
    j += (f'      tl.fromTo("#s5-d{i}", {{ opacity: 0, x: 0, y: 0 }}, {{ keyframes: [{{ opacity: 1, x: 90, y: -150, duration: 0.14 }}, {{ x: 190, y: -150, duration: 0.12 }}, {{ opacity: 0, x: 300, y: -90, duration: 0.14 }}], ease: "none" }}, {t_live - 0.35 + i * 0.12:.3f});\n')
j += f"""      tl.to("#o5-fw", {{ clipPath: "inset(0 0% 0 0)", duration: 0.45, ease: "power4.inOut" }}, {t_live - 0.05:.3f});
      tl.to("#o5-empty", {{ opacity: 0, duration: 0.15 }}, {t_live:.3f});
      tl.fromTo("#o5-feed-z", {{ scale: 1.25 }}, {{ scale: 1, duration: 1.2, ease: "expo.out" }}, {t_live - 0.05:.3f});
      tl.fromTo("#o5-live", {{ opacity: 0, scale: 2.2 }}, {{ opacity: 1, scale: 1, duration: 0.35, ease: "back.out(2.5)" }}, {t_live + 0.25:.3f});
      tl.to("#s5-st1", {{ opacity: 0, duration: 0.1 }}, {t_live + 0.25:.3f});
      tl.fromTo("#s5-st2", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.2 }}, {t_live + 0.25:.3f});
      tl.to("#w5", {{ rotationY: 0, rotationX: 0, duration: 1.0, ease: "power2.inOut" }}, 14.9);
      // dive into the preview — lands exactly on the full-bleed feed of S6
{zp("#z5", P_DIVE, 15.55, 0.8, "power4.inOut")}"""
js(j)

# ---------------------------------------------------------------- S6 hero feed: every lens, 1080p60, studio audio (16.3–21.75)
bars = "".join(f'<i class="wv" id="wv{i}"></i>' for i in range(40))
scene("s6", 16.3, 21.75, f"""      <div class="zoomer" id="z6">{feed("s6-feed")}</div>
      <div class="s6-shade" id="s6-shade"></div>
      <div class="s6-brk" id="s6-brk"><i></i><i></i><i></i><i></i></div>
      <div class="s6-lens" id="s6-lens">{"".join(f'<span id="s6-l{i}">{l}</span>' for i, l in enumerate(["0.6×", "1×", "3×", "5×"]))}</div>
      <div class="s6-spec">
        <div class="disp s6-num"><span id="s6-n">0</span><em>p</em></div>
        <div class="disp s6-fps" id="s6-fps"><span class="o">60</span> fps</div>
      </div>
      <div class="s6-aud" id="s6-aud"><div class="s6-wave">{bars}</div><span>Studio-clean audio · AAC 48 kHz</span></div>
      <div class="s6-hud" id="s6-hud"><b></b>REC · ULTRA HQ</div>""", 5)
CSS += f"""
    #s6 .feed {{ position: absolute; inset: 0; }}
    .s6-shade {{ position: absolute; inset: 0; background: linear-gradient(90deg, rgba(11,11,10,0.88) 0%, rgba(11,11,10,0.55) 34%, rgba(11,11,10,0) 60%); opacity: 0; }}
    .s6-brk {{ position: absolute; left: 870px; top: 120px; width: 360px; height: 420px; opacity: 0; }}
    .s6-brk i {{ position: absolute; width: 56px; height: 56px; border: 0 solid {O}; display: block; }}
    .s6-brk i:nth-child(1) {{ left: 0; top: 0; border-left-width: 5px; border-top-width: 5px; }}
    .s6-brk i:nth-child(2) {{ right: 0; top: 0; border-right-width: 5px; border-top-width: 5px; }}
    .s6-brk i:nth-child(3) {{ left: 0; bottom: 0; border-left-width: 5px; border-bottom-width: 5px; }}
    .s6-brk i:nth-child(4) {{ right: 0; bottom: 0; border-right-width: 5px; border-bottom-width: 5px; }}
    .s6-lens {{ position: absolute; left: 0; right: 0; bottom: 70px; display: flex; justify-content: center; gap: 18px; }}
    .s6-lens span {{ width: 104px; height: 104px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font: 700 30px "Inter", sans-serif; background: rgba(11,11,10,0.55); box-shadow: inset 0 0 0 1.5px rgba(242,237,228,0.35); opacity: 0; }}
    .s6-spec {{ position: absolute; left: 110px; top: 300px; }}
    .s6-num {{ font-size: 230px; height: 220px; display: flex; align-items: baseline; opacity: 0; }}
    .s6-num em {{ font-style: normal; font-size: 140px; color: {O}; }}
    .s6-fps {{ font-size: 96px; margin-top: 60px; opacity: 0; }}
    .s6-aud {{ position: absolute; left: 110px; top: 760px; display: flex; flex-direction: column; gap: 22px; opacity: 0; }}
    .s6-aud span {{ font: 600 30px "Inter", sans-serif; }}
    .s6-wave {{ display: flex; align-items: center; gap: 7px; height: 110px; }}
    .wv {{ width: 9px; height: 110px; border-radius: 5px; background: {O}; display: block; transform: scaleY(0.1); }}
    .s6-hud {{ position: absolute; right: 70px; top: 60px; display: flex; align-items: center; gap: 12px; height: 50px; padding: 0 22px; border-radius: 99px; background: rgba(11,11,10,0.6); font: 700 20px "Inter", sans-serif; letter-spacing: 0.1em; opacity: 0; }}
    .s6-hud b {{ width: 12px; height: 12px; border-radius: 50%; background: {O}; display: block; }}
"""
zooms = [(1.0, W['6:every']), (1.22, W['6:lens']), (1.6, W['6:lens'] + 0.25), (1.0, W['6:lens'] + 0.5)]
active = [0, 1, 2, 1]
j = f"""
      // S6 — the hero: ultra-crisp feed, lens punches, 1080p count-up, 60 fps slam, audio wave
      tl.fromTo("#z6", {{ scale: 1.06 }}, {{ scale: 1, duration: 5.4, ease: "sine.out" }}, 16.3);
      tl.fromTo("#s6-hud", {{ opacity: 0, y: -20 }}, {{ opacity: 1, y: 0, duration: 0.4, ease: "power3.out" }}, 16.4);
      tl.fromTo("#s6-lens span", {{ opacity: 0, y: 60, scale: 0.6 }}, {{ opacity: 1, y: 0, scale: 1, duration: 0.45, ease: "back.out(2)", stagger: 0.06 }}, {W['6:every'] - 0.1:.3f});
"""
for k, ((z, t), a) in enumerate(zip(zooms, active)):
    j += f'      tl.to("#s6-feed-z", {{ scale: {z}, duration: 0.22, ease: "power4.out" }}, {t:.3f});\n'
    j += f'      tl.to("#s6-l{a}", {{ backgroundColor: "#ff5b14", color: "#0b0b0a", scale: 1.12, duration: 0.12 }}, {t:.3f});\n'
    if k:
        prev = active[k - 1]
        if prev != a:
            j += f'      tl.to("#s6-l{prev}", {{ backgroundColor: "rgba(11,11,10,0.55)", color: "#f2ede4", scale: 1, duration: 0.12 }}, {t:.3f});\n'
j += f"""      tl.to("#s6-lens", {{ opacity: 0, y: 40, duration: 0.3, ease: "power2.in" }}, {W['6:crisp'] - 0.2:.3f});
      tl.fromTo("#s6-feed", {{ filter: "blur(0px)" }}, {{ keyframes: [{{ filter: "blur(7px)", duration: 0.12 }}, {{ filter: "blur(0px)", duration: 0.3 }}], ease: "none" }}, {W['6:crisp'] - 0.1:.3f});
      tl.fromTo("#s6-brk", {{ opacity: 0, scale: 1.5 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "expo.out" }}, {W['6:crisp']:.3f});
      tl.to("#s6-brk", {{ opacity: 0, duration: 0.3 }}, {W['6:crisp'] + 0.9:.3f});
      tl.to("#s6-feed-z", {{ x: 260, duration: 0.7, ease: "power3.inOut" }}, {W['6:crisp'] + 0.2:.3f});
      tl.fromTo("#s6-shade", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5 }}, {W['6:crisp'] + 0.25:.3f});
      tl.fromTo(".s6-num", {{ opacity: 0, scale: 1.5, filter: "blur(14px)" }}, {{ opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.45, ease: "power4.out" }}, {W['6:ten-eighty'] - 0.15:.3f});
      const n6 = {{ v: 0 }};
      tl.fromTo(n6, {{ v: 0 }}, {{ v: 1080, duration: 0.6, ease: "power3.out", onUpdate: () => {{ document.getElementById("s6-n").textContent = Math.round(n6.v); }} }}, {W['6:ten-eighty'] - 0.15:.3f});
      tl.fromTo("#s6-fps", {{ opacity: 0, x: -120 }}, {{ opacity: 1, x: 0, duration: 0.4, ease: "expo.out" }}, {W['6:sixty']:.3f});
      tl.fromTo("#s6-aud", {{ opacity: 0, y: 30 }}, {{ opacity: 1, y: 0, duration: 0.4, ease: "power3.out" }}, {W['6:studio-clean'] - 0.15:.3f});
      for (let i = 0; i < 40; i++) {{
        const k = [0.2, 0.9, 0.5, 1.0, 0.35, 0.75, 0.15].map((v, j) => 0.12 + Math.abs(Math.sin(i * 0.7 + j * 1.3)) * v);
        tl.fromTo("#wv" + i, {{ scaleY: 0.1 }}, {{ keyframes: {{ scaleY: k }}, duration: 1.6, ease: "none" }}, {W['6:studio-clean'] - 0.1:.3f} + i * 0.008);
      }}
"""
js(j)

# ---------------------------------------------------------------- S7 No cables / accounts / cloud → your Wi-Fi (21.45–26.15)
nos = [("Cables", W['7:cables']), ("Accounts", W['7:accounts']), ("Cloud", W['7:cloud'])]
scene("s7", 21.45, 26.15, f"""      <div class="zoomer" id="z7"><div class="glow" id="s7-glow"></div><div class="cam"><div class="world" id="w7">
        {"".join(f'<div class="s7-no disp" id="s7-n{i}"><span class="s7-x">No</span> <span class="s7-nn">{w}<i id="s7-s{i}"></i></span></div>' for i, (w, _) in enumerate(nos))}
        <div class="s7-wifi" id="s7-wifi"><svg viewBox="0 0 200 160" aria-hidden="true"><g fill="none" stroke="{O}" stroke-width="14" stroke-linecap="round">
          <path id="s7-a2" pathLength="1" d="M 20 62 A 112 112 0 0 1 180 62"/><path id="s7-a1" pathLength="1" d="M 50 94 A 70 70 0 0 1 150 94"/><path id="s7-a0" pathLength="1" d="M 78 124 A 30 30 0 0 1 122 124"/></g>
          <circle id="s7-dot" cx="100" cy="146" r="11" fill="{O}"/></svg>
          <i class="s7-rip" id="s7-r0"></i><i class="s7-rip" id="s7-r1"></i><i class="s7-rip" id="s7-r2"></i></div>
        <div class="s7-just disp" id="s7-just"><span class="mk"><span id="s7-j0">Just your</span></span><span class="mk"><span id="s7-j1" class="o">own Wi-Fi.</span></span></div>
      </div></div></div>""", 6)
CSS += f"""
    #s7 {{ background: {K}; }}
    .s7-no {{ position: absolute; left: 0; right: 0; top: 440px; text-align: center; font-size: 170px; opacity: 0; will-change: transform; backface-visibility: hidden; }}
    .s7-x {{ color: {DIM}; }}
    .s7-nn {{ position: relative; display: inline-block; }}
    .s7-no i {{ position: absolute; left: -16px; right: -16px; top: 46%; height: 14px; margin-top: -7px; background: {O}; display: block; transform-origin: 0% 50%; box-shadow: 0 0 30px rgba(255,91,20,0.6); }}
    .s7-wifi {{ position: absolute; left: 760px; top: 170px; width: 400px; height: 320px; opacity: 0; }}
    .s7-wifi svg {{ width: 100%; height: 100%; display: block; overflow: visible; }}
    .s7-wifi path {{ stroke-dasharray: 1 1; stroke-dashoffset: 1; }}
    .s7-rip {{ position: absolute; left: 200px; top: 290px; width: 200px; height: 200px; margin: -100px 0 0 -100px; border-radius: 50%; border: 3px solid {O}; opacity: 0; display: block; }}
    .s7-just {{ position: absolute; left: 0; right: 0; top: 560px; text-align: center; font-size: 130px; }}
"""
j = """
      // S7 — three "No"s slam and get struck, each receding into a depth stack; then the Wi-Fi mark draws and ripples
      tl.fromTo("#s7", { clipPath: "inset(0 0 0 100%)" }, { clipPath: "inset(0 0 0 0%)", duration: 0.4, ease: "power4.inOut" }, 21.45);
      tl.fromTo("#w7", { rotationX: 8, rotationY: -10 }, { rotationX: -4, rotationY: 10, duration: 4.7, ease: "sine.inOut" }, 21.45);
"""
for i, (w, t) in enumerate(nos):
    t0 = t - 0.3
    j += f'      tl.fromTo("#s7-n{i}", {{ opacity: 0, scale: 1.9, z: 300 }}, {{ opacity: 1, scale: 1, z: 0, duration: 0.32, ease: "power4.out" }}, {t0:.3f});\n'
    j += f'      tl.fromTo("#s7-s{i}", {{ scaleX: 0, rotation: -4 }}, {{ scaleX: 1, rotation: -4, duration: 0.2, ease: "power3.out" }}, {t + 0.12:.3f});\n'
    j += f'      tl.fromTo("#z7", {{ x: 0 }}, {{ keyframes: {{ x: [0, -16, 12, -6, 0] }}, duration: 0.22, ease: "none", immediateRender: false }}, {t + 0.14:.3f});\n'
    for k in range(i):
        depth = i - k
        j += f'      tl.to("#s7-n{k}", {{ z: {-600 * depth}, y: {-250 * depth}, opacity: {0.22 / depth:.2f}, duration: 0.4, ease: "power3.out" }}, {t0:.3f});\n'
tw = W['7:just']
j += f"""      tl.to(".s7-no", {{ z: 1600, opacity: 0, duration: 0.45, ease: "power3.in", stagger: 0.04 }}, {tw - 0.35:.3f});
      tl.fromTo("#s7-glow", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.8 }}, {tw - 0.1:.3f});
      tl.fromTo("#s7-wifi", {{ opacity: 0, scale: 0.6 }}, {{ opacity: 1, scale: 1, duration: 0.5, ease: "back.out(1.8)" }}, {tw - 0.1:.3f});
      tl.fromTo("#s7-dot", {{ scale: 0, transformOrigin: "50% 50%" }}, {{ scale: 1, duration: 0.3, ease: "back.out(3)" }}, {tw - 0.05:.3f});
      ["#s7-a0", "#s7-a1", "#s7-a2"].forEach((s, i) => tl.to(s, {{ strokeDashoffset: 0, duration: 0.3, ease: "power3.out" }}, {tw + 0.05:.3f} + i * 0.12));
      [0, 1, 2].forEach(i => tl.fromTo("#s7-r" + i, {{ opacity: 0.8, scale: 0.4 }}, {{ opacity: 0, scale: 5, duration: 1.1, ease: "power2.out" }}, {W['7:wi-fi']:.3f} + i * 0.5));
      tl.fromTo("#s7-j0", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.5, ease: "power4.out" }}, {tw:.3f});
      tl.fromTo("#s7-j1", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.5, ease: "power4.out" }}, {W['7:own']:.3f});
"""
js(j)

# ---------------------------------------------------------------- S8 controls inside OBS (25.9–32.15)
FR = dict(x=40, y=70)               # window frame
CARD = dict(x=80, y=150, w=1120, h=630)
DOCK = dict(x=1240, y=150, w=600, h=630)
lens_xy = [(DOCK['x'] + 36 + 52 + i * 116, DOCK['y'] + 171) for i in range(4)]
focus_xy = (CARD['x'] + 580, CARD['y'] + 210)
knob0, knob1 = DOCK['x'] + 36 + 160, DOCK['x'] + 36 + 430
slider_y = DOCK['y'] + 451
scene("s8", 25.9, 32.15, f"""      <div class="zoomer" id="z8"><div class="cam"><div class="world" id="w8">
        <div class="s8-frame" id="s8-frame"><div class="obs-bar"><i></i><i></i><i></i><span>OBS Studio — Stream</span></div></div>
        <div class="s8-card" id="s8-card">{feed("s8-feed")}<div class="s8-focus" id="s8-focus"><i></i><i></i><i></i><i></i></div><span class="obs-live" style="opacity: 1;"><b></b>LIVE</span></div>
        <div class="s8-dock" id="s8-dock">
          <span class="s8-title">{logo("obs-mini", O)}OpenStream Camera</span>
          <span class="s8-lbl">Lens</span>
          <div class="s8-row">{"".join(f'<span class="s8-chip" id="s8-l{i}">{l}</span>' for i, l in enumerate(["0.6×", "1×", "3×", "5×"]))}</div>
          <span class="s8-lbl">Focus</span>
          <div class="s8-row"><span class="s8-chip wide on">Tap to focus</span><span class="s8-chip wide">Auto</span></div>
          <span class="s8-lbl">Exposure</span>
          <div class="s8-slider"><i class="s8-fill" id="s8-fill"></i><i class="s8-knob" id="s8-knob"></i></div>
          <span class="s8-sync" id="s8-sync"><b></b>Live on phone</span>
        </div>
        {cursor("c8")}
      </div></div></div>""", 7)
CSS += f"""
    .s8-frame {{ position: absolute; left: {FR['x']}px; top: {FR['y']}px; width: 1840px; height: 770px; border-radius: 26px; background: {K2}; box-shadow: 0 0 0 1px {LINE}, 0 60px 140px rgba(0,0,0,0.6); }}
    .s8-card {{ position: absolute; left: {CARD['x']}px; top: {CARD['y']}px; width: {CARD['w']}px; height: {CARD['h']}px; border-radius: 18px; overflow: hidden; }}
    .s8-card .feed-z {{ transform-origin: 52% 33%; }}
    .s8-focus {{ position: absolute; left: {focus_xy[0] - CARD['x'] - 90}px; top: {focus_xy[1] - CARD['y'] - 90}px; width: 180px; height: 180px; opacity: 0; }}
    .s8-focus i {{ position: absolute; width: 40px; height: 40px; border: 0 solid {O}; display: block; }}
    .s8-focus i:nth-child(1) {{ left: 0; top: 0; border-left-width: 4px; border-top-width: 4px; }}
    .s8-focus i:nth-child(2) {{ right: 0; top: 0; border-right-width: 4px; border-top-width: 4px; }}
    .s8-focus i:nth-child(3) {{ left: 0; bottom: 0; border-left-width: 4px; border-bottom-width: 4px; }}
    .s8-focus i:nth-child(4) {{ right: 0; bottom: 0; border-right-width: 4px; border-bottom-width: 4px; }}
    .s8-dock {{ position: absolute; left: {DOCK['x']}px; top: {DOCK['y']}px; width: {DOCK['w']}px; height: {DOCK['h']}px; border-radius: 18px; background: {K}; padding: 34px 36px; }}
    .s8-title {{ display: flex; align-items: center; gap: 12px; height: 32px; font: 700 22px "Unbounded", sans-serif; letter-spacing: -0.02em; white-space: nowrap; }}
    .s8-lbl {{ display: block; height: 20px; margin: 30px 0 14px; font: 600 15px "Inter", sans-serif; letter-spacing: 0.12em; text-transform: uppercase; color: {DIM}; }}
    .s8-row {{ display: flex; gap: 12px; height: 62px; }}
    .s8-chip {{ display: flex; align-items: center; justify-content: center; width: 104px; height: 62px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {LINE}; font: 600 22px "Inter", sans-serif; }}
    .s8-chip.wide {{ width: 200px; }} .s8-chip.on {{ box-shadow: inset 0 0 0 1.5px {C}; }}
    .s8-slider {{ position: relative; height: 62px; }}
    .s8-slider::before {{ content: ""; position: absolute; left: 0; right: 0; top: 29px; height: 4px; border-radius: 2px; background: {LINE}; }}
    .s8-fill {{ position: absolute; left: 0; top: 29px; width: 430px; height: 4px; border-radius: 2px; background: {O}; display: block; transform-origin: 0 50%; transform: scaleX(0.372); }}
    .s8-knob {{ position: absolute; left: 160px; top: 13px; width: 36px; height: 36px; margin-left: -18px; border-radius: 50%; background: {C}; display: block; }}
    .s8-sync {{ position: absolute; left: 36px; bottom: 34px; display: flex; align-items: center; gap: 12px; font: 600 20px "Inter", sans-serif; color: {DIM}; }}
    .s8-sync b {{ width: 12px; height: 12px; border-radius: 50%; background: {O}; display: block; box-shadow: 0 0 12px {O}; }}
"""
P_LENS = zpose(DOCK['x'] + 250, DOCK['y'] + 260, 1.5)
P_FACE = zpose(focus_xy[0], focus_xy[1] + 60, 1.5)
P_EXPO = zpose(DOCK['x'] + 300, DOCK['y'] + 420, 1.5)
P_OUT = dict(x=96, y=130, scale=0.9)
j = f"""
      // S8 — panel ↔ feed live sync: lens taps zoom the feed, tap-to-focus snaps, exposure drag brightens; then reveal it's all inside OBS
      tl.fromTo("#s8", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.25 }}, 25.9);
      tl.fromTo("#z8", {{ x: {P_LENS['x'] + 300}, y: {P_LENS['y']}, scale: {P_LENS['scale']} }}, {{ x: {P_LENS['x']}, y: {P_LENS['y']}, scale: {P_LENS['scale']}, duration: 0.6, ease: "power3.out" }}, 25.9);
      tl.fromTo("#w8", {{ rotationY: -8, rotationX: 4 }}, {{ rotationY: 4, rotationX: 0, duration: 6.2, ease: "sine.inOut" }}, 25.9);
      tl.set("#c8", {{ x: {lens_xy[0][0] - 40}, y: {lens_xy[0][1] + 160} }}, 25.9);
"""
lz = [(0, 1.0), (1, 1.3), (2, 1.85)]
for k, (i, z) in enumerate(lz):
    t = W['8:switch'] + 0.05 + k * 0.33
    j += cur_to("c8", lens_xy[i][0], lens_xy[i][1], t - 0.22, 0.22)
    j += click("c8", t)
    j += f'      tl.to("#s8-l{i}", {{ backgroundColor: "#ff5b14", color: "#0b0b0a", duration: 0.1 }}, {t:.3f});\n'
    if k:
        j += f'      tl.to("#s8-l{lz[k - 1][0]}", {{ backgroundColor: "rgba(0,0,0,0)", color: "#f2ede4", duration: 0.1 }}, {t:.3f});\n'
    j += f'      tl.to("#s8-feed-z", {{ scale: {z}, duration: 0.28, ease: "power4.out" }}, {t:.3f});\n'
tf = W['8:focus']
j += zp("#z8", P_FACE, tf - 0.55, 0.5)
j += f'      tl.to("#s8-feed", {{ filter: "blur(6px)", duration: 0.25 }}, {tf - 0.45:.3f});\n'
j += cur_to("c8", focus_xy[0], focus_xy[1], tf - 0.5, 0.45)
j += click("c8", tf + 0.05)
j += f'      tl.fromTo("#s8-focus", {{ opacity: 0, scale: 1.6 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "expo.out" }}, {tf + 0.05:.3f});\n'
j += f'      tl.to("#s8-feed", {{ filter: "blur(0px) brightness(0.72)", duration: 0.25, ease: "power2.out" }}, {tf + 0.08:.3f});\n'
j += f'      tl.to("#s8-focus", {{ opacity: 0, duration: 0.3 }}, {tf + 0.7:.3f});\n'
te = W['8:exposure']
j += zp("#z8", P_EXPO, te - 0.35, 0.4)
j += cur_to("c8", knob0, slider_y, te - 0.35, 0.35)
j += click("c8", te + 0.02)
j += f'      tl.to("#c8", {{ x: {knob1}, duration: 0.6, ease: "power2.inOut" }}, {te + 0.08:.3f});\n'
j += f'      tl.to("#s8-knob", {{ x: {knob1 - knob0}, duration: 0.6, ease: "power2.inOut" }}, {te + 0.08:.3f});\n'
j += f'      tl.to("#s8-fill", {{ scaleX: 1, duration: 0.6, ease: "power2.inOut" }}, {te + 0.08:.3f});\n'
j += f'      tl.to("#s8-feed", {{ filter: "blur(0px) brightness(1.08)", duration: 0.6, ease: "power2.inOut" }}, {te + 0.08:.3f});\n'
j += f'      tl.fromTo("#s8-sync", {{ color: "#8a857b" }}, {{ keyframes: [{{ color: "#ff5b14", duration: 0.15 }}, {{ color: "#f2ede4", duration: 0.4 }}], ease: "none" }}, {W["8:live"]:.3f});\n'
j += zp("#z8", P_OUT, W['8:right'] - 0.15, 0.9, "power3.inOut")
j += cur_to("c8", 1500, 960, W['8:right'], 0.7, "power2.in")
j += f'      tl.to("#s8", {{ scale: 2.4, opacity: 0, filter: "blur(14px)", duration: 0.4, ease: "power3.in" }}, 31.75);\n'
js(j)

# ---------------------------------------------------------------- S9 ULTRA HQ / ZERO FRICTION — extruded 3D type (31.85–36.2)
EX_SHADOW = ", ".join(f"0 {i * 2}px 0 rgb({max(48, 154 - i * 9)},{max(20, 58 - i * 3)},{8})" for i in range(1, 12))


def extr(tid, text, cls=""):
    return f'<div class="ex disp {cls}" id="{tid}">{text}</div>'


scene("s9", 31.85, 36.2, f"""      {feed("s9-bg")}<div class="s9-dim"></div>
      <div class="zoomer" id="z9"><div class="cam"><div class="world" id="w9">
        {extr("s9-a", "Ultra HQ", "big")}{extr("s9-b", "Webcam.", "mid")}
        {extr("s9-c", "Zero", "big")}{extr("s9-d", "Friction.", "mid o")}
      </div></div></div>""", 8)
CSS += f"""
    #s9-bg {{ filter: blur(22px) brightness(0.45) saturate(1.2); transform: scale(1.1); }}
    .s9-dim {{ position: absolute; inset: 0; background: radial-gradient(ellipse 70% 60% at 50% 50%, rgba(11,11,10,0.2), rgba(11,11,10,0.85)); }}
    .ex {{ position: absolute; left: 0; right: 0; text-align: center; opacity: 0; will-change: transform; backface-visibility: hidden; }}
    .ex {{ text-shadow: {EX_SHADOW}, 0 30px 50px rgba(0,0,0,0.6); }}
    .ex.o {{ color: {O}; }}
    .ex.big {{ top: 300px; font-size: 230px; }}
    .ex.mid {{ top: 560px; font-size: 150px; }}
"""
j = f"""
      // S9 — extruded type flies at the lens and settles; camera orbits to sell the depth; Zero/Friction replaces it on the words
      tl.fromTo("#s9", {{ opacity: 0, scale: 0.7 }}, {{ opacity: 1, scale: 1, duration: 0.45, ease: "expo.out" }}, 31.85);
      tl.fromTo("#w9", {{ rotationY: -24, rotationX: 10 }}, {{ rotationY: 20, rotationX: -6, duration: 4.35, ease: "sine.inOut" }}, 31.85);
      tl.fromTo("#s9-a", {{ opacity: 0, z: -2400 }}, {{ opacity: 1, z: 0, duration: 0.5, ease: "expo.out" }}, {W['9:ultra'] - 0.15:.3f});
      tl.fromTo("#s9-b", {{ opacity: 0, y: 120, rotationX: -70 }}, {{ opacity: 1, y: 0, rotationX: 0, duration: 0.45, ease: "back.out(1.6)" }}, {W['9:webcam'] - 0.1:.3f});
      tl.to(["#s9-a", "#s9-b"], {{ z: 1800, opacity: 0, duration: 0.4, ease: "power3.in" }}, {W['9:zero'] - 0.35:.3f});
      tl.fromTo("#s9-c", {{ opacity: 0, z: -2400 }}, {{ opacity: 1, z: 0, duration: 0.45, ease: "expo.out" }}, {W['9:zero'] - 0.1:.3f});
      tl.fromTo("#s9-d", {{ opacity: 0, x: 600, rotationY: -60 }}, {{ opacity: 1, x: 0, rotationY: 0, duration: 0.45, ease: "expo.out" }}, {W['9:friction'] - 0.1:.3f});
      [35.0, 35.5].forEach(t => tl.fromTo("#z9", {{ scale: 1.04 }}, {{ scale: 1, duration: 0.3, ease: "power2.out", immediateRender: false }}, t));
"""
js(j)

# ---------------------------------------------------------------- S10 GO LIVE climax — music only (35.95–40.45)
BTN = (OW['x'] + 272 + 892 - 20 - 130, OW['y'] + 576 + 26 + 28)
P_BTN = zpose(BTN[0], BTN[1], 2.6)
hearts = "".join(f'<i class="ht" id="ht{i}">{"♥" if i % 3 else "▲"}</i>' for i in range(12))
scene("s10", 35.95, 40.45, f"""      <div class="zoomer" id="z10"><div class="glow" id="s10-glow"></div><div class="cam"><div class="world" id="w10">
        {phone("s10-ph", 170, 220, app_screen("s10"))}
        <div class="s10-win" id="s10-win">{obs("o10")}{cursor("c10")}</div>
        <i class="s10-shock" id="s10-shock"></i>
      </div></div></div>
      <div class="s10-view" id="s10-view"><b></b><span id="s10-n">0</span> watching</div>
      <div class="s10-hearts">{hearts}</div>
      <div class="flash" id="s10-flash"></div>""", 9)
CSS += f"""
    .s10-win {{ position: absolute; left: {OW['x']}px; top: {OW['y']}px; width: 1180px; height: 700px; }}
    .s10-win .obs {{ left: 0; top: 0; }}
    #o10-fw {{ clip-path: inset(0 0 0 0); }}
    #o10-empty {{ opacity: 0; }}
    .s10-shock {{ position: absolute; left: {BTN[0]}px; top: {BTN[1]}px; width: 300px; height: 300px; margin: -150px 0 0 -150px; border-radius: 50%; border: 8px solid {O}; opacity: 0; display: block; }}
    .s10-view {{ position: absolute; left: 50%; top: 70px; display: flex; align-items: center; gap: 14px; height: 70px; margin-left: -190px; width: 380px; justify-content: center; border-radius: 99px; background: {O}; color: {K}; font: 800 30px "Inter", sans-serif; opacity: 0; }}
    .s10-view b {{ width: 14px; height: 14px; border-radius: 50%; background: {K}; display: block; }}
    .s10-hearts {{ position: absolute; inset: 0; }}
    .ht {{ position: absolute; bottom: 120px; font-style: normal; font-size: 46px; color: {O}; opacity: 0; display: block; }}
"""
j = f"""
      // S10 — macro on Start Streaming, click on the bar, shock ring, violent pull-back into an orbiting wide; viewers count up, reactions rise
      tl.fromTo("#z10", {{ x: {P_BTN['x']}, y: {P_BTN['y']}, scale: {P_BTN['scale']} }}, {{ x: {P_BTN['x'] + 40}, y: {P_BTN['y'] + 20}, scale: {P_BTN['scale'] * 1.04:.3f}, duration: 0.05, ease: "none" }}, 35.95);
      tl.fromTo("#s10", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.2 }}, 35.95);
      tl.set("#c10", {{ x: 1180, y: 760 }}, 35.95);
      tl.to("#c10", {{ x: {BTN[0] - OW['x'] + 10}, y: {BTN[1] - OW['y'] + 4}, duration: 0.45, ease: "power3.out" }}, 35.97);
{click("c10", 36.5)}      tl.to("#o10-stream em:first-child", {{ opacity: 0, duration: 0.1 }}, 36.5);
      tl.fromTo("#o10-on", {{ opacity: 0, scale: 1.3 }}, {{ opacity: 1, scale: 1, duration: 0.25, ease: "back.out(2)" }}, 36.5);
      tl.fromTo("#s10-shock", {{ opacity: 1, scale: 0.2 }}, {{ opacity: 0, scale: 7, duration: 0.8, ease: "expo.out" }}, 36.5);
      tl.fromTo("#s10-flash", {{ opacity: 0.55 }}, {{ opacity: 0, duration: 0.35, ease: "power2.out", immediateRender: false }}, 36.5);
      tl.to("#z10", {{ x: 96, y: 54, scale: 0.9, duration: 0.9, ease: "expo.inOut" }}, 36.6);
      tl.fromTo("#w10", {{ rotationY: 0, rotationX: 0 }}, {{ keyframes: [{{ rotationY: -16, rotationX: 8, duration: 1.6 }}, {{ rotationY: 14, rotationX: 4, duration: 2.0 }}], ease: "sine.inOut" }}, 36.7);
      tl.fromTo("#s10-glow", {{ opacity: 0 }}, {{ opacity: 1, duration: 1 }}, 36.6);
      tl.fromTo("#o10-live", {{ opacity: 0, scale: 2 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "back.out(2.5)" }}, 36.7);
      tl.fromTo("#s10-st2", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.2 }}, 36.7);
      tl.set("#s10-pill .st0", {{ opacity: 0 }}, 36.7);
      tl.fromTo("#s10-view", {{ opacity: 0, y: -40, scale: 0.7 }}, {{ opacity: 1, y: 0, scale: 1, duration: 0.4, ease: "back.out(2)" }}, 37.0);
      const v10 = {{ v: 0 }};
      tl.fromTo(v10, {{ v: 0 }}, {{ v: 12480, duration: 3.0, ease: "power2.in", onUpdate: () => {{ document.getElementById("s10-n").textContent = Math.round(v10.v).toLocaleString("en-US"); }} }}, 37.0);
      for (let i = 0; i < 12; i++) {{
        const t = 37.2 + i * 0.25, x = 1180 + ((i * 137) % 560);
        tl.fromTo("#ht" + i, {{ opacity: 0, x: x, y: 0, scale: 0.5 }}, {{ keyframes: [{{ opacity: 1, scale: 1.1, y: -120, duration: 0.3 }}, {{ opacity: 0, scale: 0.9, y: -520, x: x + ((i % 2) ? 60 : -60), duration: 1.1 }}], ease: "power1.out" }}, t);
      }}
      [37.0, 37.5, 38.0, 38.5, 39.0, 39.5].forEach(t => tl.fromTo("#o10-meters i", {{ scaleY: 1 }}, {{ scaleY: 0.45, duration: 0.4, ease: "power2.out", stagger: 0.03, immediateRender: false }}, t));
      tl.to("#s10", {{ scale: 1.8, opacity: 0, filter: "blur(16px)", duration: 0.45, ease: "power3.in" }}, 40.0);
"""
js(j)

# ---------------------------------------------------------------- S11 end card (40.2–46)
scene("s11", 40.2, TOTAL, f"""      <div class="zoomer" id="z11"><div class="glow" id="s11-glow"></div><div class="cam"><div class="world" id="w11">
        <div class="s11-mark" id="s11-mark">{logo("s11-logo", C, "s11-o", "s11-s", 4.8)}</div>
        <div class="s11-word disp"><span class="mk"><span id="s11-w">OpenStream</span></span></div>
        <div class="s11-sub" id="s11-sub">Free &amp; open source</div>
        <div class="s11-cta" id="s11-cta"><span class="s11-btn" id="s11-btn"><b></b>Go live</span><span class="s11-url">os.yash0.in</span></div>
        {cursor("c11")}
      </div></div></div>""", 10)
CSS += f"""
    .s11-mark {{ position: absolute; left: 850px; top: 150px; width: 220px; height: 166px; }}
    .s11-logo {{ width: 100%; height: 100%; display: block; overflow: visible; }}
    .s11-logo path {{ stroke-dasharray: 1 2; }}
    .s11-word {{ position: absolute; left: 0; right: 0; top: 380px; text-align: center; font-size: 160px; }}
    .s11-sub {{ position: absolute; left: 0; right: 0; top: 580px; text-align: center; font: 500 36px "Inter", sans-serif; color: {DIM}; opacity: 0; }}
    .s11-cta {{ position: absolute; left: 0; right: 0; top: 690px; display: flex; justify-content: center; align-items: center; gap: 34px; opacity: 0; }}
    .s11-btn {{ display: flex; align-items: center; gap: 14px; height: 84px; padding: 0 46px; border-radius: 99px; background: {O}; color: {K}; font: 800 30px "Inter", sans-serif; }}
    .s11-btn b {{ width: 12px; height: 12px; border-radius: 50%; background: {K}; display: block; }}
    .s11-url {{ font: 600 38px "Inter", sans-serif; letter-spacing: -0.01em; }}
"""
j = f"""
      // S11 — calm lockup on the last bars: mark draws, word rises, CTA lands on "Go live" and gets pressed
      tl.fromTo("#s11", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.3 }}, 40.2);
      tl.fromTo("#w11", {{ z: -200, rotationX: 8 }}, {{ z: 0, rotationX: 0, duration: 2.2, ease: "expo.out" }}, 40.2);
      tl.fromTo("#s11-glow", {{ opacity: 0 }}, {{ opacity: 0.8, duration: 1.2 }}, 40.3);
      tl.fromTo("#s11-o", {{ strokeDashoffset: 1 }}, {{ strokeDashoffset: 0, duration: 0.8, ease: "power3.inOut" }}, 40.35);
      tl.fromTo("#s11-s", {{ strokeDashoffset: 1 }}, {{ strokeDashoffset: 0, duration: 0.8, ease: "power3.inOut" }}, 40.55);
      tl.fromTo("#s11-w", {{ yPercent: 110 }}, {{ yPercent: 0, duration: 0.8, ease: "power4.out" }}, {W['10:openstream'] - 0.1:.3f});
      tl.fromTo("#s11-sub", {{ opacity: 0, y: 16 }}, {{ opacity: 1, y: 0, duration: 0.5, ease: "power2.out" }}, {W['10:free'] - 0.1:.3f});
      tl.fromTo("#s11-cta", {{ opacity: 0, y: 30, scale: 0.9 }}, {{ opacity: 1, y: 0, scale: 1, duration: 0.5, ease: "back.out(1.8)" }}, {W['10:go'] - 0.15:.3f});
      tl.set("#c11", {{ x: 1300, y: 1000, opacity: 0 }}, 40.2);
      tl.to("#c11", {{ x: 820, y: 745, opacity: 1, duration: 0.6, ease: "power3.out" }}, {W['10:go']:.3f});
{click("c11", W['10:live'] + 0.45)}      tl.fromTo("#s11-btn", {{ scale: 1 }}, {{ scale: 0.92, duration: 0.08, yoyo: true, repeat: 1, ease: "power2.out", immediateRender: false }}, {W['10:live'] + 0.45:.3f});
      tl.to("#c11", {{ x: 1100, y: 1000, opacity: 0, duration: 0.8, ease: "power2.in" }}, 44.4);
      tl.to("#s11", {{ opacity: 0, duration: 0.6, ease: "power2.in" }}, 45.4);
"""
js(j)

# ============================================================== audio
MUSIC = "assets/bgm/car-ad-energy-120bpm.mp3"
# music sits ~7 dB under the voice, then opens up for the music-only Go-live climax (36–40.3)
BGM_SEGS = [(0, 36.0, 0.3), (36.0, 40.3, 0.55), (40.3, TOTAL, 0.32)]
audio = [f'    <audio id="a-bgm{k}" src="{MUSIC}" data-start="{a}" data-duration="{round(b - a, 3)}" data-media-start="{10 + a}" data-track-index="{17 + k}" data-volume="{v}"></audio>'
         for k, (a, b, v) in enumerate(BGM_SEGS)]
for i, t in enumerate(VO_START):
    audio.append(f'    <audio id="a-vo{i + 1}" src="assets/vo-v6/l{i + 1}.wav" data-start="{t}" data-duration="{VO_DUR[i]:.3f}" data-track-index="{21 + i}" data-volume="1"></audio>')
sfx = [("whoosh.mp3", 5.3, 0.5), ("impact-bass-1.mp3", 6.0, 0.55), ("whoosh-short.mp3", 7.45, 0.35),
       ("whoosh-short.mp3", W['5:add'] - 0.45, 0.3), ("whoosh.mp3", 15.55, 0.4), ("impact-bass-2.mp3", 16.3, 0.35),
       ("whoosh-short.mp3", 21.4, 0.35), ("whoosh-short.mp3", 25.85, 0.3), ("whoosh.mp3", 31.7, 0.35),
       ("impact-bass-1.mp3", 36.5, 0.5), ("sparkle.mp3", 40.4, 0.25)]
for t in [W['5:app'], W['5:add'] + 0.02, W['5:source'] + 0.08, W['5:phone'] + 0.15] + [W['8:switch'] + 0.05 + k * 0.33 for k in range(3)] + [W['8:focus'] + 0.05, W['8:exposure'] + 0.02, W['10:live'] + 0.45]:
    sfx.append(("click-soft.mp3", t, 0.4))
for t in [W['7:cables'] + 0.12, W['7:accounts'] + 0.12, W['7:cloud'] + 0.12]:
    sfx.append(("impact-bass-2.mp3", t, 0.3))
for n, (f, t, v) in enumerate(sorted(sfx, key=lambda s: s[1])):
    audio.append(f'    <audio id="a-sfx{n}" src="assets/sfx/{f}" data-start="{t:.3f}" data-track-index="{40 + n}" data-volume="{v}"></audio>')

html = f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>{CSS}
    </style>
  </head>
  <body>
  <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL}" data-width="1920" data-height="1080">
    <div id="bg" class="clip" data-start="0" data-duration="{TOTAL}" data-track-index="0" style="position: absolute; inset: 0;"><div class="bgl"></div></div>
{chr(10).join(H)}
    <div id="grain" class="clip grain" data-start="0" data-duration="{TOTAL}" data-track-index="19"></div>
{chr(10).join(audio)}
  </div>
  <script>
    (function () {{
      window.__timelines = window.__timelines || {{}};
      const tl = gsap.timeline({{ paused: true }});
{"".join(J)}
      window.__timelines["main"] = tl;
    }})();
  </script>
  </body>
</html>
"""
(ROOT / "index.html").write_text(html)
print("wrote index.html", TOTAL, "s;", len(H), "scenes")
