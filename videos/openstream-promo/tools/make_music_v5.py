#!/usr/bin/env python3
"""OpenStream v5 bed: calm, voiceover-friendly, 100 BPM, 45.6s.

0-13s    warm pads + sparse plucks (quiet hook)
13-31s   soft kick + shaker under the demo, bass pulse
31-41.6s lift: fuller kick, claps, octave plucks
41.6-end drums out, pad + bell ring out on the logo
Output: assets/bgm/openstream-v5-bed.wav. Deterministic.
"""
import wave
from pathlib import Path

import numpy as np

SR = 48000
BEAT = 60 / 100
TOTAL = 45.6
N = int(TOTAL * SR)
rng = np.random.default_rng(5)
L = np.zeros(N)
R = np.zeros(N)


def tt(n):
    return np.arange(n) / SR


def place(sig, at, pan=0.0, g=1.0):
    i = int(at * SR)
    if i >= N:
        return
    s = sig[: N - i] * g
    L[i:i + len(s)] += s * (1 - max(pan, 0))
    R[i:i + len(s)] += s * (1 + min(pan, 0))


def midi(m):
    return 440 * 2 ** ((m - 69) / 12)


def lp(x, a):
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc += a * (v - acc)
        y[i] = acc
    return y


def pad(notes, dur, g=0.05):
    n = int(dur * SR)
    t = tt(n)
    s = sum(np.sin(2 * np.pi * midi(m) * t * d) for m in notes for d in (0.997, 1.003))
    env = np.minimum(1, t / 1.2) * np.minimum(1, (dur - t) / 1.2)
    return s * env * g / len(notes)


def pluck(m, g=0.12, dur=0.9):
    n = int(dur * SR)
    t = tt(n)
    f = midi(m)
    return (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t)) * np.exp(-t * 6) * g


def kick(g=0.5):
    n = int(0.35 * SR)
    t = tt(n)
    f = 45 + 90 * np.exp(-t * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9) * g


def noise_hit(dur, decay, g, a=0.5):
    n = int(dur * SR)
    t = tt(n)
    x = rng.standard_normal(n)
    return (x - lp(x, a)) * np.exp(-t * decay) * g


PROG = [[57, 60, 64], [53, 57, 60], [48, 55, 64], [55, 59, 62]]
BASS = [45, 41, 48, 43]
BAR = 4 * BEAT
nbars = int(TOTAL / BAR) + 1
shaker = noise_hit(0.08, 60, 0.05, 0.9)
clap = noise_hit(0.2, 22, 0.12, 0.3)
for b in range(nbars):
    t0 = b * BAR
    ch = PROG[b % 4]
    end_pad = t0 >= 41.6 - BAR
    place(pad(ch + [ch[0] + 12], BAR + 1.2, 0.07 if t0 < 31 else 0.09), t0)
    if t0 >= 41.6:
        continue
    lift = t0 >= 31.0
    for k, step in enumerate((0, 3, 6) if t0 < 13 else (0, 2, 3, 5, 6, 7)):
        note = ch[k % 3] + 12 + (12 if lift and k % 2 else 0)
        place(pluck(note, 0.07 if t0 < 13 else 0.08), t0 + step * BEAT / 2, pan=0.3 if k % 2 else -0.3)
    if t0 >= 13.0:
        for q in range(4):
            place(kick(0.42 if lift else 0.3), t0 + q * BEAT)
            place(np.sin(2 * np.pi * midi(BASS[b % 4]) * tt(int(BEAT * SR * 0.9))) * np.exp(-tt(int(BEAT * SR * 0.9)) * 3) * 0.12, t0 + q * BEAT)
        for e in range(8):
            place(shaker, t0 + e * BEAT / 2 + BEAT / 4, pan=0.4)
        if lift:
            place(clap, t0 + BEAT)
            place(clap, t0 + 3 * BEAT)
# end bell
for k, m in enumerate((69, 76, 81)):
    place(pluck(m, 0.1, 3.0), 41.8 + k * 0.12)

mix = np.stack([L, R], 1)
fade = int(1.5 * SR)
mix[-fade:] *= np.linspace(1, 0, fade)[:, None]
mix[: int(0.6 * SR)] *= np.linspace(0, 1, int(0.6 * SR))[:, None]
mix /= max(1e-9, np.abs(mix).max()) / 0.8
out = Path(__file__).resolve().parent.parent / "assets" / "bgm" / "openstream-v5-bed.wav"
with wave.open(str(out), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print("wrote", out)
