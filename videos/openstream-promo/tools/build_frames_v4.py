#!/usr/bin/env python3
"""Generate the 13 OpenStream 2 promo frames (v4: energetic, 37.5s, 128 BPM).

Same minimal monochrome system as v3, re-timed for energy: medium-paced open,
gear changes into the drop, a fast one-bar section, then a slow GitHub close.
Every frame: perspective camera > beat-punch layer > preserve-3d stage.
Beat B = 0.46875s, bar = 1.875s. Run from the project root:
    python3 tools/build_frames_v4.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "compositions" / "frames"
B = 0.46875
BAR = 4 * B

DARK = "#0B0B0C"
PAPER = "#F3EFE8"
INK = "#0E0E0F"
WARM = "#5E5A54"
DIM = "#8C877F"
CARD = "#FBF9F5"
HAIR = "rgba(14,14,15,0.12)"

FACES = "\n".join(
    f'    @font-face {{ font-family: "Inter"; src: url("assets/fonts/Inter-{w}.woff2") format("woff2"); font-weight: {w}; }}'
    for w in (400, 500, 600, 700)
)
O_PATH = "M 110.6 78.6 A 29.6 29.6 0 1 0 108.6 112.6"
S_PATH = "M 137 87 L 103.5 87 A 8.7 8.7 0 0 0 103.5 104.4 L 127.6 104.4 A 8.7 8.7 0 0 1 127.6 121.8 L 107.5 121.8"
GH = ('<svg class="gh" viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>')
STAR = "12 2.5 14.94 8.46 21.5 9.41 16.75 14.04 17.87 20.58 12 17.5 6.13 20.58 7.25 14.04 2.5 9.41 9.06 8.46"


def logo(cls, color, oid=None, sid=None, width=5.4):
    a = f' id="{oid}"' if oid else ""
    b = f' id="{sid}"' if sid else ""
    return (f'<svg class="{cls}" viewBox="52 60 90 68" aria-label="OpenStream logo"><g fill="none" stroke="{color}" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round">'
            f'<path{a} pathLength="1" d="{O_PATH}"/><path{b} pathLength="1" d="{S_PATH}"/></g></svg>')


def tile(p, tid, x, y, s, depth=10):
    layers = "".join(f'<i class="tl-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (f'<div class="{p}-tile" id="{tid}" style="left: {x}px; top: {y}px; width: {s}px; height: {s}px;">{layers}'
            f'<div class="tl-f">{logo("tl-logo", PAPER)}</div></div>')


def phone(p, pid, x, y, w, h, depth=14):
    layers = "".join(f'<i class="ph-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (f'<div class="{p}-phone" id="{pid}" style="left: {x}px; top: {y}px; width: {w}px; height: {h}px;">'
            f'{layers}<i class="ph-back" style="transform: translateZ(-{depth + 1}px);"></i>'
            f'<div class="ph-front"><div class="ph-screen"><img src="assets/phone-live.jpg" alt="OpenStream Android app streaming the camera" /></div><i class="ph-hole"></i></div></div>')


def base_css(p, dark):
    bg = (f"radial-gradient(ellipse 70% 60% at 50% 42%, #1A1A1C 0%, {DARK} 70%)" if dark
          else f"radial-gradient(ellipse 75% 65% at 50% 40%, #F8F5F0 0%, {PAPER} 60%, #E9E3D8 100%)")
    sec = DIM if dark else WARM
    return f"""
    #root {{ position: absolute; inset: 0; overflow: hidden; color: {PAPER if dark else INK}; font-family: "Inter", sans-serif; }}
    .{p}-bg {{ position: absolute; inset: 0; background: {bg}; }}
    .{p}-cam {{ position: absolute; inset: 0; perspective: 1600px; perspective-origin: 50% 45%; }}
    .{p}-punch {{ position: absolute; inset: 0; transform-style: preserve-3d; }}
    .{p}-stage {{ position: absolute; inset: 0; transform-style: preserve-3d; }}
    .{p}-h {{ display: block; font-weight: 600; letter-spacing: -0.04em; line-height: 1.04; white-space: nowrap; }}
    .{p}-kick {{ display: block; font-size: 24px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; color: {sec}; white-space: nowrap; }}
    .{p}-card {{ background: {CARD}; border-radius: 28px; box-shadow: 0 0 0 1px {HAIR}, 0 2px 6px rgba(14,14,15,0.04), 0 30px 70px rgba(40,30,15,0.12); }}
    .{p}-pill {{ position: absolute; display: flex; align-items: center; gap: 12px; height: 60px; padding: 0 26px; border-radius: 9999px; background: {CARD}; color: {INK}; font-size: 25px; font-weight: 600; white-space: nowrap; box-shadow: 0 0 0 1px {HAIR}, 0 14px 34px rgba(40,30,15,0.14); }}
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


