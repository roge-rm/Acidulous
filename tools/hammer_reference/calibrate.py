#!/usr/bin/env python3
"""Calibrates Hammer's key table against a reference through the same ruler.

    calibrate.py <hammer_render binary> <set>[:group] [--rounds N] [--fields B,prompt7,...]
                 [--gain G] [--start adjust.txt] [--emit Name] [name=value ...]

What a key is (Keys.h) comes from the reference's measurements, but the model
doesn't hand back exactly what it's given: the strings mix their decays, the
partials past the 30th go flat, the measuring itself has its leanings. So
each round renders the anchor keys at two velocities, measures them with
measure.note as the recordings were measured, and moves a factor on each
design value toward what makes the measurement match: B and the six T60s
directly, the unison spread for the knee. --emit prints the factors as the
C++ table Keys.h applies (KeyTables.h); without it they're left in
<renders>/adjust.txt.
"""
import json
import math
import os
import subprocess
import sys
import tempfile
import warnings
from multiprocessing import Pool

import numpy as np

import measure
import tables

FIELDS = {  # design value: target in targets.json
    'B': 'B',
    'prompt1': 't60 1 prompt', 'after1': 't60 1 after',
    'prompt3': 't60 2-4 prompt', 'after3': 't60 2-4 after',
    'prompt7': 't60 5-10 prompt', 'after7': 't60 5-10 after',
}
VELOCITIES = '60,100'


def measured(r, field):
    if field == 'B':
        return r['B'] if r['B'] > 0 else None
    region, which = {'prompt1': ('1', 0), 'after1': ('1', 1), 'prompt3': ('2-4', 0), 'after3': ('2-4', 1),
                     'prompt7': ('5-10', 0), 'after7': ('5-10', 1)}[field]
    v = (r['t60'].get(region) or (None, None))[which]
    return v if v and v > 0 else None


def knee(r):
    v = [p['fit'][3] for p in r['partials'][:4] if p['fit']]
    return float(np.median(v)) if v else None


