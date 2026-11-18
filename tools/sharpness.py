#!/usr/bin/env python3
"""DIN 45692 sharpness of rendered wavs, in acum.

    tools/sharpness.py [--gain DB] FILE.wav... | FOLDER...

--gain turns every file up or down first, to read a render at the level it
will be played at. Sharpness moves a little with level.

For each file: the sharpness of its loudest 2 s, whether it is sustained, and
a flag on sustained files over 1.65 acum. A held sound that sharp is heard as
too loud, even when its loudness matches the rest; plucked and struck ones
get away with more because they decay.

Sustained means over 70% of the 100 ms windows of the playing part (from
the first to the last window within 20 dB of the loudest) sit within 6 dB of
the loudest window in the second before them. Against the file's loudest
400 ms instead, a phrase of slow plucks (a nylon string, a jaw harp, a
slicer's loop) reads as sustained.

Loudness is Zwicker's for stationary sounds (ISO 532-1), free field, with
full scale (a sample value of 1.0 rms) at 95 dB SPL; sharpness is DIN 45692
from that loudness. Both come from mosqito, which lives in a venv on the
scratch disk:

    /mnt/scratch/acidulous/venv/bin/python tools/sharpness.py FOLDER

If the venv is missing:

    uv venv /mnt/scratch/acidulous/venv
    VIRTUAL_ENV=/mnt/scratch/acidulous/venv uv pip install mosqito matplotlib
"""
import os
import sys

import numpy as np
from scipy.io import wavfile
from mosqito.sq_metrics import loudness_zwst, sharpness_din_from_loudness

FULL_SCALE_DB_SPL = 95.0
WINDOW_S = 2.0
LIMIT = 1.65


def read_mono(path):
    fs, data = wavfile.read(path)
    data = np.asarray(data)
    if data.dtype.kind == "i":
        data = data.astype(np.float64) / float(np.iinfo(data.dtype).max)
    elif data.dtype.kind == "u":
        data = (data.astype(np.float64) - 128.0) / 128.0
    else:
        data = data.astype(np.float64)
    if data.ndim > 1:
        data = data.mean(axis=1)
    return fs, data


def window_levels(x, fs, seconds, hop_seconds):
    """Level in dB of each window, and where each starts."""
    n, hop = int(seconds * fs), int(hop_seconds * fs)
    if len(x) <= n:
        return np.array([10 * np.log10(np.mean(x**2) + 1e-30)]), np.array([0])
    starts = np.arange(0, len(x) - n + 1, hop)
    sq = np.concatenate(([0.0], np.cumsum(x**2)))
    levels = 10 * np.log10((sq[starts + n] - sq[starts]) / n + 1e-30)
    return levels, starts


def sustained(x, fs):
    levels, _ = window_levels(x, fs, 0.1, 0.05)
    playing = np.nonzero(levels > levels.max() - 20.0)[0]
    span = levels[playing[0]:playing[-1] + 1]
    back = 20  # one second of 50 ms hops
    held = [span[i] > span[max(0, i - back):i + 1].max() - 6.0 for i in range(len(span))]
    return np.mean(held) > 0.7


def sharpness(path, gain_db=0.0):
    fs, x = read_mono(path)
    x = x * 10 ** (gain_db / 20.0)
    levels, starts = window_levels(x, fs, WINDOW_S, 0.25)
    at = starts[int(np.argmax(levels))]
    seg = x[at:at + int(WINDOW_S * fs)]
    pa = seg * 20e-6 * 10 ** (FULL_SCALE_DB_SPL / 20.0)
    N, N_spec, _ = loudness_zwst(pa, fs, field_type="free")
    return float(sharpness_din_from_loudness(N, N_spec)), float(N), sustained(x, fs)


def files(args):
    for a in args:
        if os.path.isdir(a):
            for f in sorted(os.listdir(a)):
                if f.lower().endswith(".wav"):
                    yield os.path.join(a, f)
        else:
            yield a


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    args, gain = sys.argv[1:], 0.0
    if len(args) >= 2 and args[0] == "--gain":
        gain, args = float(args[1]), args[2:]
    print(f"{'acum':>6} {'sone':>6}  {'kind':<9} file")
    over = 0
    for path in files(args):
        s, n, held = sharpness(path, gain)
        flag = held and s > LIMIT
        over += flag
        print(f"{s:6.2f} {n:6.1f}  {'sustained' if held else 'decays':<9} {os.path.basename(path)}"
              f"{'   <- sharp' if flag else ''}")
    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main())
