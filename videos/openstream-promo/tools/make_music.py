#!/usr/bin/env python3
"""Synthesize the OpenStream promo track: 128 BPM, 16 bars = exactly 30.0s.

Deterministic (seeded noise). Sections follow STORYBOARD.md frame boundaries:
  bars 0-1   F1 hook      driving intro, stabs on the word slams
  bars 2-3   F2 build     snare roll + riser, gap before the drop
  bars 4-13  F3-F6 drop   full four-on-the-floor, bass, stabs, arp
  bars 14-15 F7 CTA       impact, final chord hit, ring-out
Output: assets/bgm/openstream-128bpm.wav (48 kHz, 16-bit stereo)
"""
import wave
from pathlib import Path

import numpy as np

SR = 48000
BPM = 128
BEAT = 60 / BPM
BAR = 4 * BEAT
TOTAL = 16 * BAR  # 30.0s
N = int(round(TOTAL * SR))
rng = np.random.default_rng(128)

L = np.zeros(N)
R = np.zeros(N)
duck = np.ones(N)  # sidechain gain for the music bus
music_L = np.zeros(N)
music_R = np.zeros(N)


def t_of(n):
    return np.arange(n) / SR


def place(buf_l, buf_r, at, sig_l, sig_r=None):
    i = int(round(at * SR))
    if i >= N:
        return
    if sig_r is None:
        sig_r = sig_l
    n = min(len(sig_l), N - i)
    buf_l[i:i + n] += sig_l[:n]
    buf_r[i:i + n] += sig_r[:n]


