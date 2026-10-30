#!/usr/bin/env python3
"""Synthesises Venue's factory rooms, our own, as impulse responses.

    python3 tools/gen_impulses.py

Writes shared/src/commonMain/files/impulse-<name>.wav, 16-bit stereo at
48 kHz. The app lists them under "rooms" (model/VenueRooms.kt); Venue scales
any impulse to a nominal level on load, so the level here only needs to stay
clear of clipping.

Each room is early reflections (single taps, softened, different per side)
followed by a tail of noise split into three bands, each with its own decay
time, faded in over the build-up. The plate adds a few faint ringing modes,
the closet a box resonance. Seeded, so a run gives the same files.
"""
import pathlib
import wave

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/src/commonMain/files"
SR = 48000

# name: seconds, (low, mid, high) 60 dB times in seconds, the first
# reflection and the end of the early part in ms, taps, tail build-up in ms,
# and the tail's level against the reflections.
ROOMS = {
    "small-room": dict(seconds=0.6, rt=(0.45, 0.40, 0.25), first=2.5, early=30, taps=24, build=12, tail=0.6),
    "wooden-hall": dict(seconds=2.0, rt=(1.9, 1.7, 0.9), first=9, early=70, taps=30, build=40, tail=0.7, warm=True),
    "stone-church": dict(seconds=4.5, rt=(4.8, 4.0, 1.8), first=22, early=120, taps=26, build=90, tail=0.8),
    "metal-plate": dict(seconds=2.2, rt=(1.6, 2.0, 1.9), first=0.3, early=4, taps=12, build=3, tail=1.0, plate=True),
    "closet": dict(seconds=0.3, rt=(0.22, 0.18, 0.10), first=1.0, early=12, taps=30, build=3, tail=0.4, box=True),
}


def bands(x):
    """[x] split into low (<300 Hz), mid and high (>3 kHz) by smooth masks in the spectrum."""
    n = len(x)
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    low = 1 / (1 + (f / 300.0) ** 4)
    high = 1 / (1 + (3000.0 / np.maximum(f, 1e-3)) ** 4)
    mid = np.clip(1 - low - high, 0, 1)
    return [np.fft.irfft(spec * m, n) for m in (low, mid, high)]


def decay(n, rt):
    """An envelope falling 60 dB over [rt] seconds."""
    t = np.arange(n) / SR
    return np.exp(-6.9078 * t / rt)


def one_pole(x, a):
    y = np.empty_like(x)
    z = 0.0
    for i, v in enumerate(x):
        z += a * (v - z)
        y[i] = z
    return y


def channel(spec, rng):
    n = int(spec["seconds"] * SR)
    out = np.zeros(n)
    # Early reflections: taps spread from the first to the end of the early
    # part, closer together as they go, quieter with distance.
    first = spec["first"] / 1000 * SR
    early = spec["early"] / 1000 * SR
    taps = spec["taps"]
    when = np.sort(first + (early - first) * rng.random(taps) ** 0.7)
    for k, at in enumerate(when):
        i = int(at)
        if i >= n:
            continue
        gain = (first / max(at, 1.0)) ** 0.8 * rng.uniform(0.4, 1.0) * rng.choice([-1, 1])
        out[i] += gain
    out = one_pole(out, 0.55)
    # The tail: three bands of noise, each on its own decay.
    noise = rng.standard_normal(n)
    tail = np.zeros(n)
    for b, rt in zip(bands(noise), spec["rt"]):
        tail += b * decay(n, rt)
    if spec.get("warm"):
        lows, mids, highs = bands(tail)
        tail = 1.3 * lows + mids + 0.7 * highs
    if spec.get("plate"):
        # A few faint inharmonic modes ringing through the wash.
        t = np.arange(n) / SR
        for f0 in rng.uniform(180, 2400, 14):
            tail += 0.012 * np.sin(2 * np.pi * f0 * t + rng.uniform(0, 6.28)) * decay(n, rng.uniform(1.2, 2.2))
    if spec.get("box"):
        # A small box: the walls a metre or so apart, a comb on the tail.
        d = int(SR * rng.uniform(0.0028, 0.0034))
        boxed = tail.copy()
        for i in range(d, n):
            boxed[i] += 0.55 * boxed[i - d]
        tail = boxed * 0.5
    build = np.clip(np.arange(n) / (spec["build"] / 1000 * SR), 0, 1)
    start = int(first)
    build = np.concatenate([np.zeros(start), build[: n - start]])
    tail *= build
    tail *= spec["tail"] * np.abs(out).max() / max(np.abs(tail).max(), 1e-9) * 0.5
    x = out + tail
    # The last 50 ms faded so a trimmed file doesn't click.
    fade = int(0.05 * SR)
    x[-fade:] *= np.linspace(1, 0, fade)
    return x


def write(name, left, right):
    peak = max(np.abs(left).max(), np.abs(right).max())
    data = np.stack([left, right], axis=1) / peak * 0.89
    pcm = (data * 32767).round().astype("<i2")
    path = OUT / f"impulse-{name}.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return path


def main():
    total = 0
    for seed, (name, spec) in enumerate(ROOMS.items()):
        rng = np.random.default_rng(1000 + seed)
        left = channel(spec, rng)
        right = channel(spec, rng)
        path = write(name, left, right)
        size = path.stat().st_size
        total += size
        print(f"{path.name}: {spec['seconds']} s, {size // 1024} KB")
    print(f"total {total // 1024} KB")


if __name__ == "__main__":
    main()
