"""What the Diction review measures sounds with: WAVs in, formants, levels
and hiss out. Numpy only.

The formants are found by linear prediction at 12 kHz, which is good for the
first two in a man's voice and less sure in a higher one or for the third:
where it finds nothing it says so rather than guessing."""
import json
import struct
import numpy as np

SR = 48000


def load(path):
    """A WAV as mono floats: 16, 24 or 32-bit, integer or float."""
    raw = open(path, 'rb').read()
    i, fmt, data = 12, None, b''
    while i + 8 <= len(raw):
        cid, ln = raw[i:i + 4], struct.unpack('<I', raw[i + 4:i + 8])[0]
        if cid == b'fmt ':
            fmt = struct.unpack('<HHIIHH', raw[i + 8:i + 24])
        if cid == b'data':
            data = raw[i + 8:i + 8 + ln]
            break
        i += 8 + ln + (ln & 1)
    tag, channels, bits = fmt[0], fmt[1], fmt[5]
    if tag == 3:
        x = np.frombuffer(data, np.float32)
    elif bits == 24:
        a = np.frombuffer(data[:len(data) // 3 * 3], np.uint8).reshape(-1, 3).astype(np.int32)
        x = ((a[:, 0] << 8 | a[:, 1] << 16 | a[:, 2] << 24) >> 8) / 8388608.0
    elif bits == 32:
        x = np.frombuffer(data, np.int32) / 2147483648.0
    else:
        x = np.frombuffer(data, np.int16) / 32768.0
    return x[:len(x) // channels * channels].reshape(-1, channels).mean(1)


def down(x):
    """48 kHz to 12 kHz, after a short smoothing so nothing folds over."""
    k = np.hanning(33)
    return np.convolve(x, k / k.sum(), 'same')[::4]


def formants(seg, sr=12000, order=12):
    """The formants in a short stretch at 12 kHz, lowest first, in hertz."""
    if len(seg) < order + 2:
        return []
    seg = seg * np.hamming(len(seg))
    seg = np.append(seg[0], seg[1:] - 0.95 * seg[:-1])
    r = np.correlate(seg, seg, 'full')[len(seg) - 1:len(seg) + order]
    if r[0] <= 0:
        return []
    a, e = np.zeros(order + 1), r[0]
    a[0] = 1.0
    for i in range(1, order + 1):
        k = -(r[i] + np.dot(a[1:i], r[i - 1:0:-1])) / e
        b = a.copy()
        b[i] = k
        for j in range(1, i):
            b[j] = a[j] + k * a[i - j]
        a, e = b, e * (1 - k * k)
    roots = [z for z in np.roots(a) if z.imag > 0 and abs(z) > 0.9]
    return sorted(f for f in (np.angle(z) * sr / (2 * np.pi) for z in roots) if f > 150)


def frames(x, a, b, step=0.005):
    """Every [step] s from [a] to [b]: (time, level dB, share of energy above 3 kHz in dB, formants)."""
    y, out = down(x), []
    for t in np.arange(a, b, step):
        seg = x[int(t * SR):int(t * SR) + 480]
        if len(seg) < 480:
            break
        s = np.abs(np.fft.rfft(seg * np.hanning(480))) ** 2
        f = np.fft.rfftfreq(480, 1 / SR)
        level = 10 * np.log10(s.sum() + 1e-12)
        hiss = 10 * np.log10(s[f > 3000].sum() / (s.sum() + 1e-12) + 1e-9)
        out.append((t, level, hiss, formants(y[int(t * 12000):int(t * 12000) + 300])))
    return out


def longest(cond, fr, step=0.005):
    """The longest run of frames meeting [cond], in ms."""
    best = run = 0
    for f in fr:
        run = run + 1 if cond(f) else 0
        best = max(best, run)
    return best * step * 1000


def bark(f):
    return 13 * np.arctan(0.00076 * f) + 3.5 * np.arctan((f / 7500.0) ** 2)


def bark_apart(a, b):
    """How far apart two vowels are by their first two formants, in Bark: about 1 is a different vowel."""
    return float(np.hypot(bark(a[0]) - bark(b[0]), bark(a[1]) - bark(b[1])))


def vowel_formants(x, a, b):
    """The median first and second formants from [a] to [b] seconds, or (0, 0)."""
    y, f1, f2 = down(x), [], []
    for t in np.arange(a, b, 0.02):
        fr = formants(y[int(t * 12000):int(t * 12000) + 300])
        if len(fr) >= 2 and fr[0] < 1100:
            f1.append(fr[0])
            f2.append(fr[1])
    return (float(np.median(f1)), float(np.median(f2))) if f1 else (0.0, 0.0)


def bank(folder):
    return json.load(open(folder + '/bank.json'))


def spec(folder):
    """What the app sends the engine for a voice (see VoiceBank.engineSpec): its takes that are fine."""
    b, lines = bank(folder), []
    glides = {'EY', 'AY', 'AW', 'OY', 'OW'}
    for pid, take in b['takes'].items():
        c = b.get('cuts', {}).get(pid)
        if c is None or c.get('problem'):
            continue
        path = folder + '/' + take
        if pid.startswith('v-'):
            ph = pid[2:].upper()
            if ph in glides:
                lines.append('D|%s|%s|%d|%d|%d|%d' % (ph, path, c['holdFrom'], c['holdTo'], c['glideFrom'], c['glideTo']))
            else:
                lines.append('V|%s|%s|%d|%d' % (ph, path, c['holdFrom'], c['holdTo']))
        else:
            vowel, cons = pid.split('-')
            lines.append('C|%s|%s|%s|%d|%d' % (cons.upper(), vowel.upper(), path, c['consonantFrom'], c['consonantTo']))
    return '\n'.join(lines) + '\n'
