#!/usr/bin/env python3
"""Measures the free-reed recordings Draw is fitted to.

    measure.py [set ...]        every set fetched when none is named
    measure.py --json out.json  also writes the numbers

Per note (each file, or each note found in a longer recording):
- the pitch, steady, and how it starts: cents off the steady pitch over the
  first 80 ms (the free reed's upward glide);
- the attack as the papers time it: the fundamental from -50 dB to -5 dB of
  its steady level, and when each of the first eight harmonics reaches -6 dB
  of its own (the order they enter in);
- the steady harmonics 1 to 16 against the 1st, their tilt an octave and the
  even against the odd;
- any wobble: the strongest modulation of pitch (cents) and level (dB)
  between 2 and 12 Hz, the rate and depth of a vibrato or a musette's beat.

Layers in a file's name (Soft, Normal, Accented, Vib, HandVib) are kept, so
pitch and brightness can be compared across how hard it's played.

numpy only; ffmpeg decodes everything to 48 kHz mono. Numbers only: the
recordings stay in the reference folder (fetch.py).
"""
import json
import os
import re
import subprocess
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import fetch

SR = 48000
HOP = 240          # 5 ms
FRAME = 2048


def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'quiet', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def yin(x, lo=50.0, hi=2000.0):
    """Pitch every 5 ms (YIN, cumulative mean normalised), Hz, 0 where unsure; and its sureness."""
    tmin, tmax = int(SR / hi), int(SR / lo)
    n = (len(x) - FRAME - tmax) // HOP
    pitch = np.zeros(max(n, 0))
    sure = np.zeros(max(n, 0))
    for i in range(max(n, 0)):
        seg = x[i * HOP:i * HOP + FRAME + tmax]
        w = seg[:FRAME]
        # Difference function by FFT: d(t) = sum w^2 + sum shifted^2 - 2 r(t).
        size = 1 << int(np.ceil(np.log2(FRAME + tmax + FRAME)))
        r = np.fft.irfft(np.fft.rfft(seg, size) * np.conj(np.fft.rfft(w, size)), size)[:tmax + 1]
        e0 = np.sum(w * w)
        csum = np.cumsum(seg * seg)
        shifted = csum[FRAME - 1 + np.arange(tmax + 1)] - np.concatenate(([0.0], csum[:tmax]))
        d = e0 + shifted - 2 * r
        d[0] = 0
        cm = d[1:] * np.arange(1, tmax + 1) / np.maximum(np.cumsum(d[1:]), 1e-12)
        cm = np.concatenate(([1.0], cm))
        cand = np.where(cm[tmin:] < 0.15)[0]
        if len(cand) == 0:
            continue
        t = tmin + cand[0]
        while t + 1 <= tmax and cm[t + 1] < cm[t]:
            t += 1
        if 1 <= t < tmax:
            a, b, c = cm[t - 1], cm[t], cm[t + 1]
            shift = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0
        else:
            shift = 0.0
        pitch[i] = SR / (t + shift)
        sure[i] = 1 - cm[t]
    return pitch, sure


