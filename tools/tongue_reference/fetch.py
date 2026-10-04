#!/usr/bin/env python3
"""Fetches the recordings Tongue is measured against.

The list is `sources.json` in the reference folder (ACIDULOUS_TONGUE_REF, by
default ~/acidulous-material/tongue-reference), never in the repo: what an
instrument was and who recorded it go there and in docs/lineage.md, and only
the numbers measured from them are committed. Each set names its licence; a
set without one is refused.

    fetch.py [set ...]     every set when none is named

A set is a "url" (an archive, unpacked by "unpack": tar, 7z, zip or none),
"urls" (single files), or "api_dir" (a GitHub folder, every file in it). It
lands in <folder>/<set>/, with `fetched.json` recording what came and its
sha256, so it isn't fetched again.
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request


def folder():
    return os.path.expanduser(os.environ.get('ACIDULOUS_TONGUE_REF', '~/acidulous-material/tongue-reference'))


def download(url, path):
    if os.path.exists(path):
        return
    tmp = path + '.part'
    # A server that stops sending is given up on (one hung the whole fetch for
    # an hour), and tried again.
    # Wikimedia throttles clients that don't say who they are.
    subprocess.run(['curl', '-sSL', '--fail', '--retry', '3', '--speed-limit', '2000', '--speed-time', '30',
                    '-A', 'acidulous-reference/1.0 (https://github.com/roge-rm/Acidulous)', '-o', tmp, url], check=True)
    os.replace(tmp, path)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def unpack(path, into, how):
    if how == 'tar':
        subprocess.run(['tar', '-xf', path, '-C', into], check=True)
    elif how == '7z':
        subprocess.run(['7z', 'x', '-y', '-o' + into, path], check=True, stdout=subprocess.DEVNULL)
    elif how == 'zip':
        subprocess.run(['7z', 'x', '-y', '-o' + into, path], check=True, stdout=subprocess.DEVNULL)


def fetch(name, spec, root):
    if not spec.get('licence'):
        print(f'  {name}: no licence given, so not fetched')
        return
    here = os.path.join(root, name)
    done = os.path.join(here, 'fetched.json')
    if os.path.exists(done):
        print(f'  {name}: already here')
        return
    os.makedirs(here, exist_ok=True)
    got = {}
    if 'url' in spec:
        archive = os.path.join(here, os.path.basename(urllib.parse.urlparse(spec['url']).path))
        download(spec['url'], archive)
        got[os.path.basename(archive)] = sha256(archive)
        if spec.get('unpack', 'none') != 'none':
            unpack(archive, here, spec['unpack'])
            os.remove(archive)
    urls = list(spec.get('urls', []))
    if 'api_dir' in spec:
        with urllib.request.urlopen(spec['api_dir']) as r:
            urls += [item['download_url'] for item in json.load(r) if item.get('type') == 'file']
    for u in urls:
        path = os.path.join(here, urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(u).path)))
        download(u, path)
        got[os.path.basename(path)] = sha256(path)
    json.dump({'licence': spec['licence'], 'attribution': spec.get('attribution', ''), 'files': got},
              open(done, 'w'), indent=1)
    print(f'  {name}: {len(got)} files')


def main():
    root = folder()
    sources = json.load(open(os.path.join(root, 'sources.json')))
    names = sys.argv[1:] or list(sources)
    for n in names:
        if n not in sources:
            sys.exit(f'no set called {n}')
        fetch(n, sources[n], root)


if __name__ == '__main__':
    main()
