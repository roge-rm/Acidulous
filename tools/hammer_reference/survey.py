#!/usr/bin/env python3
"""Measures every note of a reference set (or a folder of renders).

    survey.py <set or folder> [--keys 21-108] [--jobs N]

Writes <folder>/survey.json: one measure.note() per recording, with its key
and velocity from the set's instrument files or its file names. Release-only
samples are skipped. Run fetch.py first.
"""
import json
import multiprocessing
import os
import sys
import warnings

import measure


def folder_of(arg):
    if os.path.isdir(arg):
        return arg
    root = os.path.expanduser(os.environ.get('ACIDULOUS_HAMMER_REF', '~/acidulous-material/hammer-reference'))
    return os.path.join(root, arg)


def one(entry):
    warnings.simplefilter('ignore')
    try:
        r = measure.note(entry['file'], entry['key'])
    except Exception as e:  # a file that won't decode is reported, not fatal
        return {**entry, 'error': str(e)}
    if r is None:
        return {**entry, 'error': 'silent'}
    return {**entry, **r}


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    where = folder_of(args[0])
    lo, hi, jobs = 0, 127, max(1, multiprocessing.cpu_count() // 2)
    if '--keys' in args:
        lo, hi = map(int, args[args.index('--keys') + 1].split('-'))
    if '--jobs' in args:
        jobs = int(args[args.index('--jobs') + 1])
    notes = [n for n in measure.manifest(where) if lo <= n['key'] <= hi]
    with multiprocessing.Pool(jobs) as pool:
        results = pool.map(one, notes, chunksize=1)
    results.sort(key=lambda r: (r.get('group', ''), r['key'], r['velocity']))
    json.dump(results, open(os.path.join(where, 'survey.json'), 'w'), indent=1, default=float)
    bad = [r for r in results if 'error' in r]
    print(f'{where}: {len(results) - len(bad)} notes measured, {len(bad)} not')


if __name__ == '__main__':
    main()
