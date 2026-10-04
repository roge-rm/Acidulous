#!/usr/bin/env python3
"""How close Tongue comes to the recordings' harmonics and fall.

    fit.py [--sets a,b] [--note N] [--rate R] [name=value ...]

Renders a played phrase (plucks R a second, each a little harder or softer
and a little early or late, the mouth swept by the mod wheel) and measures it
as measure.py measures the recordings: the 90th-percentile level of
harmonics 1 to 12 against the 1st, then the rms difference from the
recordings' median after taking out the mean. Also the fall after each pluck,
in dB a second.

The target is the median over every recording, or over the named sets, from
measured.json in the reference folder (measure.py --json writes it).
"""
import json
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import measure as M

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')


def recordings(sets=None):
    """The measured recordings at least 1.5 s long, from every set or the named ones."""
    data = json.load(open(os.path.join(M.folder(), 'measured.json')))
    return [m for s, ms in data.items() if sets is None or s in sets for m in ms if m['seconds'] >= 1.5]


def target(sets=None):
    rs = recordings(sets)
    profile = np.median([r['harmonics_db'][:12] for r in rs], axis=0)
    falls = [r['fall_db_per_s'][1] for r in rs if r['fall_db_per_s']]
    return profile, float(np.median(falls)) if falls else None


def render(args, note, rate, seconds=6.0):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, 'fit.wav')
        subprocess.run([os.path.join(ROOT, 'build/tongue-render/tongue_render'), out, '--note', str(note),
                        '--seconds', str(seconds), '--rate', str(rate), '--wheel', '0.7', '--spread', '25',
                        'mouth=0'] + list(args), check=True, capture_output=True)
        return M.decode(out)


def profile(args, note=55, rate=6.0):
    x = render(args, note, rate)
    x = x / (np.max(np.abs(x)) + 1e-12)
    f0 = 440 * 2 ** ((note - 69) / 12)
    levels = M.harmonic_levels(M.spectra(x), f0)
    top = np.percentile(levels, 90, axis=0)
    return top[:12] - top[0]


def fall(args, note=55, rate=7.0):
    x = render(args, note, rate)
    starts, db, hop = M.onsets(x)
    rates = M.ring(db, hop, starts, len(x) / M.SR)
    return float(np.median(rates)) if rates else None


def score(rel, want):
    d = rel - want
    d -= d.mean()
    return float(np.sqrt(np.mean(d ** 2)))


if __name__ == '__main__':
    args = sys.argv[1:]
    sets, note, rate = None, 55, 6.0
    while args and args[0].startswith('--'):
        if args[0] == '--sets':
            sets = args[1].split(',')
        elif args[0] == '--note':
            note = int(args[1])
        elif args[0] == '--rate':
            rate = float(args[1])
        args = args[2:]
    want, want_fall = target(sets)
    rel = profile(args, note, rate)
    print('model ', ' '.join(f'{v:5.1f}' for v in rel))
    print('target', ' '.join(f'{v:5.1f}' for v in want))
    print(f'fit {score(rel, want):.1f} dB rms   fall {fall(args, note, rate):.1f} dB/s (recordings {want_fall:.1f})')
