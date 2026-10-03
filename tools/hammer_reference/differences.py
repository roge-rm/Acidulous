#!/usr/bin/env python3
"""Every way Hammer and a reference set differ, measured, ranked.

    differences.py <hammer_render binary> <set> [--velocities 30,60,92,124] [--keys 21-108]
                   [--out folder] [name=value ...]

Renders Hammer at every recorded key and velocity layer near the ones asked
for, and puts each render beside its recording through every ruler that has
found something so far:

  pitch      cents, partials 1-8, against the recording's own
  partials   levels of partials 1-40, 20-400 ms, against the strongest
  attack     half-octave bands in 0-10, 10-40 and 40-150 ms, against the note
  settled    the same bands at 0.3-1 s and 1-3 s
  decay      dB/s of octave bands 0.25-8 kHz, 0.05-0.65 s and 0.65-3 s
  envelope   the whole note's level at 0.05, 0.5, 1, 2, 3 s against 0.1-0.2 s
  rise       10% to 90% of the first peak, ms
  between    the share of each octave's energy that isn't on a partial,
             0-60 ms and 0.3-1 s (the knock, the click, noise)
  centroid   brightness at 0-50 ms, 50-300 ms and 0.5-1.5 s, as a ratio
  body       (printed, not ranked) every note's partials against the
             recording's at the same frequency, each note's own level taken
             out: the colour of the instrument, the same for every key
  strike     what isn't on a partial in the first 100 ms, by octave to 16
             kHz, against the note, with each note's partials found in its
             own sound (the mallet on top of every note)

Everything is against each note's own level: loudness is the house law's,
not the recording's. Prints, per ruler, the typical difference by register
and velocity and the five notes that differ most, then all rulers ranked by
how far past a just-noticeable step they are.
"""
import json
import os
import subprocess
import sys
import tempfile
import warnings
from multiprocessing import Pool

import numpy as np

import measure
import tables

SR = 48000
BANDS = 2.0 ** np.arange(np.log2(60), np.log2(16000), 0.5)
OCTAVES = [(250, 500), (500, 1000), (1000, 2000), (2000, 4000), (4000, 8000)]
# A just-noticeable step for each ruler, to rank them on one scale.
STEP = {'pitch': 3.0, 'partials': 4.0, 'attack': 4.0, 'settled': 4.0, 'decay': 6.0,
        'envelope': 3.0, 'rise': 5.0, 'between': 4.0, 'centroid': 8.0, 'strike': 4.0}
UNIT = {'pitch': 'c', 'partials': 'dB', 'attack': 'dB', 'settled': 'dB', 'decay': 'dB/s',
        'envelope': 'dB', 'rise': 'ms', 'between': 'dB', 'centroid': '%', 'strike': 'dB'}


def bands(x, t0, t1, ref):
    seg = x[int(t0 * SR):int(t1 * SR)]
    if len(seg) < 64:
        return None
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)))) ** 2 / len(seg)
    f = np.fft.rfftfreq(len(seg), 1 / SR)
    return np.array([10 * np.log10(S[(f >= a) & (f < a * np.sqrt(2))].sum() / ref + 1e-20) for a in BANDS])


def floor_of(path):
    """A recording's noise floor: its quietest tenth of a second, as a spectrum, and the note's first second's level."""
    x, _ = measure.load(path, SR)
    st = measure.onset(x, SR)
    hop = SR // 10
    # Not the last second: sample files fade out, and the fade took the
    # recording's mains hum (50 Hz and up) with it, which then read as the
    # piano ringing below its notes.
    lo, hi = st + SR // 2, max(st + SR // 2 + hop, len(x) - SR - hop)
    quiet = min(range(lo, hi, hop), key=lambda i: np.mean(x[i:i + hop] ** 2))
    psd = np.abs(np.fft.rfft(x[quiet:quiet + hop] * np.hanning(hop))) ** 2
    return psd, np.mean(x[st:st + SR] ** 2) + 1e-20


