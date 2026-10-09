#!/usr/bin/env python3
"""Draws the controller pictures in manual/images: the Exquis's pages and
the Launchpad Pro's, as the app lays them out (midi/exquis/ExquisSurface.kt,
midi/launchpad/Surface.kt). Run it again after changing a layout:

    python3 tools/controller_pictures.py

Needs ffmpeg, which turns the drawings into PNGs.
"""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "manual" / "images"

BG, PANEL, EDGE, INK, MUTED = "#15191b", "#1f2427", "#6c767c", "#e6eaec", "#9aa4aa"
PAD, KNOB, DARK = "#1a1d20", "#22402a", "#2a2f33"
# Sample track colours, as the app might give them.
TRACKS = ["#b8434f", "#d08a2e", "#c9b33a", "#4f9a4a", "#3f8fb5", "#6b62c4", "#a85cb8", "#4aa89a", "#c46a6a", "#7a8a3a"]
MUTE, SOLO, GREEN, RED, WHITE = "#c45a1e", "#2f6fc4", "#2f8f4a", "#a83232", "#dcdcdc"
FONT = "DejaVu Sans Mono, monospace"


def text(x, y, s, size=13, fill=INK, anchor="middle", weight="normal"):
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    return (f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" fill="{fill}" '
            f'text-anchor="{anchor}" font-weight="{weight}" dominant-baseline="middle">{s}</text>')


def render(name, width, height, body):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">'
           f'<rect width="{width}" height="{height}" fill="{BG}"/>{body}</svg>')
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg)
        path = f.name
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path, str(OUT / name)], check=True)
    pathlib.Path(path).unlink()
    print("wrote", OUT / name)


# --- The Exquis, upright, knobs at the top ------------------------------------

W, H = 520, 980
HEX = 27  # a pad's radius


def row_len(r):
    return 6 if r % 2 == 0 else 5


def pad_centre(r, c):
    """Row 0 is at the bottom, next to the buttons."""
    # Centred on the panel: a row of 6 spans five steps, a row of 5 four.
    step = HEX * 1.732 * 1.06
    x = W / 2 + (c - (row_len(r) - 1) / 2) * step
    y = 640 - r * HEX * 1.5 * 1.06
    return x, y


def hexagon(x, y, fill, label="", ink=INK, size=11):
    pts = []
    for i in range(6):
        import math
        a = math.radians(60 * i + 30)
        pts.append(f"{x + HEX * math.cos(a):.1f},{y + HEX * math.sin(a):.1f}")
    out = f'<polygon points="{" ".join(pts)}" fill="{fill}" stroke="{EDGE}" stroke-width="1.5"/>'
    if label:
        out += text(x, y, label, size=size, fill=BG if fill == WHITE else ink, weight="bold")
    return out


def exquis(name, title, knobs, knob_note, pads, arrows, slider, buttons, side=None):
    """pads: {(row, col): (fill, label)}. buttons: labels under settings,
    sound, record, loop, clips and play. side: {row: label} beside the rows."""
    b = f'<rect x="20" y="10" width="{W - 40}" height="{H - 20}" rx="22" fill="{PANEL}" stroke="{EDGE}"/>'
    b += text(W / 2, 38, title, size=16, weight="bold")
    for i, k in enumerate(knobs):
        x = 105 + i * 103
        b += text(x, 70, k, size=12)
        b += f'<circle cx="{x}" cy="104" r="22" fill="{KNOB}" stroke="{EDGE}"/>'
    b += text(W / 2, 142, knob_note, size=11, fill=MUTED)
    for r in range(11):
        for c in range(row_len(r)):
            x, y = pad_centre(r, c)
            fill, label = pads.get((r, c), (PAD, ""))
            b += hexagon(x, y, fill, label)
        if side and r in side:
            b += text(W - 30, pad_centre(r, 0)[1], side[r], size=10, fill=MUTED, anchor="end")
    y = 700
    b += f'<rect x="50" y="{y}" width="110" height="44" rx="8" fill="{DARK}" stroke="{EDGE}"/>'
    b += text(105, y + 22, "▼ ▲", size=16)
    b += f'<rect x="175" y="{y}" width="190" height="44" rx="8" fill="{PAD}" stroke="{EDGE}"/>'
    b += text(270, y + 22, "slider", size=12)
    b += f'<rect x="380" y="{y}" width="100" height="44" rx="8" fill="{DARK}" stroke="{EDGE}"/>'
    b += text(430, y + 22, "undo redo", size=12)
    b += text(105, y + 64, arrows, size=11, fill=MUTED)
    b += text(270, y + 64, slider, size=11, fill=MUTED)
    b += text(430, y + 64, "undo, redo", size=11, fill=MUTED)
    names = ["settings", "sound", "record", "loop", "clips", "play"]
    fills = [DARK, DARK, "#6a2424", DARK, "#24506a", DARK]
    for i, (n, label) in enumerate(zip(names, buttons)):
        x = 72 + i * 75
        b += f'<circle cx="{x}" cy="840" r="27" fill="{fills[i]}" stroke="{EDGE}"/>'
        b += text(x, 840, n, size=9, fill=MUTED)
        for j, line in enumerate(label.split("\n")):
            b += text(x, 888 + j * 16, line, size=11)
    render(name, W, H, b)


