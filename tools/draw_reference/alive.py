#!/usr/bin/env python3
"""How alive a held note is, cycle by cycle: what makes a played reed sound
played rather than mechanical.

    alive.py accordion|harmonica [name=value ...]     Draw against the recordings

Jitter: how much consecutive cycles differ in length, in parts per thousand.
Shimmer: how much consecutive cycles differ in size, dB. Drift: how far the
pitch wanders over the note, cents (the spread of a 20 ms pitch track after
taking out its straight-line trend). All over 1.5 s of the held part.
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import fit
import measure as M
import natural


def alive(x, f0, start=0.3):
    seg = x[int(start * M.SR):][:int(1.5 * M.SR)]
    # Cycles from upward zero crossings of the fundamental, isolated by a band-pass.
    spec = np.fft.rfft(seg)
    f = np.fft.rfftfreq(len(seg), 1 / M.SR)
    spec[(f < 0.6 * f0) | (f > 1.4 * f0)] = 0
    fund = np.fft.irfft(spec, len(seg))
    up = np.where((fund[:-1] < 0) & (fund[1:] >= 0))[0]
    t = up + fund[up] / (fund[up] - fund[up + 1])
    periods = np.diff(t)
    if len(periods) < 20:
        return None
    jitter = float(np.mean(np.abs(np.diff(periods))) / np.mean(periods) * 1000)
    peaks = np.array([np.max(np.abs(seg[int(a):int(b)])) for a, b in zip(t[:-1], t[1:])])
    shimmer = float(np.mean(np.abs(np.diff(20 * np.log10(peaks + 1e-12)))))
    pitch, sure = M.yin(seg)
    good = sure > 0.8
    cents = 1200 * np.log2(pitch[good] / np.median(pitch[good]))
    if len(cents) > 10:
        idx = np.arange(len(cents))
        cents = cents - np.polyval(np.polyfit(idx, cents, 1), idx)
    drift = float(np.std(cents)) if len(cents) > 10 else float('nan')
    return jitter, shimmer, drift


def recorded(kind):
    out = []
    for note, _ in natural.recordings(kind):
        pass
    sets = {'harmonica': (['vcsl_special20_c', 'vcsl_special20_f'], 'Normal'), 'accordion': (['freepats_button_accordion'], '')}[kind]
    for s in sets[0]:
        for p in sorted(glob.glob(os.path.join(fit.fetch.folder(), s, '*'))):
            if not p.endswith(('.wav', '.flac')) or (sets[1] and f'_{sets[1]}' not in p.replace(' ', '')):
                continue
            x = M.decode(p)
            pitch, sure = M.yin(x[:M.SR * 2])
            if (sure > 0.8).sum() < 10:
                continue
            f0 = float(np.median(pitch[sure > 0.8]))
            a = alive(x, f0)
            if a:
                out.append((int(round(69 + 12 * np.log2(f0 / 440))), a))
    return out


def compare(kind, args, recs=None):
    recs = recs or recorded(kind)
    mine = []
    for note, _ in recs:
        y = fit.render(note, [f'model={fit.KINDS[kind][0]}'] + list(args), seconds=2.2)
        a = alive(y, 440 * 2 ** ((note - 69) / 12))
        if a:
            mine.append(a)
    med = lambda xs, i: float(np.nanmedian([v[i] for v in xs]))
    return [med([r for _, r in recs], i) for i in range(3)], [med(mine, i) for i in range(3)]


if __name__ == '__main__':
    rec, mine = compare(sys.argv[1], sys.argv[2:])
    print(f'{sys.argv[1]}: jitter {mine[0]:.2f} per mille (recordings {rec[0]:.2f}), shimmer {mine[1]:.2f} dB ({rec[1]:.2f}), '
          f'drift {mine[2]:.2f} cents ({rec[2]:.2f})')
