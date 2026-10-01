"""Measures a struck note the way Hammer is judged: one recording at a time.

Used on the reference recordings (fetch.py) and on Hammer's own renders, so
the two are measured alike. numpy only; ffmpeg decodes whatever the file is.

What a note gives (note() returns a dict):
  f0, B        the fundamental and the inharmonicity: partials lie at
               n·f0·sqrt(1 + B·n²), fitted by least squares from the peaks
  cents        f0 against equal temperament at the note asked for
  partials     per partial: frequency, level at the start, and the decay as
               two slopes (prompt and after, dB/s), the knee's time and level,
               and how far its level wobbles around that (beating)
  t60          the prompt and after T60 of the partial regions 1, 2-4, 5-10
  centroid     brightness over the first 150 ms and around one second
  reach        how far up the partials the strike reaches: the highest
               partial within 20 dB of the strongest of the first four, in
               the first 50 ms (a harder, shorter hammer reaches further)
  level        rms of the first 300 ms, dBFS
  knock        what isn't a partial in the first 60 ms against the note's
               start, dB: the action and the board
"""
import os
import re
import subprocess

import numpy as np

NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


def midi_of(name):
    """'C4', 'Db1', 'F#2', 'Bb0' to a MIDI key (C4 = 60)."""
    m = re.fullmatch(r'([A-Ga-g])([#b]?)(-?\d)', name)
    if not m:
        return None
    k = NAMES[m.group(1).upper()] + {'#': 1, 'b': -1, '': 0}[m.group(2)]
    return k + 12 * (int(m.group(3)) + 1)


def hz_of(key):
    return 440.0 * 2.0 ** ((key - 69) / 12.0)


def load(path, sr=None):
    """Any audio file, as mono float32 and its rate (both channels summed)."""
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-f', 'f32le']
    if sr:
        cmd += ['-ar', str(sr)]
    cmd += ['-']
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    probe = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=sample_rate',
                            '-of', 'csv=p=0', path], check=True, capture_output=True, text=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy(), (sr or int(probe.strip().split(',')[0]))


# --- what each file is --------------------------------------------------------

def sfz_regions(path):
    """The regions of an SFZ file, each with what it inherits: sample, key, lovel, hivel."""
    text = open(path, encoding='utf-8', errors='replace').read()
    text = re.sub(r'//[^\n]*', '', text)
    defines = dict(re.findall(r'#define\s+(\$\w+)\s+(\S+)', text))
    for k, v in defines.items():
        text = text.replace(k, v)
    base = os.path.dirname(path)
    scopes = {'global': {}, 'master': {}, 'group': {}, 'control': {}}
    regions, current, header = [], None, None
    # Headers and opcodes; a sample path runs to the next opcode or header.
    for tok in re.finditer(r'<(\w+)>|(\w+)=((?:(?!\s+\w+=|\s*<).)*)', text, re.S):
        if tok.group(1):
            if current is not None:
                regions.append(current)
                current = None
            header = tok.group(1)
            if header in scopes:
                scopes[header] = {}
                if header == 'global':
                    scopes['master'] = {}
                    scopes['group'] = {}
                if header == 'master':
                    scopes['group'] = {}
            if header == 'region':
                current = {**scopes['global'], **scopes['master'], **scopes['group']}
            continue
        key, value = tok.group(2), tok.group(3).strip()
        if header == 'region' and current is not None:
            current[key] = value
        elif header in scopes:
            scopes[header][key] = value
    if current is not None:
        regions.append(current)
    out = []
    for r in regions:
        if 'sample' not in r:
            continue
        sample = r['sample'].replace('\\', '/')
        where = os.path.normpath(os.path.join(base, scopes['control'].get('default_path', ''), sample))
        key = r.get('pitch_keycenter') or r.get('key')
        if key is None:
            continue
        key = int(key) if key.lstrip('-').isdigit() else midi_of(key)
        # A layer crossfaded with its neighbours gives its range as the fades.
        lovel = int(r.get('lovel', r.get('xfin_lovel', 1)))
        hivel = int(r.get('hivel', r.get('xfout_hivel', 127)))
        out.append({'file': where, 'key': key, 'lovel': lovel, 'hivel': hivel,
                    'trigger': r.get('trigger', 'attack')})
    return out