def punches(p, times, amt):
    return "".join(
        f'      tl.fromTo("#{p}-punch", {{ scale: {1 + amt:.3f} }}, {{ scale: 1, duration: 0.22, ease: "power2.out", immediateRender: false }}, {t:.5f});\n'
        for t in times
    )


def frame(fid, p, dark, dur, css, body, js, overlay=""):
    return f"""<template>
  <style>
{FACES}
{base_css(p, dark)}{css}
  </style>

  <div id="root" data-composition-id="{fid}" data-width="1920" data-height="1080">
    <div id="{p}-bg" class="{p}-bg clip" data-start="0" data-duration="{dur}" data-track-index="0"></div>
    <div id="{p}-cam" class="{p}-cam clip" data-start="0" data-duration="{dur}" data-track-index="1">
      <div class="{p}-punch" id="{p}-punch">
        <div class="{p}-stage" id="{p}-stage">
{body}
        </div>
      </div>
    </div>{overlay}
  </div>

  <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
  <script>
    (function () {{
      const B = {B};
      const D = {dur};
      const tl = gsap.timeline({{ paused: true }});
{js}
      window.__timelines["{fid}"] = tl;
    }})();
  </script>
</template>
"""


def slam(sel, t, z=260, y=70, d=0.34, blur=10):
    return (f'      tl.fromTo("{sel}", {{ opacity: 0, y: {y}, z: {z}, filter: "blur({blur}px)" }}, '
            f'{{ opacity: 1, y: 0, z: 0, filter: "blur(0px)", duration: {d}, ease: "expo.out" }}, {t:.5f});\n')


frames = {}
every = lambda n, step=1: [i * B * step for i in range(n)]

# ---------------------------------------------------------------- 01 hook (medium)
p, dur = "f01", 2 * BAR
words1 = ["Your", "phone", "has"]
words2 = ["a", "great", "camera."]
body = ('          <div class="f01-l" style="top: 360px; color: #8C877F;">' + "".join(f'<span class="f01-w" id="f01-a{i}">{w}</span>' for i, w in enumerate(words1)) + "</div>\n"
        '          <div class="f01-l" style="top: 500px;">' + "".join(f'<span class="f01-w" id="f01-b{i}">{w}</span>' for i, w in enumerate(words2)) + "</div>")
css = """
    .f01-l { position: absolute; left: 0; right: 0; display: flex; justify-content: center; gap: 30px; transform-style: preserve-3d; }
    .f01-w { display: block; font-size: 124px; font-weight: 600; letter-spacing: -0.045em; line-height: 1.05; white-space: nowrap; }
"""
js = '      tl.fromTo("#f01-stage", { rotationX: 10, rotationY: -6, z: -160 }, { rotationX: 0, rotationY: 4, z: 40, duration: D, ease: "power2.inOut" }, 0);\n'
js += "".join(slam(f"#f01-a{i}", i * B) for i in range(3)) + "".join(slam(f"#f01-b{i}", (4 + i) * B) for i in range(3))
js += '      tl.fromTo("#f01-b2", { scale: 1 }, { scale: 1.06, duration: 0.2, ease: "power2.out", yoyo: true, repeat: 1, immediateRender: false }, 7 * B);\n'
js += punches(p, every(4, 2), 0.014)
frames["01-hook"] = frame("01-hook", p, True, dur, css, body, js)

# ---------------------------------------------------------------- 02 problem (medium)
p, dur = "f02", 2 * BAR
chunks = ["Getting it", "into", "OBS?"]
items = ["Capture cards", "Webcam apps", "Cloud relays"]
body = ('          <div class="f02-l">' + "".join(f'<span class="f02-w" id="f02-w{i}">{w}</span>' for i, w in enumerate(chunks)) + "</div>\n"
        '          <div class="f02-items">' + "".join(f'<span class="f02-it" id="f02-i{i}">{t}<i class="f02-x" id="f02-x{i}"></i></span>' for i, t in enumerate(items)) + "</div>")
