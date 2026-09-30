"""Every consonant in a recorded voice, checked by what makes it that
consonant, against the singer's own take of it sung between ahs: each has to
keep at least half of what was sung, capped at a length that's natural in a
song. A stop's closure, a hiss's length, an R's low third formant, a W's low
second, a Y's high second, a nasal's hum.

    consonants.py phrases > phrases.txt   the phrases to render
    consonants.py grade <voice folder> <renders folder>

The phrases are every consonant starting a word, ending one and between two,
before ah and ee, and fourteen clusters, each on a note at 0.6 s."""
import glob
import os
import sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure import load, bank, frames, longest

CONSONANTS = ['P', 'B', 'T', 'D', 'K', 'G', 'CH', 'JH', 'F', 'V', 'TH', 'DH', 'S', 'Z', 'SH', 'ZH',
              'HH', 'M', 'N', 'NG', 'L', 'R', 'W', 'Y']
CLUSTERS = [('S T', 'AA'), ('S T R', 'IY'), ('S P', 'IY'), ('S K', 'AA'), ('B L', 'AA'), ('T R', 'AA'), ('G R', 'IY'),
            ('K L', 'IY'), ('F R', 'AA'), ('S L', 'IY'), ('S M', 'AA'), ('S N', 'IY'), ('S W', 'IY'), ('T W', 'IY')]
# What's natural in a song, in ms: no more than this is asked of any take.
CAP = {'closure': 40, 'hiss': 60, 'breath': 40, 'hum': 40, 'F3 low': 20, 'F2 low': 40, 'F2 high': 15, 'L': 15}


def phrases():
    for c in CONSONANTS:
        for v in ('AA', 'IY'):
            if c != 'NG':
                print('on-%s-%s|60|57:2:%s %s' % (c, v, c, v))
            if c not in ('HH', 'W', 'Y'):
                print('end-%s-%s|60|57:2:%s %s' % (v, c, v, c))
            if c != 'NG':
                print('mid-%s-%s|60|57:1:%s;57:1:%s %s' % (v, c, v, c, v))
    for cl, v in CLUSTERS:
        print('cl-%s-%s|60|57:2:%s %s' % (cl.replace(' ', ''), v, cl, v))


def measures(c, fr, vowel):
    quiet = lambda f: f[1] < vowel - 15
    hiss = lambda f: f[2] > -6 and f[1] > vowel - 40
    fmt = lambda f, k: f[3][k] if len(f[3]) > k else 0
    if c in ('P', 'B', 'T', 'D', 'K', 'G', 'CH', 'JH'):
        m = {'closure': longest(quiet, fr)}
        if c in ('CH', 'JH'):
            m['hiss'] = longest(hiss, fr)
        return m
    if c in ('S', 'Z', 'SH', 'ZH', 'F', 'V', 'TH', 'DH'):
        return {'hiss': longest(hiss, fr)}
    if c == 'HH':
        return {'breath': longest(lambda f: f[2] > -12 and f[1] > vowel - 35, fr)}
    if c in ('M', 'N', 'NG'):
        return {'hum': longest(lambda f: vowel - 25 < f[1] < vowel - 3 and 0 < fmt(f, 0) < 420, fr)}
    if c == 'R':
        return {'F3 low': longest(lambda f: 0 < fmt(f, 2) < 2000, fr)}
    if c == 'L':
        return {'L': longest(lambda f: f[1] < vowel - 2 and fmt(f, 2) > 2200, fr)}
    if c == 'W':
        return {'F2 low': longest(lambda f: 0 < fmt(f, 1) < 950, fr)}
    return {'F2 high': longest(lambda f: fmt(f, 1) > 2000, fr)}


def region(name):
    kind = name.split('-')[0]
    if kind in ('on', 'cl'):
        return 0.15, 0.68
    if kind == 'end':
        return 2.30, 2.95
    return 1.15, 1.68


def grade(voice, renders):
    b = bank(voice)
    need = {}
    for c in CONSONANTS:
        k = 'aa-' + c.lower()
        cut = b['cuts'][k]
        fr = frames(load(voice + '/' + b['takes'][k]), cut['consonantFrom'] / 48000 - 0.1, cut['consonantTo'] / 48000 + 0.1)
        v = np.percentile([f[1] for f in fr], 90)
        need[c] = {m: min(0.5 * val, CAP[m]) for m, val in measures(c, fr, v).items()}
    fails, n = [], 0
    for p in sorted(glob.glob(renders + '/*.wav')):
        name = os.path.basename(p)[:-4]
        parts = name.split('-')
        if parts[0] not in ('on', 'end', 'mid', 'cl'):
            continue
        n += 1
        a, e = region(name)
        fr = frames(load(p), a, e)
        v = np.percentile([f[1] for f in fr], 90)
        bad = []
        if parts[0] == 'cl':
            cl = parts[1]
            if cl.startswith('S') and measures('S', fr, v)['hiss'] < 40:
                bad.append('S hiss %d' % measures('S', fr, v)['hiss'])
            for c in ('R', 'L', 'W'):
                if c in cl[1:]:
                    got, want = list(measures(c, fr, v).values())[0], list(need[c].values())[0]
                    if got < want:
                        bad.append('%s %d<%d' % (c, got, want))
        else:
            c = parts[1] if parts[0] == 'on' else parts[2]
            got = measures(c, fr, v)
            bad = ['%s %s %d<%d' % (c, m, got[m], need[c][m]) for m in got if got[m] < need[c][m]]
        if bad:
            fails.append('%s: %s' % (name, ', '.join(bad)))
    print('%d of %d fall short of half the singer\'s own take' % (len(fails), n))
    for f in fails:
        print('   ', f)


if __name__ == '__main__':
    if sys.argv[1] == 'phrases':
        phrases()
    else:
        grade(sys.argv[2], sys.argv[3])
