#!/usr/bin/env python3
"""Measures the jaw harp recordings Tongue is fitted to.

    measure.py [set ...]        every set fetched when none is named
    measure.py --json out.json  also writes the numbers

Per recording: the drone's pitch, the harmonics' levels (their tilt, the odd
against the even, how far each sits from a whole multiple), the plucks (how
often, how fast the sound and each harmonic die), the harmonic the mouth
brings forward from moment to moment (the melody) and where its formants
sit, and the breath (the noise between the harmonics). Numbers only: the
recordings stay in the reference folder (fetch.py).

numpy only; ffmpeg decodes everything to 48 kHz mono.
"""
import json
import os
import subprocess
import sys

import numpy as np

SR = 48000
N = 8192          # long enough to part harmonics 6 Hz apart
HOP = 1024
HARMS = 32


def folder():
    return os.path.expanduser(os.environ.get('ACIDULOUS_TONGUE_REF', '~/acidulous-material/tongue-reference'))


def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'quiet', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def spectra(x):
    """Magnitude frames, Hann windowed."""
    w = np.hanning(N)
    count = max(1, (len(x) - N) // HOP + 1)
    out = np.empty((count, N // 2 + 1))
    for i in range(count):
        seg = x[i * HOP:i * HOP + N]
        if len(seg) < N:
            seg = np.pad(seg, (0, N - len(seg)))
        out[i] = np.abs(np.fft.rfft(seg * w))
    return out


def drone(avg):
    """The fundamental: the pitch whose harmonics carry the most of the long-term spectrum (a harmonic sum)."""
    freqs = np.arange(len(avg)) * SR / N
    best, score = 0.0, -1.0
    for f in np.arange(55.0, 600.0, 0.25):
        idx = np.round(np.arange(1, 13) * f * N / SR).astype(int)
        idx = idx[idx < len(avg)]
        s = np.sum(np.log1p(avg[idx] / (np.median(avg) + 1e-12)))
        if s > score:
            best, score = f, s
    # Refined on the strongest of the first eight harmonics, by the peak's own frequency.
    refined = []
    for h in range(1, 9):
        k = int(round(h * best * N / SR))
        lo, hi = max(1, k - 3), min(len(avg) - 2, k + 3)
        p = lo + int(np.argmax(avg[lo:hi + 1]))
        a, b, c = np.log(avg[p - 1] + 1e-12), np.log(avg[p] + 1e-12), np.log(avg[p + 1] + 1e-12)
        d = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0
        refined.append(((p + d) * SR / N / h, avg[p]))
    refined.sort(key=lambda r: -r[1])
    return float(np.median([r[0] for r in refined[:4]])), freqs


def harmonic_levels(frames, f0):
    """Each frame's level at each harmonic, dB (the largest bin within a third of a harmonic)."""
    out = np.full((len(frames), HARMS), -140.0)
    for h in range(1, HARMS + 1):
        k = h * f0 * N / SR
        lo, hi = int(k - 0.15 * f0 * N / SR), int(k + 0.15 * f0 * N / SR) + 1
        if hi >= frames.shape[1]:
            break
        out[:, h - 1] = 20 * np.log10(np.max(frames[:, lo:hi], axis=1) + 1e-12)
    return out


def detune_cents(avg, f0):
    """How far each of the first twelve harmonic peaks sits from a whole multiple."""
    out = []
    for h in range(1, 13):
        k = h * f0 * N / SR
        lo, hi = int(k) - 4, int(k) + 5
        if hi + 1 >= len(avg):
            break
        p = lo + int(np.argmax(avg[lo:hi]))
        a, b, c = np.log(avg[p - 1] + 1e-12), np.log(avg[p] + 1e-12), np.log(avg[p + 1] + 1e-12)
        d = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0
        out.append(1200 * np.log2(((p + d) * SR / N) / (h * f0)))
    return out


def onsets(x):
    """Plucks: where the level jumps more than 6 dB over the last 30 ms, at least 60 ms apart."""
    hop = 240
    env = np.array([np.sqrt(np.mean(x[i:i + hop] ** 2) + 1e-12) for i in range(0, len(x) - hop, hop)])
    db = 20 * np.log10(env)
    peak = np.max(db)
    out, last = [], -1e9
    for i in range(6, len(db)):
        t = i * hop / SR
        if db[i] > peak - 40 and db[i] - np.min(db[i - 6:i]) > 6 and t - last > 0.06:
            out.append(t)
            last = t
    return out, db, hop


def ring(db, hop, starts, total):
    """How fast the level falls after each pluck, dB/s fitted over the first 60% of the gap to the next."""
    rates = []
    for i, t in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else total
        a, b = int(t * SR / hop) + 2, int((t + 0.6 * (end - t)) * SR / hop)
        if b - a < 8:
            continue
        seg = db[a:b]
        tt = np.arange(len(seg)) * hop / SR
        slope = np.polyfit(tt, seg, 1)[0]
        rates.append(slope)
    return rates


def harmonic_ring(levels, starts, total):
    """For long gaps only (> 0.4 s), each of the first eight harmonics' fall, dB/s; the median over plucks."""
    per = {h: [] for h in range(8)}
    for i, t in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else total
        if end - t < 0.4:
            continue
        a, b = int(t * SR / HOP) + 2, min(len(levels), int((t + 0.8 * (end - t)) * SR / HOP))
        if b - a < 5:
            continue
        tt = np.arange(b - a) * HOP / SR
        for h in range(8):
            per[h].append(np.polyfit(tt, levels[a:b, h], 1)[0])
    return [float(np.median(v)) if v else None for v in per.values()]


def mouth(levels, f0):
    """
    The melody and the formants. In each loud frame: the harmonic from the
    third up that stands furthest above its neighbours (what the mouth picks),
    and the peaks of the harmonic envelope (F1, F2) smoothed over three.
    """
    loud = np.max(levels, axis=1) > np.max(levels) - 30
    picked, f1s, f2s = [], [], []
    for row in levels[loud]:
        above = [row[h] - 0.5 * (row[h - 1] + row[h + 1]) for h in range(2, min(HARMS - 1, 20))]
        picked.append(3 + int(np.argmax(above)))
        env = np.convolve(row[:24], np.ones(3) / 3, mode='same')
        peaks = [h for h in range(1, 23) if env[h] > env[h - 1] and env[h] >= env[h + 1]]
        peaks.sort(key=lambda h: -env[h])
        hz = sorted((p + 1) * f0 for p in peaks[:2])
        if len(hz) == 2:
            f1s.append(hz[0])
            f2s.append(hz[1])
    used = sorted(set(picked), key=lambda h: -picked.count(h))
    return {
        'picked': used[:8],
        'f1': [float(np.percentile(f1s, q)) for q in (10, 50, 90)] if f1s else None,
        'f2': [float(np.percentile(f2s, q)) for q in (10, 50, 90)] if f2s else None,
    }


def breath(frames, f0):
    """Noise between the harmonics against the harmonics, dB, the median over loud frames."""
    out = []
    k0 = f0 * N / SR
    loud = frames[np.max(frames, axis=1) > 0.03 * np.max(frames)]
    for row in loud[::4]:
        harm, gap = 0.0, 0.0
        for h in range(1, 16):
            k = h * k0
            harm += np.sum(row[int(k - 0.1 * k0):int(k + 0.1 * k0) + 1] ** 2)
            gap += np.sum(row[int(k + 0.35 * k0):int(k + 0.65 * k0) + 1] ** 2)
        out.append(10 * np.log10((gap + 1e-12) / (harm + 1e-12)))
    return float(np.median(out)) if out else None


def measure(path):
    x = decode(path)
    if len(x) < N * 2:
        return None
    x = x / (np.max(np.abs(x)) + 1e-12)
    frames = spectra(x)
    avg = np.mean(frames, axis=0)
    f0, _ = drone(avg)
    levels = harmonic_levels(frames, f0)
    top = np.percentile(levels, 90, axis=0)
    rel = top - top[0]
    even = float(np.mean(rel[1:12:2]) - np.mean(rel[2:12:2]))
    hs = np.arange(1, 17)
    tilt = float(np.polyfit(np.log2(hs), top[:16], 1)[0])
    starts, db, hop = onsets(x)
    total = len(x) / SR
    rates = ring(db, hop, starts, total)
    return {
        'file': os.path.basename(path),
        'seconds': round(total, 2),
        'drone_hz': round(f0, 2),
        'harmonics_db': [round(float(v), 1) for v in rel[:16]],
        'tilt_db_per_octave': round(tilt, 1),
        'even_minus_odd_db': round(even, 1),
        'detune_cents': [round(float(c), 1) for c in detune_cents(avg, f0)],
        'plucks': len(starts),
        'plucks_per_second': round(len(starts) / total, 2),
        'fall_db_per_s': [round(float(np.percentile(rates, q)), 1) for q in (25, 50, 75)] if rates else None,
        'harmonic_fall_db_per_s': [None if v is None else round(v, 1) for v in harmonic_ring(levels, starts, total)],
        'mouth': mouth(levels, f0),
        'breath_db': None if (b := breath(frames, f0)) is None else round(b, 1),
    }


def files(root, name):
    here = os.path.join(root, name)
    for d, _, fs in os.walk(here):
        for f in sorted(fs):
            if f.lower().endswith(('.wav', '.ogg', '.mp3', '.oga', '.flac')):
                yield os.path.join(d, f)


def main():
    args = sys.argv[1:]
    out_json = None
    if '--json' in args:
        i = args.index('--json')
        out_json = args[i + 1]
        del args[i:i + 2]
    root = folder()
    sets = args or sorted(json.load(open(os.path.join(root, 'sources.json'))))
    results = {}
    for s in sets:
        results[s] = []
        for p in files(root, s):
            try:
                m = measure(p)
            except subprocess.CalledProcessError:
                m = None
            if m is None:
                continue
            results[s].append(m)
            mo = m['mouth']
            print(f"{s:14s} {m['file'][:44]:44s} drone {m['drone_hz']:7.2f} Hz  tilt {m['tilt_db_per_octave']:5.1f}  "
                  f"even-odd {m['even_minus_odd_db']:5.1f}  plucks/s {m['plucks_per_second']:4.1f}  "
                  f"fall {m['fall_db_per_s']}  breath {m['breath_db']}  picks {mo['picked'][:5]}  "
                  f"F1 {None if mo['f1'] is None else [round(v) for v in mo['f1']]}  "
                  f"F2 {None if mo['f2'] is None else [round(v) for v in mo['f2']]}")
    if out_json:
        json.dump(results, open(out_json, 'w'), indent=1)


if __name__ == '__main__':
    main()