css = f"""
    .f02-l {{ position: absolute; left: 0; right: 0; top: 330px; display: flex; justify-content: center; gap: 34px; transform-style: preserve-3d; }}
    .f02-w {{ display: block; font-size: 132px; font-weight: 600; letter-spacing: -0.045em; white-space: nowrap; }}
    .f02-items {{ position: absolute; left: 0; right: 0; top: 620px; display: flex; justify-content: center; gap: 28px; transform-style: preserve-3d; }}
    .f02-it {{ position: relative; display: block; padding: 18px 34px; border-radius: 9999px; border: 1px solid rgba(243,239,232,0.22); font-size: 36px; font-weight: 500; color: {PAPER}; white-space: nowrap; }}
    .f02-x {{ position: absolute; left: 22px; right: 22px; top: 50%; height: 3px; margin-top: -1px; background: {PAPER}; display: block; }}
"""
js = '      tl.fromTo("#f02-stage", { rotationX: 6, rotationY: 8, z: -120 }, { rotationX: 0, rotationY: -6, z: 60, duration: D, ease: "power2.inOut" }, 0);\n'
js += "".join(slam(f"#f02-w{i}", i * B) for i in range(3))
for i in range(3):
    t = (4 + i) * B
    js += f'      tl.fromTo("#f02-i{i}", {{ opacity: 0, scale: 0.6, z: 200 }}, {{ opacity: 1, scale: 1, z: 0, duration: 0.3, ease: "back.out(2)" }}, {t:.5f});\n'
    js += f'      tl.fromTo("#f02-x{i}", {{ scaleX: 0 }}, {{ scaleX: 1, duration: 0.16, ease: "power3.out", transformOrigin: "0% 50%" }}, {t + 0.18:.5f});\n'
    js += f'      tl.to("#f02-i{i}", {{ opacity: 0.4, duration: 0.15 }}, {t + 0.3:.5f});\n'
js += '      tl.fromTo(".f02-items", { x: 0 }, { keyframes: { x: [-14, 12, -8, 5, 0] }, duration: 0.22, ease: "none", immediateRender: false }, 7 * B);\n'
js += punches(p, every(4, 2) + [5 * B], 0.014)
frames["02-problem"] = frame("02-problem", p, True, dur, css, body, js)

# ---------------------------------------------------------------- 03 turn (step up, 1 bar)
p, dur = "f03", BAR
body = f"""          <div class="f03-glow" id="f03-glow"></div>
          {phone(p, "f03-ph", 430, 170, 350, 722)}
          <div class="f03-copy"><span class="f03-w" id="f03-a" style="color: {DIM};">There's a</span><span class="f03-w" id="f03-b">simpler way.</span></div>"""
css = """
    .f03-glow { position: absolute; left: 230px; top: 140px; width: 760px; height: 780px; border-radius: 50%; background: radial-gradient(closest-side, rgba(243,239,232,0.18), transparent); }
    .f03-copy { position: absolute; left: 950px; top: 390px; display: flex; flex-direction: column; gap: 4px; transform-style: preserve-3d; }
    .f03-w { display: block; font-size: 112px; font-weight: 600; letter-spacing: -0.045em; line-height: 1.04; white-space: nowrap; }
"""
js = """      tl.fromTo("#f03-stage", { rotationY: 10, z: -60 }, { rotationY: -4, z: 60, duration: D, ease: "power2.out" }, 0);
      tl.fromTo("#f03-ph", { opacity: 0, z: -900, rotationY: -70, rotationX: 18 }, { opacity: 1, z: 0, rotationY: -16, rotationX: 4, duration: 0.6, ease: "expo.out" }, 0);
      tl.to("#f03-ph", { rotationY: 6, duration: D - 0.6, ease: "power1.inOut" }, 0.6);
      tl.fromTo("#f03-glow", { opacity: 0, scale: 0.5 }, { opacity: 1, scale: 1, duration: 0.6, ease: "expo.out" }, 0);
"""
js += slam("#f03-a", B, z=200, y=40, d=0.3) + slam("#f03-b", 2 * B, z=200, y=40, d=0.3)
js += punches(p, every(4), 0.02)
frames["03-turn"] = frame("03-turn", p, True, dur, css, body, js)

