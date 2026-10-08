#!/usr/bin/env python3
"""Diction's lyric chords for the FM-1, and a check of them against English.

    python3 fm1/chords.py FREQ            check the table, FREQ a "word count" list
    python3 fm1/chords.py FREQ -v         and list more of the misreadings
    python3 fm1/chords.py FREQ --table    print every chord the FM-1 reads, as Markdown
    python3 fm1/chords.py FREQ --c        write fm1/chord_tables.h, the same for the decoder

A syllable is one chord on the FM-1's 27 keys: starting consonants under the
left hand (F3..B3), the vowel under the thumbs (C4..E4), ending consonants
under the right (F4..D#5). See fm1/CHORDS.md.

The FM-1 reads each bank of a chord on its own: the left keys as a run of
consonants, the vowel keys as a vowel, the right keys as a run of consonants.
The check splits every word in Diction's dictionary into syllables as
Lyrics.syllables does, writes each as a chord, reads it back bank by bank
(each piece of a chord as the commonest thing written with it) and counts,
weighted by how often the words are used, the syllables that come back wrong.
"""
import pathlib
import struct
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent

# ---- the keys -------------------------------------------------------------

# Steno's names for the keys, bank by bank; the tables below spell chords with them.
ONSET_KEYS = {"S": "F3", "T": "F#3", "K": "G3", "P": "G#3", "W": "A3", "H": "A#3", "R": "B3"}
VOWEL_KEYS = {"A": "C4", "O": "C#4", "@": "D4", "E": "D#4", "U": "E4"}
CODA_KEYS = {"*": "F4", "-F": "F#4", "-R": "G4", "-P": "G#4", "-B": "A4", "-L": "A#4", "-G": "B4",
             "-S": "C5", "-T": "C#5", "-Z": "D5", "-D": "D#5"}
COMMAND_KEYS = {"hold": "E5", "rest": "F5", "delete": "F#5", "play": "G5"}

# ---- the sounds -----------------------------------------------------------

# Starting consonants, as steno has them where steno is phonetic.
ONSET = {
    "S": "S", "T": "T", "K": "K", "P": "P", "W": "W", "HH": "H", "R": "R",
    "B": "PW", "D": "TK", "F": "TP", "G": "TKPW", "L": "HR", "M": "PH", "N": "TPH",
    "V": "SR", "Y": "KWR", "Z": "STKPW", "CH": "KH", "SH": "SH", "TH": "TH", "JH": "SKWR",
    # Steno spells these by their letters, so they're ours.
    "DH": "THR", "ZH": "SKWRH",
}
# Clusters whose keys added up would read as something commoner.
ONSET_CLUSTER = {
    ("SH", "R"): "SWHR",   # S + HR is SL (slow)
    ("TH", "R"): "TWHR",   # TH + R is DH
    ("G", "W"): "TKPWH",   # G has W in it already (language)
}

# Vowels, as steno's long-vowel combinations, and the schwa key.
VOWEL = {
    "AE": "A", "AA": "O", "EH": "E", "AH": "U", "IH": "EU",
    "EY": "AEU", "IY": "AOE", "AY": "AOEU", "OW": "OE", "UW": "AOU", "UH": "AO",
    "AW": "OU", "OY": "OEU", "AO": "AU", "AX": "@",
}
# A Y after a consonant (cute, few, music, particular) goes with the vowel, as
# the schwa key added to it. On its own, Y is KWR.
Y_VOWEL = {"UW": "@AOU", "UH": "@AO", "AX": "@U", "ER": "@U"}
# ER is the schwa and an R: @ (or @U after a Y) with -R.
ER = ("@", "-R")

CODA = {
    "F": "-F", "R": "-R", "P": "-P", "B": "-B", "L": "-L", "G": "-G",
    "T": "-T", "S": "-S", "D": "-D", "Z": "-Z",
    "M": "-PL", "N": "-PB", "K": "-BG", "NG": "-PBG", "SH": "-RB", "CH": "-FP", "JH": "-PBLG",
    "V": "*F", "TH": "*T", "DH": "*D", "ZH": "*Z",
}
CODA_CLUSTER = {
    ("NG", "K"): "*PBG",     # think: PBG + BG is thing
    ("N", "K"): "*PBG",
    ("M", "P"): "*PL",       # jump: PL + P is M
    ("L", "P"): "*LG",       # help: L + P is M
    ("L", "M"): "*PLB",      # film: L + PL is M
    ("R", "B"): "*RB",       # curb: R + B is SH
    ("L", "B"): "*LB",       # bulb
    ("N", "JH"): "*PBLG",    # change: PB + PBLG is JH
    ("S", "K", "T"): "-SBGD",  # asked, as it's spelled: -S -BG -T is next
}