def fft_filter(x, lo=None, hi=None, slope=1.5):
    """Smooth band filter in the frequency domain."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = np.ones_like(f)
    if hi is not None:
        g *= 1 / (1 + (f / hi) ** (2 * slope))
    if lo is not None:
        g *= 1 / (1 + (lo / np.maximum(f, 1e-3)) ** (2 * slope))
    return np.fft.irfft(X * g, len(x))


def saw(freq, dur, detune=0.0, max_h=9000):
    t = t_of(int(dur * SR))
    f = freq * (1 + detune)
    out = np.zeros_like(t)
    for k in range(1, int(max_h / f) + 1):
        out += ((-1) ** (k + 1)) * np.sin(2 * np.pi * k * f * t) / k
    return out * (2 / np.pi)


def env_adsr(n, a=0.005, d=0.1, s=0.6, r=0.05):
    t = t_of(n)
    dur = n / SR
    e = np.where(t < a, t / max(a, 1e-6), s + (1 - s) * np.exp(-(t - a) / max(d, 1e-6)))
    rel_start = max(dur - r, 0)
    e = np.where(t > rel_start, e * np.clip((dur - t) / max(r, 1e-6), 0, 1), e)
    return e


def midi(m):
    return 440 * 2 ** ((m - 69) / 12)


# ---------- drums ----------
def kick(gain=1.0):
    n = int(0.45 * SR)
    t = t_of(n)
    f = 45 + 110 * np.exp(-t / 0.035)
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-t / 0.22)
    click = fft_filter(rng.standard_normal(n), lo=2000) * np.exp(-t / 0.004) * 0.35
    return np.tanh((body + click) * 1.6) * gain


def clap(gain=0.55):
    n = int(0.35 * SR)
    t = t_of(n)
    noise = fft_filter(rng.standard_normal(n), lo=900, hi=6000)
    e = np.zeros(n)
    for off in (0, 0.009, 0.018):
        e += np.where(t >= off, np.exp(-(t - off) / 0.012), 0)
    e += np.exp(-t / 0.11) * 0.6
    return noise * e * gain


def hat(open_=False, gain=0.22):
    n = int((0.2 if open_ else 0.05) * SR)
    t = t_of(n)
    noise = fft_filter(rng.standard_normal(n), lo=7000)
    return noise * np.exp(-t / (0.07 if open_ else 0.012)) * gain


def snare(gain=0.4):
    n = int(0.18 * SR)
    t = t_of(n)
    noise = fft_filter(rng.standard_normal(n), lo=1500, hi=9000)
    tone = np.sin(2 * np.pi * 190 * t) * np.exp(-t / 0.03)
    return (noise * np.exp(-t / 0.05) + tone * 0.5) * gain


def impact(gain=1.0):
    n = int(2.6 * SR)
    t = t_of(n)
    f = 30 + 80 * np.exp(-t / 0.08)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.7)
    crash = fft_filter(rng.standard_normal(n), lo=3000) * np.exp(-t / 0.5) * 0.35
    return np.tanh((boom * 1.4 + crash)) * gain


KICK, CLAP = kick(), clap()
HAT_C, HAT_O = hat(), hat(True)
SNARE, IMPACT = snare(), impact()

kick_times = []


def add_kick(at, gain=1.0):
    place(L, R, at, KICK * gain)
    kick_times.append(at)


# ---------- harmony ----------
# Am – F – C – G  (one chord per bar)
PROG = [
    (57, [57, 60, 64, 69]),  # Am
    (53, [53, 57, 60, 65]),  # F
    (48, [55, 60, 64, 67]),  # C
    (55, [55, 59, 62, 67]),  # G
]


def stab(chord, dur, cutoff=3500, gain=0.16):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in chord:
        f = midi(m + 12)
        l += saw(f, dur, -0.006) + saw(f, dur, 0.004)
        r += saw(f, dur, 0.006) + saw(f, dur, -0.003)
    e = env_adsr(n, a=0.003, d=0.09, s=0.25, r=0.04)
    return fft_filter(l, hi=cutoff) * e * gain, fft_filter(r, hi=cutoff) * e * gain


def bass_note(root, dur, gain=0.32, cutoff=900):
    f = midi(root - 24)
    n = int(dur * SR)
    s = saw(f, dur) + 0.6 * np.sin(2 * np.pi * f * t_of(n))
    e = env_adsr(n, a=0.002, d=0.12, s=0.7, r=0.02)
    return fft_filter(s, hi=cutoff) * e * gain


def pluck(m, dur=0.14, gain=0.07):
    n = int(dur * SR)
    t = t_of(n)
    f = midi(m)
    s = np.sign(np.sin(2 * np.pi * f * t)) * 0.6 + saw(f, dur) * 0.4
    return fft_filter(s, hi=4500) * np.exp(-t / 0.05) * gain


def pad(chord, dur, cutoff, gain=0.06):
    n = int(dur * SR)
    l = np.zeros(n)
    r = np.zeros(n)
    for m in chord:
        f = midi(m)
        l += saw(f, dur, -0.008, 5000)
        r += saw(f, dur, 0.008, 5000)
    e = env_adsr(n, a=0.3, d=1.0, s=0.9, r=0.3)
    return fft_filter(l, hi=cutoff) * e * gain, fft_filter(r, hi=cutoff) * e * gain


def bar_start(b):
    return b * BAR


# ---------- arrangement ----------
# F1 (bars 0-1): driving intro — kick, offbeat hats, stab on each word slam (beats 0,2,4,6)
for b in (0, 1):
    for k in range(4):
        at = bar_start(b) + k * BEAT
        add_kick(at, 0.85)
        place(L, R, at + BEAT / 2, HAT_C * 1.1, HAT_C * 0.9)
    root, ch = PROG[b % 4]
    for k in (0, 2):
        sl, sr = stab(ch, 0.32, cutoff=1800, gain=0.2)
        place(music_L, music_R, bar_start(b) + k * BEAT, sl, sr)
    for k in range(8):
        place(music_L, music_R, bar_start(b) + k * BEAT / 2,
              *(2 * [bass_note(root, BEAT / 2 * 0.9, 0.22, 500)]))

# F2 (bars 2-3): build — kick bar 2 only, accelerating snare roll, riser, swelling pad
for k in range(4):
    add_kick(bar_start(2) + k * BEAT, 0.8)
    place(L, R, bar_start(2) + k * BEAT + BEAT / 2, HAT_C)
pl, pr = pad(PROG[2][1], 2 * BAR - 0.25, cutoff=2500, gain=0.07)
place(music_L, music_R, bar_start(2), pl, pr)
roll = []
t = bar_start(2)
end_roll = bar_start(4) - BEAT / 2
while t < end_roll:
    roll.append(t)
    frac = (t - bar_start(2)) / (end_roll - bar_start(2))
    t += BEAT / 2 if frac < 0.25 else BEAT / 4 if frac < 0.6 else BEAT / 8 if frac < 0.85 else BEAT / 16
for at in roll:
    frac = (at - bar_start(2)) / (end_roll - bar_start(2))
    place(L, R, at, SNARE * (0.7 + 1.3 * frac))
# riser: band-swept noise + rising sine
rs, re_ = bar_start(2), end_roll
n = int((re_ - rs) * SR)
tt = t_of(n)
frac = tt / tt[-1]
noise = rng.standard_normal(n)
riser = np.zeros(n)
chunk = 2048
for i in range(0, n, chunk):
    c = noise[i:i + chunk]
    fc = 400 + 9000 * frac[min(i, n - 1)] ** 2
    riser[i:i + chunk] = fft_filter(c, lo=fc * 0.5, hi=fc)
riser *= frac ** 1.2 * 0.9
sweep = np.sin(2 * np.pi * np.cumsum(200 + 1400 * frac ** 2) / SR) * frac ** 2 * 0.08
place(L, R, rs, riser + sweep, riser * 0.9 + sweep)

# Drop bars 4-13 (F3..F6)
for b in range(4, 14):
    root, ch = PROG[b % 4]
    for k in range(4):
        at = bar_start(b) + k * BEAT
        add_kick(at, 1.0)
        place(L, R, at + BEAT / 2, HAT_O * 0.9, HAT_O * 1.1)
        place(L, R, at + BEAT / 4, HAT_C * 0.6)
        place(L, R, at + 3 * BEAT / 4, HAT_C * 0.6)
        if k in (1, 3):
            place(L, R, at, CLAP)
    # offbeat rolling bass (16ths, skip the downbeat for pump)
    for s in range(16):
        if s % 4 == 0:
            continue
        place(music_L, music_R, bar_start(b) + s * BEAT / 4,
              *(2 * [bass_note(root, BEAT / 4 * 0.85, 0.3, 1100)]))
    # syncopated stabs: 1, 2&, 4
    for off in (0, 1.5 * BEAT, 3 * BEAT):
        sl, sr = stab(ch, 0.24, cutoff=4200, gain=0.15)
        place(music_L, music_R, bar_start(b) + off, sl, sr)
    # 16th arp
    notes = ch + [ch[1] + 12]
    for s in range(16):
        m = notes[(s * 3) % len(notes)] + 12
        p = pluck(m)
        pan = 0.5 + 0.3 * np.sin(s * 0.9)
        place(music_L, music_R, bar_start(b) + s * BEAT / 4, p * (1 - pan) * 1.6, p * pan * 1.6)
    # fill on the last beat of each 2-bar phrase
    if b % 2 == 1:
        for s in range(4):
            place(L, R, bar_start(b) + 3 * BEAT + s * BEAT / 4, SNARE * (0.25 + 0.1 * s))

# Impact at the drop (7.5s) and CTA (26.25s)
place(L, R, bar_start(4), IMPACT * 0.9)
place(L, R, bar_start(14), IMPACT * 1.0)

# F7 (bars 14-15): CTA — big chord + kicks bar 14, final hit at bar 15 then ring out
for k in range(4):
    add_kick(bar_start(14) + k * BEAT, 1.0)
    place(L, R, bar_start(14) + k * BEAT + BEAT / 2, HAT_O * 0.8)
    if k in (1, 3):
        place(L, R, bar_start(14) + k * BEAT, CLAP)
for s in range(16):
    if s % 4:
        place(music_L, music_R, bar_start(14) + s * BEAT / 4,
              *(2 * [bass_note(57, BEAT / 4 * 0.85, 0.3, 1100)]))
sl, sr = stab(PROG[0][1], 0.5, cutoff=4500, gain=0.18)
place(music_L, music_R, bar_start(14), sl, sr)
# final hit
add_kick(bar_start(15), 1.1)
place(L, R, bar_start(15), IMPACT * 0.7)
pl, pr = pad([45, 57, 60, 64, 69, 72], BAR, cutoff=3500, gain=0.09)
place(music_L, music_R, bar_start(15), pl, pr)
sl, sr = stab([57, 60, 64, 69], 1.2, cutoff=5000, gain=0.2)
place(music_L, music_R, bar_start(15), sl, sr)

# ---------- sidechain + mix ----------
for kt in kick_times:
    i = int(round(kt * SR))
    n = min(int(0.3 * SR), N - i)
    if n <= 0:
        continue
    g = 1 - 0.65 * np.exp(-t_of(n) / 0.09)
    duck[i:i + n] = np.minimum(duck[i:i + n], g)

L += music_L * duck
R += music_R * duck

# gentle tail fade over the last 0.9s
fade = np.ones(N)
fn = int(0.9 * SR)
fade[-fn:] = np.linspace(1, 0, fn) ** 1.5
L *= fade
R *= fade

# master: soft clip + normalize to -1 dBFS
mix = np.stack([L, R], axis=1)
mix = np.tanh(mix * 1.15)
mix *= 10 ** (-1 / 20) / np.max(np.abs(mix))

out = Path(__file__).resolve().parent.parent / "assets" / "bgm" / "openstream-128bpm.wav"
out.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(out), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print(f"wrote {out} ({TOTAL:.3f}s, {len(kick_times)} kicks)")
