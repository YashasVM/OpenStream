#!/usr/bin/env python3
"""OpenStream 2 promo track (v4): 128 BPM, 20 bars = exactly 37.5s.

Energy steps up in gears (not one smooth ramp):
  bars 0-3   (0-7.5s)      medium: kick, offbeat hats, bass riff, pluck hook
  bars 4-5   (7.5-11.25s)  step up: claps + open hats, snare roll and riser, gap
  bars 6-13  (11.25-26.25) drop: full drums, driving bass, stabs, arp
  bars 14-17 (26.25-33.75) peak: 16th hats, perc, octave-up arp, fills every bar
  bars 18-19 (33.75-37.5)  slow-down: impact, drums out, pads + slow plucks ring out
Output: assets/bgm/openstream2-v4-128bpm.wav. Deterministic.
"""
import wave
from pathlib import Path

import numpy as np

SR = 48000
BPM = 128
BEAT = 60 / BPM
BAR = 4 * BEAT
TOTAL = 20 * BAR
N = int(round(TOTAL * SR))
rng = np.random.default_rng(4)

L = np.zeros(N)
R = np.zeros(N)
mus_L = np.zeros(N)
mus_R = np.zeros(N)
kicks = []


def tt(n):
    return np.arange(n) / SR


def place(bl, br, at, sl, sr=None):
    i = int(round(at * SR))
    if i >= N or i < 0:
        return
    sr = sl if sr is None else sr
    n = min(len(sl), N - i)
    bl[i:i + n] += sl[:n]
    br[i:i + n] += sr[:n]


def filt(x, lo=None, hi=None, slope=1.5):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = np.ones_like(f)
    if hi is not None:
        g *= 1 / (1 + (f / hi) ** (2 * slope))
    if lo is not None:
        g *= 1 / (1 + (lo / np.maximum(f, 1e-3)) ** (2 * slope))
    return np.fft.irfft(X * g, len(x))


def saw(freq, dur, det=0.0, max_h=9000):
    t = tt(int(dur * SR))
    f = freq * (1 + det)
    out = np.zeros_like(t)
    for k in range(1, int(max_h / f) + 1):
        out += ((-1) ** (k + 1)) * np.sin(2 * np.pi * k * f * t) / k
    return out * (2 / np.pi)


def midi(m):
    return 440 * 2 ** ((m - 69) / 12)


def adsr(n, a, d, s, r):
    t = tt(n)
    dur = n / SR
    e = np.where(t < a, t / max(a, 1e-6), s + (1 - s) * np.exp(-(t - a) / max(d, 1e-6)))
    return np.where(t > dur - r, e * np.clip((dur - t) / max(r, 1e-6), 0, 1), e)


# ---------- drums (punchier than v3) ----------
def mk_kick():
    n = int(0.42 * SR)
    t = tt(n)
    f = 46 + 130 * np.exp(-t / 0.03)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.2)
    click = filt(rng.standard_normal(n), lo=3000) * np.exp(-t / 0.0025) * 0.45
    return np.tanh((body + click) * 2.2) * 0.9


def mk_clap():
    n = int(0.3 * SR)
    t = tt(n)
    noise = filt(rng.standard_normal(n), lo=1200, hi=8000)
    e = sum(np.where(t >= o, np.exp(-(t - o) / 0.01), 0) for o in (0, 0.008, 0.017)) + np.exp(-t / 0.09) * 0.6
    return np.tanh(noise * e * 1.4) * 0.5


def mk_hat(open_=False):
    n = int((0.2 if open_ else 0.045) * SR)
    t = tt(n)
    return filt(rng.standard_normal(n), lo=8500) * np.exp(-t / (0.06 if open_ else 0.01)) * 0.22


def mk_snare():
    n = int(0.18 * SR)
    t = tt(n)
    return (filt(rng.standard_normal(n), lo=1600, hi=10000) * np.exp(-t / 0.045)
            + np.sin(2 * np.pi * 210 * t) * np.exp(-t / 0.025) * 0.5) * 0.42


def mk_perc():
    n = int(0.12 * SR)
    t = tt(n)
    return np.sin(2 * np.pi * (520 + 300 * np.exp(-t / 0.01)) * t) * np.exp(-t / 0.03) * 0.12


def mk_impact(g=1.0):
    n = int(3.6 * SR)
    t = tt(n)
    f = 30 + 90 * np.exp(-t / 0.08)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.9)
    air = filt(rng.standard_normal(n), lo=2500) * np.exp(-t / 0.7) * 0.3
    return np.tanh(boom * 1.5 + air) * g