def manifest(folder):
    """Every note recording under [folder]: file, key, velocity (the layer's middle)."""
    notes = []
    sfzs = [os.path.join(d, f) for d, _, fs in os.walk(folder) for f in fs if f.endswith('.sfz')]
    seen = set()
    for s in sorted(sfzs):
        for r in sfz_regions(s):
            if r['trigger'] != 'attack' or r['file'] in seen or not os.path.exists(r['file']):
                continue
            seen.add(r['file'])
            notes.append({'file': r['file'], 'key': r['key'], 'velocity': (r['lovel'] + r['hivel']) // 2,
                          'group': os.path.basename(os.path.dirname(s))})
    if notes:
        return notes
    # No instrument file: the names say. "Piano.mf.C4.aiff", "A_029__F1_3.flac".
    layer_vel = {'pp': 30, 'p': 45, 'mp': 60, 'mf': 75, 'f': 95, 'ff': 115}
    for d, _, fs in os.walk(folder):
        for f in sorted(fs):
            p = os.path.join(d, f)
            m = re.match(r'.*\.(pp|p|mp|mf|f|ff)\.([A-G][b#]?-?\d)\.\w+$', f)
            if m:
                notes.append({'file': p, 'key': midi_of(m.group(2)), 'velocity': layer_vel[m.group(1)], 'group': ''})
                continue
            m = re.match(r'\w_(\d{3})__\w+?_(\d)\.\w+$', f)
            if m:
                notes.append({'file': p, 'key': int(m.group(1)), 'velocity': int(m.group(2)) * 25, 'group': ''})
    return notes


# --- one note ------------------------------------------------------------------

def onset(x, sr):
    a = np.abs(x)
    peak = a.max()
    if peak <= 0:
        return 0
    i = int(np.argmax(a > 0.1 * peak))
    return max(0, i - int(0.003 * sr))


def spectrum(seg, sr, pad=4):
    n = len(seg)
    w = np.hanning(n)
    s = np.abs(np.fft.rfft(seg * w, pad * n))
    return s, np.fft.rfftfreq(pad * n, 1.0 / sr)


def peak_near(s, f, hz, width):
    """The peak within [hz ± width], parabolically interpolated: (frequency, amplitude) or None."""
    lo, hi = np.searchsorted(f, hz - width), np.searchsorted(f, hz + width)
    if hi - lo < 3:
        return None
    k = lo + int(np.argmax(s[lo:hi]))
    if k <= 0 or k >= len(s) - 1:
        return None
    a, b, c = np.log(s[k - 1] + 1e-20), np.log(s[k] + 1e-20), np.log(s[k + 1] + 1e-20)
    d = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0
    d = float(np.clip(d, -0.5, 0.5))
    return f[k] + d * (f[1] - f[0]), float(np.exp(b - 0.25 * (a - c) * d))


