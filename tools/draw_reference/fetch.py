#!/usr/bin/env python3
"""Fetches the recordings Draw is measured against.

As tools/tongue_reference/fetch.py, with its own folder: ACIDULOUS_DRAW_REF,
by default ~/acidulous-material/draw-reference, holding `sources.json`.

    fetch.py [set ...]     every set when none is named
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'tongue_reference'))
import fetch as shared


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
