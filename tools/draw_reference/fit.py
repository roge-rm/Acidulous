#!/usr/bin/env python3
"""How close Draw comes to the recordings, note by note.

    fit.py accordion|melodica|harmonica [name=value ...]

Each recorded note (from measured.json) is played on Draw at the nearest
MIDI note with the kind's model, held, and measured as measure.py measured
the recording: the steady harmonics 1 to 12 against the 1st, compared after
taking out the mean (the shape), and the centroid (the brightness). Prints
each note and the medians.
"""
import json
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import fetch
import measure as M

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
KINDS = {
    'harmonica': (0, ['vcsl_special20_c', 'vcsl_special20_f'], ['Normal']),
    'accordion': (4, ['freepats_button_accordion'], ['']),
    'melodica': (7, ['freesound_melodica'], ['']),
    'harmonium': (8, ['freesound_harmonium'], ['']),
}
# One accordion's registers, one note each: the file, the register (Draw's
# footage list), and how far the key sits above the lowest sounding rank.
REGISTERS = {
    'bassoon': ('Voix_basson', 4, 12), 'flute': ('Voix_fl', 1, 0), 'piccolo': ('Voix_piccolo', 5, -12),
    'oboe': ('Registre_hautbois', 7, 0), 'organ': ('Registre_orgue', 8, 12),
    'bandoneon': ('Registre_bandon', 6, 12), 'plein': ('Plein_jeu_(trois', 9, 12),
    'tremolo': ('Registre_vibrato', 2, 0),
}
# A bandoneon, one low note: its own register (16' 8'), so the key is the 8'.
BANDONEON = ('Bandon', 0, 12)


def recorded(kind):
    if kind == 'bandoneon':
        data = json.load(open(os.path.join(fetch.folder(), 'measured.json')))
        return [dict(m, shift=BANDONEON[2]) for m in data.get('commons_accordion', [])
                if m.get('harmonics_db') and m['note'].startswith(BANDONEON[0])]
    if kind in REGISTERS or kind == 'registers':
        data = json.load(open(os.path.join(fetch.folder(), 'measured.json')))
        names = REGISTERS if kind == 'registers' else {kind: REGISTERS[kind]}
        out = []
        for name, (part, register, shift) in names.items():
            for m in data.get('commons_accordion', []):
                if m.get('harmonics_db') and m['note'].startswith(part):
                    out.append(dict(m, register=register, shift=shift))
        return out
    model, sets, layers = KINDS[kind]
    data = json.load(open(os.path.join(fetch.folder(), 'measured.json')))
    return [m for s in sets for m in data.get(s, []) if m.get('harmonics_db') and m.get('layer', '') in layers]


def render(note, args, seconds=1.6):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, 'fit.wav')
        subprocess.run([os.path.join(ROOT, 'build/draw-render/draw_render'), out, '--note', str(note),
                        '--seconds', str(seconds)] + list(args), check=True, capture_output=True)
        return M.decode(out)


def shape(rel):
    a = np.array([np.nan if v is None else v for v in rel[:12]], dtype=float)
    return a - np.nanmean(a)


def compare(kind, args):
    model = KINDS[kind][0] if kind in KINDS else 5 if kind == 'bandoneon' else 4
    rows = []
    for rec in recorded(kind):
        note = int(round(69 + 12 * np.log2(rec['hz'] / 440))) + rec.get('shift', 0)
        extra = [f'register={rec["register"]}'] if 'register' in rec else []
        x = render(note, [f'model={model}'] + extra + list(args))
        mine = M.measure_note(x / (np.max(np.abs(x)) + 1e-12), 'draw')
        if not mine or not mine.get('harmonics_db'):
            rows.append((note, None, None, rec, None))
            continue
        d = shape(mine['harmonics_db']) - shape(rec['harmonics_db'])
        d -= np.nanmean(d)
        rms = float(np.sqrt(np.nanmean(d ** 2)))
        bright = 1200 * np.log2(mine['centroid_hz'] / rec['centroid_hz']) / 100
        rows.append((note, rms, bright, rec, mine))
    return rows


if __name__ == '__main__':
    kind = sys.argv[1]
    rows = compare(kind, sys.argv[2:])
    for note, rms, bright, rec, mine in rows:
        if rms is None:
            print(f'  note {note}: no note')
            continue
        print(f"  note {note:3d}: shape {rms:4.1f} dB rms, brightness {bright:+5.1f} semitones "
              f"(centroid {mine['centroid_hz']} against {rec['centroid_hz']} Hz)")
    good = [r for r in rows if r[1] is not None]
    print(f"{kind}: {len(good)} of {len(rows)} notes; shape {np.median([r[1] for r in good]):.1f} dB rms, "
          f"brightness {np.median([r[2] for r in good]):+.1f} semitones (median)")