def fit_partials(x, sr, start, key):
    """f0 and B from the partials of a second's steady part, and the partials found."""
    f_et = hz_of(key)
    a = start + int(0.1 * sr)
    n = int(min(1.0, max(0.25, 40.0 / f_et)) * sr)
    seg = x[a:a + n]
    if len(seg) < n // 2:
        seg = x[start:start + n]
    s, f = spectrum(seg, sr)
    # Where the note is, from partials 2 to 6 rather than the first: the
    # lowest strings have next to no fundamental, and A0's search found a
    # stray peak 300 cents down.
    guesses = []
    for k in range(2, 7):
        q = peak_near(s, f, k * f_et, k * f_et * 0.04)
        if q:
            guesses.append((q[0] / k, q[1]))
    if guesses:
        f0 = float(np.median([g for g, _ in sorted(guesses, key=lambda g: -g[1])[:3]]))
    else:
        p = peak_near(s, f, f_et, f_et * 0.04)
        f0 = p[0] if p else f_et
    B = 0.0
    found = []
    top = min(0.9 * sr / 2, 12000.0)
    # Outward from the low partials: fit on those found so far, predict the
    # next ones from it, and widen. Searched near k·f0 alone, a bass string's
    # 30th partial is several partials away from where B = 0 puts it.
    for reach in (8, 16, 32, 64, 80):
        found = []
        for k in range(1, reach + 1):
            want = k * f0 * np.sqrt(1 + B * k * k)
            if want > top:
                break
            q = peak_near(s, f, want, max(f0 * 0.08, 3 * (f[1] - f[0])))
            if q and q[1] > s.max() * 1e-4:
                found.append((k, q[0], q[1]))
        if len(found) < 3:
            break
        ks = np.array([k for k, _, _ in found], float)
        fs = np.array([fr for _, fr, _ in found], float)
        amps = np.array([am for _, _, am in found], float)
        # (f_k / k)^2 = f0^2 + f0^2 B k^2: a straight line in k^2, weighted by level.
        wts = np.sqrt(amps / amps.max())
        A = np.vstack([np.ones_like(ks), ks * ks]).T * wts[:, None]
        y = (fs / ks) ** 2 * wts
        c, *_ = np.linalg.lstsq(A, y, rcond=None)
        if c[0] <= 0:
            break
        f0 = float(np.sqrt(c[0]))
        B = float(max(0.0, c[1] / c[0]))
        if reach * f0 > top:
            break
    return f0, B, found


def envelope(x, sr, start, hz, f0):
    """A partial's level against time, dB, from a band-limited STFT around [hz]."""
    win = int(min(0.2, max(0.02, 6.0 / f0)) * sr)
    hop = int(0.01 * sr)
    t = np.arange(start, len(x) - win, hop)
    if len(t) < 4:
        return np.array([]), np.array([])
    w = np.hanning(win)
    ph = np.exp(-2j * np.pi * hz * np.arange(win) / sr)
    lev = np.array([abs(np.dot(x[i:i + win] * w, ph)) for i in t]) / (w.sum() / 2)
    return (t - start + win / 2) / sr, 20 * np.log10(lev + 1e-9)


