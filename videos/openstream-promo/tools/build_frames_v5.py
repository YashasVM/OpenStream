#!/usr/bin/env python3
"""Generate the OpenStream v5 promo: "Your phone is the webcam" (~45.6s, male VO).

Website design system (openstream site V4 "Kinetic"): ink #0e0e0c, paper #f2ede4,
one orange accent #ff5b14, Unbounded display + Inter body, masked line reveals.
SaaS-demo feel: 3D phone + OBS window, one smooth cursor, slow dollies.
Writes compositions/v5/*.html and index.html. Run from the project root:
    python3 tools/build_frames_v5.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "compositions" / "v5"
OUT.mkdir(parents=True, exist_ok=True)

K, K2, C, O, DIM = "#0e0e0c", "#1a1a17", "#f2ede4", "#ff5b14", "#8a857b"
LINE = "rgba(242,237,228,0.14)"

FACES = "\n".join(
    f'    @font-face {{ font-family: "Inter"; src: url("assets/fonts/Inter-{w}.woff2") format("woff2"); font-weight: {w}; }}'
    for w in (400, 500, 600, 700)
) + '\n    @font-face { font-family: "Unbounded"; src: url("assets/fonts/Unbounded-var.woff2") format("woff2"); font-weight: 200 900; }'

O_PATH = "M 110.6 78.6 A 29.6 29.6 0 1 0 108.6 112.6"
S_PATH = "M 137 87 L 103.5 87 A 8.7 8.7 0 0 0 103.5 104.4 L 127.6 104.4 A 8.7 8.7 0 0 1 127.6 121.8 L 107.5 121.8"


def logo(cls, color=C, oid="", sid="", width=5.4):
    a = f' id="{oid}"' if oid else ""
    b = f' id="{sid}"' if sid else ""
    return (f'<svg class="{cls}" viewBox="52 60 90 68" aria-label="OpenStream logo"><g fill="none" stroke="{color}" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round">'
            f'<path{a} pathLength="1" d="{O_PATH}"/><path{b} pathLength="1" d="{S_PATH}"/></g></svg>')


# Crop of the real OBS preview inside assets/obs-live.png (1911x1038): x442 y50 w772 h435.
def feed(fid, w, h, extra=""):
    k = w / 772
    return (f'<div class="feed" id="{fid}" style="width: {w}px; height: {h}px;{extra}"><div class="feed-z" id="{fid}-z">'
            f'<img src="assets/obs-live.png" alt="Live phone camera feed in OBS" style="width: {1911 * k:.1f}px; left: {-442 * k:.1f}px; top: {-50 * k:.1f}px;" />'
            f'</div></div>')


def phone(pid, x, y, w, h, depth=14):
    layers = "".join(f'<i class="ph-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (f'<div class="phone" id="{pid}" style="left: {x}px; top: {y}px; width: {w}px; height: {h}px;">'
            f'{layers}<i class="ph-back" style="transform: translateZ(-{depth + 1}px);"></i>'
            f'<div class="ph-front"><div class="ph-screen"><img src="assets/phone-live.jpg" alt="OpenStream Android app" /></div><i class="ph-hole"></i></div></div>')


CURSOR = ('<div class="cur" id="{id}"><i class="cur-ring" id="{id}-ring"></i><svg viewBox="0 0 28 36" aria-hidden="true">'
          f'<path d="M3 2 L3 29 L10.5 22 L15.5 33 L20 31 L15 20.5 L25 20.5 Z" fill="{C}" stroke="{K}" stroke-width="2.2" stroke-linejoin="round"/></svg></div>')


def cursor(cid):
    return CURSOR.replace("{id}", cid)


def move(cid, x, y, t, d=0.7, ease="power3.inOut"):
    return f'      tl.to("#{cid}", {{ x: {x}, y: {y}, duration: {d}, ease: "{ease}" }}, {t:.3f});\n'


def click(cid, t):
    return (f'      tl.fromTo("#{cid} svg", {{ scale: 1 }}, {{ scale: 0.82, duration: 0.08, ease: "power2.out", yoyo: true, repeat: 1, immediateRender: false }}, {t:.3f});\n'
            f'      tl.fromTo("#{cid}-ring", {{ scale: 0.2, opacity: 0.9 }}, {{ scale: 1.6, opacity: 0, duration: 0.3, ease: "expo.out", immediateRender: false }}, {t:.3f});\n')


def mask_up(sel, t, d=0.9, stagger=0.0):
    st = f", stagger: {stagger}" if stagger else ""
    return f'      tl.fromTo("{sel}", {{ yPercent: 110 }}, {{ yPercent: 0, duration: {d}, ease: "power4.out"{st} }}, {t:.3f});\n'


def pop(sel, t, d=0.5):
    return f'      tl.fromTo("{sel}", {{ opacity: 0, scale: 0.7, y: 20 }}, {{ opacity: 1, scale: 1, y: 0, duration: {d}, ease: "back.out(1.8)" }}, {t:.3f});\n'


def fade_in(sel, t, d=0.5, extra_from="", extra_to=""):
    return f'      tl.fromTo("{sel}", {{ opacity: 0{extra_from} }}, {{ opacity: 1{extra_to}, duration: {d}, ease: "power2.out" }}, {t:.3f});\n'


def obs_window(wid, with_feed=False, live_btn=False):
    """Clean OBS-style window, 1180x700. Coordinates are window-local for the cursor."""
    preview = feed(f"{wid}-feed", 892, 502) if with_feed else ""
    return f"""<div class="obs" id="{wid}">
            <div class="obs-bar"><i></i><i></i><i></i><span>OBS Studio</span></div>
            <div class="obs-side">
              <span class="obs-h">Scenes</span><span class="obs-row on">Scene</span>
              <span class="obs-h" style="margin-top: 150px;">Sources</span>
              <span class="obs-row src" id="{wid}-src">{logo("obs-mini")}OpenStream</span>
              <span class="obs-plus" id="{wid}-plus">+</span>
            </div>
            <div class="obs-prev" id="{wid}-prev"><span class="obs-empty" id="{wid}-empty">No source selected</span><div class="obs-feedwrap" id="{wid}-feedwrap">{preview}</div>
              <span class="obs-live" id="{wid}-live"><b></b>LIVE</span></div>
            <div class="obs-bot">
              <div class="obs-meters"><i style="height: 62%;"></i><i style="height: 78%;"></i><i style="height: 54%;"></i><i style="height: 70%;"></i></div>
              <span class="obs-stream" id="{wid}-stream"><em id="{wid}-st1">Start Streaming</em><em id="{wid}-st2">Stop Streaming</em></span>
            </div>
          </div>"""


BASE_CSS = f"""
    #root {{ position: absolute; inset: 0; overflow: hidden; color: {C}; font-family: "Inter", sans-serif; background: {K}; }}
    .bg {{ position: absolute; inset: 0; background: radial-gradient(ellipse 70% 60% at 50% 45%, #1b1a17 0%, {K} 70%); }}
    .cam {{ position: absolute; inset: 0; perspective: 1800px; perspective-origin: 50% 45%; }}
    .stage {{ position: absolute; inset: 0; transform-style: preserve-3d; }}
    .disp {{ font-family: "Unbounded", sans-serif; font-weight: 800; letter-spacing: -0.055em; line-height: 0.9; text-transform: uppercase; white-space: nowrap; }}
    .mask {{ display: block; overflow: hidden; padding-bottom: 0.06em; }}
    .mask > span {{ display: block; }}
    .o {{ color: {O}; }}
    .label {{ font: 500 24px "Inter", sans-serif; letter-spacing: 0.02em; color: {DIM}; white-space: nowrap; }}
    .chip {{ display: inline-flex; align-items: center; gap: 12px; height: 64px; padding: 0 30px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {LINE}; font: 600 26px "Inter", sans-serif; white-space: nowrap; position: relative; }}
    .chip b {{ width: 10px; height: 10px; border-radius: 50%; background: {O}; display: block; }}
    .chip.solid {{ background: {O}; color: {K}; box-shadow: none; }}
    .feed {{ position: relative; overflow: hidden; border-radius: 14px; background: #000; }}
    .feed-z {{ position: absolute; inset: 0; }}
    .feed img {{ position: absolute; display: block; height: auto; }}
    .phone {{ position: absolute; transform-style: preserve-3d; }}
    .phone .ph-l {{ position: absolute; inset: 0; border-radius: 46px; background: #2a2a28; }}
    .phone .ph-back {{ position: absolute; inset: 0; border-radius: 46px; background: linear-gradient(140deg, #4a4944, #1c1c1a); }}
    .phone .ph-front {{ position: absolute; inset: 0; border-radius: 46px; background: #0d0d0c; box-shadow: inset 0 0 0 2px #3a3936; }}
    .phone .ph-screen {{ position: absolute; inset: 11px; border-radius: 36px; overflow: hidden; background: #000; }}
    .phone .ph-screen img {{ display: block; width: 100%; height: 100%; object-fit: cover; }}
    .phone .ph-hole {{ position: absolute; left: 50%; top: 22px; width: 16px; height: 16px; margin-left: -8px; border-radius: 50%; background: #050505; box-shadow: 0 0 0 2px #1d1d1b; }}
    .cur {{ position: absolute; left: 0; top: 0; width: 40px; height: 50px; z-index: 50; filter: drop-shadow(0 8px 14px rgba(0,0,0,0.55)); }}
    .cur svg {{ position: absolute; left: 0; top: 0; width: 40px; height: 50px; transform-origin: 6px 4px; }}
    .cur-ring {{ position: absolute; left: -26px; top: -28px; width: 60px; height: 60px; border-radius: 50%; border: 3px solid {O}; opacity: 0; display: block; }}
    .obs {{ position: absolute; width: 1180px; height: 700px; border-radius: 22px; background: {K2}; box-shadow: 0 0 0 1px {LINE}, 0 50px 120px rgba(0,0,0,0.6); overflow: hidden; transform-style: preserve-3d; }}
    .obs-bar {{ position: absolute; left: 0; right: 0; top: 0; height: 44px; display: flex; align-items: center; gap: 9px; padding-left: 18px; border-bottom: 1px solid {LINE}; }}
    .obs-bar i {{ width: 12px; height: 12px; border-radius: 50%; background: #3a3934; display: block; }}
    .obs-bar span {{ margin-left: 14px; font: 500 17px "Inter", sans-serif; color: {DIM}; }}
    .obs-side {{ position: absolute; left: 16px; top: 60px; width: 240px; height: 624px; border-radius: 14px; background: {K}; padding: 18px 14px; }}
    .obs-h {{ display: block; font: 600 15px "Inter", sans-serif; color: {DIM}; letter-spacing: 0.06em; text-transform: uppercase; margin-bottom: 10px; }}
    .obs-row {{ display: flex; align-items: center; gap: 10px; height: 42px; padding: 0 12px; border-radius: 10px; font: 500 18px "Inter", sans-serif; color: {C}; }}
    .obs-row.on {{ background: rgba(242,237,228,0.08); }}
    .obs-row.src {{ background: rgba(255,91,20,0.14); box-shadow: inset 0 0 0 1.5px {O}; opacity: 0; }}
    .obs-mini {{ width: 26px; height: 20px; display: block; }}
    .obs-plus {{ position: absolute; left: 14px; bottom: 14px; width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font: 500 28px "Inter", sans-serif; background: rgba(242,237,228,0.08); }}
    .obs-prev {{ position: absolute; left: 272px; top: 60px; width: 892px; height: 502px; border-radius: 14px; background: #050505; overflow: hidden; }}
    .obs-empty {{ position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font: 500 20px "Inter", sans-serif; color: #4a4843; }}
    .obs-feedwrap {{ position: absolute; inset: 0; }}
    .obs-live {{ position: absolute; left: 20px; top: 18px; display: flex; align-items: center; gap: 9px; height: 38px; padding: 0 16px; border-radius: 99px; background: {O}; color: {K}; font: 700 17px "Inter", sans-serif; letter-spacing: 0.08em; opacity: 0; }}
    .obs-live b {{ width: 9px; height: 9px; border-radius: 50%; background: {K}; display: block; }}
    .obs-bot {{ position: absolute; left: 272px; top: 576px; width: 892px; height: 108px; border-radius: 14px; background: {K}; }}
    .obs-meters {{ position: absolute; left: 22px; top: 20px; bottom: 20px; display: flex; gap: 8px; align-items: flex-end; }}
    .obs-meters i {{ width: 10px; border-radius: 3px; background: linear-gradient(0deg, {C} 0%, {DIM} 100%); opacity: 0.5; display: block; }}
    .obs-stream {{ position: absolute; right: 20px; top: 26px; width: 260px; height: 56px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {C}; font: 600 19px "Inter", sans-serif; overflow: hidden; }}
    .obs-stream em {{ position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-style: normal; }}
    .obs-stream em + em {{ background: {O}; color: {K}; transform: translateY(101%); }}
"""


def comp(fid, dur, css, body, js):
    return f"""<template>
  <style>
{FACES}
{BASE_CSS}{css}
  </style>

  <div id="root" data-composition-id="{fid}" data-width="1920" data-height="1080">
    <div id="{fid}-bg" class="bg clip" data-start="0" data-duration="{dur}" data-track-index="0"></div>
    <div id="{fid}-cam" class="cam clip" data-start="0" data-duration="{dur}" data-track-index="1">
      <div class="stage" id="{fid.split('-')[0]}-stage">
{body}
      </div>
    </div>
  </div>

  <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
  <script>
    (function () {{
      const D = {dur};
      const tl = gsap.timeline({{ paused: true }});
{js}
      window.__timelines["{fid}"] = tl;
    }})();
  </script>
</template>
"""


def exit_fade(fid, dur, d=0.3):
    return f'      tl.to("#{fid.split('-')[0]}-stage", {{ opacity: 0, filter: "blur(8px)", duration: {d}, ease: "power2.in" }}, {dur - d:.3f});\n'


# scene id, start, duration (root seconds)
SCENES = [
    ("s1-hook", 0.0, 4.4),
    ("s2-pocket", 4.4, 3.8),
    ("s3-headline", 8.2, 5.2),
    ("s4-demo", 13.4, 9.2),
    ("s5-quality", 22.6, 10.0),
    ("s6-controls", 32.6, 5.0),
    ("s7-live", 37.6, 4.0),
    ("s8-end", 41.6, 4.0),
]
VO = [0.4, 4.6, 8.5, 13.8, 19.4, 22.9, 26.4, 32.8, 37.8, 42.0]
frames = {}

# ------------------------------------------------------------- S1 hook: the sad laptop webcam
f, dur = "s1-hook", 4.4
body = f"""        <div class="s1-lap" id="s1-lap">
          <i class="s1-dot"></i>
          <div class="s1-shot">{feed("s1-feed", 760, 428)}<i class="s1-noise"></i></div>
          <span class="s1-tag" id="s1-tag">webcam · 720p · 24fps</span>
        </div>
        <div class="s1-q disp" id="s1-q"><span class="mask"><span id="s1-qt">720p?</span></span></div>"""
css = f"""
    .s1-lap {{ position: absolute; left: 520px; top: 200px; width: 880px; height: 560px; border-radius: 28px; background: {K2}; box-shadow: 0 0 0 1px {LINE}; }}
    .s1-dot {{ position: absolute; left: 50%; top: 20px; width: 10px; height: 10px; margin-left: -5px; border-radius: 50%; background: #3a3934; display: block; }}
    .s1-shot {{ position: absolute; left: 60px; top: 50px; width: 760px; height: 428px; border-radius: 14px; overflow: hidden; }}
    .s1-shot .feed {{ filter: grayscale(0.7) blur(3.5px) brightness(0.55) contrast(0.8); border-radius: 0; }}
    .s1-noise {{ position: absolute; inset: 0; display: block; background: repeating-linear-gradient(0deg, rgba(255,255,255,0.05) 0 2px, transparent 2px 4px); }}
    .s1-tag {{ position: absolute; left: 60px; top: 500px; font: 500 22px "Inter", sans-serif; color: {DIM}; }}
    .s1-q {{ position: absolute; left: 0; right: 0; top: 800px; text-align: center; font-size: 120px; color: {DIM}; }}
"""
js = f"""      tl.fromTo("#s1-stage", {{ z: -300, rotationX: 8 }}, {{ z: 60, rotationX: 0, duration: D, ease: "power1.inOut" }}, 0);
      tl.fromTo("#s1-lap", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.9, ease: "power2.out" }}, 0);
      tl.fromTo("#s1-feed-z", {{ x: 0 }}, {{ keyframes: {{ x: [0, -4, 3, -2, 0] }}, duration: 0.5, ease: "none", repeat: 5 }}, 0.2);
      tl.fromTo("#s1-tag", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4 }}, 0.9);
""" + mask_up("#s1-qt", 1.7) + exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S2 pocket: phone rises, orange rim light
f, dur = "s2-pocket", 3.8
body = f"""        <div class="s2-glow" id="s2-glow"></div>
        {phone("s2-ph", 785, 150, 350, 722)}
        <div class="s2-cap" id="s2-cap"><span class="mask"><span id="s2-c1">The best camera you own</span></span><span class="mask"><span id="s2-c2" class="o">is already in your pocket.</span></span></div>"""
css = f"""
    .s2-glow {{ position: absolute; left: 560px; top: 120px; width: 800px; height: 800px; border-radius: 50%; background: radial-gradient(closest-side, rgba(255,91,20,0.32), transparent); }}
    .s2-cap {{ position: absolute; left: 0; right: 0; top: 900px; text-align: center; font: 600 46px "Inter", sans-serif; letter-spacing: -0.02em; }}
"""
js = """      tl.fromTo("#s2-stage", { z: -100 }, { z: 80, duration: D, ease: "power1.inOut" }, 0);
      tl.fromTo("#s2-ph", { y: 700, rotationY: -60, rotationX: 30, rotationZ: -8 }, { y: 0, rotationY: -18, rotationX: 6, rotationZ: 0, duration: 1.4, ease: "expo.out" }, 0);
      tl.to("#s2-ph", { rotationY: 16, rotationX: 2, duration: D - 1.4, ease: "sine.inOut" }, 1.4);
      tl.fromTo("#s2-glow", { opacity: 0, scale: 0.4 }, { opacity: 1, scale: 1, duration: 1.4, ease: "expo.out" }, 0.1);
      tl.fromTo("#s2-ph .ph-front", { boxShadow: "inset 0 0 0 2px #3a3936, 0 0 0 0 rgba(255,91,20,0)" }, { boxShadow: "inset 0 0 0 2px #ff5b14, 0 0 60px 0 rgba(255,91,20,0.45)", duration: 1.2, ease: "power2.out" }, 1.0);
""" + mask_up("#s2-c1", 0.3) + mask_up("#s2-c2", 1.3) + exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S3 headline: site hero type
f, dur = "s3-headline", 5.2
ring = "OPENSTREAM · OBS CAMERA · " * 2
body = f"""        <div class="s3-top" id="s3-top"><span class="s3-logo">{logo("s3-mark")}OpenStream</span><span class="label">Android → OBS · local Wi-Fi</span><span class="s3-gh">GitHub ↗</span></div>
        <div class="s3-title disp">
          <span class="mask s3-l1"><span id="s3-a">Your phone.</span></span>
          <span class="mask s3-l2"><span id="s3-b">Now an <i class="s3-badge" id="s3-badge"><svg viewBox="0 0 100 100"><defs><path id="s3-circ" d="M50,50 m-38,0 a38,38 0 1,1 76,0 a38,38 0 1,1 -76,0"/></defs><text><textPath href="#s3-circ">{ring}</textPath></text></svg>{logo("s3-bmark", O)}</i></span></span>
          <span class="mask s3-l3"><span id="s3-c"><em class="o">OBS</em> camera.</span></span>
        </div>"""
css = f"""
    .s3-top {{ position: absolute; left: 80px; right: 80px; top: 60px; display: flex; justify-content: space-between; align-items: center; font: 500 22px "Inter", sans-serif; }}
    .s3-logo {{ display: flex; align-items: center; gap: 12px; font: 700 26px "Unbounded", sans-serif; letter-spacing: -0.02em; }}
    .s3-mark {{ width: 44px; height: 34px; display: block; }}
    .s3-gh {{ font-weight: 500; }}
    .s3-title {{ position: absolute; left: 80px; right: 80px; top: 250px; font-size: 200px; }}
    .s3-l2 > span {{ padding-left: 150px; display: flex; align-items: center; gap: 60px; }}
    .s3-l3 > span {{ text-align: right; }}
    .s3-title em {{ font-style: normal; }}
    .s3-badge {{ position: relative; display: block; width: 170px; height: 170px; }}
    .s3-badge svg {{ position: absolute; inset: 0; width: 100%; height: 100%; }}
    .s3-badge text {{ font: 600 9.6px "Inter", sans-serif; letter-spacing: 0.18em; fill: {C}; }}
    .s3-bmark {{ position: absolute; left: 50%; top: 50%; width: 70px; height: 53px; margin: -26px 0 0 -35px; }}
"""
js = """      tl.fromTo("#s3-stage", { z: -80, rotationY: -4 }, { z: 60, rotationY: 3, duration: D, ease: "power1.inOut" }, 0);
      tl.fromTo("#s3-top", { opacity: 0, y: -20 }, { opacity: 1, y: 0, duration: 0.6, ease: "power2.out" }, 0.1);
      tl.fromTo("#s3-badge svg", { rotation: 0 }, { rotation: 140, duration: D, ease: "none" }, 0);
""" + mask_up("#s3-a", 0.3, 1.0) + mask_up("#s3-b", 1.4, 1.0) + mask_up("#s3-c", 2.6, 1.0) + \
    '      tl.fromTo("#s3-c .o", { color: "#f2ede4" }, { color: "#ff5b14", duration: 0.4 }, 3.2);\n' + exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S4 demo: phone -> OBS with the cursor
f, dur = "s4-demo", 9.2
# OBS window placed at left 640 top 200; cursor is a child of the window (window coordinates).
body = f"""        {phone("s4-ph", 150, 250, 300, 620)}
        <svg class="s4-wifi" viewBox="0 0 1920 1080" aria-hidden="true"><path id="s4-arc" d="M 460 520 C 560 360 640 340 760 420" pathLength="1"/></svg>
        <i class="s4-pulse" id="s4-p1"></i><i class="s4-pulse" id="s4-p2"></i><i class="s4-pulse" id="s4-p3"></i>
        <div class="s4-win" id="s4-win">
          {obs_window("s4-obs", with_feed=True)}
          <div class="s4-menu" id="s4-menu"><span>Image</span><span>Media Source</span><span class="hl" id="s4-mi">{logo("obs-mini")}OpenStream</span><span>Video Capture Device</span></div>
          <div class="s4-pick" id="s4-pick">
            <span class="s4-pt">Pick your phone</span>
            <span class="s4-dev" id="s4-dev"><b></b><span><strong>Galaxy S24 Ultra</strong><em>192.168.1.24 · ready</em></span><i id="s4-chk">✓</i></span>
          </div>
          {cursor("s4-cur")}
        </div>
        <div class="s4-nos" id="s4-nos"><span class="chip" id="s4-n0">Cables<i></i></span><span class="chip" id="s4-n1">Accounts<i></i></span><span class="chip" id="s4-n2">Cloud<i></i></span></div>"""
css = f"""
    .s4-win {{ position: absolute; left: 640px; top: 200px; width: 1180px; height: 700px; transform-style: preserve-3d; }}
    .s4-win .obs {{ left: 0; top: 0; }}
    .obs-feedwrap {{ clip-path: inset(0 100% 0 0); }}
    .s4-wifi {{ position: absolute; inset: 0; width: 1920px; height: 1080px; overflow: visible; }}
    .s4-wifi path {{ fill: none; stroke: {O}; stroke-width: 4; stroke-linecap: round; stroke-dasharray: 1 1; stroke-dashoffset: 1; }}
    .s4-pulse {{ position: absolute; left: 450px; top: 510px; width: 20px; height: 20px; border-radius: 50%; background: {O}; box-shadow: 0 0 24px {O}; display: block; opacity: 0; }}
    .s4-menu {{ position: absolute; left: 30px; top: 430px; width: 300px; padding: 10px; border-radius: 14px; background: #24231f; box-shadow: 0 0 0 1px {LINE}, 0 24px 50px rgba(0,0,0,0.5); display: flex; flex-direction: column; gap: 2px; opacity: 0; transform-origin: 0% 100%; }}
    .s4-menu span {{ display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 14px; border-radius: 9px; font: 500 19px "Inter", sans-serif; color: {DIM}; }}
    .s4-menu .hl {{ color: {C}; }}
    .s4-pick {{ position: absolute; left: 398px; top: 160px; width: 640px; padding: 28px; border-radius: 20px; background: #24231f; box-shadow: 0 0 0 1px {LINE}, 0 30px 70px rgba(0,0,0,0.6); opacity: 0; }}
    .s4-pt {{ display: block; font: 700 26px "Unbounded", sans-serif; letter-spacing: -0.03em; margin-bottom: 20px; }}
    .s4-dev {{ display: flex; align-items: center; gap: 18px; height: 92px; padding: 0 22px; border-radius: 14px; box-shadow: inset 0 0 0 1.5px {LINE}; }}
    .s4-dev b {{ width: 14px; height: 14px; border-radius: 50%; background: {O}; display: block; box-shadow: 0 0 14px {O}; }}
    .s4-dev > span {{ display: flex; flex-direction: column; gap: 4px; flex: 1; }}
    .s4-dev strong {{ font: 600 23px "Inter", sans-serif; }}
    .s4-dev em {{ font: 500 18px "Inter", sans-serif; font-style: normal; color: {DIM}; }}
    .s4-dev i {{ font: 700 28px "Inter", sans-serif; font-style: normal; color: {O}; opacity: 0; }}
    .s4-nos {{ position: absolute; left: 0; right: 0; top: 950px; display: flex; justify-content: center; gap: 24px; }}
    .s4-nos .chip {{ opacity: 0; }}
    .s4-nos .chip i {{ position: absolute; left: 20px; right: 20px; top: 50%; height: 3px; margin-top: -1px; background: {O}; display: block; transform: scaleX(0); transform-origin: 0% 50%; }}
"""
t_open, t_plus, t_item, t_dev, t_done = 0.4, 1.9, 2.6, 3.5, 4.1
js = f"""      tl.fromTo("#s4-stage", {{ z: -260, rotationY: 10, rotationX: 6, opacity: 0 }}, {{ z: 0, rotationY: 2, rotationX: 2, opacity: 1, duration: 2.0, ease: "power3.out" }}, 0);
      tl.to("#s4-stage", {{ z: 60, rotationY: -3, rotationX: 0, duration: D - 2.0, ease: "sine.inOut" }}, 2.0);
      tl.fromTo("#s4-ph", {{ rotationY: 30 }}, {{ rotationY: 18, duration: D, ease: "sine.inOut" }}, 0);
      tl.fromTo("#s4-win", {{ rotationY: -14 }}, {{ rotationY: -6, duration: D, ease: "sine.inOut" }}, 0);
      tl.fromTo("#s4-ph .ph-front", {{ boxShadow: "inset 0 0 0 2px #3a3936" }}, {{ boxShadow: "inset 0 0 0 3px #ff5b14", duration: 0.3, yoyo: true, repeat: 1 }}, {t_open:.2f});
      tl.set("#s4-cur", {{ x: 700, y: 760 }}, 0);
"""
js += move("s4-cur", 36, 644, 0.9, 1.0)          # glide to "+"
js += click("s4-cur", t_plus)
js += f'      tl.fromTo("#s4-menu", {{ opacity: 0, scale: 0.9, y: 10 }}, {{ opacity: 1, scale: 1, y: 0, duration: 0.3, ease: "power3.out" }}, {t_plus + 0.08:.2f});\n'
js += move("s4-cur", 120, 538, t_plus + 0.2, 0.45)  # to "OpenStream" menu item
js += f'      tl.to("#s4-mi", {{ backgroundColor: "rgba(255,91,20,0.18)", duration: 0.15 }}, {t_item - 0.15:.2f});\n'
js += click("s4-cur", t_item)
js += f'      tl.to("#s4-menu", {{ opacity: 0, duration: 0.2 }}, {t_item + 0.1:.2f});\n'
js += f'      tl.fromTo("#s4-obs-src", {{ opacity: 0, x: -20 }}, {{ opacity: 1, x: 0, duration: 0.35, ease: "power3.out" }}, {t_item + 0.15:.2f});\n'
js += f'      tl.fromTo("#s4-pick", {{ opacity: 0, scale: 0.92, z: 80 }}, {{ opacity: 1, scale: 1, z: 0, duration: 0.45, ease: "expo.out" }}, {t_item + 0.25:.2f});\n'
js += move("s4-cur", 700, 300, t_item + 0.2, 0.65)  # to device row
js += click("s4-cur", t_dev)
js += f'      tl.to("#s4-dev", {{ boxShadow: "inset 0 0 0 2px #ff5b14", duration: 0.2 }}, {t_dev:.2f});\n'
js += f'      tl.fromTo("#s4-chk", {{ opacity: 0, scale: 0.4 }}, {{ opacity: 1, scale: 1, duration: 0.35, ease: "back.out(3)" }}, {t_dev + 0.1:.2f});\n'
js += f'      tl.to("#s4-pick", {{ opacity: 0, scale: 0.96, duration: 0.3, ease: "power2.in" }}, {t_done - 0.05:.2f});\n'
js += f'      tl.to("#s4-arc", {{ strokeDashoffset: 0, duration: 0.6, ease: "power3.inOut" }}, {t_done - 0.3:.2f});\n'
for i in range(3):
    t = t_done + 0.1 + i * 0.5
    js += (f'      tl.fromTo("#s4-p{i + 1}", {{ opacity: 0, x: 0, y: 0 }}, {{ keyframes: [{{ opacity: 1, x: 100, y: -130, duration: 0.25 }}, {{ x: 190, y: -150, duration: 0.2 }}, {{ opacity: 0, x: 300, y: -90, duration: 0.25 }}], ease: "none" }}, {t:.2f});\n')
js += f'      tl.to("#s4-obs-feedwrap", {{ clipPath: "inset(0 0% 0 0)", duration: 0.7, ease: "power4.inOut" }}, {t_done + 0.25:.2f});\n'
js += f'      tl.to("#s4-obs-empty", {{ opacity: 0, duration: 0.2 }}, {t_done + 0.25:.2f});\n'
js += f'      tl.fromTo("#s4-obs-feed-z", {{ scale: 1.15 }}, {{ scale: 1, duration: 1.6, ease: "expo.out" }}, {t_done + 0.25:.2f});\n'
js += move("s4-cur", 1250, 820, t_done + 0.6, 1.0, "power2.in")  # cursor leaves
t_no = VO[4] - 13.4
for i in range(3):
    js += pop(f"#s4-n{i}", t_no + i * 0.55, 0.4)
    js += f'      tl.to("#s4-n{i} i", {{ scaleX: 1, duration: 0.25, ease: "power3.out" }}, {t_no + i * 0.55 + 0.3:.2f});\n'
    js += f'      tl.to("#s4-n{i}", {{ opacity: 0.45, duration: 0.2 }}, {t_no + i * 0.55 + 0.5:.2f});\n'
js += exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S5 quality: 1080p60, the 4K joke
f, dur = "s5-quality", 10.0
body = f"""        <div class="s5-card" id="s5-card">{feed("s5-feed", 1040, 585)}<span class="s5-tag" id="s5-tag"><b></b>LIVE · 1080p60</span></div>
        <div class="s5-left">
          <div class="disp s5-big"><span class="mask"><span id="s5-b1">1080p</span></span><span class="mask"><span id="s5-b2" class="o">60.</span></span></div>
          <div class="s5-chips"><span class="chip" id="s5-c0"><b></b>Every lens</span><span class="chip" id="s5-c1"><b></b>HW encoded</span><span class="chip" id="s5-c2"><b></b>AAC audio</span></div>
          <div class="s5-4k" id="s5-4k"><span class="chip s5-k" id="s5-k">4K<i id="s5-kx"></i></span><span class="s5-note" id="s5-note">we tried. your Wi-Fi cried.</span></div>
        </div>
        <div class="s5-router" id="s5-router"><svg viewBox="0 0 120 80" aria-hidden="true"><rect x="10" y="44" width="100" height="26" rx="8" fill="none" stroke="{DIM}" stroke-width="4"/><path d="M30 44 L22 12 M90 44 L98 12" stroke="{DIM}" stroke-width="4" stroke-linecap="round"/><circle cx="32" cy="57" r="4" fill="{O}"/><circle cx="48" cy="57" r="4" fill="{DIM}"/></svg><span>router.exe has retired</span></div>"""
css = f"""
    .s5-card {{ position: absolute; left: 790px; top: 230px; width: 1040px; height: 585px; border-radius: 20px; box-shadow: 0 0 0 1px {LINE}, 0 50px 120px rgba(0,0,0,0.6); transform-style: preserve-3d; }}
    .s5-card .feed {{ border-radius: 20px; }}
    .s5-tag {{ position: absolute; left: 22px; top: 20px; display: flex; align-items: center; gap: 10px; height: 42px; padding: 0 18px; border-radius: 99px; background: rgba(14,14,12,0.75); font: 700 18px "Inter", sans-serif; letter-spacing: 0.06em; }}
    .s5-tag b {{ width: 9px; height: 9px; border-radius: 50%; background: {O}; display: block; }}
    .s5-left {{ position: absolute; left: 90px; top: 220px; width: 660px; }}
    .s5-big {{ font-size: 150px; }}
    .s5-chips {{ display: flex; flex-wrap: wrap; gap: 14px; margin-top: 44px; }}
    .s5-chips .chip {{ opacity: 0; }}
    .s5-4k {{ display: flex; align-items: center; gap: 22px; margin-top: 36px; }}
    .s5-k {{ font: 800 30px "Unbounded", sans-serif; letter-spacing: -0.03em; box-shadow: inset 0 0 0 1.5px {C}; opacity: 0; }}
    .s5-k i {{ position: absolute; left: 14px; right: 14px; top: 50%; height: 4px; margin-top: -2px; background: {O}; display: block; transform: scaleX(0) rotate(-8deg); transform-origin: 0% 50%; }}
    .s5-note {{ font: 500 26px "Inter", sans-serif; font-style: italic; color: {DIM}; opacity: 0; white-space: nowrap; }}
    .s5-router {{ position: absolute; left: 90px; top: 870px; display: flex; align-items: center; gap: 20px; font: 500 30px "Inter", sans-serif; color: {DIM}; opacity: 0; }}
    .s5-router svg {{ width: 108px; height: 72px; display: block; }}
"""
t6 = VO[5] - 22.6          # 0.3  "Every lens, up to 1080p at 60"
t7 = VO[6] - 22.6          # 3.8  "Why not 4K?"
js = f"""      tl.fromTo("#s5-card", {{ x: -380, y: -60, scale: 1.35, rotationY: 0 }}, {{ x: 0, y: 0, scale: 1, rotationY: -10, duration: 1.4, ease: "power4.inOut" }}, 0);
      tl.to("#s5-card", {{ rotationY: -4, rotationX: 2, duration: D - 1.4, ease: "sine.inOut" }}, 1.4);
      tl.fromTo("#s5-feed-z", {{ scale: 1.08 }}, {{ scale: 1.0, duration: D, ease: "none" }}, 0);
      tl.fromTo("#s5-stage", {{ z: 0 }}, {{ z: 50, duration: D, ease: "sine.inOut" }}, 0);
      tl.fromTo("#s5-tag", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4 }}, 0.9);
""" + mask_up("#s5-b1", t6 + 0.7) + mask_up("#s5-b2", t6 + 1.5)
for i, dt in enumerate((0.1, 1.4, 2.4)):
    js += pop(f"#s5-c{i}", t6 + dt if i else t6 + 0.4, 0.45)
js += f'      tl.fromTo("#s5-k", {{ opacity: 0, x: -60 }}, {{ opacity: 1, x: 0, duration: 0.5, ease: "back.out(2)" }}, {t7 + 0.1:.2f});\n'
js += f'      tl.fromTo("#s5-k", {{ rotation: 0 }}, {{ keyframes: {{ rotation: [0, -6, 5, -4, 3, 0] }}, duration: 0.7, ease: "none", immediateRender: false }}, {t7 + 1.6:.2f});\n'
js += f'      tl.to("#s5-kx", {{ scaleX: 1, duration: 0.3, ease: "power3.out" }}, {t7 + 2.7:.2f});\n'
js += f'      tl.to("#s5-k", {{ opacity: 0.4, rotation: -4, y: 6, duration: 0.4, ease: "power2.out" }}, {t7 + 2.95:.2f});\n'
js += fade_in("#s5-note", t7 + 3.2, 0.5, ", x: -16", ", x: 0")
js += fade_in("#s5-router", t7 + 3.9, 0.5, ", y: 16", ", y: 0")
js += f'      tl.fromTo("#s5-router svg", {{ rotation: 0 }}, {{ rotation: 9, y: 6, duration: 0.6, ease: "bounce.out" }}, {t7 + 4.5:.2f});\n'
js += exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S6 controls: lenses, exposure, focus
f, dur = "s6-controls", 5.0
lenses = ["0.6×", "1×", "3×", "5×"]
body = f"""        <div class="s6-wrap" id="s6-wrap">
          <div class="s6-card">{feed("s6-feed", 1100, 619)}<i class="s6-focus" id="s6-focus"></i></div>
          <div class="s6-dock" id="s6-dock">
            <span class="s6-title">{logo("obs-mini")}OpenStream Camera Control</span>
            <span class="s6-lbl">Lens</span>
            <div class="s6-lenses">{"".join(f'<span class="s6-lens" id="s6-l{i}">{l}</span>' for i, l in enumerate(lenses))}</div>
            <span class="s6-lbl">Exposure</span>
            <div class="s6-slider"><i class="s6-fill" id="s6-fill"></i><i class="s6-knob" id="s6-knob"></i></div>
            <span class="s6-lbl">Focus</span>
            <div class="s6-lenses"><span class="s6-lens on">Tap to focus</span><span class="s6-lens">Auto</span></div>
          </div>
          {cursor("s6-cur")}
        </div>
        <div class="s6-live" id="s6-live"><span class="chip solid">Live, right from OBS</span></div>"""
css = f"""
    .s6-wrap {{ position: absolute; left: 90px; top: 170px; width: 1740px; height: 640px; transform-style: preserve-3d; }}
    .s6-card {{ position: absolute; left: 0; top: 0; width: 1100px; height: 619px; border-radius: 20px; box-shadow: 0 0 0 1px {LINE}, 0 50px 120px rgba(0,0,0,0.6); }}
    .s6-card .feed {{ border-radius: 20px; }}
    .s6-focus {{ position: absolute; left: 640px; top: 210px; width: 130px; height: 130px; margin: -65px 0 0 -65px; border: 3px solid {O}; border-radius: 14px; opacity: 0; display: block; }}
    .s6-dock {{ position: absolute; left: 1150px; top: 0; width: 590px; height: 619px; border-radius: 20px; background: {K2}; box-shadow: 0 0 0 1px {LINE}; padding: 34px 36px; }}
    .s6-title {{ display: flex; align-items: center; gap: 12px; font: 700 22px "Unbounded", sans-serif; letter-spacing: -0.02em; margin-bottom: 30px; white-space: nowrap; }}
    .s6-lbl {{ display: block; font: 600 16px "Inter", sans-serif; letter-spacing: 0.1em; text-transform: uppercase; color: {DIM}; margin: 26px 0 14px; }}
    .s6-lenses {{ display: flex; gap: 12px; }}
    .s6-lens {{ display: flex; align-items: center; justify-content: center; height: 62px; min-width: 104px; padding: 0 20px; border-radius: 99px; box-shadow: inset 0 0 0 1.5px {LINE}; font: 600 22px "Inter", sans-serif; white-space: nowrap; }}
    .s6-lens.on {{ box-shadow: inset 0 0 0 1.5px {C}; }}
    .s6-slider {{ position: relative; height: 62px; }}
    .s6-slider::before {{ content: ""; position: absolute; left: 0; right: 0; top: 29px; height: 4px; border-radius: 2px; background: {LINE}; }}
    .s6-fill {{ position: absolute; left: 0; top: 29px; width: 400px; height: 4px; border-radius: 2px; background: {O}; display: block; transform-origin: 0% 50%; }}
    .s6-knob {{ position: absolute; left: 160px; top: 15px; width: 32px; height: 32px; margin-left: -16px; border-radius: 50%; background: {C}; display: block; }}
    .s6-live {{ position: absolute; left: 0; right: 0; top: 880px; display: flex; justify-content: center; }}
    .s6-live .chip {{ height: 72px; font-size: 28px; padding: 0 36px; }}
"""
# dock-local -> wrap coords: dock left 1150. lens chips at y ~ 34+52+30+26+30 ≈ 175 center ~205
lx = [1150 + 36 + 52 + i * 116 for i in range(4)]
js = f"""      tl.fromTo("#s6-wrap", {{ rotationY: 12, rotationX: 6, z: -200, opacity: 0 }}, {{ rotationY: 4, rotationX: 2, z: 0, opacity: 1, duration: 0.9, ease: "expo.out" }}, 0);
      tl.to("#s6-wrap", {{ rotationY: -3, rotationX: 0, z: 40, duration: D - 0.9, ease: "sine.inOut" }}, 0.9);
      tl.set("#s6-cur", {{ x: 1500, y: 560 }}, 0);
      tl.set("#s6-l0", {{ boxShadow: "inset 0 0 0 1.5px rgba(242,237,228,0.14)" }}, 0);
"""
zooms = [1.0, 1.3, 2.1]
t = 0.3
for i, z in enumerate(zooms):
    tc = 0.45 + i * 0.42
    js += move("s6-cur", lx[i], 222, tc - (0.4 if i == 0 else 0.3), 0.4 if i == 0 else 0.3)
    js += click("s6-cur", tc)
    js += f'      tl.to("#s6-l{i}", {{ backgroundColor: "#ff5b14", color: "#0e0e0c", duration: 0.12 }}, {tc:.2f});\n'
    if i:
        js += f'      tl.to("#s6-l{i - 1}", {{ backgroundColor: "rgba(0,0,0,0)", color: "#f2ede4", duration: 0.12 }}, {tc:.2f});\n'
    js += f'      tl.to("#s6-feed-z", {{ scale: {z}, duration: 0.35, ease: "power3.out", transformOrigin: "62% 48%" }}, {tc:.2f});\n'
t_ex = 1.62
js += move("s6-cur", 1186 + 160, 343, t_ex - 0.3, 0.28)
js += click("s6-cur", t_ex)
js += f'      tl.to("#s6-cur", {{ x: {1186 + 400}, duration: 0.5, ease: "power2.inOut" }}, {t_ex + 0.1:.2f});\n'
js += f'      tl.to("#s6-knob", {{ x: 240, duration: 0.5, ease: "power2.inOut" }}, {t_ex + 0.1:.2f});\n'
js += f'      tl.fromTo("#s6-fill", {{ scaleX: 0.4 }}, {{ scaleX: 1, duration: 0.5, ease: "power2.inOut" }}, {t_ex + 0.1:.2f});\n'
js += f'      tl.fromTo("#s6-feed", {{ filter: "brightness(0.7) blur(0px)" }}, {{ filter: "brightness(1.12) blur(0px)", duration: 0.5, ease: "power2.inOut" }}, {t_ex + 0.1:.2f});\n'
t_fo = 2.75
js += f'      tl.to("#s6-feed", {{ filter: "brightness(1.12) blur(4px)", duration: 0.3 }}, {t_fo - 0.45:.2f});\n'
js += move("s6-cur", 650, 220, t_fo - 0.5, 0.5)
js += click("s6-cur", t_fo)
js += f'      tl.fromTo("#s6-focus", {{ opacity: 0, scale: 1.6 }}, {{ opacity: 1, scale: 1, duration: 0.3, ease: "power3.out" }}, {t_fo:.2f});\n'
js += f'      tl.to("#s6-feed", {{ filter: "brightness(1.12) blur(0px)", duration: 0.35, ease: "power2.out" }}, {t_fo + 0.05:.2f});\n'
js += f'      tl.to("#s6-focus", {{ opacity: 0, duration: 0.4 }}, {t_fo + 0.8:.2f});\n'
js += pop("#s6-live", 3.05, 0.5)
js += exit_fade(f, dur)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S7 live: start streaming, pull back
f, dur = "s7-live", 4.0
body = f"""        <div class="s7-words disp"><span class="mask"><span id="s7-w0">Ultra-HQ webcam.</span></span><span class="mask"><span id="s7-w1">Zero friction.</span></span><span class="mask"><span id="s7-w2" class="o">Go live.</span></span></div>
        <div class="s7-set" id="s7-set">
          {phone("s7-ph", 0, 120, 270, 558)}
          <div class="s7-win" id="s7-win">{obs_window("s7-obs", with_feed=True)}{cursor("s7-cur")}</div>
        </div>"""
css = f"""
    .s7-words {{ position: absolute; left: 0; right: 0; top: 70px; display: flex; justify-content: center; gap: 40px; font-size: 64px; }}
    .s7-set {{ position: absolute; left: 230px; top: 250px; width: 1560px; height: 720px; transform-style: preserve-3d; }}
    .s7-win {{ position: absolute; left: 380px; top: 0; width: 1180px; height: 700px; transform-style: preserve-3d; }}
    .s7-win .obs {{ left: 0; top: 0; }}
"""
js = """      tl.fromTo("#s7-set", { z: 260, rotationY: -2, rotationX: 0, opacity: 0 }, { z: -160, opacity: 1, rotationY: -12, rotationX: 6, duration: D, ease: "power2.inOut" }, 0);
      tl.fromTo("#s7-ph", { rotationY: 24 }, { rotationY: 30, duration: D, ease: "none" }, 0);
      tl.set("#s7-cur", { x: 700, y: 760 }, 0);
""" + mask_up("#s7-w0", 0.2, 0.7) + mask_up("#s7-w1", 1.75, 0.7) + mask_up("#s7-w2", 2.6, 0.6)
js += move("s7-cur", 1030, 660, 1.5, 0.9)
js += click("s7-cur", 2.6)
js += '      tl.to("#s7-obs-st2", { yPercent: -101, duration: 0.45, ease: "power4.inOut" }, 2.6);\n'
js += '      tl.to("#s7-obs-st1", { yPercent: -101, duration: 0.45, ease: "power4.inOut" }, 2.6);\n'
js += '      tl.fromTo("#s7-obs-live", { opacity: 0, scale: 0.6 }, { opacity: 1, scale: 1, duration: 0.4, ease: "back.out(2.4)" }, 2.7);\n'
js += '      tl.fromTo("#s7-obs-live b", { opacity: 1 }, { opacity: 0.25, duration: 0.3, yoyo: true, repeat: 3, ease: "sine.inOut", immediateRender: false }, 3.1);\n'
js += exit_fade(f, dur, 0.35)
frames[f] = comp(f, dur, css, body, js)

# ------------------------------------------------------------- S8 end card
f, dur = "s8-end", 4.0
body = f"""        <div class="s8-mark" id="s8-mark">{logo("s8-logo", C, "s8-o", "s8-s", 4.6)}</div>
        <div class="s8-word disp"><span class="mask"><span id="s8-w">OpenStream</span></span></div>
        <div class="s8-sub" id="s8-sub">Free &amp; open source</div>
        <div class="s8-cta" id="s8-cta"><span class="chip solid s8-btn">Get it <span>↓</span></span><span class="s8-url">os.yash0.in</span></div>"""
css = f"""
    .s8-mark {{ position: absolute; left: 840px; top: 170px; width: 240px; height: 182px; }}
    .s8-logo {{ display: block; width: 100%; height: 100%; }}
    .s8-logo path {{ stroke-dasharray: 1 2; }}
    .s8-word {{ position: absolute; left: 0; right: 0; top: 410px; text-align: center; font-size: 150px; }}
    .s8-sub {{ position: absolute; left: 0; right: 0; top: 600px; text-align: center; font: 500 34px "Inter", sans-serif; color: {DIM}; }}
    .s8-cta {{ position: absolute; left: 0; right: 0; top: 720px; display: flex; justify-content: center; align-items: center; gap: 34px; }}
    .s8-btn {{ height: 80px; padding: 0 44px; font-size: 26px; }}
    .s8-url {{ font: 600 34px "Inter", sans-serif; letter-spacing: -0.01em; }}
"""
js = """      tl.fromTo("#s8-stage", { z: -120 }, { z: 0, duration: D, ease: "power2.out" }, 0);
      tl.fromTo("#s8-o", { strokeDashoffset: 1 }, { strokeDashoffset: 0, duration: 0.9, ease: "power3.inOut" }, 0.1);
      tl.fromTo("#s8-s", { strokeDashoffset: 1 }, { strokeDashoffset: 0, duration: 0.9, ease: "power3.inOut" }, 0.35);
      tl.fromTo("#s8-mark", { rotationY: -40, scale: 0.8 }, { rotationY: 0, scale: 1, duration: 1.2, ease: "expo.out" }, 0);
""" + mask_up("#s8-w", 0.4, 1.0) + fade_in("#s8-sub", 1.1, 0.6, ", y: 14", ", y: 0") + pop("#s8-cta", 1.6, 0.6)
frames[f] = comp(f, dur, css, body, js)

for name, html in frames.items():
    (OUT / f"{name}.html").write_text(html)

# ------------------------------------------------------------- root index.html
TOTAL = SCENES[-1][1] + SCENES[-1][2]
scenes_html = "\n".join(
    f'      <div id="el-{sid}" class="scene" data-composition-id="{sid}" data-composition-src="compositions/v5/{sid}.html" '
    f'data-start="{st}" data-duration="{du}" data-track-index="1"></div>'
    for sid, st, du in SCENES
)
vo_html = "\n".join(
    f'      <audio id="el-vo-{i + 1}" src="assets/vo-v5/l{i + 1}.wav" data-start="{t}" data-track-index="{10 + i}" data-volume="1"></audio>'
    for i, t in enumerate(VO)
)
# cursor clicks (root time) + scene whooshes
CLICKS = [13.4 + 1.9, 13.4 + 2.6, 13.4 + 3.5, 32.6 + 0.45, 32.6 + 0.87, 32.6 + 1.29, 32.6 + 1.55, 32.6 + 2.75, 37.6 + 2.6]
WHOOSH = [4.4, 8.2, 13.4, 22.6, 32.6, 37.6, 41.6]
POPS = [13.4 + 4.15, 19.4, 19.95, 20.5, 26.5, 26.4 + 2.7]
sfx = []
for t in CLICKS:
    sfx.append(("click-soft.mp3", t, 0.35))
for t in WHOOSH:
    sfx.append(("whoosh-short.mp3", t - 0.15, 0.22))
for t in POPS:
    sfx.append(("pop.mp3", t, 0.18))
sfx.append(("sparkle.mp3", 41.8, 0.18))
sfx_html = "\n".join(
    f'      <audio id="el-sfx-{i}" src="assets/sfx/{fn}" data-start="{t:.3f}" data-track-index="{30 + i}" data-volume="{v}"></audio>'
    for i, (fn, t, v) in enumerate(sorted(sfx, key=lambda s: s[1]))
)
index = f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ width: 1920px; height: 1080px; overflow: hidden; background: #000; }}
      #root {{ position: relative; width: 1920px; height: 1080px; overflow: hidden; background: {K}; }}
      .scene {{ position: absolute; inset: 0; width: 100%; height: 100%; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{TOTAL}" data-width="1920" data-height="1080">
{scenes_html}
      <audio id="el-bgm" src="assets/bgm/openstream-v5-bed.wav" data-start="0" data-duration="{TOTAL}" data-track-index="2" data-volume="0.5"></audio>
{vo_html}
{sfx_html}
    </div>
    <script>
      window.__timelines = window.__timelines || {{}};
      window.__timelines["main"] = gsap.timeline({{ paused: true }});
    </script>
  </body>
</html>
"""
(ROOT / "index.html").write_text(index)
print(f"wrote {len(frames)} scenes, total {TOTAL}s")
