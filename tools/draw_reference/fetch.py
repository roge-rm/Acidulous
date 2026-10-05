#!/usr/bin/env python3
"""Fetches the recordings Draw is measured against.

As tools/tongue_reference/fetch.py, with its own folder: ACIDULOUS_DRAW_REF,
by default ~/acidulous-material/draw-reference, holding `sources.json`.

    fetch.py [set ...]     every set when none is named
"""
import importlib.util
import json
import os
import sys

# Tongue's fetch, by its path: putting its folder on the module path would
# shadow this folder's measure.py with Tongue's.
_spec = importlib.util.spec_from_file_location(
    'tongue_fetch', os.path.join(os.path.dirname(__file__), '..', 'tongue_reference', 'fetch.py'))
shared = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shared)


def folder():
    return os.path.expanduser(os.environ.get('ACIDULOUS_DRAW_REF', '~/acidulous-material/draw-reference'))


def main():
    root = folder()
    sources = json.load(open(os.path.join(root, 'sources.json')))
    for n in sys.argv[1:] or list(sources):
        if n not in sources:
            sys.exit(f'no set called {n}')
        shared.fetch(n, sources[n], root)


if __name__ == '__main__':
    main()
