#!/usr/bin/env python3
"""Turns a survey into what Hammer is built to: smooth curves over the keyboard.

    tables.py <set or folder>[:group] [more ...]

A set holding several instruments (one instrument file each) is told apart by
group, the instrument file's folder: electric-a:Wurl takes the groups whose
name contains "Wurl".

For each set (survey.py first), across the keys and velocity layers it has:
  B and the stretch (cents from equal temperament), fitted smoothly against key;
  the prompt and after T60 of the partial regions, from recordings that ring out;
  the knee (how far the prompt sound falls before the aftersound) and the wobble;
  brightness, reach and level against velocity, as a slope per register;
  the knock between the partials.
Prints a table at anchor keys and writes <folder>/targets.json for the
engine's key tables and for compare.py.
"""
import json
import os
import sys

import numpy as np

ANCHORS = [21, 24, 28, 33, 36, 40, 45, 48, 52, 57, 60, 64, 69, 72, 76, 81, 84, 88, 93, 96, 100, 105, 108]


def folder_of(arg):
    if os.path.isdir(arg):
        return arg
    root = os.path.expanduser(os.environ.get('ACIDULOUS_HAMMER_REF', '~/acidulous-material/hammer-reference'))
    return os.path.join(root, arg)


def smooth(keys, values, at, log=False, degree=3):
    """A low-order polynomial through (keys, values), robust to a few strays, read at [at]."""
    k = np.asarray(keys, float)
    v = np.asarray(values, float)
    ok = np.isfinite(v) & (v > 0 if log else np.isfinite(v))
    k, v = k[ok], v[ok]
    if len(k) < degree + 2:
        return [None for _ in at]
    y = np.log(v) if log else v
    keep = np.ones(len(k), bool)
    for _ in range(3):
        c = np.polyfit(k[keep], y[keep], degree)
        r = y - np.polyval(c, k)
        s = np.std(r[keep]) + 1e-12
        keep = np.abs(r) < 2.5 * s
    out = np.polyval(c, np.asarray(at, float))
    lo, hi = k.min(), k.max()
    return [float(np.exp(o) if log else o) if lo - 3 <= a <= hi + 3 else None for a, o in zip(at, out)]


def per_key(rows, field):
    by = {}
    for r in rows:
        v = field(r)
        if v is not None and np.isfinite(v):
            by.setdefault(r['key'], []).append(v)
    keys = sorted(by)
    return keys, [float(np.median(by[k])) for k in keys]


def velocity_slope(rows, field, lo, hi):
    """Per key in [lo, hi], the change of [field] per 10 velocity steps, median across keys."""
    by = {}
    for r in rows:
        if lo <= r['key'] <= hi and field(r) is not None:
            by.setdefault(r['key'], []).append((r['velocity'], field(r)))
    slopes = []
    for pts in by.values():
        if len({p[0] for p in pts}) >= 2:
            v, y = np.array(pts, float).T
            slopes.append(np.polyfit(v, y, 1)[0] * 10)
    return float(np.median(slopes)) if slopes else None


def summarise(folder, group=''):
    rows = [r for r in json.load(open(os.path.join(folder, 'survey.json')))
            if 'error' not in r and group.lower() in r.get('group', '').lower()]
    whole = [r for r in rows if r.get('whole')]
    t = {}
    keys, B = per_key(rows, lambda r: r['B'] if r['B'] > 0 else None)
    t['B'] = smooth(keys, B, ANCHORS, log=True)
    keys, c = per_key(rows, lambda r: r['cents'])
    t['cents'] = smooth(keys, c, ANCHORS)
    for region in ('1', '2-4', '5-10'):
        for i, name in ((0, 'prompt'), (1, 'after')):
            src = rows if name == 'prompt' else whole
            keys, v = per_key(src, lambda r, region=region, i=i: (r['t60'].get(region) or (None, None))[i])
            t[f't60 {region} {name}'] = smooth(keys, v, ANCHORS, log=True, degree=2)
    keys, v = per_key(rows, lambda r: np.median([p['fit'][3] for p in r['partials'][:4] if p['fit']]) if r['partials'] else None)
    t['knee dB'] = smooth(keys, v, ANCHORS, degree=2)
    keys, v = per_key(rows, lambda r: r.get('wobble'))
    t['wobble dB'] = smooth(keys, v, ANCHORS, degree=2)
    keys, v = per_key(rows, lambda r: r.get('reach_hz'))
    t['reach Hz'] = smooth(keys, v, ANCHORS, log=True, degree=2)
    keys, v = per_key(rows, lambda r: r['centroid']['start'] / r['f0'])
    t['bright x f0'] = smooth(keys, v, ANCHORS, log=True, degree=2)
    keys, v = per_key(rows, lambda r: r.get('knock'))
    t['knock dB'] = smooth(keys, v, ANCHORS, degree=2)
    regions = [(21, 47), (48, 71), (72, 108)]
    t['per 10 velocity'] = {
        f'{lo}-{hi}': {
            'level dB': velocity_slope(rows, lambda r: r['level'], lo, hi),
            'bright %': velocity_slope(rows, lambda r: 100 * np.log(r['centroid']['start']), lo, hi),
            'reach %': velocity_slope(rows, lambda r: 100 * np.log(r['reach_hz']) if r.get('reach_hz') else None, lo, hi),
        } for lo, hi in regions}
    t['anchors'] = ANCHORS
    t['notes'] = len(rows)
    t['ring out'] = len(whole)
    t['velocities'] = sorted({r['velocity'] for r in rows})
    return t


def show(name, t):
    print(f'\n{name}: {t["notes"]} notes ({t["ring out"]} ring out), velocities {t["velocities"]}')
    cols = ['B', 'cents', 't60 1 prompt', 't60 1 after', 't60 2-4 prompt', 't60 5-10 prompt', 'knee dB', 'wobble dB',
            'reach Hz', 'bright x f0', 'knock dB']
    print('  key ' + ' '.join(f'{c[:12]:>12}' for c in cols))
    for i, k in enumerate(t['anchors']):
        cells = []
        for c in cols:
            v = t[c][i]
            if v is None:
                cells.append(f'{"-":>12}')
            elif c == 'B':
                cells.append(f'{v:12.2e}')
            else:
                cells.append(f'{v:12.2f}' if abs(v) < 100 else f'{v:12.0f}')
        print(f'  {k:3d} ' + ' '.join(cells))
    print('  per 10 velocity steps:')
    for reg, d in t['per 10 velocity'].items():
        print('    keys %-7s ' % reg + '  '.join(f'{k} {v:+.2f}' if v is not None else f'{k} -' for k, v in d.items()))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for arg in sys.argv[1:]:
        name, _, group = arg.partition(':')
        folder = folder_of(name)
        t = summarise(folder, group)
        out = 'targets.json' if not group else f'targets-{group.lower().replace(" ", "-")}.json'
        json.dump(t, open(os.path.join(folder, out), 'w'), indent=1)
        show(os.path.basename(folder.rstrip('/')) + (f' ({group})' if group else ''), t)


if __name__ == '__main__':
    main()
