#!/usr/bin/env python3
"""How natural Draw is against the recordings: the noise between the
harmonics, and how much the harmonics wander from moment to moment.

    natural.py accordion|harmonica [name=value ...]

Noise: the power halfway between harmonics 1 to 16 against the power on
them, over 2 s of a held note, dB. Wander: the spread over time of each
harmonic's level in 85 ms frames, after taking out each frame's overall level
and each harmonic's straight-line trend, its median over the harmonics, dB:
the tone's own change from moment to moment, not a swell or a fade.
"""
import glob
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import fit
import measure as M


def natural(x, f0, start=0.3):
    seg = x[int(start * M.SR):][:M.SR * 2]
    n = 8192
    win = np.hanning(n)
    f = np.fft.rfftfreq(n, 1 / M.SR)
    harm = gap = 0.0
    frames = []
    for i in range(0, len(seg) - n, n // 2):
        spec = np.abs(np.fft.rfft(seg[i:i + n] * win)) ** 2
        levels = []
        for h in range(1, 17):
            if h * f0 > 18000:
                break
            on = np.abs(f - h * f0) < 0.08 * f0
            off = np.abs(f - (h + 0.5) * f0) < 0.2 * f0
            harm += spec[on].sum()
            gap += spec[off].sum()
            levels.append(10 * np.log10(spec[on].max() + 1e-20))
        frames.append(levels)
    fr = np.array(frames)
    if len(fr) <= 3:
        return 10 * np.log10(gap / harm), float('nan')
    # The tone's own change: each frame's overall level taken out, then each
    # harmonic's straight-line trend, so a swell or a fade isn't counted.
    fr = fr - fr.mean(axis=1, keepdims=True)
    t = np.arange(len(fr))
    for h in range(fr.shape[1]):
        fr[:, h] -= np.polyval(np.polyfit(t, fr[:, h], 1), t)
    wander = float(np.median(np.std(fr, axis=0)))
    return 10 * np.log10(gap / harm), wander


def recordings(kind):
    sets = {'harmonica': (['vcsl_special20_c', 'vcsl_special20_f'], 'Normal'), 'accordion': (['freepats_button_accordion'], '')}[kind]
    out = []
    for s in sets[0]:
        for p in sorted(glob.glob(os.path.join(fit.fetch.folder(), s, '*'))):
            if not p.endswith(('.wav', '.flac')) or (sets[1] and f'_{sets[1]}' not in p.replace(' ', '')):
                continue
            x = M.decode(p)
            x /= np.abs(x).max() + 1e-12
            pitch, sure = M.yin(x[:M.SR * 2])
            if (sure > 0.8).sum() < 10:
                continue
            f0 = float(np.median(pitch[sure > 0.8]))
            out.append((int(round(69 + 12 * np.log2(f0 / 440))), natural(x, f0)))
    return out


def compare(kind, args, recs=None):
    recs = recs or recordings(kind)
    mine = []
    for note, _ in recs:
        y = fit.render(note, [f'model={fit.KINDS[kind][0]}'] + list(args), seconds=2.6)
        y /= np.abs(y).max() + 1e-12
        mine.append(natural(y, 440 * 2 ** ((note - 69) / 12)))
    med = lambda xs, i: float(np.median([v[i] for v in xs]))
    return (med([r for _, r in recs], 0), med([r for _, r in recs], 1)), (med(mine, 0), med(mine, 1))


if __name__ == '__main__':
    rec, mine = compare(sys.argv[1], sys.argv[2:])
    print(f'{sys.argv[1]}: noise {mine[0]:.1f} dB (recordings {rec[0]:.1f}), wander {mine[1]:.2f} dB (recordings {rec[1]:.2f})')