def with_floor(x, floor, seed):
    """[x] with a recording's floor under it, as far below x's first second as the floor is below the recording's."""
    psd, level = floor
    hop = SR // 10
    rng = np.random.default_rng(seed)
    noise = np.concatenate([np.fft.irfft(np.sqrt(psd) * np.exp(2j * np.pi * rng.random(len(psd))), hop)
                            for _ in range(len(x) // hop + 1)])[:len(x)]
    # The floor was measured through a Hann window; undo its power loss.
    noise *= np.sqrt(1.0 / np.mean(np.hanning(hop) ** 2))
    st = measure.onset(x, SR)
    gain = np.sqrt(np.mean(x[st:st + SR] ** 2) / level)
    return x + noise * gain


def walk(x, f0, B, top=16000.0):
    """Where each partial actually is, Hz, up to [top]: found one at a time
    from the spacing of the last few, 0.05-1 s. One B puts a long bass
    string's high partials a semitone or more from where they are (they
    stretch less), and every ruler that looks for a partial looked there."""
    seg = x[int(0.05 * SR):int(1.0 * SR)]
    S = np.abs(np.fft.rfft(seg * np.blackman(len(seg)), 1 << 19))
    f = np.fft.rfftfreq(1 << 19, 1 / SR)
    hz = []
    for n in range(1, 400):
        if len(hz) >= 6:
            k = np.arange(n - 6, n)
            p = np.polyfit(k, hz[-6:], 2)
            want, gap = np.polyval(p, n), np.polyval(np.polyder(p), n)
        else:
            want, gap = n * f0 * np.sqrt(1 + B * n * n), f0 * (1 + 2 * B * n * n) / np.sqrt(1 + B * n * n)
        if not np.isfinite(want) or (hz and want <= hz[-1]):
            want, gap = (hz[-1] if hz else 0.0) + f0, f0
        if want > top or want > 0.45 * SR:
            break
        gap = max(gap, 0.5 * f0)
        m = np.abs(f - want) < 0.3 * gap
        if not m.any():
            hz.append(want)
            continue
        i = np.argmax(S[m])
        # A faint one is taken where it was expected, so one miss can't
        # send the rest astray.
        around = S[np.abs(f - want) < 0.5 * gap]
        hz.append(f[m][i] if S[m][i] > 4 * np.median(around) else want)
    return np.array(hz)


def describe(job):
    """Every ruler's reading of one note, from its onset."""
    warnings.simplefilter('ignore')
    path, key, floor_from = job
    try:
        x, sr = measure.load(path, SR)
    except Exception:
        return None
    if floor_from:
        # A render has no hiss; the recording does, and a dying note
        # disappears into it. Without it, the top octave's last second reads
        # as hundreds of dB apart.
        x = with_floor(x, floor_of(floor_from), key)
    st = measure.onset(x, sr)
    x = x[st:]
    if len(x) < 3 * SR:
        x = np.concatenate([x, np.zeros(3 * SR - len(x))])
    f0, B, found = measure.fit_partials(np.concatenate([np.zeros(10), x]), sr, 10, key)
    where = walk(x, f0, B)
    at = lambda n: where[n - 1] if n <= len(where) else np.inf
    out = {'f0': f0, 'B': B}
    ref = np.mean(x[:SR] ** 2) * 1.0 + 1e-20
    # pitch: partials 1-8 from 0.2-2 s
    seg = x[int(0.2 * SR):int(2.0 * SR)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 19))
    f = np.fft.rfftfreq(1 << 19, 1 / SR)
    hz = []
    for n in range(1, 9):
        want = n * f0 * np.sqrt(1 + B * n * n)
        m = np.abs(f - want) < f0 * 0.25
        # Only partials that stand out of what's around them.
        ok = m.any() and want < 12000 and S[m].max() > 30 * np.median(S[(f > want - f0) & (f < want + f0)])
        hz.append(f[m][np.argmax(S[m])] if ok else np.nan)
    out['hz'] = hz
    # partials: 1-40 levels, 20-400 ms
    seg = x[int(0.02 * SR):int(0.4 * SR)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 17))
    f = np.fft.rfftfreq(1 << 17, 1 / SR)
    lv = []
    for n in range(1, 41):
        want = at(n)
        if want > 15000:
            lv.append(np.nan)
            continue
        m = np.abs(f - want) < f0 * 0.3
        lv.append(20 * np.log10(S[m].max() + 1e-12))
    lv = np.array(lv)
    out['partials'] = list(lv - np.nanmax(lv))
    out['attack'] = [list(bands(x, a, b, ref)) for a, b in ((0, 0.01), (0.01, 0.04), (0.04, 0.15))]
    out['settled'] = [list(bands(x, a, b, ref)) for a, b in ((0.3, 1.0), (1.0, 3.0))]
    # decay: octave bands
    dec = []
    X = np.fft.rfft(x[:3 * SR])
    fx = np.fft.rfftfreq(3 * SR, 1 / SR)
    for a, b in OCTAVES:
        Y = X.copy()
        Y[(fx < a) | (fx >= b)] = 0
        y = np.fft.irfft(Y, 3 * SR)
        env = np.array([10 * np.log10(np.mean(y[i:i + 480] ** 2) + 1e-20) for i in range(0, len(y) - 480, 480)])
        t = np.arange(len(env)) * 0.01
        row = []
        for t0, t1 in ((0.05, 0.65), (0.65, 3.0)):
            m = (t >= t0) & (t < t1) & (env > env.max() - 60)
            row.append(np.polyfit(t[m], env[m], 1)[0] if m.sum() > 5 else np.nan)
        dec.append(row)
    out['decay'] = dec
    # envelope
    env = np.array([10 * np.log10(np.mean(x[i:i + 2400] ** 2) + 1e-20) for i in range(0, 3 * SR - 2400, 2400)])
    base = 10 * np.log10(np.mean(x[int(0.1 * SR):int(0.2 * SR)] ** 2) + 1e-20)
    out['envelope'] = [env[int(t / 0.05)] - base for t in (0.05, 0.5, 1.0, 2.0, 2.9)]
    # rise: 10% to 90% of the first peak, 1 ms steps
    e = np.array([np.sqrt(np.mean(x[i:i + 48] ** 2)) for i in range(0, int(0.2 * SR), 48)])
    pk = np.argmax(e[:100])
    i10 = np.argmax(e >= 0.1 * e[pk])
    i90 = np.argmax(e >= 0.9 * e[pk])
    out['rise'] = float(i90 - i10)
    # between: share of each octave's energy off the partials
    btw = []
    for t0, t1 in ((0.0, 0.06), (0.3, 1.0)):
        seg = x[int(t0 * SR):int(t1 * SR)]
        S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 16)) ** 2
        f = np.fft.rfftfreq(1 << 16, 1 / SR)
        near = np.zeros_like(f, bool)
        width = max(2.0 / (t1 - t0), f0 * 0.12)
        for n in range(1, 400):
            want = at(n)
            if want > 9000:
                break
            near |= np.abs(f - want) < width
        row = []
        for a, b in OCTAVES:
            m = (f >= a) & (f < b)
            row.append(10 * np.log10(S[m & ~near].sum() / (S[m].sum() + 1e-30) + 1e-12))
        btw.append(row)
    out['between'] = btw
    # centroid
    cen = []
    for t0, t1 in ((0.0, 0.05), (0.05, 0.3), (0.5, 1.5)):
        seg = x[int(t0 * SR):int(t1 * SR)]
        S = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
        f = np.fft.rfftfreq(len(seg), 1 / SR)
        m = (f > 60) & (f < 12000)
        cen.append(float((S[m] * f[m]).sum() / (S[m].sum() + 1e-20)))
    out['centroid'] = cen
    # strike: off-partial energy in the first 100 ms, each partial at its own peak
    seg = x[:int(0.1 * SR)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 16)) ** 2 / len(seg)
    f = np.fft.rfftfreq(1 << 16, 1 / SR)
    near = np.zeros_like(f, bool)
    for n in range(1, 400):
        want = at(n)
        if want > 16000:
            break
        m = np.abs(f - want) < f0 * 0.25
        if m.any():
            near |= np.abs(f - f[m][np.argmax(S[m])]) < max(25.0, f0 * 0.08)
    # body: each partial's frequency and level, 0.05-1 s
    seg = x[int(0.05 * SR):int(1.0 * SR)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 18))
    f = np.fft.rfftfreq(1 << 18, 1 / SR)
    body = {}
    for n in range(1, 120):
        want = at(n)
        if want > 12000:
            break
        m = np.abs(f - want) < f0 * 0.25
        if not m.any():
            continue
        body[n] = (float(f[m][np.argmax(S[m])]), float(20 * np.log10(S[m].max() + 1e-12)))
    out['body'] = body
    seg = x[:int(0.1 * SR)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 16)) ** 2 / len(seg)
    f = np.fft.rfftfreq(1 << 16, 1 / SR)
    out['strike'] = [10 * np.log10(S[(f >= a) & (f < 2 * a) & ~near].sum() / ref + 1e-20)
                     for a in (250, 500, 1000, 2000, 4000, 8000)]
    return out


