#!/usr/bin/env python3
"""Measures Hammer's renders as the references were measured, and sets them side by side.

    compare.py <renders folder> <set>[:group] [--fields B,cents,...]

The renders come from tools/hammer_render (tools/hammer_reference.sh runs
both). Each row is an anchor key; each column is render / reference for a
ratio field (B, T60s, reach, brightness) or render - reference for a
difference field (cents, knee, wobble, knock). Last, the share of keys
inside the bands the plan sets: B 15%, T60 20%, cents 1.5, reach and
brightness 20%, knee and wobble 6 dB.
"""
import json
import os
import subprocess
import sys

import tables

RATIO = {'B': 0.15, 't60 1 prompt': 0.2, 't60 1 after': 0.2, 't60 2-4 prompt': 0.2, 't60 2-4 after': 0.2,
         't60 5-10 prompt': 0.2, 't60 5-10 after': 0.2, 'reach Hz': 0.2, 'bright x f0': 0.2}
DIFF = {'cents': 1.5, 'knee dB': 6.0, 'wobble dB': 6.0, 'knock dB': 6.0}


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        sys.exit(__doc__)
    renders, ref = args[0], args[1]
    fields = list(RATIO) + list(DIFF)
    if '--fields' in args:
        fields = args[args.index('--fields') + 1].split(',')
    here = os.path.dirname(os.path.abspath(__file__))
    if not os.path.exists(os.path.join(renders, 'survey.json')) or \
            os.path.getmtime(os.path.join(renders, 'survey.json')) < os.path.getmtime(os.path.join(renders, 'renders.sfz')):
        subprocess.run([sys.executable, os.path.join(here, 'survey.py'), renders], check=True)
    set_, _, group = ref.partition(':')
    want = tables.summarise(tables.folder_of(set_), group)
    got = tables.summarise(renders)
    print('  key ' + ' '.join(f'{f[:11]:>11}' for f in fields))
    inside = {f: [0, 0] for f in fields}
    for i, k in enumerate(want['anchors']):
        cells = []
        for f in fields:
            a, b = got[f][i], want[f][i]
            if a is None or b is None:
                cells.append(f'{"-":>11}')
                continue
            if f in RATIO:
                r = a / b
                ok = abs(r - 1) <= RATIO[f]
                cells.append(f'{r:10.2f}{" " if ok else "*"}')
            else:
                d = a - b
                ok = abs(d) <= DIFF[f]
                cells.append(f'{d:+10.1f}{" " if ok else "*"}')
            inside[f][0] += ok
            inside[f][1] += 1
        print(f'  {k:3d} ' + ' '.join(cells))
    print('   in ' + ' '.join(f'{(100 * n // t if t else 0):10d}%' for n, t in inside.values()))
    print('\n  per 10 velocity, render (reference):')
    for reg, d in got['per 10 velocity'].items():
        w = want['per 10 velocity'][reg]
        print(f'    keys {reg:7s} ' + '  '.join(
            f'{k} {v:+.2f} ({w[k]:+.2f})' if v is not None and w[k] is not None else f'{k} -' for k, v in d.items()))


if __name__ == '__main__':
    main()
