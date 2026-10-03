#!/usr/bin/env python3
"""Generate the 7 OpenStream promo frames (v2: beige + brights, Inter, CSS 3D).

Every frame shares one design system (fonts, palette, drifting colour blobs,
a perspective camera over a preserve-3d stage) and keeps the 128 BPM grid:
beat B = 0.46875s. Run from the project root:  python3 tools/build_frames.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "compositions" / "frames"
B = 0.46875

INK = "#121212"
MUTED = "#4A4A4F"
BEIGE = "#F2EBDD"
CARD = "#FFFCF7"
ORANGE, PINK, VIOLET, BLUE, LIME, YELLOW = "#FF5A1F", "#FF3D7F", "#7B61FF", "#2F6BFF", "#7ED321", "#FFC531"
GRAD = f"linear-gradient(95deg, {ORANGE} 0%, {PINK} 48%, {VIOLET} 100%)"

FACES = "\n".join(
    f'    @font-face {{ font-family: "Inter"; src: url("assets/fonts/Inter-{w}.woff2") format("woff2"); font-weight: {w}; }}'
    for w in (400, 500, 600, 700, 800)
)


def base_css(p):
    return f"""
    #root {{ position: absolute; inset: 0; overflow: hidden; color: {INK}; font-family: "Inter", sans-serif; }}
    .{p}-bg {{ position: absolute; inset: 0; overflow: hidden; background: {BEIGE}; }}
    .{p}-blob {{ position: absolute; border-radius: 50%; filter: blur(120px); opacity: 0.42; }}
    .{p}-cam {{ position: absolute; inset: 0; perspective: 1600px; perspective-origin: 50% 45%; }}
    .{p}-stage {{ position: absolute; inset: 0; transform-style: preserve-3d; }}
    .{p}-chrome {{ position: absolute; top: 64px; display: flex; align-items: center; gap: 14px; font-size: 22px; font-weight: 600; letter-spacing: -0.01em; color: {INK}; white-space: nowrap; }}
    .{p}-chrome i {{ display: block; width: 26px; height: 26px; border-radius: 8px; background: {GRAD}; }}
    .{p}-count {{ position: absolute; top: 66px; right: 160px; font-size: 20px; font-weight: 500; color: {MUTED}; font-variant-numeric: tabular-nums; white-space: nowrap; }}
    .{p}-grad {{ background: {GRAD}; -webkit-background-clip: text; background-clip: text; color: transparent; }}
    .{p}-kick {{ font-size: 24px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #D63F0B; white-space: nowrap; }}
    .{p}-card {{ background: {CARD}; border-radius: 28px; box-shadow: 0 2px 4px rgba(18,18,18,0.04), 0 24px 60px rgba(60,40,10,0.14); }}
    .{p}-pill {{ display: flex; align-items: center; gap: 14px; height: 68px; padding: 0 30px; border-radius: 9999px; background: {CARD}; font-size: 28px; font-weight: 600; white-space: nowrap; box-shadow: 0 2px 4px rgba(18,18,18,0.05), 0 16px 40px rgba(60,40,10,0.14); }}
    .{p}-pill b {{ display: block; width: 14px; height: 14px; border-radius: 50%; }}
    .{p}-phone {{ position: absolute; transform-style: preserve-3d; }}
    .{p}-phone .ph-l {{ position: absolute; inset: 0; border-radius: 46px; background: #2B2B2E; }}
    .{p}-phone .ph-back {{ position: absolute; inset: 0; border-radius: 46px; background: linear-gradient(140deg, #4A4A4F, #1C1C1E); }}
    .{p}-phone .ph-front {{ position: absolute; inset: 0; border-radius: 46px; background: #0E0E10; box-shadow: inset 0 0 0 2px #3A3A3E; }}
    .{p}-phone .ph-screen {{ position: absolute; inset: 11px; border-radius: 36px; overflow: hidden; background: #000; }}
    .{p}-phone .ph-screen img {{ display: block; width: 100%; height: 100%; object-fit: cover; }}
    .{p}-phone .ph-hole {{ position: absolute; left: 50%; top: 22px; width: 16px; height: 16px; margin-left: -8px; border-radius: 50%; background: #050505; box-shadow: 0 0 0 2px #1d1d20; }}
    .{p}-shadow {{ position: absolute; border-radius: 50%; background: radial-gradient(closest-side, rgba(60,40,10,0.28), transparent); }}
    .{p}-icon {{ position: absolute; transform-style: preserve-3d; }}
    .{p}-icon .ic-l {{ position: absolute; inset: 0; border-radius: 26%; background: #B5361A; }}
    .{p}-icon .ic-f {{ position: absolute; inset: 0; border-radius: 26%; background: {GRAD}; display: flex; align-items: center; justify-content: center; }}
    .{p}-icon .ic-ring {{ display: block; width: 50%; height: 50%; border-radius: 50%; border: solid {CARD}; border-width: calc(var(--s) * 0.065); box-sizing: border-box; display: flex; align-items: center; justify-content: center; }}
    .{p}-icon .ic-dot {{ display: block; width: 34%; height: 34%; border-radius: 50%; background: {CARD}; }}
"""


def phone(p, pid, x, y, w, h, img, depth=14, extra=""):
    layers = "".join(f'<i class="ph-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (
        f'<div class="{p}-phone" id="{pid}" style="left: {x}px; top: {y}px; width: {w}px; height: {h}px;{extra}">'
        f'{layers}<i class="ph-back" style="transform: translateZ(-{depth + 1}px);"></i>'
        f'<div class="ph-front"><div class="ph-screen"><img src="{img}" alt="OpenStream Android app camera screen" /></div><i class="ph-hole"></i></div></div>'
    )


def app_icon(p, iid, x, y, s, depth=10):
    layers = "".join(f'<i class="ic-l" style="transform: translateZ(-{i}px);"></i>' for i in range(1, depth + 1))
    return (
        f'<div class="{p}-icon" id="{iid}" style="left: {x}px; top: {y}px; width: {s}px; height: {s}px; --s: {s}px;">'
        f'{layers}<div class="ic-f"><i class="ic-ring"><i class="ic-dot"></i></i></div></div>'
    )


def blobs(p, spec):
    return "".join(
        f'<div class="{p}-blob" id="{p}-b{i}" style="left: {x}px; top: {y}px; width: {s}px; height: {s}px; background: {c};"></div>'
        for i, (x, y, s, c) in enumerate(spec)
    )


def blob_js(p, n, d, drift):
    return "".join(
        f'tl.fromTo("#{p}-b{i}", {{ x: 0, y: 0 }}, {{ x: {dx}, y: {dy}, duration: {d}, ease: "sine.inOut" }}, 0);\n      '
        for i, (dx, dy) in zip(range(n), drift)
    )


def frame(fid, p, d, css, body, js, blob_spec, drift, overlay="", num=""):
    return f"""<template>
  <style>
{FACES}
{base_css(p)}{css}
  </style>

  <div id="root" data-composition-id="{fid}" data-width="1920" data-height="1080">
    <div id="{p}-bg" class="{p}-bg clip" data-start="0" data-duration="{d}" data-track-index="0">{blobs(p, blob_spec)}</div>
    <div id="{p}-cam" class="{p}-cam clip" data-start="0" data-duration="{d}" data-track-index="1">
      <div class="{p}-chrome" style="left: 160px;"><i></i>OpenStream</div>
      <div class="{p}-count">{num} / 07</div>
{body}
    </div>{overlay}
  </div>

  <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
  <script>
    (function () {{
      const B = {B};
      const D = {d};
      const tl = gsap.timeline({{ paused: true }});
      {blob_js(p, len(blob_spec), d, drift)}
{js}
      window.__timelines["{fid}"] = tl;
    }})();
  </script>
</template>
"""


frames = {}

# ---------------------------------------------------------------- 01 hook
p = "f01"
cards = [
    ("Capture card", "Extra hardware", YELLOW, 300, -16),
    ("Webcam app", "Another desktop client", BLUE, 760, 0),
    ("Cloud relay", "Someone else's server", VIOLET, 1220, 16),
]
glyphs = [
    '<i class="f01-g-card"></i>',
    '<i class="f01-g-lens"><i></i></i>',
    '<i class="f01-g-cloud"><i></i><i></i><i></i></i>',
]
body = f"""      <div class="f01-head" id="f01-head"><span class="f01-kick">The old way</span><span class="f01-title">Getting your phone into OBS?</span></div>
      <div class="f01-stage" id="f01-stage">
""" + "".join(
    f"""        <div class="f01-c f01-card" id="f01-c{i}" style="left: {x}px;">
          <div class="f01-ico" style="background: {col};">{glyphs[i]}</div>
          <div class="f01-ct">{t}</div><div class="f01-cs">{s}</div>
          <div class="f01-dim" id="f01-d{i}"></div>
          <div class="f01-x" id="f01-x{i}"><i></i><i></i></div>
        </div>
"""
    for i, (t, s, col, x, r) in enumerate(cards)
) + "      </div>"
css = f"""
    .f01-head {{ position: absolute; left: 160px; top: 150px; display: flex; flex-direction: column; gap: 18px; }}
    .f01-title {{ display: block; font-size: 84px; font-weight: 800; letter-spacing: -0.045em; line-height: 1; white-space: nowrap; }}
    .f01-c {{ position: absolute; top: 420px; width: 400px; height: 400px; padding: 44px; box-sizing: border-box; }}
    .f01-ico {{ width: 96px; height: 96px; border-radius: 26px; display: flex; align-items: center; justify-content: center; }}
    .f01-ct {{ margin-top: 120px; font-size: 48px; font-weight: 800; letter-spacing: -0.04em; white-space: nowrap; }}
    .f01-cs {{ margin-top: 10px; font-size: 26px; font-weight: 500; color: {MUTED}; white-space: nowrap; }}
    .f01-dim {{ position: absolute; inset: 0; border-radius: 28px; background: {BEIGE}; }}
    .f01-x {{ position: absolute; right: -26px; top: -26px; width: 92px; height: 92px; border-radius: 50%; background: {PINK}; box-shadow: 0 12px 30px rgba(255,61,127,0.4); }}
    .f01-x i {{ position: absolute; left: 22px; top: 42px; width: 48px; height: 8px; border-radius: 4px; background: #fff; transform: rotate(45deg); }}
    .f01-x i + i {{ transform: rotate(-45deg); }}
    .f01-x {{ transform-origin: 50% 50%; }}
    .f01-g-card {{ display: block; width: 52px; height: 34px; border-radius: 8px; background: #fff; box-shadow: inset 0 -8px 0 rgba(0,0,0,0.12); }}
    .f01-g-lens {{ display: flex; align-items: center; justify-content: center; width: 50px; height: 50px; border-radius: 50%; background: #fff; }}
    .f01-g-lens i {{ display: block; width: 20px; height: 20px; border-radius: 50%; background: {BLUE}; }}
    .f01-g-cloud {{ position: relative; display: block; width: 60px; height: 40px; }}
    .f01-g-cloud i {{ position: absolute; border-radius: 50%; background: #fff; }}
    .f01-g-cloud i:nth-child(1) {{ left: 0; top: 14px; width: 30px; height: 26px; }}
    .f01-g-cloud i:nth-child(2) {{ left: 14px; top: 0; width: 34px; height: 34px; }}
    .f01-g-cloud i:nth-child(3) {{ left: 32px; top: 12px; width: 28px; height: 28px; }}
"""
js = f"""      // camera: a slow orbit + dolly across the whole shot
      tl.fromTo("#f01-stage", {{ rotationX: 16, rotationY: 18, z: -260 }}, {{ rotationX: 4, rotationY: -12, z: 0, duration: D, ease: "power2.inOut" }}, 0);
      tl.fromTo("#f01-head", {{ opacity: 0, y: 40 }}, {{ opacity: 1, y: 0, duration: 0.45, ease: "expo.out" }}, 0);
      const rot = [-16, 0, 16];
      for (let i = 0; i < 3; i++) {{
        const t = i * 2 * B;
        tl.fromTo("#f01-c" + i, {{ opacity: 0, z: 700, rotationX: -35, rotationY: rot[i] * 2, y: 120 }},
          {{ opacity: 1, z: 0, rotationX: 0, rotationY: rot[i], y: 0, duration: 0.5, ease: "expo.out" }}, t);
        tl.fromTo("#f01-d" + i, {{ opacity: 0 }}, {{ opacity: 0.55, duration: 0.2, ease: "power2.out" }}, t + B);
        tl.fromTo("#f01-x" + i, {{ scale: 0, rotation: -90 }}, {{ scale: 1, rotation: 0, duration: 0.35, ease: "back.out(2.4)" }}, t + B);
      }}
      // beat 6: the stack shudders; beat 7: everything tumbles back into depth
      tl.fromTo(["#f01-c0", "#f01-c1", "#f01-c2"], {{ x: 0 }}, {{ keyframes: {{ x: [-16, 12, -8, 5, 0] }}, duration: 0.24, ease: "none", stagger: 0.03, immediateRender: false }}, 6 * B);
      tl.to(["#f01-c0", "#f01-c1", "#f01-c2"], {{ rotationX: 75, y: 380, z: -500, opacity: 0, duration: 0.42, ease: "power3.in", stagger: 0.05 }}, 7 * B - 0.1);
"""
frames["01-hook"] = frame("01-hook", p, 3.75, css, body, js,
                          [(-200, -260, 760, ORANGE), (1300, 380, 720, VIOLET), (520, 700, 640, YELLOW)],
                          [(140, 80), (-120, -60), (90, -70)], num="01")

# ---------------------------------------------------------------- 02 turn
p = "f02"
body = f"""      <div class="f02-stage" id="f02-stage">
        <div class="f02-none" id="f02-none"><span class="f02-nw" id="f02-n0">None</span><span class="f02-nw" id="f02-n1">of</span><span class="f02-nw f02-grad" id="f02-n2">it.</span></div>
        <div class="f02-shadow" id="f02-sh" style="left: 290px; top: 880px; width: 420px; height: 70px;"></div>
        {phone(p, "f02-ph", 330, 190, 340, 703, "assets/phone-app.jpg")}
        <div class="f02-copy" id="f02-copy"><span class="f02-kick">The new way</span><span class="f02-just">Just your <span class="f02-grad">phone.</span></span><span class="f02-sub">The Android camera already in your pocket.</span></div>
      </div>"""
css = f"""
    .f02-none {{ position: absolute; left: 0; right: 0; top: 330px; height: 260px; display: flex; justify-content: center; align-items: center; gap: 48px; }}
    .f02-nw {{ display: block; font-size: 240px; font-weight: 800; letter-spacing: -0.055em; line-height: 1; }}
    .f02-copy {{ position: absolute; left: 790px; top: 360px; display: flex; flex-direction: column; gap: 22px; }}
    .f02-just {{ display: block; font-size: 128px; font-weight: 800; letter-spacing: -0.05em; line-height: 1; white-space: nowrap; }}
    .f02-just .f02-grad {{ display: inline; }}
    .f02-sub {{ display: block; font-size: 34px; font-weight: 500; color: {MUTED}; white-space: nowrap; }}
    .f02-flash {{ position: absolute; inset: 0; background: #FFFFFF; }}
"""
overlay = '\n    <div id="f02-flash" class="f02-flash clip" data-start="3.47" data-duration="0.28" data-track-index="2"></div>'
js = f"""      tl.fromTo("#f02-stage", {{ rotationX: 8, rotationY: -6, z: -120 }}, {{ rotationX: 2, rotationY: 6, z: 0, duration: 4 * B, ease: "power2.inOut" }}, 0);
      ["#f02-n0", "#f02-n1", "#f02-n2"].forEach(function (s, i) {{
        tl.fromTo(s, {{ opacity: 0, z: 900, rotationX: -40 }}, {{ opacity: 1, z: 0, rotationX: 0, duration: 0.38, ease: "expo.out" }}, i * B);
      }});
      tl.fromTo("#f02-none", {{ scale: 1 }}, {{ scale: 1.05, duration: 0.3, ease: "power2.out", immediateRender: false }}, 3 * B);
      // beat 4: words blast back into depth, the phone rises and turns
      tl.to("#f02-none", {{ z: -1400, opacity: 0, duration: 0.4, ease: "power3.in" }}, 4 * B - 0.25);
      tl.fromTo("#f02-ph", {{ y: 820, z: -300, rotationY: -70, rotationX: 24 }}, {{ y: 0, z: 0, rotationY: -24, rotationX: 6, duration: 0.65, ease: "power3.out" }}, 4 * B);
      tl.to("#f02-ph", {{ rotationY: 14, rotationX: 2, duration: 1.3, ease: "sine.inOut" }}, 4 * B + 0.65);
      tl.fromTo("#f02-sh", {{ opacity: 0, scaleX: 0.4 }}, {{ opacity: 1, scaleX: 1, duration: 0.6, ease: "power3.out" }}, 4 * B);
      tl.fromTo("#f02-copy", {{ opacity: 0, x: 120, z: -200 }}, {{ opacity: 1, x: 0, z: 0, duration: 0.5, ease: "expo.out" }}, 4 * B + 0.12);
      // the build: camera dives toward the phone into the drop
      tl.to("#f02-stage", {{ z: 520, rotationY: -4, duration: 2 * B + 0.1, ease: "power3.in" }}, 6 * B - 0.1);
      tl.fromTo("#f02-flash", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.24, ease: "power2.in" }}, 3.47);
"""
frames["02-turn"] = frame("02-turn", p, 3.75, css, body, js,
                          [(1200, -240, 760, PINK), (-260, 520, 700, YELLOW), (760, 640, 560, BLUE)],
                          [(-120, 90), (140, -60), (-80, -80)], overlay=overlay, num="02")

# ---------------------------------------------------------------- 03 drop
p = "f03"
body = f"""      <div class="f03-stage" id="f03-stage">
        {app_icon(p, "f03-icon", 850, 130, 220)}
        <div class="f03-word" id="f03-word">OpenStream</div>
        <div class="f03-tag" id="f03-tag">Your Android phone, now a <span class="f03-grad">native OBS camera.</span></div>
        <div class="f03-chips">
          <div class="f03-pill" id="f03-p0"><b style="background: {ORANGE};"></b>No capture card</div>
          <div class="f03-pill" id="f03-p1"><b style="background: {BLUE};"></b>No cloud</div>
          <div class="f03-pill" id="f03-p2"><b style="background: {LIME};"></b>Open source</div>
        </div>
      </div>"""
css = f"""
    .f03-word {{ position: absolute; left: 0; right: 0; top: 390px; text-align: center; font-size: 200px; font-weight: 800; letter-spacing: -0.06em; line-height: 1; white-space: nowrap; }}
    .f03-tag {{ position: absolute; left: 0; right: 0; top: 630px; text-align: center; font-size: 48px; font-weight: 600; letter-spacing: -0.03em; white-space: nowrap; }}
    .f03-tag .f03-grad {{ display: inline; }}
    .f03-chips {{ position: absolute; left: 0; right: 0; top: 760px; display: flex; justify-content: center; gap: 24px; transform-style: preserve-3d; }}
    .f03-flash {{ position: absolute; inset: 0; background: #FFFFFF; }}
"""
overlay = '\n    <div id="f03-flash" class="f03-flash clip" data-start="0" data-duration="0.45" data-track-index="2"></div>'
js = f"""      tl.fromTo("#f03-flash", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.4, ease: "power2.out" }}, 0);
      tl.fromTo("#f03-stage", {{ rotationX: 10, rotationY: 12, z: -200 }}, {{ rotationX: 0, rotationY: -6, z: 0, duration: D, ease: "power2.out" }}, 0);
      tl.fromTo("#f03-icon", {{ rotationY: 220, rotationX: 30, z: 600, opacity: 0 }}, {{ rotationY: 0, rotationX: 0, z: 0, opacity: 1, duration: 0.8, ease: "expo.out" }}, 0);
      tl.fromTo("#f03-word", {{ opacity: 0, z: 500, filter: "blur(24px)" }}, {{ opacity: 1, z: 0, filter: "blur(0px)", duration: 0.55, ease: "expo.out" }}, 0.08);
      tl.fromTo("#f03-tag", {{ opacity: 0, y: 40 }}, {{ opacity: 1, y: 0, duration: 0.45, ease: "expo.out" }}, B);
      // icon breathes on every beat
      for (let k = 2; k < 8; k++) {{
        tl.fromTo("#f03-icon", {{ z: 70 }}, {{ z: 0, duration: 0.32, ease: "power2.out", immediateRender: false }}, k * B);
      }}
      ["#f03-p0", "#f03-p1", "#f03-p2"].forEach(function (s, i) {{
        tl.fromTo(s, {{ opacity: 0, rotationX: -90, y: 30 }}, {{ opacity: 1, rotationX: 0, y: 0, duration: 0.45, ease: "back.out(1.8)", transformOrigin: "50% 100%" }}, (4 + i) * B);
      }});
"""
frames["03-drop"] = frame("03-drop", p, 3.75, css, body, js,
                          [(-160, -200, 780, ORANGE), (1180, -120, 720, VIOLET), (560, 660, 760, PINK)],
                          [(120, 100), (-140, 80), (60, -90)], overlay=overlay, num="03")

# ---------------------------------------------------------------- 04 pipeline
p = "f04"
nodes = [("Camera2", "Capture", ORANGE), ("MediaCodec", "AVC encode", PINK), ("MPEG-TS", "Mux", VIOLET),
         ("SRT", "Over your LAN", BLUE), ("OBS", "Native source", LIME)]
node_html = "".join(
    f'<div class="f04-n f04-card" id="f04-n{i}" style="left: {i * 335}px;"><div class="f04-glow" id="f04-g{i}" style="box-shadow: 0 0 0 4px {c}, 0 20px 60px {c}66;"></div>'
    f'<div class="f04-ni" style="background: {c};">{i + 1}</div><div class="f04-nm">{n}</div><div class="f04-ns">{s}</div></div>'
    for i, (n, s, c) in enumerate(nodes)
)
pk_cols = [ORANGE, PINK, VIOLET, BLUE, LIME]
pkts = "".join(f'<i class="f04-pk" id="f04-k{i}" style="background: {pk_cols[i % 5]};"></i>' for i in range(14))
body = f"""      <div class="f04-head"><span class="f04-kick">How it works</span><span class="f04-title">Explicit pipes. <span class="f04-grad">Local network.</span></span></div>
      <div class="f04-stage" id="f04-stage">
        <div class="f04-board" id="f04-board">
          <div class="f04-track"><div class="f04-fill" id="f04-fill"></div></div>
          {pkts}
          {node_html}
          <div class="f04-labels">
            <div class="f04-pill" id="f04-l0"><b style="background: {PINK};"></b>Video AVC + audio AAC</div>
            <div class="f04-pill" id="f04-l1"><b style="background: {BLUE};"></b>SRT over Wi-Fi</div>
            <div class="f04-pill" id="f04-l2"><b style="background: {LIME};"></b>No relay server</div>
          </div>
        </div>
      </div>
      <div class="f04-status" id="f04-status"><b></b>Live&nbsp;&nbsp;·&nbsp;&nbsp;1920×1080&nbsp;&nbsp;·&nbsp;&nbsp;30 fps&nbsp;&nbsp;·&nbsp;&nbsp;12 Mbps</div>"""
css = f"""
    .f04-head {{ position: absolute; left: 160px; top: 140px; display: flex; flex-direction: column; gap: 16px; }}
    .f04-title {{ display: block; font-size: 80px; font-weight: 800; letter-spacing: -0.045em; line-height: 1; white-space: nowrap; }}
    .f04-title .f04-grad {{ display: inline; }}
    .f04-board {{ position: absolute; left: 175px; top: 380px; width: 1570px; height: 430px; transform-style: preserve-3d; }}
    .f04-track {{ position: absolute; left: 100px; right: 100px; top: 112px; height: 8px; border-radius: 4px; background: rgba(18,18,18,0.08); overflow: hidden; }}
    .f04-fill {{ position: absolute; inset: 0; background: {GRAD}; }}
    .f04-n {{ position: absolute; top: 0; width: 230px; height: 232px; padding: 28px; box-sizing: border-box; }}
    .f04-glow {{ position: absolute; inset: 0; border-radius: 28px; }}
    .f04-ni {{ width: 60px; height: 60px; border-radius: 18px; color: #fff; font-size: 28px; font-weight: 800; display: flex; align-items: center; justify-content: center; }}
    .f04-nm {{ margin-top: 54px; font-size: 34px; font-weight: 800; letter-spacing: -0.035em; white-space: nowrap; }}
    .f04-ns {{ margin-top: 6px; font-size: 21px; font-weight: 500; color: {MUTED}; white-space: nowrap; }}
    .f04-pk {{ position: absolute; left: 100px; top: 106px; width: 20px; height: 20px; border-radius: 50%; }}
    .f04-labels {{ position: absolute; left: 0; right: 0; top: 300px; display: flex; justify-content: center; gap: 24px; }}
    .f04-status {{ position: absolute; left: 50%; top: 880px; width: 640px; margin-left: -320px; height: 72px; border-radius: 9999px; background: {INK}; color: #fff; display: flex; align-items: center; justify-content: center; gap: 14px; font-size: 26px; font-weight: 600; white-space: nowrap; font-variant-numeric: tabular-nums; }}
    .f04-status b {{ display: block; width: 14px; height: 14px; border-radius: 50%; background: {LIME}; }}
"""
js = f"""      // camera: start low over a tilted board, crane up to face-on
      tl.fromTo("#f04-board", {{ rotationX: 62, rotationZ: -12, z: -300, y: 60 }}, {{ rotationX: 16, rotationZ: 0, z: 0, y: 0, duration: 1.3, ease: "power3.inOut" }}, 0);
      tl.to("#f04-board", {{ rotationX: 6, rotationY: -6, duration: D - 1.3, ease: "sine.inOut" }}, 1.3);
      tl.fromTo(".f04-head", {{ opacity: 0, y: 40 }}, {{ opacity: 1, y: 0, duration: 0.45, ease: "expo.out" }}, 0);
      tl.fromTo(".f04-n", {{ opacity: 0, z: 200 }}, {{ opacity: 1, z: 0, duration: 0.5, ease: "expo.out", stagger: 0.06 }}, 0.15);
      tl.fromTo("#f04-fill", {{ scaleX: 0 }}, {{ scaleX: 1, duration: 4 * B, ease: "none", transformOrigin: "0% 50%" }}, 2 * B);
      for (let i = 0; i < 5; i++) {{
        const t = (2 + i) * B;
        tl.fromTo("#f04-n" + i, {{ z: 0 }}, {{ z: 70, duration: 0.3, ease: "back.out(2.5)", immediateRender: false }}, t);
        tl.fromTo("#f04-g" + i, {{ opacity: 0 }}, {{ opacity: 1, duration: 0.15, ease: "power2.out" }}, t);
      }}
      for (let i = 0; i < 14; i++) {{
        const t0 = 6 * B + i * (B / 2);
        if (t0 >= D) break;
        tl.fromTo("#f04-k" + i, {{ x: 0, opacity: 0, z: 80 }}, {{ x: 1350, opacity: 1, duration: 1.1, ease: "power1.inOut" }}, t0);
        tl.to("#f04-k" + i, {{ opacity: 0, duration: 0.1 }}, t0 + 1.0);
      }}
      ["#f04-l0", "#f04-l1", "#f04-l2"].forEach(function (s, i) {{
        tl.fromTo(s, {{ opacity: 0, rotationX: -90, z: 40 }}, {{ opacity: 1, rotationX: 0, z: 40, duration: 0.4, ease: "back.out(1.8)", transformOrigin: "50% 100%" }}, (6 + i) * B);
      }});
      tl.fromTo("#f04-status", {{ opacity: 0, y: 40, scale: 0.9 }}, {{ opacity: 1, y: 0, scale: 1, duration: 0.45, ease: "back.out(1.8)" }}, 10 * B);
"""
frames["04-pipeline"] = frame("04-pipeline", p, 5.625, css, body, js,
                              [(-220, 300, 720, BLUE), (1240, -200, 700, ORANGE), (700, 760, 620, LIME)],
                              [(160, -60), (-140, 120), (-90, -100)], num="04")

# ---------------------------------------------------------------- 05 proof
p = "f05"
body = f"""      <div class="f05-head"><span class="f05-kick">Real OBS · Real phone</span><span class="f05-title">It's <span class="f05-grad">already live.</span></span></div>
      <div class="f05-stage" id="f05-stage">
        <div class="f05-win f05-card" id="f05-win"><div class="f05-bar"><i></i><i></i><i></i><span>OBS Studio</span></div><img src="assets/obs-live.png" alt="OBS Studio showing the OpenStream phone camera live" /></div>
        <div class="f05-shadow" id="f05-sh" style="left: 190px; top: 900px; width: 410px; height: 56px;"></div>
        {phone(p, "f05-ph", 250, 300, 290, 600, "assets/phone-app.jpg")}
        <div class="f05-pill" id="f05-c0" style="left: 1050px; top: 330px;"><b style="background: {LIME};"></b>Live in OBS</div>
        <div class="f05-pill" id="f05-c1" style="left: 1290px; top: 770px;"><b style="background: {VIOLET};"></b>Camera control dock</div>
        <div class="f05-pill" id="f05-c2" style="left: 440px; top: 860px;"><b style="background: {ORANGE};"></b>0.6× · 1× · 3× · 5× lenses</div>
      </div>"""
css = f"""
    .f05-head {{ position: absolute; left: 160px; top: 130px; display: flex; flex-direction: column; gap: 14px; }}
    .f05-title {{ display: block; font-size: 72px; font-weight: 800; letter-spacing: -0.045em; line-height: 1; white-space: nowrap; }}
    .f05-title .f05-grad {{ display: inline; }}
    .f05-win {{ position: absolute; left: 600px; top: 290px; width: 1100px; height: 649px; overflow: hidden; border-radius: 22px; background: #1E1E20; }}
    .f05-bar {{ height: 52px; display: flex; align-items: center; gap: 10px; padding: 0 20px; background: #2A2A2D; }}
    .f05-bar i {{ display: block; width: 14px; height: 14px; border-radius: 50%; background: #FF5F57; }}
    .f05-bar i + i {{ background: #FEBC2E; }}
    .f05-bar i + i + i {{ background: #28C840; }}
    .f05-bar span {{ display: block; margin-left: 14px; font-size: 20px; font-weight: 600; color: rgba(255,255,255,0.7); }}
    .f05-pill {{ position: absolute; }}
    .f05-win img {{ display: block; width: 1100px; height: 597px; }}
"""
js = f"""      // camera: a slow hero orbit across the whole shot
      tl.fromTo("#f05-stage", {{ rotationY: 26, rotationX: 10, z: -220 }}, {{ rotationY: -8, rotationX: 2, z: 120, duration: D, ease: "power2.inOut" }}, 0);
      tl.fromTo(".f05-head", {{ opacity: 0, y: 40 }}, {{ opacity: 1, y: 0, duration: 0.45, ease: "expo.out" }}, 0);
      tl.fromTo("#f05-win", {{ opacity: 0, z: -1200, rotationY: 30 }}, {{ opacity: 1, z: 0, rotationY: 0, duration: 0.8, ease: "expo.out" }}, 0);
      tl.fromTo("#f05-ph", {{ x: -700, z: 400, rotationY: -80, rotationZ: -10 }}, {{ x: 0, z: 120, rotationY: 18, rotationZ: 0, duration: 0.7, ease: "expo.out" }}, 2 * B);
      tl.to("#f05-ph", {{ rotationY: 4, duration: D - 2 * B - 0.7, ease: "sine.inOut" }}, 2 * B + 0.7);
      tl.fromTo("#f05-sh", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.5 }}, 2 * B + 0.2);
      ["#f05-c0", "#f05-c1", "#f05-c2"].forEach(function (s, i) {{
        tl.fromTo(s, {{ opacity: 0, scale: 0.4, z: 170 }}, {{ opacity: 1, scale: 1, z: 170, duration: 0.42, ease: "back.out(2.2)" }}, (4 + 2 * i) * B);
      }});
      // last two beats: the camera pushes toward the live feed
      
"""
frames["05-proof"] = frame("05-proof", p, 5.625, css, body, js,
                           [(1300, -260, 760, VIOLET), (-280, 420, 720, ORANGE), (760, 760, 560, YELLOW)],
                           [(-150, 100), (130, -80), (-60, -90)], num="05")

# ---------------------------------------------------------------- 06 numbers
p = "f06"
stats = [("12", "Mbps", "Default bitrate", ORANGE), ("8–50", "Mbps", "Tune it live from OBS", PINK),
         ("0", "bytes", "Sent to the cloud", VIOLET), ("$0", "", "Free & open source", BLUE)]
cards6 = "".join(
    f'<div class="f06-sc f06-card" id="f06-sc{i}" style="transform: rotateY({i * 90}deg) translateZ(560px);">'
    f'<div class="f06-lab"><b style="background: {c};"></b>{lab}</div>'
    f'<div class="f06-num"><span class="f06-fig f06-grad"{" id=\"f06-count\"" if i == 0 else ""}>{fig}</span><span class="f06-unit">{unit}</span></div></div>'
    for i, (fig, unit, lab, c) in enumerate(stats)
)
body = f"""      <div class="f06-head"><span class="f06-kick">By the numbers</span></div>
      <div class="f06-stage" id="f06-stage">
        <div class="f06-ring" id="f06-ring" data-layout-allow-overlap>{cards6}</div>
      </div>
      <div class="f06-dots">""" + "".join(f'<i id="f06-d{i}"></i>' for i in range(4)) + "</div>"
css = f"""
    .f06-head {{ position: absolute; left: 160px; top: 140px; }}
    .f06-ring {{ position: absolute; left: 960px; top: 520px; width: 0; height: 0; transform-style: preserve-3d; }}
    .f06-sc {{ position: absolute; left: -440px; top: -270px; width: 880px; height: 540px; padding: 60px 70px; box-sizing: border-box; backface-visibility: hidden; display: flex; flex-direction: column; justify-content: space-between; }}
    .f06-lab {{ display: flex; align-items: center; gap: 16px; font-size: 34px; font-weight: 600; white-space: nowrap; }}
    .f06-lab b {{ display: block; width: 18px; height: 18px; border-radius: 50%; }}
    .f06-num {{ display: flex; align-items: baseline; gap: 28px; }}
    .f06-fig {{ display: block; font-size: 270px; font-weight: 800; letter-spacing: -0.06em; line-height: 0.9; white-space: nowrap; font-variant-numeric: tabular-nums; }}
    .f06-unit {{ display: block; font-size: 64px; font-weight: 700; letter-spacing: -0.03em; white-space: nowrap; }}
    .f06-dots {{ position: absolute; left: 0; right: 0; top: 880px; display: flex; justify-content: center; gap: 2px; }}
    .f06-dots i {{ display: block; width: 44px; height: 14px; border-radius: 7px; background: rgba(18,18,18,0.18); }}
"""
js = f"""      tl.fromTo("#f06-stage", {{ rotationX: 12, z: -120 }}, {{ rotationX: 4, z: 0, duration: D, ease: "power2.out" }}, 0);
      tl.fromTo(".f06-head", {{ opacity: 0, y: 30 }}, {{ opacity: 1, y: 0, duration: 0.4, ease: "expo.out" }}, 0);
      tl.fromTo("#f06-ring", {{ z: -560, rotationY: 30, opacity: 0 }}, {{ z: -560, rotationY: 0, opacity: 1, duration: 0.45, ease: "expo.out" }}, 0);
      const counter = {{ v: 0 }};
      const el = document.getElementById("f06-count");
      tl.fromTo(counter, {{ v: 0 }}, {{ v: 12, duration: 0.5, ease: "power2.out", onUpdate: function () {{ el.textContent = String(Math.round(counter.v)); }} }}, 0);
      // only the facing card (and the one turning in) is visible
      for (let i = 1; i < 4; i++) {{
        tl.fromTo("#f06-sc" + i, {{ opacity: 0 }}, {{ opacity: 1, duration: 0.2, ease: "power1.out" }}, i * 2 * B - 0.3);
      }}
      for (let i = 0; i < 3; i++) {{
        tl.to("#f06-sc" + i, {{ opacity: 0, duration: 0.15, ease: "power1.in" }}, (i + 1) * 2 * B - 0.05);
      }}
      // the carousel turns a quarter on every second beat, landing on the beat
      for (let i = 1; i < 4; i++) {{
        tl.to("#f06-ring", {{ rotationY: -90 * i, duration: 0.5, ease: "expo.inOut" }}, i * 2 * B - 0.3);
      }}
      for (let i = 0; i < 4; i++) {{
        tl.fromTo("#f06-d" + i, {{ scaleX: 0.32, backgroundColor: "rgba(18,18,18,0.18)" }}, {{ scaleX: 1, backgroundColor: "{ORANGE}", duration: 0.3, ease: "power2.out" }}, i * 2 * B);
        if (i < 3) tl.to("#f06-d" + i, {{ scaleX: 0.32, backgroundColor: "rgba(18,18,18,0.18)", duration: 0.3, ease: "power2.out" }}, (i + 1) * 2 * B);
      }}
"""
frames["06-numbers"] = frame("06-numbers", p, 3.75, css, body, js,
                             [(-240, -220, 760, PINK), (1300, 420, 760, BLUE), (500, 760, 560, ORANGE)],
                             [(140, 90), (-150, -70), (80, -80)], num="06")

# ---------------------------------------------------------------- 07 cta
p = "f07"
GH = ('<svg class="f07-gh" viewBox="0 0 16 16" aria-hidden="true"><path fill="#fff" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>')
tiles = [(170, 210, 120, ORANGE, -300), (1650, 170, 100, VIOLET, -500), (240, 760, 90, BLUE, -200),
         (1600, 740, 130, PINK, -350), (2037, 189, 70, LIME, -650), (-170, 797, 80, YELLOW, -450)]
tile_html = "".join(
    f'<i class="f07-tile" id="f07-t{i}" style="left: {x}px; top: {y}px; width: {s}px; height: {s}px; background: {c};"></i>'
    for i, (x, y, s, c, z) in enumerate(tiles)
)
body = f"""      <div class="f07-stage" id="f07-stage">
        {tile_html}
        <div class="f07-brand" id="f07-brand"><div class="f07-brandtxt">OpenStream</div></div>
        {app_icon(p, "f07-icon", 714, 196, 96, 8)}
        <div class="f07-line"><span class="f07-w" id="f07-a">Your phone.</span><span class="f07-w f07-grad" id="f07-b">Your OBS.</span></div>
        <div class="f07-btnwrap"><div class="f07-btn" id="f07-btn">{GH}<span>Star on GitHub</span></div></div>
        <div class="f07-url" id="f07-url">github.com/YashasVM/OpenStream</div>
        <div class="f07-foot" id="f07-foot"><span>Free</span><i></i><span>Open source</span><i></i><span>Local-only</span></div>
      </div>"""
css = f"""
    .f07-tile {{ position: absolute; display: block; border-radius: 28%; box-shadow: 0 20px 50px rgba(60,40,10,0.18); }}
    .f07-brand {{ position: absolute; left: 836px; top: 210px; height: 70px; display: flex; align-items: center; }}
    .f07-brandtxt {{ font-size: 64px; font-weight: 800; letter-spacing: -0.05em; white-space: nowrap; }}
    .f07-line {{ position: absolute; left: 0; right: 0; top: 340px; display: flex; justify-content: center; gap: 36px; }}
    .f07-w {{ display: block; font-size: 136px; font-weight: 800; letter-spacing: -0.055em; line-height: 1.05; white-space: nowrap; }}
    .f07-btnwrap {{ position: absolute; left: 0; right: 0; top: 560px; display: flex; justify-content: center; }}
    .f07-btn {{ display: flex; align-items: center; gap: 22px; height: 116px; padding: 0 56px; border-radius: 9999px; background: {INK}; color: #fff; font-size: 46px; font-weight: 700; letter-spacing: -0.02em; white-space: nowrap; box-shadow: 0 24px 60px rgba(18,18,18,0.28); }}
    .f07-gh {{ display: block; width: 54px; height: 54px; }}
    .f07-url {{ position: absolute; left: 0; right: 0; top: 712px; text-align: center; font-size: 34px; font-weight: 600; color: {INK}; white-space: nowrap; }}
    .f07-foot {{ position: absolute; left: 0; right: 0; top: 790px; display: flex; justify-content: center; align-items: center; gap: 22px; font-size: 26px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: {INK}; }}
    .f07-foot span {{ display: block; white-space: nowrap; }}
    .f07-foot i {{ display: block; width: 8px; height: 8px; border-radius: 50%; background: {ORANGE}; }}
"""
tile_js = "".join(
    f'tl.fromTo("#f07-t{i}", {{ z: {z - 600}, rotationX: {40 + i * 13}, rotationY: {-30 + i * 17}, opacity: 0 }}, {{ z: {z}, rotationX: {i * 9}, rotationY: {-10 + i * 6}, opacity: 1, duration: 1.2, ease: "expo.out" }}, {0.05 * i});\n      '
    f'tl.to("#f07-t{i}", {{ rotationX: {i * 9 + 25}, rotationY: {-10 + i * 6 + 30}, y: {-20 - i * 6}, duration: D - 1.2, ease: "sine.inOut" }}, {0.05 * i + 1.2});\n      '
    for i, (x, y, s, c, z) in enumerate(tiles)
)
js = f"""      tl.fromTo("#f07-stage", {{ z: -380, rotationX: 10 }}, {{ z: 0, rotationX: 0, duration: 1.6, ease: "power3.out" }}, 0);
      tl.to("#f07-stage", {{ rotationY: -4, duration: D - 1.6, ease: "sine.inOut" }}, 1.6);
      {tile_js}
      tl.fromTo("#f07-a", {{ opacity: 0, z: 600, rotationX: -30 }}, {{ opacity: 1, z: 0, rotationX: 0, duration: 0.45, ease: "expo.out" }}, 0);
      tl.fromTo("#f07-b", {{ opacity: 0, z: 600, rotationX: -30 }}, {{ opacity: 1, z: 0, rotationX: 0, duration: 0.45, ease: "expo.out" }}, B);
      tl.fromTo("#f07-icon", {{ rotationY: -200, opacity: 0 }}, {{ rotationY: 0, opacity: 1, duration: 0.7, ease: "expo.out" }}, 0.05);
      tl.fromTo("#f07-brand", {{ opacity: 0, x: -30 }}, {{ opacity: 1, x: 0, duration: 0.45, ease: "expo.out" }}, 0.15);
      tl.fromTo("#f07-btn", {{ opacity: 0, rotationX: -90, z: 100 }}, {{ opacity: 1, rotationX: 0, z: 0, duration: 0.55, ease: "back.out(1.6)", transformOrigin: "50% 100%" }}, 2 * B);
      tl.fromTo("#f07-url", {{ opacity: 0, y: 20 }}, {{ opacity: 1, y: 0, duration: 0.4, ease: "expo.out" }}, 2 * B + 0.15);
      tl.fromTo("#f07-foot", {{ opacity: 0, y: 20 }}, {{ opacity: 1, y: 0, duration: 0.4, ease: "expo.out" }}, 3 * B);
      // final hit at beat 4 (28.125s): the button punches
      tl.fromTo("#f07-btn", {{ scale: 1.1 }}, {{ scale: 1, duration: 0.5, ease: "back.out(3)", immediateRender: false }}, 4 * B);
"""
frames["07-cta"] = frame("07-cta", p, 3.75, css, body, js,
                         [(-200, 300, 760, ORANGE), (1250, -260, 760, PINK), (820, 700, 640, VIOLET)],
                         [(130, -90), (-120, 110), (-90, -70)], num="07")

for fid, html in frames.items():
    (OUT / f"{fid}.html").write_text(html)
    print("wrote", fid)