def levels(x, f0, n=16):
    """Each harmonic's level every 5 ms, dB, from a 4096 window at the steady pitch."""
    win = np.hanning(4096)
    count = max(0, (len(x) - 4096) // HOP)
    out = np.full((count, n), np.nan)
    freqs = np.fft.rfftfreq(4096, 1 / SR)
    for i in range(count):
        spec = np.abs(np.fft.rfft(x[i * HOP:i * HOP + 4096] * win))
        for h in range(1, n + 1):
            if h * f0 > 20000.0:
                break
            band = (freqs > (h - 0.3) * f0) & (freqs < (h + 0.3) * f0)
            if band.any():
                out[i, h - 1] = 20 * np.log10(spec[band].max() + 1e-12)
    return out


def wobble(track, rate):
    """The strongest modulation between 2 and 12 Hz of a track sampled at [rate]: Hz and peak depth."""
    t = track - np.polyval(np.polyfit(np.arange(len(track)), track, 2), np.arange(len(track)))
    if len(t) < 64:
        return None, None
    spec = np.abs(np.fft.rfft(t * np.hanning(len(t))))
    f = np.fft.rfftfreq(len(t), 1 / rate)
    band = (f >= 2) & (f <= 12)
    if not band.any():
        return None, None
    k = np.argmax(spec * band)
    depth = 2 * spec[k] / (np.sum(np.hanning(len(t))) + 1e-12)
    return float(f[k]), float(depth)


def notes_in(x):
    """Where notes sound, as (start, end) samples: the level above -40 dB of the loudest, gaps over 80 ms."""
    env = np.array([np.sqrt(np.mean(x[i:i + HOP] ** 2) + 1e-20) for i in range(0, len(x) - HOP, HOP)])
    db = 20 * np.log10(env)
    on = db > db.max() - 40
    out, start = [], None
    for i, v in enumerate(on):
        if v and start is None:
            start = i
        if not v and start is not None:
            if i - start > 30:
                out.append((start * HOP, i * HOP))
            start = None
    if start is not None and len(on) - start > 30:
        out.append((start * HOP, len(on) * HOP))
    return out


def measure_note(x, name):
    pitch, sure = yin(x)
    good = sure > 0.8
    if good.sum() < 20:
        return None
    steady = float(np.median(pitch[good][len(pitch[good]) // 4:]))
    out = {'note': name, 'seconds': round(len(x) / SR, 2), 'hz': round(steady, 2)}
    lv = levels(x, steady)
    if len(lv) < 40:
        return out
    top = np.nanpercentile(lv, 90, axis=0)
    rel = top - top[0]
    # Harmonics above 20 kHz are left out (None).
    out['harmonics_db'] = [None if np.isnan(v) else round(float(v), 1) for v in rel]
    hs = np.arange(1, 17)
    have = ~np.isnan(top)
    out['tilt_db_per_octave'] = round(float(np.polyfit(np.log2(hs[have]), top[have], 1)[0]), 1) if have.sum() >= 3 else None
    odd, even = rel[2:12:2], rel[1:12:2]
    out['even_minus_odd_db'] = round(float(np.nanmean(even) - np.nanmean(odd)), 1) if have.sum() >= 4 else None
    # The attack: from the first sound.
    h1 = lv[:, 0]
    target = np.median(h1[len(h1) // 3:])
    start = np.argmax(h1 > target - 50)
    reach = np.argmax(h1[start:] > target - 5) + start
    out['attack_ms'] = round((reach - start) * HOP / SR * 1000)
    entries = []
    for h in range(8):
        lvl = np.median(lv[len(lv) // 3:, h])
        entries.append(round(float((np.argmax(lv[start:, h] > lvl - 6)) * HOP / SR * 1000)))
    out['harmonic_entry_ms'] = entries
    # The glide: pitch over the first 80 ms, against steady.
    first = slice(start, start + 16)
    early = pitch[first][sure[first] > 0.6]
    if len(early) >= 3:
        out['glide_cents'] = [round(float(1200 * np.log2(early[0] / steady))), round(float(1200 * np.log2(early[-1] / steady)))]
    body = slice(len(pitch) // 4, len(pitch) - len(pitch) // 8)
    cents = 1200 * np.log2(np.where(good[body], pitch[body], steady) / steady)
    rate, depth = wobble(cents, SR / HOP)
    lrate, ldepth = wobble(lv[len(lv) // 4:len(lv) - len(lv) // 8, 0], SR / HOP)
    out['pitch_wobble'] = None if rate is None else [round(rate, 2), round(depth, 1)]
    out['level_wobble'] = None if lrate is None else [round(lrate, 2), round(ldepth, 1)]
    amp = np.where(have, 10 ** (np.nan_to_num(top, nan=-300.0) / 20), 0.0)
    out['centroid_hz'] = round(float(np.sum(amp * hs * steady) / np.sum(amp)))
    return out


def layer_of(name):
    m = re.search(r'_(Soft|Normal ?|Accented|Vib|HandVib|Stac)_', name)
    return m.group(1).strip() if m else ''


def measure_file(path):
    x = decode(path)
    if len(x) < SR // 4:
        return []
    x = x / (np.max(np.abs(x)) + 1e-12)
    name = os.path.basename(path)
    spans = notes_in(x)
    # A short file is one note; a long one is taken apart.
    if len(x) < 12 * SR or len(spans) <= 1:
        spans = [(spans[0][0], spans[-1][1])] if spans else []
    out = []
    for k, (a, b) in enumerate(spans[:40]):
        m = measure_note(x[a:b], name if len(spans) == 1 else f'{name}#{k + 1}')
        if m:
            m['layer'] = layer_of(name)
            out.append(m)
    return out


def files(root, name):
    for d, _, fs in os.walk(os.path.join(root, name)):
        for f in sorted(fs):
            if f.lower().endswith(('.wav', '.ogg', '.mp3', '.flac', '.oga')):
                yield os.path.join(d, f)


def main():
    args = sys.argv[1:]
    out_json = None
    if '--json' in args:
        i = args.index('--json')
        out_json = args[i + 1]
        del args[i:i + 2]
    root = fetch.folder()
    sets = args or sorted(json.load(open(os.path.join(root, 'sources.json'))))
    results = {}
    for s in sets:
        results[s] = []
        for p in files(root, s):
            for m in measure_file(p):
                results[s].append(m)
                print(f"{s:26s} {m['note'][:44]:44s} {m.get('layer', ''):8s} {m['hz']:7.1f} Hz  "
                      f"attack {m.get('attack_ms', '-')} ms  glide {m.get('glide_cents')}  tilt {m.get('tilt_db_per_octave')}  "
                      f"e-o {m.get('even_minus_odd_db')}  wobble {m.get('pitch_wobble')} / {m.get('level_wobble')}", flush=True)
    if out_json:
        json.dump(results, open(out_json, 'w'), indent=1)


if __name__ == '__main__':
    main()
