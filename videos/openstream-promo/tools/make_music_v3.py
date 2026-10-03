#!/usr/bin/env python3
"""OpenStream 2 promo track (v3): 120 BPM, 20 bars = exactly 40.0s.

Starts quiet and grows:
  bars 0-3   (0-8s)    pads + sparse echoing plucks, no drums
  bars 4-7   (8-16s)   filtered kick fades in, hats, filter opens, snare roll + riser
  bars 8-15  (16-32s)  the drop: kick, clap, bass, plucks, chords
  bars 16-17 (32-36s)  drop continues with a lifted top line
  bars 18-19 (36-40s)  final hit, pad ring-out
Output: assets/bgm/openstream2-120bpm.wav (48 kHz, 16-bit stereo). Deterministic.
"""
import wave
from pathlib import Path

import numpy as np

SR = 48000
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
TOTAL = 20 * BAR
N = int(round(TOTAL * SR))
rng = np.random.default_rng(2)

L = np.zeros(N)
R = np.zeros(N)
mus_L = np.zeros(N)
mus_R = np.zeros(N)
kick_times = []


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


def saw(freq, dur, det=0.0, max_h=8000):
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


# ---------- sounds ----------
def kick():
    n = int(0.5 * SR)
    t = tt(n)
    f = 44 + 100 * np.exp(-t / 0.04)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.26)
    click = filt(rng.standard_normal(n), lo=2500) * np.exp(-t / 0.003) * 0.2
    return np.tanh((body + click) * 1.4)


def clap():
    n = int(0.4 * SR)
    t = tt(n)
    noise = filt(rng.standard_normal(n), lo=1000, hi=7000)
    e = sum(np.where(t >= o, np.exp(-(t - o) / 0.012), 0) for o in (0, 0.01, 0.02)) + np.exp(-t / 0.14) * 0.5
    return noise * e * 0.4


def hat(open_=False):
    n = int((0.25 if open_ else 0.06) * SR)
    t = tt(n)
    return filt(rng.standard_normal(n), lo=8000) * np.exp(-t / (0.08 if open_ else 0.014)) * 0.16


def snare():
    n = int(0.2 * SR)
    t = tt(n)
    return (filt(rng.standard_normal(n), lo=1800, hi=9000) * np.exp(-t / 0.05) + np.sin(2 * np.pi * 200 * t) * np.exp(-t / 0.03) * 0.4) * 0.3


def impact(g=1.0):
    n = int(3.5 * SR)
    t = tt(n)
    f = 32 + 70 * np.exp(-t / 0.1)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.9)
    air = filt(rng.standard_normal(n), lo=2500) * np.exp(-t / 0.8) * 0.25
    return np.tanh(boom * 1.3 + air) * g


def pad(chord, dur, cutoff, gain):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in chord:
        f = midi(m)
        l += saw(f, dur, -0.007, 4000) + saw(f, dur, 0.003, 4000)
        r += saw(f, dur, 0.007, 4000) + saw(f, dur, -0.004, 4000)
    e = adsr(n, 0.6, 1.5, 0.85, 0.6)
    return filt(l, hi=cutoff) * e * gain, filt(r, hi=cutoff) * e * gain


def pluck(m, gain=0.09, dur=0.5):
    n = int(dur * SR)
    t = tt(n)
    f = midi(m)
    s = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * 2 * f * t) + 0.12 * np.sin(2 * np.pi * 3 * f * t)
    return s * np.exp(-t / 0.12) * gain


def bass(m, dur, gain=0.3, cutoff=700):
    f = midi(m)
    n = int(dur * SR)
    s = saw(f, dur, 0, 3000) * 0.6 + np.sin(2 * np.pi * f * tt(n))
    return filt(s, hi=cutoff) * adsr(n, 0.004, 0.15, 0.7, 0.03) * gain


def chord_stab(ch, dur, cutoff, gain):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in ch:
        l += saw(midi(m), dur, -0.005, 6000)
        r += saw(midi(m), dur, 0.005, 6000)
    e = adsr(n, 0.004, 0.12, 0.2, 0.05)
    return filt(l, hi=cutoff) * e * gain, filt(r, hi=cutoff) * e * gain


KICK, CLAP, HC, HO, SN = kick(), clap(), hat(), hat(True), snare()

# Am9 – Fmaj7 – Cmaj7 – G6 (one chord per bar)
PROG = [
    (45, [57, 60, 64, 67, 71]),
    (41, [53, 57, 60, 64, 69]),
    (48, [55, 59, 60, 64, 67]),
    (43, [55, 59, 62, 64, 67]),
]
ARP = [[69, 72, 76, 79], [69, 72, 76, 77], [67, 71, 72, 76], [67, 71, 74, 76]]


def bs(b):
    return b * BAR


def echo_pluck(at, m, gain):
    """pluck with two dotted-eighth echoes, panned"""
    p = pluck(m, gain)
    place(mus_L, mus_R, at, p, p)
    place(mus_L, mus_R, at + 0.75 * BEAT, p * 0.45, p * 0.2)
    place(mus_L, mus_R, at + 1.5 * BEAT, p * 0.12, p * 0.25)