def compare(r, h):
    """Per ruler: one number (how far apart, in its unit) and what it's made of."""
    d = {}
    hr, hh = np.array(r['hz']), np.array(h['hz'])
    c = 1200 * np.log2(hh / hr)
    d['pitch'] = (np.nanmax(np.abs(c)) if np.isfinite(c).any() else np.nan, ' '.join('%+.1f' % v for v in c))
    pr, ph = np.array(r['partials']), np.array(h['partials'])
    m = (pr > -40) | (ph > -40)
    diff = ph - pr
    d['partials'] = (np.sqrt(np.nanmean(diff[m] ** 2)), ' '.join('%d:%+.0f' % (i + 1, v) for i, v in enumerate(diff) if m[i] and abs(v) > 6))
    for name, labels in (('attack', ('0-10ms', '10-40ms', '40-150ms')), ('settled', ('0.3-1s', '1-3s'))):
        rows = []
        worst = 0.0
        for lab, a, b in zip(labels, r[name], h[name]):
            a, b = np.array(a), np.array(b)
            m = (a > -45) | (b > -45)
            dd = b - a
            worst = max(worst, np.sqrt(np.mean(dd[m] ** 2)))
            big = [(BANDS[i], dd[i]) for i in range(len(dd)) if m[i] and abs(dd[i]) > 6]
            if big:
                rows.append(lab + ' ' + ' '.join('%dHz:%+.0f' % (hz, v) for hz, v in big))
        d[name] = (worst, '; '.join(rows))
    dr, dh = np.array(r['decay']), np.array(h['decay'])
    dd = dh - dr
    d['decay'] = (np.nanmax(np.abs(dd)), '; '.join('%d-%dHz %s' % (a, b, ' '.join('%+.0f' % v for v in row))
                                                    for (a, b), row in zip(OCTAVES, dd) if np.nanmax(np.abs(row)) > 6))
    er, eh = np.array(r['envelope']), np.array(h['envelope'])
    d['envelope'] = (np.max(np.abs(eh - er)), ' '.join('%s:%+.0f' % (t, v) for t, v in zip(('0.05', '0.5', '1', '2', '3'), eh - er)))
    d['rise'] = (abs(h['rise'] - r['rise']), 'ref %.0f ms, hammer %.0f ms' % (r['rise'], h['rise']))
    br, bh = np.array(r['between']), np.array(h['between'])
    dd = bh - br
    d['between'] = (np.nanmax(np.abs(dd)), '; '.join('%s %s' % (lab, ' '.join('%d:%+.0f' % (a, v) for (a, b), v in zip(OCTAVES, row)))
                                                      for lab, row in zip(('0-60ms', '0.3-1s'), dd)))
    cr, ch = np.array(r['centroid']), np.array(h['centroid'])
    rat = 100 * (ch / cr - 1)
    d['centroid'] = (np.max(np.abs(rat)), ' '.join('%s:%+.0f%%' % (t, v) for t, v in zip(('0-50ms', '50-300ms', '0.5-1.5s'), rat)))
    sr_, sh_ = np.array(r['strike']), np.array(h['strike'])
    dd = sh_ - sr_
    d['strike'] = (float(np.sqrt(np.mean(dd ** 2))), ' '.join('%d:%+.0f' % (a, v) for a, v in zip((250, 500, 1000, 2000, 4000, 8000), dd)))
    return d