def two_slopes(t, db, floor):
    """Fits a decay as two straight lines in dB: (prompt dB/s, after dB/s, knee s, knee dB below start, wobble dB)."""
    keep = db > floor + 6
    # From the peak: a bass fundamental grows for a moment after the strike,
    # and fitted from the strike that rise read as a prompt sound slower than
    # the aftersound.
    i0 = int(np.argmax(db[:max(3, min(len(db) // 3, int(2.0 / max(t[1] - t[0], 1e-3))))]))
    idx = np.where(keep)[0]
    idx = idx[idx >= i0]
    if len(idx) < 8:
        return None
    end = idx[-1]
    tt, dd = t[i0:end + 1], db[i0:end + 1]
    best = None
    for k in range(3, len(tt) - 3, max(1, len(tt) // 60)):
        a1 = np.polyfit(tt[:k], dd[:k], 1)
        a2 = np.polyfit(tt[k:], dd[k:], 1)
        r = np.sum((np.polyval(a1, tt[:k]) - dd[:k]) ** 2) + np.sum((np.polyval(a2, tt[k:]) - dd[k:]) ** 2)
        if best is None or r < best[0]:
            best = (r, k, a1, a2)
    if best is None:
        a = np.polyfit(tt, dd, 1)
        return a[0], a[0], tt[-1], dd[0] - dd[-1], float(np.std(dd - np.polyval(a, tt)))
    _, k, a1, a2 = best
    if a1[0] > a2[0]:
        # A first part slower than the second isn't a prompt sound: one slope.
        a = np.polyfit(tt, dd, 1)
        return float(a[0]), float(a[0]), float(tt[-1]), 0.0, float(np.std(dd - np.polyval(a, tt)))
    fit = np.concatenate([np.polyval(a1, tt[:k]), np.polyval(a2, tt[k:])])
    return float(a1[0]), float(a2[0]), float(tt[k]), float(dd[0] - np.polyval(a1, tt[k])), float(np.std(dd - fit))


def centroid(seg, sr):
    s, f = spectrum(seg, sr, pad=1)
    p = s * s
    return float((p * f).sum() / (p.sum() + 1e-20))


def note(path, key, sr=None, partials=12):
    x, sr = load(path, sr)
    if x.size == 0 or np.abs(x).max() <= 0:
        return None
    start = onset(x, sr)
    f0, B, found = fit_partials(x, sr, start, key)
    out = {'key': key, 'f0': f0, 'B': B, 'cents': 1200 * np.log2(f0 / hz_of(key)), 'sr': sr,
           'length': (len(x) - start) / sr}
    floor = 20 * np.log10(np.sqrt(np.mean(x[-int(0.2 * sr):] ** 2)) + 1e-9)
    peak_db = 20 * np.log10(np.abs(x).max() + 1e-9)
    # A recording cut short (a looped sample, a note let go) never reaches its
    # floor: its "after" slope is how it was cut, not how it rings.
    out['whole'] = bool(floor < peak_db - 50)
    rows = []
    for k, fr, _ in found[:partials]:
        t, db = envelope(x, sr, start, fr, f0)
        if len(t) == 0:
            continue
        fit = two_slopes(t, db, floor)
        rows.append({'n': k, 'hz': fr, 'start': float(db[:5].max()), 'fit': fit})
    out['partials'] = rows

    def t60s(lo, hi):
        prompt, after = [], []
        for r in rows:
            if lo <= r['n'] <= hi and r['fit'] and r['fit'][0] < 0:
                prompt.append(-60.0 / r['fit'][0])
                if r['fit'][1] < 0:
                    after.append(-60.0 / r['fit'][1])
        return (float(np.median(prompt)) if prompt else None, float(np.median(after)) if after else None)

    out['t60'] = {'1': t60s(1, 1), '2-4': t60s(2, 4), '5-10': t60s(5, 10)}
    out['wobble'] = float(np.median([r['fit'][4] for r in rows[:6] if r['fit']])) if rows else None
    out['centroid'] = {'start': centroid(x[start:start + int(0.15 * sr)], sr),
                       'later': centroid(x[start + int(0.9 * sr):start + int(1.2 * sr)], sr)
                       if len(x) > start + int(1.2 * sr) else None}
    out['level'] = float(20 * np.log10(np.sqrt(np.mean(x[start:start + int(0.3 * sr)] ** 2)) + 1e-9))
    # How far up the strike reaches, in the first 50 ms.
    head = x[start:start + int(0.05 * sr)]
    if len(head) > 64:
        s, f = spectrum(head, sr, pad=8)
        amps = []
        for k in range(1, 200):
            hz = k * f0 * np.sqrt(1 + B * k * k)
            if hz > 0.9 * sr / 2:
                break
            q = peak_near(s, f, hz, f0 * 0.3)
            amps.append(q[1] if q else 0.0)
        amps = np.array(amps)
        if len(amps) >= 4:
            ref = amps[:4].max()
            loud = np.where(amps > ref * 0.1)[0]
            out['reach'] = int(loud[-1] + 1) if len(loud) else 1
            out['reach_hz'] = float(out['reach'] * f0)
        # What isn't a partial: the knock.
        s2, f2 = spectrum(x[start:start + int(0.06 * sr)], sr, pad=4)
        mask = np.ones_like(s2, bool)
        for k in range(1, 400):
            hz = k * f0 * np.sqrt(1 + B * k * k)
            if hz > sr / 2:
                break
            mask &= np.abs(f2 - hz) > max(f0 * 0.15, 15)
        mask &= f2 > 40
        out['knock'] = float(10 * np.log10((s2[mask] ** 2).mean() / ((s2 ** 2).mean() + 1e-20) + 1e-20))
    return out