KICK, CLAP, HC, HO, SN, PERC = mk_kick(), mk_clap(), mk_hat(), mk_hat(True), mk_snare(), mk_perc()

# Am – F – C – G
PROG = [(45, [57, 60, 64, 69]), (41, [53, 57, 60, 65]), (48, [55, 60, 64, 67]), (43, [55, 59, 62, 67])]
HOOK = [[81, 76, 79, 76, 72, 76, 79, 81], [81, 77, 79, 77, 72, 77, 79, 84],
        [79, 76, 79, 76, 72, 76, 79, 84], [79, 74, 79, 74, 71, 74, 79, 83]]


def pluck(m, gain, dur=0.3, bright=4500):
    n = int(dur * SR)
    t = tt(n)
    f = midi(m)
    s = saw(f, dur, 0, 7000) * 0.6 + np.sin(2 * np.pi * f * t)
    return filt(s, hi=bright) * np.exp(-t / 0.07) * gain


def bass(m, dur, gain=0.32, cutoff=900):
    f = midi(m)
    n = int(dur * SR)
    s = saw(f, dur, 0, 3000) + 0.8 * np.sin(2 * np.pi * f * tt(n))
    return np.tanh(filt(s, hi=cutoff) * 1.6) * adsr(n, 0.002, 0.1, 0.6, 0.02) * gain


def stab(ch, dur, cutoff, gain):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in ch:
        l += saw(midi(m + 12), dur, -0.006) + saw(midi(m + 12), dur, 0.004)
        r += saw(midi(m + 12), dur, 0.006) + saw(midi(m + 12), dur, -0.003)
    e = adsr(n, 0.002, 0.08, 0.15, 0.03)
    return filt(l, hi=cutoff) * e * gain, filt(r, hi=cutoff) * e * gain


def pad(ch, dur, cutoff, gain):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in ch:
        l += saw(midi(m), dur, -0.008, 4000) + saw(midi(m - 12), dur, 0.004, 3000)
        r += saw(midi(m), dur, 0.008, 4000) + saw(midi(m - 12), dur, -0.004, 3000)
    e = adsr(n, 0.4, 1.2, 0.85, 0.8)
    return filt(l, hi=cutoff) * e * gain, filt(r, hi=cutoff) * e * gain


def bs(b):
    return b * BAR


def kick(at, g=1.0, depth=0.6):
    place(L, R, at, KICK * g)
    kicks.append((at, depth))


# ---------- A: medium groove (bars 0-3) ----------
for b in range(0, 4):
    root, ch = PROG[b % 4]
    for k in range(4):
        at = bs(b) + k * BEAT
        kick(at, 0.55, 0.35)
        place(L, R, at + BEAT / 2, HC * 1.1, HC * 0.9)
    for s in (0, 3, 6, 7):  # syncopated bass riff in 8ths
        place(mus_L, mus_R, bs(b) + s * BEAT / 2, *(2 * [bass(root, BEAT / 2 * 0.8, 0.28, 650)]))
    for s, m in enumerate(HOOK[b % 4]):
        if s % 2 == 0 or b >= 2:
            p = pluck(m, 0.075, bright=3000 + 600 * b)
            place(mus_L, mus_R, bs(b) + s * BEAT / 2, p * 1.1, p * 0.9)
    pl, pr = pad(ch, BAR, 1600, 0.025)
    place(mus_L, mus_R, bs(b), pl, pr)

# ---------- B: step up (bars 4-5) ----------
for b in (4, 5):
    root, ch = PROG[b % 4]
    for k in range(4):
        at = bs(b) + k * BEAT
        if not (b == 5 and k == 3):
            kick(at, 0.75, 0.5)
        place(L, R, at + BEAT / 2, HO * 0.8, HO)
        if k in (1, 3) and b == 4:
            place(L, R, at, CLAP)
    for s in range(8):
        if s % 2 or b == 4:
            place(mus_L, mus_R, bs(b) + s * BEAT / 2, *(2 * [bass(root, BEAT / 2 * 0.8, 0.3, 800)]))
    for s, m in enumerate(HOOK[b % 4]):
        p = pluck(m, 0.08, bright=5000)
        place(mus_L, mus_R, bs(b) + s * BEAT / 2, p, p)
# snare roll across bar 5 + riser, gap on the last half-beat
t, end = bs(5), bs(6) - BEAT / 2
while t < end:
    fr = (t - bs(5)) / (end - bs(5))
    place(L, R, t, SN * (0.5 + 1.2 * fr))
    t += BEAT / 2 if fr < 0.25 else BEAT / 4 if fr < 0.6 else BEAT / 8