VOWELS = set(VOWEL) | {"ER"}


def keys_of(spec):
    """'TKPW' -> {T, K, P, W}; '-PBG' -> {-P, -B, -G}; '*PL' -> {*, -P, -L}."""
    right = spec[0] in "-*"
    return {"*" if c == "*" else ("-" + c if right else c) for c in spec if c != "-"}


def onset_keys(onset):
    if onset in ONSET_CLUSTER:
        return keys_of(ONSET_CLUSTER[onset])
    keys = set()
    for p in onset:
        if p not in ONSET:
            return None
        keys |= keys_of(ONSET[p])
    return keys


def coda_keys(coda):
    """A run of ending consonants. A cluster that isn't in the table is its parts, and a part
    of it that is (the NK of thinks) takes its own chord."""
    keys = set()
    i = 0
    while i < len(coda):
        for n in range(len(coda) - i, 1, -1):
            if coda[i:i + n] in CODA_CLUSTER:
                keys |= keys_of(CODA_CLUSTER[coda[i:i + n]])
                i += n
                break
        else:
            if coda[i] not in CODA:
                return None
            keys |= keys_of(CODA[spelled(coda, i)])
            i += 1
    return keys


# Ending sounds whose order a chord can't show are told apart by key, as the
# spelling does: a last S after these is the plural's, on -Z (gets, six, facts),
# so -S -T is always ST (just, next) and -S -BG always SK (ask) ...
PLURAL_AFTER = {"P", "T", "K", "F", "TH"}


def spelled(coda, i):
    """The key coda[i] is written with."""
    if coda[i] == "S" and i == len(coda) - 1 and i > 0 and coda[i - 1] in PLURAL_AFTER:
        return "Z"
    # ... and a Z before a D is written S, as it's spelled (used, closed), so -D -Z is
    # always DZ (kids).
    if coda[i] == "Z" and i + 1 < len(coda) and coda[i + 1] == "D":
        return "S"
    return coda[i]


def has_y(onset, vowel):
    return len(onset) > 1 and onset[-1] == "Y" and vowel in Y_VOWEL


def chord(onset, vowel, coda):
    """The keys for one syllable, or None if it can't be written."""
    y = has_y(onset, vowel)
    left = onset_keys(onset[:-1] if y else onset)
    right = coda_keys(coda)
    if left is None or right is None:
        return None
    if y:
        mid = set(Y_VOWEL[vowel])
    else:
        mid = {ER[0]} if vowel == "ER" else set(VOWEL[vowel])
    if vowel == "ER":
        mid.add(ER[1])
    return frozenset(left | mid | right)


def banks(keys):
    """A chord as the FM-1 reads it: the left keys, the vowel keys and the right keys, each
    looked up on its own. A schwa with -R is ER, and that -R is the vowel's."""
    left = frozenset(k for k in keys if k in ONSET_KEYS)
    vowel = frozenset(k for k in keys if k in VOWEL_KEYS)
    right = frozenset(k for k in keys if k in CODA_KEYS)
    if vowel in ({"@"}, {"@", "U"}) and "-R" in right:
        vowel |= {"-R"}
        right -= {"-R"}
    return left, vowel, right


def values(onset, vowel, coda):
    """What each bank of a syllable's chord has to read back as. The schwa and AH are both
    the short u to a singer, so they count as the same."""
    v = "AH" if vowel == "AX" else vowel
    if has_y(onset, vowel):
        return onset[:-1], ("Y", v), coda
    return onset, (v,), coda


# ---- English --------------------------------------------------------------

VOWEL_NAMES = ["AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"]
CONSONANTS = ["B", "CH", "D", "DH", "F", "G", "HH", "JH", "K", "L", "M", "N", "NG", "P", "R", "S", "SH", "T",
              "TH", "V", "W", "Y", "Z", "ZH"]
# Lyrics.ONSETS: the clusters a syllable inside a word may begin with.
LEGAL_ONSETS = {tuple(s.split()) for s in [
    "P R", "P L", "B R", "B L", "T R", "D R", "K R", "K L", "G R", "G L", "F R", "F L", "TH R", "SH R",
    "S P", "S T", "S K", "S M", "S N", "S L", "S W", "T W", "D W", "K W", "G W",
    "P Y", "B Y", "K Y", "F Y", "M Y", "HH Y", "V Y",
    "S P R", "S P L", "S T R", "S K R", "S K W", "S P Y", "S K Y",
]}