def signed(pairs, got, regs):
    """Which way: hammer minus reference, the median over notes and velocities, by register."""
    notes = [(k, got[2 * n], got[2 * n + 1]) for n, (k, v) in enumerate(sorted(pairs))]
    notes = [(k, r, h) for k, r, h in notes if r and h]

    def med(fn, lo, hi):
        xs = [fn(r, h) for k, r, h in notes if lo <= k <= hi]
        return np.nanmedian(np.array(xs, dtype=float), axis=0) if xs else None

    def table(title, cols, fn, fmt='%5.0f'):
        print(f'\n  {title}')
        print('    ' + ' ' * 7 + ' '.join('%5s' % c for c in cols))
        for name, lo, hi in regs:
            v = med(fn, lo, hi)
            if v is not None:
                print(f'    {name:7}' + ' '.join(fmt % q for q in np.atleast_1d(v)))

    print('\nwhich way: hammer minus reference, median over notes and velocities')
    hz = ['%d' % b for b in BANDS]
    for i, lab in enumerate(('0-10 ms', '10-40 ms', '40-150 ms')):
        table(f'attack {lab}, dB by band', hz, lambda r, h, i=i: np.array(h['attack'][i]) - np.array(r['attack'][i]))
    for i, lab in enumerate(('0.3-1 s', '1-3 s')):
        table(f'settled {lab}, dB by band', hz, lambda r, h, i=i: np.array(h['settled'][i]) - np.array(r['settled'][i]))
    table('envelope, dB at', ['0.05', '0.5', '1', '2', '3'], lambda r, h: np.array(h['envelope']) - np.array(r['envelope']))
    table('centroid, % at', ['0-50', '50-300', '.5-1.5'], lambda r, h: 100 * (np.array(h['centroid']) / np.array(r['centroid']) - 1))
    table('rise, ms (reference, hammer)', ['ref', 'ham'], lambda r, h: np.array([r['rise'], h['rise']]))
    table('decay, dB/s by octave, early then late', ['%d' % a for a, b in OCTAVES] * 2,
          lambda r, h: (np.array(h['decay']) - np.array(r['decay'])).T.ravel())
    table('between partials, dB by octave, 0-60 ms then 0.3-1 s', ['%d' % a for a, b in OCTAVES] * 2,
          lambda r, h: (np.array(h['between']) - np.array(r['between'])).ravel())
    table('strike (off the partials, first 100 ms), dB by octave', ['250', '500', '1k', '2k', '4k', '8k'],
          lambda r, h: np.array(h['strike']) - np.array(r['strike']))
    table('pitch, cents, partials 1-8', [str(n) for n in range(1, 9)],
          lambda r, h: 1200 * np.log2(np.array(h['hz'], dtype=float) / np.array(r['hz'], dtype=float)), '%5.1f')
    table('partials 1-24, dB', [str(n) for n in range(1, 25)],
          lambda r, h: (np.array(h['partials'], dtype=float) - np.array(r['partials'], dtype=float))[:24], '%5.0f')