# ---------------------------------------------------------------- 04 logo (build, 1 bar)
p, dur = "f04", BAR
body = f"""          <div class="f04-mark" id="f04-mark">{logo("f04-logo", PAPER, "f04-o", "f04-s", 4.6)}</div>
          <div class="f04-word" id="f04-word">OpenStream</div>"""
css = """
    .f04-mark { position: absolute; left: 720px; top: 200px; width: 480px; height: 363px; }
    .f04-logo { display: block; width: 100%; height: 100%; }
    .f04-logo path { stroke-dasharray: 1 2; }
    .f04-word { position: absolute; left: 0; right: 0; top: 640px; text-align: center; font-size: 80px; font-weight: 600; letter-spacing: -0.03em; white-space: nowrap; }
    .f04-flash { position: absolute; inset: 0; background: #FFFFFF; }
"""
overlay = f'\n    <div id="f04-flash" class="f04-flash clip" data-start="{dur - 0.22:.5f}" data-duration="0.22" data-track-index="2"></div>'
js = """      tl.fromTo("#f04-o", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.7, ease: "power2.inOut" }, 0);
      tl.fromTo("#f04-s", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.55, ease: "power2.inOut" }, 0.35);
      tl.fromTo("#f04-word", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.3, ease: "expo.out" }, 2 * B);
      tl.fromTo("#f04-stage", { z: -60 }, { z: 0, duration: 3 * B, ease: "power1.out" }, 0);
      tl.to("#f04-stage", { z: 1000, duration: B, ease: "power3.in" }, 3 * B);
"""
js += f'      tl.fromTo("#f04-flash", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.2, ease: "power2.in" }}, {dur - 0.22:.5f});\n'
js += punches(p, every(3), 0.02)
frames["04-logo"] = frame("04-logo", p, True, dur, css, body, js, overlay)

# ---------------------------------------------------------------- 05 reveal (drop)
p, dur = "f05", 2 * BAR
body = f"""          {tile(p, "f05-tile", 860, 140, 200)}
          <div class="f05-kick" id="f05-k">Introducing</div>
          <div class="f05-title" id="f05-t">OpenStream <span class="f05-two">2</span></div>
          <div class="f05-sub" id="f05-s">Your Android phone. A native OBS camera.</div>
          <div class="f05-tag" id="f05-g">Works out of the box.</div>"""
css = f"""
    .f05-kick {{ position: absolute; left: 0; right: 0; top: 380px; text-align: center; font-size: 24px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase; color: {WARM}; white-space: nowrap; }}
    .f05-title {{ position: absolute; left: 0; right: 0; top: 462px; text-align: center; font-size: 180px; font-weight: 700; letter-spacing: -0.05em; line-height: 1; white-space: nowrap; }}
    .f05-two {{ display: inline-block; color: {WARM}; font-weight: 500; }}
    .f05-sub {{ position: absolute; left: 0; right: 0; top: 676px; text-align: center; font-size: 44px; font-weight: 500; letter-spacing: -0.02em; color: {WARM}; white-space: nowrap; }}
    .f05-tag {{ position: absolute; left: 50%; top: 776px; width: 520px; margin-left: -260px; height: 76px; border-radius: 9999px; background: {INK}; color: {PAPER}; display: flex; align-items: center; justify-content: center; font-size: 30px; font-weight: 600; white-space: nowrap; }}
    .f05-flash {{ position: absolute; inset: 0; background: #FFFFFF; }}
"""
overlay = '\n    <div id="f05-flash" class="f05-flash clip" data-start="0" data-duration="0.35" data-track-index="2"></div>'
js = """      tl.fromTo("#f05-flash", { opacity: 1 }, { opacity: 0, duration: 0.3, ease: "power2.out" }, 0);
      tl.fromTo("#f05-stage", { rotationX: 12, rotationY: -16, z: -260 }, { rotationX: 0, rotationY: 8, z: 40, duration: D, ease: "power3.out" }, 0);
      tl.fromTo("#f05-tile", { rotationY: -220, rotationX: 30, z: 600, opacity: 0 }, { rotationY: 0, rotationX: 0, z: 0, opacity: 1, duration: 0.6, ease: "expo.out" }, 0);
      tl.fromTo("#f05-t", { opacity: 0, z: 500, filter: "blur(16px)" }, { opacity: 1, z: 0, filter: "blur(0px)", duration: 0.4, ease: "expo.out" }, 0.03);
      tl.fromTo("#f05-k", { opacity: 0, y: 14 }, { opacity: 1, y: 0, duration: 0.3, ease: "expo.out" }, B);
      tl.fromTo("#f05-s", { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 0.35, ease: "expo.out" }, 2 * B);
      tl.fromTo("#f05-g", { opacity: 0, scale: 0.6, z: 120 }, { opacity: 1, scale: 1, z: 0, duration: 0.4, ease: "back.out(2)" }, 4 * B);
"""
js += punches(p, every(8), 0.022)
frames["05-reveal"] = frame("05-reveal", p, False, dur, css, body, js, overlay)