def read_dictionary():
    """Diction's dictionary (tools/gen_dictionary.py has the format): word -> sounds, AH0 as AX."""
    b = (ROOT / "shared/src/commonMain/files/dictionary.bin").read_bytes()
    words, blocks = struct.unpack("<II", b[5:13])
    at = 13 + blocks * 4
    out = {}
    current = b""
    while at < len(b):
        shared, n = b[at], b[at + 1]
        current = current[:shared] + b[at + 2:at + 2 + n]
        at += 2 + n
        k = b[at]
        sounds = []
        for c in b[at + 1:at + 1 + k]:
            if c < 45:
                v = VOWEL_NAMES[c // 3]
                sounds.append("AX" if v == "AH" and c % 3 == 0 else v)
            else:
                sounds.append(CONSONANTS[c - 45])
        at += 1 + k
        out[current.decode("ascii")] = sounds
    assert len(out) == words
    return out


def syllables(sounds):
    """(onset, vowel, coda) triples, the gaps between vowels split as Lyrics.syllables splits them."""
    vowels = [i for i, s in enumerate(sounds) if s in VOWELS]
    if not vowels:
        return None
    cuts = [0]
    for a, b in zip(vowels, vowels[1:]):
        between = sounds[a + 1:b]
        onset = 0
        for n in range(len(between), 0, -1):
            tail = tuple(between[-n:])
            if (n == 1 and tail[0] != "NG") or tail in LEGAL_ONSETS:
                onset = n
                break
        cuts.append(b - onset)
    cuts.append(len(sounds))
    out = []
    for s, e in zip(cuts, cuts[1:]):
        part = sounds[s:e]
        v = next(i for i, x in enumerate(part) if x in VOWELS)
        out.append((tuple(part[:v]), part[v], tuple(part[v + 1:])))
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    verbose = "-v" in sys.argv
    freq = {}
    for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        w, _, n = line.partition(" ")
        if n.isdigit():
            freq[w] = int(n)
    dictionary = read_dictionary()

    weight = Counter()  # syllable -> how often it's sung
    words_of = defaultdict(Counter)
    known = 0
    for w, n in freq.items():
        syl = syllables(dictionary[w]) if w in dictionary else None
        if syl is None:
            continue
        known += n
        for s in syl:
            weight[s] += n
            words_of[s][w] += n

    # Each bank's table: a piece of a chord reads as the commonest thing written with it.
    tables = [defaultdict(Counter) for _ in range(3)]
    unwritable = Counter()
    written = {}
    for syl, n in weight.items():
        c = chord(*syl)
        if c is None:
            unwritable[syl] = n
            continue
        written[syl] = (c, banks(c), values(*syl))
        for table, piece, value in zip(tables, written[syl][1], written[syl][2]):
            table[piece][value] += n
    read = [{piece: vs.most_common(1)[0][0] for piece, vs in t.items()} for t in tables]

    lost = Counter()
    wrong_in = Counter()
    sizes = Counter()
    for syl, (c, pieces, want) in written.items():
        sizes[len(c)] += weight[syl]
        heard = tuple(r[p] for r, p in zip(read, pieces))
        if heard != want:
            lost[(syl, heard)] = weight[syl]
            for bank, h, w in zip(("start", "vowel", "end"), heard, want):
                if h != w:
                    wrong_in[bank] += weight[syl]

    total = sum(weight.values())
    if "--table" in sys.argv:
        print_tables(tables, read, written, weight, words_of, total)
        return
    if "--c" in sys.argv:
        write_c(tables, read)
        return
    bad = sum(lost.values()) + sum(unwritable.values())
    print(f"words in the list that Diction's dictionary has: {known / sum(freq.values()):.1%} of use")
    print(f"syllables: {len(weight)} kinds; chords in the tables: start {len(read[0])},"
          f" vowel {len(read[1])}, end {len(read[2])}")
    print(f"sung right: {1 - bad / total:.2%} of syllables sung"
          f" (misread {sum(lost.values()) / total:.2%}, can't be written {sum(unwritable.values()) / total:.2%})")
    print("misread in: " + ", ".join(f"{b} {n / total:.2%}" for b, n in wrong_in.most_common()))

    def show(s):
        o, v, c = s
        return " ".join(o + (v,) + c)

    def heard_as(h):
        o, v, c = h
        return " ".join(o + tuple(v) + c)

    def eg(s):
        return ", ".join(w for w, _ in words_of[s].most_common(3))

    print("\ncommonest misreadings (syllable -> heard as):")
    for (s, h), n in lost.most_common(30 if verbose else 15):
        print(f"  {n / total:6.3%}  {show(s):16} -> {heard_as(h):16}  ({eg(s)})")
    print("\ncommonest syllables that can't be written:")
    for s, n in unwritable.most_common(10 if verbose else 5):
        print(f"  {n / total:6.3%}  {show(s):16}  ({eg(s)})")
    print("\nkeys held at once, by use: " + ", ".join(f"{k}: {sizes[k] / total:.1%}" for k in sorted(sizes)))


ORDER = {k: i for i, k in enumerate(list(ONSET_KEYS) + list(VOWEL_KEYS) + list(CODA_KEYS))}
NOTE = {**ONSET_KEYS, **VOWEL_KEYS, **CODA_KEYS}


def steno(piece):
    """A bank's keys in steno order, as steno writes them: 'TKPW', 'AOE', '*PBG'."""
    keys = sorted(piece, key=ORDER.get)
    return "".join(k.lstrip("-") for k in keys) or "-"


def notes(piece):
    return " ".join(NOTE[k] for k in sorted(piece, key=ORDER.get))


def print_tables(tables, read, written, weight, words_of, total):
    """Every piece of chord in use, commonest first, with the words it's commonest in."""
    examples = [defaultdict(Counter) for _ in range(3)]
    for syl, (_, pieces, want) in written.items():
        for bank, (piece, value) in enumerate(zip(pieces, want)):
            if read[bank][piece] == value:
                for w, n in words_of[syl].most_common(3):
                    examples[bank][piece][w] += n
    titles = ("Starting consonants (left hand, F3 to B3)", "Vowels (thumbs, C4 to E4)",
              "Ending consonants (right hand, F4 to D#5)")
    for bank, title in enumerate(titles):
        print(f"\n### {title}\n")
        print("| Sounds | Keys | Steno | Use | As in |")
        print("|---|---|---|---|---|")
        for piece, values in sorted(tables[bank].items(), key=lambda kv: -sum(kv[1].values())):
            if not piece:
                continue
            value = read[bank][piece]
            sounds = " ".join(value)
            words = ", ".join(w for w, _ in examples[bank][piece].most_common(3))
            print(f"| {sounds} | {notes(piece)} | {steno(piece)} | {sum(values.values()) / total:.2%} | {words} |")


# The keys as the FM-1 numbers them: bit n is note key n, F3 = 0 .. G5 = 26.
NOTE_NAMES = ["F3", "F#3", "G3", "G#3", "A3", "A#3", "B3", "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4",
              "G4", "G#4", "A4", "A#4", "B4", "C5", "C#5", "D5", "D#5", "E5", "F5", "F#5", "G5"]
# The vowel bank's ER: a bit past the five vowel keys for the -R it takes from the right.
ER_BIT = 5


def mask(piece, bank):
    """A piece of chord as the decoder sees it: the bank's keys, from bit 0."""
    first = NOTE_NAMES.index((ONSET_KEYS, VOWEL_KEYS, CODA_KEYS)[bank][next(iter(
        (ONSET_KEYS, VOWEL_KEYS, CODA_KEYS)[bank]))])
    m = 0
    for k in piece:
        if k == "-R" and bank == 1:
            m |= 1 << ER_BIT
        else:
            m |= 1 << (NOTE_NAMES.index(NOTE[k]) - first)
    return m


def sung(piece, value, bank):
    """What a piece sings, as Diction's phone names. The schwa key sings AX, not AH."""
    v = list(value)
    if bank == 1 and "@" in piece and v[-1] == "AH":
        v[-1] = "AX"
    return " ".join(v)


def write_c(tables, read):
    out = ROOT / "fm1/chord_tables.h"
    lines = [
        "/* Generated by fm1/chords.py --c from its tables: do not edit. */",
        "/* Each bank of a chord, as bits from the bank's first key, and the sounds it sings */",
        "/* as Diction's phone names. The vowel bank's bit 5 is ER's -R (see chords.h). */",
        "#pragma once",
        "#include <stdint.h>",
        "",
        "typedef struct { uint16_t keys; const char *sounds; } chord_piece_t;",
        "",
    ]
    for bank, name in enumerate(("CHORD_START", "CHORD_VOWEL", "CHORD_END")):
        rows = sorted(((mask(p, bank), sung(p, read[bank][p], bank)) for p in tables[bank] if p),
                      key=lambda r: r[0])
        lines.append(f"static const chord_piece_t {name}[] = {{")
        lines += [f'    {{0x{m:03x}, "{v}"}},' for m, v in rows]
        lines.append("};")
        lines.append(f"#define {name}_COUNT {len(rows)}")
        lines.append("")
    out.write_text("\n".join(lines))
    print(f"wrote {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
