"""Diction's vowels in a recorded voice, measured two ways: how close each is
to the singer's own take (fidelity), and where the take sits against the
average man's English vowels (Hillenbrand and others, 1995). Distances are in
Bark; about 1 is a different vowel.

    vowels.py <voice folder> <renders folder> <notes, e.g. 45,52,57>

The renders are v-<VOWEL>-<note>.wav, a vowel held from 0.6 s for 2 s."""
import os
import sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure import load, bank, vowel_formants, bark_apart

AVERAGE_MAN = {'IY': (342, 2322), 'IH': (427, 2034), 'EH': (580, 1799), 'AE': (588, 1952), 'AA': (768, 1333),
               'AO': (652, 997), 'AH': (623, 1200), 'UH': (469, 1122), 'UW': (378, 997), 'ER': (474, 1379)}


def main(voice, renders, notes):
    b = bank(voice)
    print('vowel  take       ' + '   '.join('at %-5d' % n for n in notes) + '  apart from the take (Bark)   take from average  nearest average')
    rows = []
    for v in AVERAGE_MAN:
        c = b['cuts']['v-' + v.lower()]
        take = vowel_formants(load(voice + '/' + b['takes']['v-' + v.lower()]), c['holdFrom'] / 48000, c['holdTo'] / 48000)
        sung = [vowel_formants(load('%s/v-%s-%d.wav' % (renders, v, n)), 0.9, 2.3) for n in notes]
        apart = [bark_apart(s, take) for s in sung]
        near = min(AVERAGE_MAN, key=lambda r: bark_apart(sung[0], AVERAGE_MAN[r]))
        rows.append((v, apart, near))
        print('%-5s %4d/%4d  %s  %s   %.2f   %s' % (v, take[0], take[1], '  '.join('%4d/%4d' % s for s in sung),
                                                   ' '.join('%.2f' % a for a in apart), bark_apart(take, AVERAGE_MAN[v]), near))
    a = np.array([r[1] for r in rows])
    print('apart from the take, median by note: %s; worst %.2f Bark' % (' '.join('%.2f' % m for m in np.median(a, axis=0)), a.max()))
    print('nearest the right average vowel at the first note: %d of %d' % (sum(r[2] == r[0] for r in rows), len(rows)))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], [int(n) for n in sys.argv[3].split(',')])