# ---------------------------------------------------------------- 06 steps
p, dur = "f06", 2 * BAR
steps = [("01", "Install the app", "Open it, grant the camera."),
         ("02", "Add the OBS plugin", "Your phone shows up on its own."),
         ("03", "Go live", "No accounts. No cloud.")]
body = ('          <div class="f06-head" id="f06-head"><span class="f06-kick">Out of the box</span><span class="f06-h" style="font-size: 88px;">Three steps. That\'s it.</span></div>\n' + "".join(
    f'          <div class="f06-c f06-card" id="f06-c{i}" style="left: {160 + i * 560}px;"><span class="f06-n">{n}</span><span class="f06-t">{t}</span><span class="f06-d">{d}</span><i class="f06-ok" id="f06-ok{i}"><i></i></i></div>\n'
    for i, (n, t, d) in enumerate(steps)))
css = f"""
    .f06-head {{ position: absolute; left: 160px; top: 140px; display: flex; flex-direction: column; gap: 18px; }}
    .f06-c {{ position: absolute; top: 420px; width: 500px; height: 400px; padding: 48px; box-sizing: border-box; display: flex; flex-direction: column; }}
    .f06-n {{ display: block; font-size: 30px; font-weight: 600; color: {WARM}; font-variant-numeric: tabular-nums; }}
    .f06-t {{ display: block; margin-top: 108px; font-size: 46px; font-weight: 600; letter-spacing: -0.035em; white-space: nowrap; }}
    .f06-d {{ display: block; margin-top: 12px; font-size: 26px; font-weight: 500; color: {WARM}; white-space: nowrap; }}
    .f06-ok {{ position: absolute; right: 44px; top: 40px; width: 56px; height: 56px; border-radius: 50%; background: {INK}; display: block; }}
    .f06-ok i {{ position: absolute; left: 19px; top: 13px; width: 13px; height: 24px; border: solid {PAPER}; border-width: 0 4px 4px 0; transform: rotate(45deg); display: block; }}
"""
js = """      tl.fromTo("#f06-stage", { rotationY: 18, rotationX: 10, z: -180 }, { rotationY: -8, rotationX: 2, z: 30, duration: D, ease: "power3.inOut" }, 0);
      tl.fromTo("#f06-head", { opacity: 0, y: 40 }, { opacity: 1, y: 0, duration: 0.35, ease: "expo.out" }, 0);
      for (let i = 0; i < 3; i++) {
        const t = i * 1.5 * B + 0.05;
        tl.fromTo("#f06-c" + i, { opacity: 0, z: -700, rotationX: 35, y: 90 }, { opacity: 1, z: 0, rotationX: 0, y: 0, duration: 0.5, ease: "expo.out" }, t);
        tl.fromTo("#f06-ok" + i, { scale: 0, rotation: -60 }, { scale: 1, rotation: 0, duration: 0.3, ease: "back.out(2.6)" }, t + B);
      }
"""
js += punches(p, every(8), 0.022)
frames["06-steps"] = frame("06-steps", p, False, dur, css, body, js)

