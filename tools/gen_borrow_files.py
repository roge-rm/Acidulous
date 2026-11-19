#!/usr/bin/env python3
"""Synthesises Abacus's factory file to borrow from, our own: a sung line.

    python3 tools/gen_borrow_files.py

Writes shared/src/commonMain/files/borrow-voice.wav, 16-bit mono at 48 kHz,
about 3.2 s. The app offers it as "voice" (model/BorrowFiles.kt). Abacus sets
a file's levels on its own loud frames, so the level here only needs to stay
clear of clipping.

A glottal pulse train (a smoothed Rosenberg pulse) with a slow vibrato,
through four formant resonators that glide a, e, i, o, u, with an "s" of
shaped noise between e and i and a breath at the start. The pitch walks a
short line between A2 and E3, so the partials mode has a pitch to follow.
Seeded, so a run gives the same file.
"""
import math
import pathlib
import wave

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/src/commonMain/files"
SR = 48000

# Each vowel: its time in seconds, then four formants as (Hz, bandwidth Hz).
VOWELS = [
    (0.10, "a", [(730, 90), (1090, 110), (2440, 160), (3400, 250)]),
    (0.70, "e", [(530, 70), (1840, 100), (2480, 160), (3500, 250)]),
    (1.45, "i", [(270, 60), (2290, 100), (3010, 180), (3700, 250)]),
    (2.15, "o", [(570, 70), (840, 90), (2410, 160), (3400, 250)]),
    (2.80, "u", [(300, 60), (870, 90), (2240, 160), (3300, 250)]),
]
# The line: (time, note number).
LINE = [(0.0, 45), (0.65, 49), (1.4, 52), (2.1, 50), (2.75, 45)]
SECONDS = 3.2
HISS = (1.18, 1.36)  # the "s", seconds


def interp(table, t):
    """[table] of (time, value) read at [t], gliding over 120 ms around each change."""
    v = table[0][1]
    for when, value in table[1:]:
        x = min(1.0, max(0.0, (t - when + 0.06) / 0.12))
        x = x * x * (3 - 2 * x)
        v = v + (np.asarray(value, dtype=float) - v) * x if x > 0 else v
    return v


def main():
    rng = np.random.default_rng(0xAB4C05)
    n = int(SR * SECONDS)
    t = np.arange(n) / SR
    # Pitch: the line, glided, with a 5.5 Hz vibrato of 20 cents fading in.
    notes = np.array([interp(LINE, x) for x in t[::64]])
    notes = np.interp(t, t[::64], notes)
    vib = 0.2 * np.minimum(1.0, t / 0.6) * np.sin(2 * math.pi * 5.5 * t)
    hz = 440.0 * 2 ** ((notes + vib - 69) / 12)
    phase = np.cumsum(hz / SR) % 1.0
    # A Rosenberg pulse: opening 40% of the cycle, closing 16%, its derivative as the source.
    op, cl = 0.40, 0.16
    pulse = np.where(phase < op, 0.5 * (1 - np.cos(math.pi * phase / op)),
                     np.where(phase < op + cl, np.cos(0.5 * math.pi * (phase - op) / cl), 0.0))
    src = np.diff(pulse, prepend=0.0)
    # A little aspiration on the voice, and the hiss between e and i.
    breath = rng.standard_normal(n) * 0.004
    hiss_env = np.clip(np.minimum((t - HISS[0]) / 0.03, (HISS[1] - t) / 0.03), 0, 1)
    voice_env = np.clip(np.minimum(t / 0.04, (SECONDS - 0.05 - t) / 0.12), 0, 1) * (1 - 0.85 * hiss_env)
    src = src * voice_env + breath * voice_env

    # The formants, coefficients worked every 64 samples.
    tables = [(when, [f for f, _ in fs] + [b for _, b in fs]) for when, _, fs in VOWELS]
    out = np.zeros(n)
    state = np.zeros((4, 2))
    for at in range(0, n, 64):
        fb = interp(tables, at / SR)
        coefs = []
        for k in range(4):
            f, bw = fb[k], fb[4 + k]
            r = math.exp(-math.pi * bw / SR)
            coefs.append((2 * r * math.cos(2 * math.pi * f / SR), -r * r, (1 - r) * (1 + 0.5 * k)))
        for i in range(at, min(n, at + 64)):
            x = src[i]
            y = 0.0
            for k, (a1, a2, g) in enumerate(coefs):
                s = state[k]
                v = g * x + a1 * s[0] + a2 * s[1]
                s[1] = s[0]
                s[0] = v
                y += v * (1.0 if k < 2 else 0.5)
            out[i] = y
    # The "s": noise through a 6.5 kHz band, its own level.
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec *= np.exp(-0.5 * ((np.log2(np.maximum(f, 1)) - math.log2(6500)) / 0.5) ** 2)
    hiss = np.fft.irfft(spec, n)
    hiss *= hiss_env / (np.max(np.abs(hiss * hiss_env)) + 1e-12)
    out /= np.max(np.abs(out)) + 1e-12
    out = out * 0.85 + hiss * 0.25
    out *= 0.8 / np.max(np.abs(out))

    path = OUT / "borrow-voice.wav"
    data = np.clip(np.round(out * 32767), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())
    print(f"{path.relative_to(ROOT)}: {n / SR:.2f} s, {path.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