# pads: whole piece, filter opening over time
for b in range(18):
    root, ch = PROG[b % 4]
    cutoff = 900 + 2600 * min(1, b / 8)
    gain = 0.075 if b < 4 else 0.065 if b < 8 else 0.04
    pl, pr = pad(ch, BAR + 0.3, cutoff, gain)
    place(mus_L, mus_R, bs(b), pl, pr)
    if b < 8:  # sub swell under the intro
        n = int(BAR * SR)
        sub = np.sin(2 * np.pi * midi(root - 12) * tt(n)) * adsr(n, 0.5, 1.0, 0.8, 0.4) * (0.08 if b < 4 else 0.12)
        place(mus_L, mus_R, bs(b), sub)

# intro plucks: sparse in bars 0-3, denser in 4-7
for b in range(0, 8):
    notes = ARP[b % 4]
    steps = (0, 3, 6) if b < 2 else (0, 2, 3, 6) if b < 4 else (0, 1, 2, 3, 4, 5, 6, 7)
    for s in steps:
        echo_pluck(bs(b) + s * BEAT / 2, notes[s % 4], 0.11 if b < 4 else 0.08)

# build: kick fades in (filtered), hats, snare roll, riser
for b in range(4, 8):
    k_gain = 0.12 + 0.11 * (b - 4)
    cutoff = 300 + 500 * (b - 4)
    for k in range(4):
        at = bs(b) + k * BEAT
        place(L, R, at, filt(KICK, hi=cutoff) * k_gain)
        kick_times.append((at, 0.15 + 0.08 * (b - 4)))
        if b >= 5:
            place(L, R, at + BEAT / 2, HC * (0.6 + 0.15 * (b - 5)))
roll_start, roll_end = bs(7), bs(8) - BEAT / 2
t = roll_start
while t < roll_end:
    frac = (t - roll_start) / (roll_end - roll_start)
    place(L, R, t, SN * (0.4 + 1.1 * frac))
    t += BEAT / 2 if frac < 0.3 else BEAT / 4 if frac < 0.7 else BEAT / 8
n = int((bs(8) - bs(6)) * SR)
x = np.linspace(0, 1, n)
noise = rng.standard_normal(n)
riser = np.zeros(n)
for i in range(0, n, 2048):
    fc = 300 + 8000 * x[i] ** 2
    riser[i:i + 2048] = filt(noise[i:i + 2048], lo=fc * 0.5, hi=fc)
riser *= x ** 1.6 * 0.5
riser[-int(BEAT / 2 * SR):] *= np.linspace(1, 0, int(BEAT / 2 * SR))  # gap before the drop
place(L, R, bs(6), riser, riser * 0.9)

# the drop: bars 8-17
for b in range(8, 18):
    root, ch = PROG[b % 4]
    for k in range(4):
        at = bs(b) + k * BEAT
        place(L, R, at, KICK * 0.95)
        kick_times.append((at, 0.6))
        place(L, R, at + BEAT / 2, HO * 0.9, HO * 1.1)
        place(L, R, at + BEAT / 4, HC * 0.5)
        place(L, R, at + 3 * BEAT / 4, HC * 0.5)
        if k in (1, 3):
            place(L, R, at, CLAP)
    for s in (1, 2, 3, 5, 6, 7):  # offbeat-ish bass in 8ths
        place(mus_L, mus_R, bs(b) + s * BEAT / 2, *(2 * [bass(root, BEAT / 2 * 0.85)]))
    for off in (0, 1.5 * BEAT, 3 * BEAT):
        sl, sr = chord_stab(ch, 0.3, 3200, 0.05)
        place(mus_L, mus_R, bs(b) + off, sl, sr)
    notes = ARP[b % 4] if b < 16 else [n + 12 for n in ARP[b % 4]]
    for s in range(8):
        echo_pluck(bs(b) + s * BEAT / 2, notes[(s * 3) % 4], 0.05)
    if b % 4 == 3:
        for s in range(4):
            place(L, R, bs(b) + 3 * BEAT + s * BEAT / 4, SN * (0.3 + 0.12 * s))

place(L, R, bs(8), impact(0.8))

# ending: final hit at bar 18 (36s), ring out
place(L, R, bs(18), KICK * 1.1)
place(L, R, bs(18), impact(0.9))
kick_times.append((bs(18), 0.6))
pl, pr = pad([45, 57, 64, 67, 71, 76], 2 * BAR, 3800, 0.075)
place(mus_L, mus_R, bs(18), pl, pr)
sl, sr = chord_stab([57, 60, 64, 67, 71], 1.4, 4000, 0.09)
place(mus_L, mus_R, bs(18), sl, sr)
for i, m in enumerate([76, 79, 83, 88]):
    echo_pluck(bs(18) + BEAT + i * BEAT / 2, m, 0.05)

# sidechain on the music bus
duck = np.ones(N)
for at, depth in kick_times:
    i = int(round(at * SR))
    n = min(int(0.32 * SR), N - i)
    if n > 0:
        duck[i:i + n] = np.minimum(duck[i:i + n], 1 - depth * np.exp(-tt(n) / 0.1))
L += mus_L * duck
R += mus_R * duck

# fade in the very first 0.3s and out over the last 2.5s
fade = np.ones(N)
fade[:int(0.3 * SR)] = np.linspace(0, 1, int(0.3 * SR))
fo = int(2.5 * SR)
fade[-fo:] = np.linspace(1, 0, fo) ** 1.4
mix = np.stack([L * fade, R * fade], axis=1)
mix = np.tanh(mix * 1.1)
mix *= 10 ** (-1 / 20) / np.max(np.abs(mix))

out = Path(__file__).resolve().parent.parent / "assets" / "bgm" / "openstream2-120bpm.wav"
out.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(out), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print(f"wrote {out} ({TOTAL:.1f}s)")