# ---------------------------------------------------------------- 07 proof
p, dur = "f07", 2 * BAR
body = f"""          <div class="f07-head" id="f07-head"><span class="f07-kick">Real footage</span><span class="f07-h" style="font-size: 88px;">Live in OBS.</span></div>
          <div class="f07-win f07-card" id="f07-win"><div class="f07-bar"><i></i><i></i><i></i><span>OBS Studio</span></div><img src="assets/obs-live.png" alt="OBS Studio showing the OpenStream phone camera live" /></div>
          <div class="f07-shadow" id="f07-sh" style="left: 220px; top: 920px; width: 400px; height: 54px;"></div>
          {phone(p, "f07-ph", 270, 320, 290, 598)}
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
js = """      tl.fromTo("#f07-stage", { rotationY: 26, rotationX: 10, z: -260 }, { rotationY: -6, rotationX: 2, z: 40, duration: 5 * B, ease: "power3.inOut" }, 0);
      tl.to("#f07-stage", { z: 200, rotationY: -10, duration: 3 * B, ease: "power2.in" }, 5 * B);
      tl.fromTo("#f07-head", { opacity: 0, y: 40 }, { opacity: 1, y: 0, duration: 0.35, ease: "expo.out" }, 0);
      tl.fromTo("#f07-win", { opacity: 0, z: -1100, rotationY: 30 }, { opacity: 1, z: 0, rotationY: 0, duration: 0.55, ease: "expo.out" }, 0);
      tl.fromTo("#f07-ph", { x: -600, z: 400, rotationY: -80 }, { x: 0, z: 120, rotationY: 16, duration: 0.5, ease: "expo.out" }, 1.5 * B);
      tl.to("#f07-ph", { rotationY: 2, duration: D - 1.5 * B - 0.5, ease: "power1.inOut" }, 1.5 * B + 0.5);
      tl.fromTo("#f07-sh", { opacity: 0 }, { opacity: 1, duration: 0.3 }, 1.5 * B + 0.1);
      tl.fromTo("#f07-p0", { opacity: 0, scale: 0.5, z: 160 }, { opacity: 1, scale: 1, z: 160, duration: 0.3, ease: "back.out(2.4)" }, 4 * B);
      tl.fromTo("#f07-p1", { opacity: 0, scale: 0.5, z: 160 }, { opacity: 1, scale: 1, z: 160, duration: 0.3, ease: "back.out(2.4)" }, 5 * B);