n = int((bs(6) - bs(4)) * SR)
x = np.linspace(0, 1, n)
noise = rng.standard_normal(n)
riser = np.zeros(n)
for i in range(0, n, 2048):
    fc = 500 + 9000 * x[i] ** 2
    riser[i:i + 2048] = filt(noise[i:i + 2048], lo=fc * 0.5, hi=fc)
riser *= x ** 1.4 * 0.6
g = int(BEAT / 2 * SR)
riser[-g:] *= np.linspace(1, 0, g) ** 3
place(L, R, bs(4), riser, riser * 0.9)


# ---------- C + D: drop (bars 6-13) and peak (bars 14-17) ----------
def groove(b, peak):
    root, ch = PROG[b % 4]
    for k in range(4):
        at = bs(b) + k * BEAT
        kick(at, 1.0, 0.65)
        place(L, R, at + BEAT / 2, HO * 0.9, HO * 1.1)
        if k in (1, 3):
            place(L, R, at, CLAP * (1.1 if peak else 1.0))
        hats = (1, 2, 3) if peak else (1, 3)
        for q in hats:
            place(L, R, at + q * BEAT / 4, HC * (0.7 if q != 2 else 0.4))
        if peak:
            place(L, R, at + 3 * BEAT / 4, PERC, PERC * 0.5)
    for s in range(16):  # rolling 16th bass, skip downbeats for pump
        if s % 4:
            place(mus_L, mus_R, bs(b) + s * BEAT / 4, *(2 * [bass(root, BEAT / 4 * 0.85, 0.32, 1200)]))
    for off in (0, 1.5 * BEAT, 2.5 * BEAT):
        sl, sr = stab(ch, 0.22, 4500, 0.11)
        place(mus_L, mus_R, bs(b) + off, sl, sr)
    hook = HOOK[b % 4]
    step = BEAT / 4 if peak else BEAT / 2  # the arp doubles its rate in the peak
    for s in range(16 if peak else 8):
        m = hook[(s // (2 if peak else 1)) % 8] + (12 if peak and s % 2 else 0)
        p = pluck(m, 0.06 if peak else 0.07, dur=0.2, bright=6000)
        place(mus_L, mus_R, bs(b) + s * step, p * 1.1, p * 0.9)
    fill = peak or b % 2 == 1
    if fill:
        for s in range(4):
            place(L, R, bs(b) + 3 * BEAT + s * BEAT / 4, SN * (0.35 + 0.15 * s))


for b in range(6, 14):
    groove(b, False)
for b in range(14, 18):
    groove(b, True)
place(L, R, bs(6), mk_impact(0.85))
place(L, R, bs(14), mk_impact(0.45))
# a last roll into the slow-down
t = bs(17) + 2 * BEAT
while t < bs(18) - BEAT / 4:
    place(L, R, t, SN * 0.9)
    t += BEAT / 8

# ---------- E: slow-down (bars 18-19) ----------
kick(bs(18), 1.15, 0.7)
place(L, R, bs(18), mk_impact(1.0))
pl, pr = pad([57, 60, 64, 69, 72], 2 * BAR + 0.2, 3500, 0.07)
place(mus_L, mus_R, bs(18), pl, pr)
sl, sr = stab([57, 60, 64, 69], 1.2, 4000, 0.12)
place(mus_L, mus_R, bs(18), sl, sr)
for i, m in enumerate([81, 76, 79, 84, 88]):  # slow echoing plucks, half-time
    at = bs(18) + BEAT + i * BEAT
    p = pluck(m, 0.07, dur=0.6, bright=4000)
    place(mus_L, mus_R, at, p, p * 0.6)
    place(mus_L, mus_R, at + 0.75 * BEAT, p * 0.4, p * 0.6)
place(mus_L, mus_R, bs(19), *(2 * [bass(45, BAR, 0.25, 500)]))

# ---------- sidechain + master ----------
duck = np.ones(N)
for at, depth in kicks:
    i = int(round(at * SR))
    n = min(int(0.28 * SR), N - i)
    if n > 0:
        duck[i:i + n] = np.minimum(duck[i:i + n], 1 - depth * np.exp(-tt(n) / 0.085))
L += mus_L * duck
R += mus_R * duck
fade = np.ones(N)
fo = int(2.2 * SR)
fade[-fo:] = np.linspace(1, 0, fo) ** 1.3
mix = np.stack([L * fade, R * fade], axis=1)
mix = np.tanh(mix * 1.2)
mix *= 10 ** (-1 / 20) / np.max(np.abs(mix))

out = Path(__file__).resolve().parent.parent / "assets" / "bgm" / "openstream2-v4-128bpm.wav"
with wave.open(str(out), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print(f"wrote {out} ({TOTAL:.2f}s)")