BOTTOM = ["its own", "its own", "record", "loop", "pages", "play,\nstop"]


def exquis_pages():
    # Play: the Exquis's own layout at its own octave (the bottom-left pad is
    # D#1), with C major lit. Semitones to the right, thirds going up.
    notes = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

    def note_at(r, c):
        return 27 + (r // 2) * 7 + (4 if r % 2 else 0) + c

    pads = {}
    for r in range(11):
        for c in range(row_len(r)):
            n = note_at(r, c)
            name = notes[n % 12]
            label = name + (str(n // 12 - 1) if name == "C" else "")
            in_key = "#" not in name
            pads[(r, c)] = ("#3a2a4d" if name == "C" else ("#24394d" if in_key else PAD), label)
    exquis("exquis-play.png", "Play", ["knob 1", "knob 2", "knob 3", "knob 4"],
           "the open machine's knobs; click for the next four", pads,
           "octave", "the track's level", BOTTOM)

    # Play on a drum machine: three and two pads a row, stacked up the
    # middle, kick at the bottom left.
    count = 16

    def block(first):
        out, r = [], first
        while len(out) < count and r < 11:
            out += [(r, c) for c in range(2, 5 if r % 2 == 0 else 4)][:count - len(out)]
            r += 1
        return out
    spots = min((block(f) for f in range(11) if len(block(f)) == count),
                key=lambda b: abs(b[0][0] + b[-1][0] - 10))
    best = {i: (0, rc) for i, rc in enumerate(spots[:count])}
    pads = {rc: ("#1f5a2c", str(i + 1)) for i, (_, rc) in best.items()}
    exquis("exquis-drums.png", "Play, on a drum machine", ["knob 1", "knob 2", "knob 3", "knob 4"],
           "the open machine's knobs; click for the next four", pads,
           "octave", "the track's level", BOTTOM, {6: "1 is the kick"})

    # Session: a track a row, scenes across, scene launches at the bottom.
    pads = {}
    import random
    random.seed(4)
    for r in range(1, 11):
        colour = TRACKS[10 - r]
        for c in range(5):
            if random.random() < 0.6:
                pads[(r, c)] = (colour, "")
    # More scenes to the right: each row of 6's last pad, faintly.
    for r in range(2, 11, 2):
        pads[(r, 5)] = ("#2a2e31", "")
    pads[(10, 1)] = (TRACKS[0], "▶")
    pads[(8, 1)] = (TRACKS[2], "▶")
    for c in range(5):
        pads[(0, c)] = ("#1f4a2c", str(c + 1))
    pads[(0, 5)] = ("#4a1f1f", "■")
    side = {10: "track 1", 9: "track 2", 1: "track 10", 0: "scenes"}
    exquis("exquis-session.png", "Session", ["scenes", "tracks", "", ""],
           "turn to scroll; click for the next five scenes or ten tracks", pads, "tracks, by five", "the track's level", BOTTOM, side)

    # Mixer: select, mute, solo and a level bar on each track's row.
    pads = {}
    for r in range(1, 11):
        colour = TRACKS[10 - r]
        pads[(r, 0)] = (colour, "")
        pads[(r, 1)] = (MUTE if r == 7 else "#2a1d14", "M")
        pads[(r, 2)] = (SOLO if r == 9 else "#141d2a", "S")
        bar = row_len(r) - 3
        lit = [3, 2, 2, 1, 3, 2, 1, 2, 3, 2][r - 1] if bar == 3 else [2, 1, 2, 1, 2][(r - 1) // 2]
        for i in range(bar):
            pads[(r, 3 + i)] = (colour if i < lit else DARK, "")
    exquis("exquis-mixer.png", "Mixer", ["level 1", "level 2", "level 3", "level 4"],
           "the levels of the top four tracks", pads, "tracks, by five", "the track's level", BOTTOM,
           {10: "track 1", 1: "track 10"})

    # Steps: sixteen steps at the top, the notes to choose from, the bar's pages.
    pads = {}
    steps = [(10, c) for c in range(6)] + [(9, c) for c in range(5)] + [(8, c) for c in range(5)]
    on = {0, 4, 8, 10, 12}
    for i, (r, c) in enumerate(steps):
        pads[(r, c)] = (TRACKS[4] if i in on else ("#2c3136" if i % 4 == 0 else DARK), str(i + 1))
    lanes = [(r, c) for r in range(6, 1, -1) for c in range(row_len(r))]
    names = ["kick", "rim", "snare", "clap", "tom", "tom", "tom", "c hat", "o hat", "crash", "ride", "bell", "clave"]
    for i, (r, c) in enumerate(lanes[:len(names)]):
        pads[(r, c)] = (TRACKS[4] if i == 0 else ("#22313a" if i in (2, 7) else PAD), names[i])
    for c in range(6):
        pads[(0, c)] = (WHITE if c == 0 else ("#2c3136" if c < 2 else PAD), str(c + 1) if c < 2 else "")
    exquis("exquis-steps.png", "Steps", ["knob 1", "knob 2", "knob 3", "knob 4"],
           "the open machine's knobs; click for the next four", pads,
           "octave (not drums)", "the track's level", BOTTOM,
           {10: "steps", 6: "notes", 0: "bars"})


# --- The Launchpad Pro ---------------------------------------------------------

LW, LH = 760, 800
CELL = 66


def launchpad(name, title, grid, top, right, bottom, left, controls, note=""):
    """grid: {(row, col): (fill, label)}, row 0 at the bottom."""
    b = f'<rect x="10" y="10" width="{LW - 20}" height="{LH - 20}" rx="18" fill="{PANEL}" stroke="{EDGE}"/>'
    b += text(LW / 2, 34, title, size=16, weight="bold")
    x0, y0 = 120, 110
    def cell(x, y, fill, label, w=CELL - 8, h=CELL - 8, size=11):
        return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="7" fill="{fill}" stroke="{EDGE}"/>' +
                (text(x + w / 2, y + h / 2, label, size=size, weight="bold") if label else ""))
    b += cell(30, 58, DARK, "Shift", w=80, h=36, size=9)
    for i, label in enumerate(top):
        b += cell(x0 + i * CELL, 58, DARK, label, h=36, size=9)
    for r in range(8):
        for c in range(8):
            fill, label = grid.get((r, c), (PAD, ""))
            b += cell(x0 + c * CELL, y0 + (7 - r) * CELL, fill, label)
    for i, label in enumerate(right):
        b += cell(x0 + 8 * CELL + 8, y0 + i * CELL, DARK, label, w=70, size=9)
    for i, label in enumerate(bottom):
        b += cell(x0 + i * CELL, y0 + 8 * CELL + 8, DARK, label, h=36, size=9)
    for i, label in enumerate(controls):
        b += cell(x0 + i * CELL, y0 + 8 * CELL + 52, DARK, label, h=36, size=9)
    for i, label in enumerate(left):
        b += cell(30, y0 + i * CELL, DARK, label, w=80, size=9)
    if note:
        b += text(LW / 2, LH - 30, note, size=12, fill=MUTED)
    render(name, LW, LH, b)


def launchpad_pages():
    top = ["◀", "▶", "Session", "Note", "Chord", "Custom", "Seq", "Projects"]
    right = [f"track {i + 1}" for i in range(8)]
    bottom = [f"scene {i + 1}" for i in range(8)]
    left = ["▲", "▼", "Clear", "Duplicate", "Quantise", "Fixed", "Play", "Record"]
    controls = ["Rec Arm", "Mute", "Solo", "Volume", "Pan", "Sends", "Device", "Stop"]
    # Note: white keys on even rows, black keys above them; C3 at the bottom left.
    white = ["C", "D", "E", "F", "G", "A", "B", "C"]
    black = ["", "C#", "D#", "", "F#", "G#", "A#", ""]
    grid = {}
    for octave in range(4):
        for c in range(8):
            grid[(octave * 2, c)] = ("#3a2a4d" if white[c] == "C" else "#24394d", white[c] + (str(3 + octave) if white[c] == "C" and c == 0 else ""))
            if black[c]:
                grid[(octave * 2 + 1, c)] = ("#18222c", black[c])
    launchpad("launchpad-note.png", "Note", grid, top, right, bottom, left, controls,
              "the scale lit, the root brightest; up and down change the octave")
    grid = {}
    import random
    random.seed(7)
    for r in range(8):
        colour = TRACKS[7 - r]
        for c in range(8):
            if random.random() < 0.55:
                grid[(r, c)] = (colour, "")
    grid[(7, 2)] = (TRACKS[0], "▶")
    grid[(5, 2)] = (TRACKS[2], "▶")
    launchpad("launchpad-session.png", "Session", grid, top, right, bottom, left, controls,
              "tracks down, scenes across, like the song grid")


if __name__ == "__main__":
    exquis_pages()
    launchpad_pages()