"""
js += punches(p, every(8), 0.022)
frames["07-proof"] = frame("07-proof", p, False, dur, css, body, js)

# ---------------------------------------------------------------- 08 control
p, dur = "f08", 2 * BAR
rows = ["Front & rear lenses", "Zoom", "Torch on / off", "Identify the phone"]
body = (f'          <div class="f08-head" id="f08-head"><span class="f08-kick">Remote control</span><span class="f08-h" style="font-size: 88px;">Run the camera</span><span class="f08-h" style="font-size: 88px; color: {WARM};">from OBS.</span></div>\n'
        '          <div class="f08-list">' + "".join(f'<div class="f08-row" id="f08-r{i}"><i></i>{t}</div>' for i, t in enumerate(rows)) + "</div>\n"
        '          <div class="f08-shadow" id="f08-sh" style="left: 1150px; top: 930px; width: 620px; height: 60px;"></div>\n'
        '          <div class="f08-dock f08-card" id="f08-dock"><img src="assets/obs-dock-panel.png" alt="OpenStream Camera Control dock in OBS" /></div>')
css = f"""
    .f08-head {{ position: absolute; left: 160px; top: 140px; display: flex; flex-direction: column; gap: 12px; }}
    .f08-list {{ position: absolute; left: 160px; top: 520px; width: 760px; }}
    .f08-row {{ display: flex; align-items: center; gap: 20px; height: 84px; border-top: 1px solid {HAIR}; font-size: 36px; font-weight: 500; letter-spacing: -0.02em; white-space: nowrap; }}
    .f08-row i {{ display: block; width: 10px; height: 10px; border-radius: 50%; background: {INK}; }}
    .f08-dock {{ position: absolute; left: 1180px; top: 150px; width: 560px; height: 800px; overflow: hidden; border-radius: 26px; background: #1E1E20; }}
    .f08-dock img {{ display: block; width: 560px; height: 800px; }}
"""
js = """      tl.fromTo("#f08-stage", { rotationY: -16, rotationX: 8, z: -160 }, { rotationY: 8, rotationX: 0, z: 50, duration: D, ease: "power3.inOut" }, 0);
      tl.fromTo("#f08-head", { opacity: 0, y: 40 }, { opacity: 1, y: 0, duration: 0.35, ease: "expo.out" }, 0);
      tl.fromTo("#f08-dock", { opacity: 0, z: -800, rotationY: -60, x: 300 }, { opacity: 1, z: 0, rotationY: -14, x: 0, duration: 0.55, ease: "expo.out" }, 0.05);
      tl.to("#f08-dock", { rotationY: -2, duration: D - 0.65, ease: "power1.inOut" }, 0.65);
      tl.fromTo("#f08-sh", { opacity: 0 }, { opacity: 1, duration: 0.4 }, 0.2);
      for (let i = 0; i < 4; i++) {
        tl.fromTo("#f08-r" + i, { opacity: 0, x: -60 }, { opacity: 1, x: 0, duration: 0.28, ease: "expo.out" }, (2 + i) * B);
      }
"""
js += punches(p, every(8), 0.022)
frames["08-control"] = frame("08-control", p, False, dur, css, body, js)


# ---------------------------------------------------------------- 09-12 the fast section (1 bar each)
def statement(fid, p, dark, kicker, head, sub, tilt, count=None):
    sec = DIM if dark else WARM
    body = (f'          <div class="{p}-kick" id="{p}-k" style="position: absolute; left: 160px; top: 300px;">{kicker}</div>\n'
            f'          <div class="{p}-mask"><div class="{p}-big" id="{p}-big">{head}</div></div>\n'
            f'          <div class="{p}-sub" id="{p}-sub">{sub}</div>\n'
            f'          <i class="{p}-bar" id="{p}-bar"></i>')
    css = f"""
    .{p}-mask {{ position: absolute; left: 140px; right: 140px; top: 350px; height: 300px; overflow: hidden; }}
    .{p}-big {{ position: absolute; left: 20px; top: 20px; font-size: 230px; font-weight: 700; letter-spacing: -0.055em; line-height: 1; white-space: nowrap; font-variant-numeric: tabular-nums; }}
    .{p}-sub {{ position: absolute; left: 160px; top: 690px; font-size: 46px; font-weight: 500; letter-spacing: -0.02em; color: {sec}; white-space: nowrap; }}
    .{p}-bar {{ position: absolute; left: 160px; top: 800px; width: 1600px; height: 4px; background: {PAPER if dark else INK}; display: block; }}
"""
    js = f"""      tl.fromTo("#{p}-stage", {{ rotationY: {tilt}, rotationX: 6, z: -200 }}, {{ rotationY: 0, rotationX: 0, z: 0, duration: 0.6, ease: "expo.out" }}, 0);
      tl.to("#{p}-stage", {{ z: 70, rotationY: {-tilt / 4}, duration: D - 0.6, ease: "none" }}, 0.6);
      tl.fromTo("#{p}-big", {{ yPercent: 105 }}, {{ yPercent: 0, duration: 0.32, ease: "expo.out" }}, 0);
      tl.fromTo("#{p}-k", {{ opacity: 0, x: -30 }}, {{ opacity: 1, x: 0, duration: 0.25, ease: "expo.out" }}, 0.05);
      tl.fromTo("#{p}-sub", {{ opacity: 0, y: 30 }}, {{ opacity: 1, y: 0, duration: 0.28, ease: "expo.out" }}, B);
      tl.fromTo("#{p}-bar", {{ scaleX: 0 }}, {{ scaleX: 1, duration: D, ease: "none", transformOrigin: "0% 50%" }}, 0);
"""
    if count:
        js += f"""      const c = {{ v: 0 }};
      const el = document.getElementById("{p}-num");
      tl.fromTo(c, {{ v: 0 }}, {{ v: {count}, duration: 0.5, ease: "power2.out", onUpdate: function () {{ el.textContent = String(Math.round(c.v)); }} }}, 0);
"""
    js += punches(p, every(4), 0.03) + punches(p, [B / 2 + i * B for i in range(4)], 0.012)
    return frame(fid, p, dark, BAR, css, body, js)


frames["09-wifi"] = statement("09-wifi", "f09", True, "01 · Connection", "Local network.", "Phone to OBS over your own Wi-Fi.", 14)
frames["10-srt"] = statement("10-srt", "f10", False, "02 · Transport", "Built on SRT.", "H.264 + AAC, straight into OBS.", -14)
frames["11-bitrate"] = statement("11-bitrate", "f11", True, "03 · Quality", '<span id="f11-num">12</span> Mbps.', "By default. Tune it from the OBS dock.", 14, count=12)
frames["12-cloud"] = statement("12-cloud", "f12", False, "04 · Privacy", "Zero cloud.", "Nothing leaves your network.", -14)

# ---------------------------------------------------------------- 13 CTA (slow)
p, dur = "f13", 2 * BAR
body = f"""          <div class="f13-mark">{logo("f13-logo", PAPER, "f13-o", "f13-s", 5)}</div>
          <div class="f13-title" id="f13-t">OpenStream <span style="color: {DIM}; font-weight: 500;">2</span></div>
          <div class="f13-sub" id="f13-sub">Free and open source.</div>
          <div class="f13-btnwrap"><div class="f13-btn" id="f13-btn">{GH}<span>Star on GitHub</span><svg class="f13-star" id="f13-star" viewBox="0 0 24 24" aria-hidden="true"><polygon id="f13-sf" points="{STAR}" fill="{INK}" stroke="none"/><polygon id="f13-sp" pathLength="1" points="{STAR}" fill="none" stroke="{INK}" stroke-width="1.6" stroke-linejoin="round"/></svg></div></div>
          <div class="f13-url" id="f13-url">github.com/YashasVM/OpenStream</div>"""
css = f"""
    .f13-mark {{ position: absolute; left: 870px; top: 150px; width: 180px; height: 136px; }}
    .f13-logo {{ display: block; width: 100%; height: 100%; }}
    .f13-logo path {{ stroke-dasharray: 1 2; }}
    .f13-title {{ position: absolute; left: 0; right: 0; top: 340px; text-align: center; font-size: 140px; font-weight: 700; letter-spacing: -0.05em; line-height: 1; white-space: nowrap; }}
    .f13-sub {{ position: absolute; left: 0; right: 0; top: 520px; text-align: center; font-size: 42px; font-weight: 500; color: {DIM}; white-space: nowrap; }}
    .f13-btnwrap {{ position: absolute; left: 0; right: 0; top: 640px; display: flex; justify-content: center; }}
    .f13-btn {{ display: flex; align-items: center; gap: 20px; height: 104px; padding: 0 44px 0 52px; border-radius: 9999px; background: {PAPER}; color: {INK}; font-size: 40px; font-weight: 600; letter-spacing: -0.02em; white-space: nowrap; }}
    .f13-btn .gh {{ display: block; width: 46px; height: 46px; }}
    .f13-star {{ display: block; width: 50px; height: 50px; margin-left: 6px; }}
    .f13-star polygon {{ stroke-dasharray: 1 2; }}
    .f13-url {{ position: absolute; left: 0; right: 0; top: 790px; text-align: center; font-size: 30px; font-weight: 500; color: {DIM}; white-space: nowrap; }}
"""
js = """      tl.fromTo("#f13-stage", { z: -220, rotationX: 6 }, { z: 0, rotationX: 0, duration: 1.4, ease: "power3.out" }, 0);
      tl.to("#f13-stage", { z: 60, duration: D - 1.4, ease: "sine.inOut" }, 1.4);
      tl.fromTo("#f13-o", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.3, ease: "power2.inOut" }, 0);
      tl.fromTo("#f13-s", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 1.0, ease: "power2.inOut" }, 0.5);
      tl.fromTo("#f13-t", { opacity: 0, filter: "blur(14px)", y: 20 }, { opacity: 1, filter: "blur(0px)", y: 0, duration: 1.0, ease: "power3.out" }, 0.15);
      tl.fromTo("#f13-sub", { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.8, ease: "power3.out" }, 0.9);
      tl.fromTo("#f13-btn", { opacity: 0, y: 30, scale: 0.94 }, { opacity: 1, y: 0, scale: 1, duration: 0.9, ease: "power3.out" }, 1.2);
      tl.fromTo("#f13-sp", { attr: { "stroke-dashoffset": 1.02 } }, { attr: { "stroke-dashoffset": 0 }, duration: 0.8, ease: "power2.inOut" }, 1.3);
      tl.fromTo("#f13-sf", { opacity: 0 }, { opacity: 1, duration: 0.5, ease: "power2.out" }, 1.95);
      tl.fromTo("#f13-star", { rotation: -30, scale: 0.8 }, { rotation: 0, scale: 1, duration: 1.6, ease: "power2.out" }, 1.3);
      tl.fromTo("#f13-url", { opacity: 0 }, { opacity: 1, duration: 0.8, ease: "power2.out" }, 2.0);
      tl.to("#f13-stage", { opacity: 0, duration: 0.5, ease: "power2.in" }, D - 0.5);
"""
js += punches(p, [0], 0.04)
frames["13-cta"] = frame("13-cta", p, True, dur, css, body, js)

for old in OUT.glob("*.html"):
    old.unlink()
for fid, html in frames.items():
    (OUT / f"{fid}.html").write_text(html)
    print("wrote", fid)