def body_curve(pairs, got):
    """Hammer minus the recording by absolute frequency over every partial of every note, each note's level fitted out."""
    pts = []
    for n in range(len(pairs)):
        r, h = got[2 * n], got[2 * n + 1]
        if not (r and h):
            continue
        a = {int(k): v for k, v in r['body'].items()}
        b = {int(k): v for k, v in h['body'].items()}
        common = [k for k in a if k in b]
        if len(common) < 3:
            continue
        top = max(a[k][1] for k in common)
        pts += [(n, a[k][0], b[k][1] - a[k][1]) for k in common if a[k][1] > top - 45]
    if not pts:
        return
    pts = np.array(pts)
    edges = 2 ** np.arange(np.log2(40), np.log2(12000), 1 / 3)
    bi = np.clip(np.searchsorted(edges, pts[:, 1]) - 1, 0, len(edges) - 2)
    notes = pts[:, 0].astype(int)
    off = np.zeros(notes.max() + 1)
    curve = np.zeros(len(edges) - 1)
    for _ in range(20):
        for i in np.unique(notes):
            m = notes == i
            off[i] = np.median(pts[m, 2] - curve[bi[m]])
        for j in range(len(curve)):
            m = bi == j
            if m.sum() > 3:
                curve[j] = np.median(pts[m, 2] - off[notes[m]])
        curve -= np.median(curve[(edges[:-1] > 200) & (edges[:-1] < 1000)])
    print('\n  body: hammer minus reference by frequency, every partial, each note\'s level out, dB')
    cells = [(np.sqrt(edges[j] * edges[j + 1]), curve[j]) for j in range(len(curve)) if (bi == j).sum() > 3]
    print('    ' + ' '.join('%5.0f' % hz for hz, v in cells))
    print('    ' + ' '.join('%5.1f' % v for hz, v in cells))


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        sys.exit(__doc__)
    binary, ref_set = args[0], args[1]
    want_v, keys, out, knobs = [30, 60, 92, 124], (21, 108), None, []
    i = 2
    while i < len(args):
        if args[i] == '--velocities':
            want_v = [int(v) for v in args[i + 1].split(',')]; i += 2
        elif args[i] == '--keys':
            keys = tuple(int(v) for v in args[i + 1].split('-')); i += 2
        elif args[i] == '--out':
            out = args[i + 1]; i += 2
        else:
            knobs.append(args[i]); i += 1
    rows = [r for r in json.load(open(os.path.join(tables.folder_of(ref_set), 'survey.json')))
            if 'error' not in r and keys[0] <= r['key'] <= keys[1]]
    pairs = {}
    for k in sorted({r['key'] for r in rows}):
        for v in want_v:
            r = min([x for x in rows if x['key'] == k], key=lambda x: abs(x['velocity'] - v))
            pairs[(k, r['velocity'])] = r['file']
    out = out or tempfile.mkdtemp(prefix='hammer-differences-')
    os.makedirs(out, exist_ok=True)
    by_v = {}
    for k, v in pairs:
        by_v.setdefault(v, []).append(k)
    for v, ks in by_v.items():
        subprocess.run([binary, out, '--keys', ','.join(map(str, ks)), '--velocities', str(v), '--seconds', '3.2'] + knobs,
                       check=True, stdout=subprocess.DEVNULL)
    jobs = []
    for (k, v), f in sorted(pairs.items()):
        jobs += [(f, k, None), (os.path.join(out, 'k%03d_v%03d.wav' % (k, v)), k, f)]
    with Pool(max(1, os.cpu_count() // 2)) as pool:
        got = pool.map(describe, jobs)
    json.dump([{'key': k, 'velocity': v, 'ref': got[2 * n], 'hammer': got[2 * n + 1]} for n, (k, v) in enumerate(sorted(pairs))],
              open(os.path.join(out, 'readings.json'), 'w'), default=float)
    results = []
    for n, (k, v) in enumerate(sorted(pairs)):
        r, h = got[2 * n], got[2 * n + 1]
        if r and h:
            results.append((k, v, compare(r, h)))
    regs = [('bass', 21, 47), ('middle', 48, 71), ('treble', 72, 108)]
    vels = sorted({v for _, v, _ in results})
    ranking = []
    for ruler in STEP:
        vals = [d[ruler][0] for _, _, d in results if np.isfinite(d[ruler][0])]
        typical = float(np.median(vals)) if vals else 0.0
        ranking.append((typical / STEP[ruler], ruler, typical))
        print(f'\n{ruler}  (typical difference, {UNIT[ruler]}; a just-noticeable step is about {STEP[ruler]:g})')
        print('  ' + ' ' * 8 + ''.join(f'{"v" + str(v):>8}' for v in vels))
        for name, lo, hi in regs:
            cells = []
            for v in vels:
                xs = [d[ruler][0] for k, vv, d in results if vv == v and lo <= k <= hi and np.isfinite(d[ruler][0])]
                cells.append(f'{np.median(xs):8.1f}' if xs else f'{"-":>8}')
            print(f'  {name:8}' + ''.join(cells))
        worst = sorted(results, key=lambda t: -(t[2][ruler][0] if np.isfinite(t[2][ruler][0]) else -1))[:5]
        for k, v, d in worst:
            print(f'    key {k:3d} v{v:3d}  {d[ruler][0]:6.1f}  {d[ruler][1]}')
    signed(pairs, got, regs)
    body_curve(pairs, got)
    print('\nranked, typical difference in just-noticeable steps:')
    for steps, ruler, typical in sorted(ranking, reverse=True):
        print(f'  {ruler:9} {steps:5.1f}  ({typical:.1f} {UNIT[ruler]})')
    print(f'\noverall: {np.mean([r[0] for r in ranking]):.2f} just-noticeable steps, the mean over the rulers')
    print(f'renders in {out}')


if __name__ == '__main__':
    main()