def one(job):
    warnings.simplefilter('ignore')
    path, key = job
    try:
        return key, measure.note(path, key)
    except Exception:
        return key, None


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        sys.exit(__doc__)
    binary, ref = args[0], args[1]
    rounds, emit, knobs = 6, None, []
    moving = set(FIELDS) | {'unison', 'contact', 'level'}
    gain, start = 0.5, None
    i = 2
    while i < len(args):
        if args[i] == '--rounds':
            rounds = int(args[i + 1]); i += 2
        elif args[i] == '--gain':
            gain = float(args[i + 1]); i += 2
        elif args[i] == '--start':
            start = args[i + 1]; i += 2
        elif args[i] == '--fields':
            moving = set(args[i + 1].split(',')); i += 2
        elif args[i] == '--emit':
            emit = args[i + 1]; i += 2
        else:
            knobs.append(args[i]); i += 1
    set_, _, group = ref.partition(':')
    want = tables.summarise(tables.folder_of(set_), group)
    anchors = want['anchors']
    factor = {k: {f: 1.0 for f in list(FIELDS) + ['unison', 'contact', 'level']} for k in anchors}
    # The level across the keyboard at a velocity near 100, against middle C.
    loud = [r for r in json.load(open(os.path.join(tables.folder_of(set_), 'survey.json')))
            if 'error' not in r and 90 <= r['velocity'] <= 110 and group.lower() in r.get('group', '').lower()]
    lk, lv = tables.per_key(loud, lambda r: r['level'])
    shape = tables.smooth(lk, lv, anchors, degree=3)
    shape = [s - shape[anchors.index(60)] if s is not None else None for s in shape]
    if start:
        for line in open(start):
            w = line.split()
            if w and int(w[0]) in factor:
                factor[int(w[0])].update({a: float(b) for a, b in (x.split('=') for x in w[1:])})
    work = tempfile.mkdtemp(prefix='hammer-calibrate-')
    adjust = os.path.join(work, 'adjust.txt')
    with Pool(max(1, os.cpu_count() // 2)) as pool:
        for round_ in range(rounds + 1):
            with open(adjust, 'w') as f:
                for k in anchors:
                    f.write(f'{k} ' + ' '.join(f'{n}={v:.5g}' for n, v in factor[k].items()) + '\n')
            out = os.path.join(work, f'round{round_}')
            os.makedirs(out)
            subprocess.run([binary, out, '--velocities', VELOCITIES, '--adjust', adjust] + knobs,
                           check=True, stdout=subprocess.DEVNULL)
            jobs = [(os.path.join(out, f), int(f[1:4])) for f in sorted(os.listdir(out)) if f.endswith('.wav')]
            rows = []
            for (path, key), (_, r) in zip(jobs, pool.map(one, jobs)):
                if r is not None:
                    rows.append({**r, 'key': key, 'velocity': int(path[-7:-4]), 'group': ''})
            json.dump(rows, open(os.path.join(out, 'survey.json'), 'w'), default=float)
            # Smoothed over the keys as the targets are, so one stray note
            # can't throw its key's factor about.
            got = tables.summarise(out)
            errors = {f: [] for f in list(FIELDS) + ['knee', 'reach', 'level']}
            hk, hv = tables.per_key([r for r in rows if r['velocity'] == 100], lambda r: r['level'])
            heard = tables.smooth(hk, hv, anchors, degree=3)
            heard = [h - heard[anchors.index(60)] if h is not None else None for h in heard]
            for i, k in enumerate(anchors):
                for f, t in FIELDS.items():
                    target, m = want[t][i], got[t][i]
                    if target is None or m is None or m <= 0:
                        continue
                    ratio = target / m
                    errors[f].append(math.log(ratio))
                    if f not in moving:
                        continue
                    step = float(np.clip(ratio ** gain, 1 / 1.5, 1.5))
                    factor[k][f] = float(np.clip(factor[k][f] * step, 0.2, 5.0))
                # How far up the strike reaches: a shorter contact reaches higher.
                target, m = want['reach Hz'][i], got['reach Hz'][i]
                if target is not None and m is not None and m > 0:
                    errors['reach'].append(math.log(target / m))
                    if 'contact' in moving:
                        step = float(np.clip((m / target) ** (1.4 * gain), 1 / 1.5, 1.5))
                        factor[k]['contact'] = float(np.clip(factor[k]['contact'] * step, 0.1, 10.0))
                if shape[i] is not None and heard[i] is not None:
                    d = shape[i] - heard[i]
                    errors['level'].append(d)
                    if 'level' in moving:
                        factor[k]['level'] = float(np.clip(factor[k]['level'] * 10 ** (np.clip(d, -6, 6) * 0.8 / 20), 0.01, 100.0))
                target, m = want['knee dB'][i], got['knee dB'][i]
                if target is not None and m is not None:
                    d = m - target
                    errors['knee'].append(d)
                    if 'unison' not in moving:
                        continue
                    # Deeper than measured: the aftersound is too weak, so the
                    # strings spread a little further apart.
                    factor[k]['unison'] = float(np.clip(factor[k]['unison'] * 10 ** np.clip(d * gain / 40, -0.1, 0.1), 0.1, 10.0))
            print(f'round {round_}: ' + '  '.join(
                f'{f} {100 * (math.exp(np.sqrt(np.mean(np.square(e)))) - 1):.0f}%' if f not in ('knee', 'level')
                else f'{f} {np.sqrt(np.mean(np.square(e))):.1f} dB' for f, e in errors.items() if e), flush=True)
    print(f'factors in {adjust}')
    if emit:
        cols = list(FIELDS) + ['unison', 'contact', 'level']
        print(f'// {emit}: tools/hammer_reference/calibrate.py, what the model needs to measure like the reference.')
        print('// Columns: key, ' + ', '.join(cols) + '.')
        print(f'constexpr Adjust k{emit}[] = {{')
        for k in anchors:
            print(f'    {{{k}, ' + ', '.join(tables.literal(factor[k][c], '.4g') for c in cols) + '},')
        print('};')


if __name__ == '__main__':
    main()
