#!/usr/bin/env python3
"""How close Tongue's steel harp comes to the recordings' harmonics.

    fit.py [name=value ...]

Renders a played phrase (plucks at 6 a second, each a little harder or
softer, the mouth swept by the mod wheel) at G3 and measures it as measure.py measures the recordings: the
90th-percentile level of harmonics 1 to 12 against the 1st, then the rms
difference from the recordings' median, after taking out the mean.
"""
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import measure as M

# Median over the steel and khomus recordings (PLAN 4.25).
TARGET = np.array([0, 9.3, 4.8, 9.1, 14.1, 16.5, 16.3, 15.9, 14.1, 13.9, 11.3, 8.5])
ROOT = os.path.join(os.path.dirname(__file__), '..', '..')


def profile(args, note=55):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, 'fit.wav')
        subprocess.run([os.path.join(ROOT, 'build/tongue-render/tongue_render'), out, '--note', str(note),
                        '--seconds', '6', '--rate', '6', '--wheel', '0.7', '--spread', '25', 'mouth=0'] + args,
                       check=True, capture_output=True)
        x = M.decode(out)
    x = x / (np.max(np.abs(x)) + 1e-12)
    f0 = 440 * 2 ** ((note - 69) / 12)
    levels = M.harmonic_levels(M.spectra(x), f0)
    top = np.percentile(levels, 90, axis=0)
    return top[:12] - top[0]


def score(rel):
    d = rel - TARGET
    d -= d.mean()
    return float(np.sqrt(np.mean(d ** 2)))


if __name__ == '__main__':
    rel = profile(sys.argv[1:])
    print('model ', ' '.join(f'{v:5.1f}' for v in rel))
    print('target', ' '.join(f'{v:5.1f}' for v in TARGET))
    print(f'fit {score(rel):.1f} dB rms')
