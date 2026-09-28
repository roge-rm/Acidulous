#!/usr/bin/env python3
"""Builds Diction's pronouncing dictionary from the CMU Pronouncing Dictionary.

    python3 tools/gen_dictionary.py            fetch the pinned source and build
    python3 tools/gen_dictionary.py FILE       build from a local cmudict.dict

Writes shared/src/commonMain/files/dictionary.bin and the dictionary's licence
beside it. The format is read by model/lyrics/Dictionary.kt:

    "CMUD" u8 version, u32 words, u32 blocks, u32 block offsets[blocks]
    then the words, sorted, in blocks of BLOCK:
        u8 shared   letters shared with the word before (0 at a block's start)
        u8 n, n letters of the rest
        u8 k, k sounds

A sound is a byte: a vowel is its index in VOWELS times three plus its stress
(0, 1 or 2); a consonant is 45 plus its index in CONSONANTS. Only the first
pronunciation of each word is kept.
"""
import pathlib
import re
import struct
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "shared/src/commonMain/files"
COMMIT = "74790861f652b15e4ac49015a90074ad62a27690"
URL = f"https://raw.githubusercontent.com/cmusphinx/cmudict/{COMMIT}/"
BLOCK = 16

VOWELS = ["AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"]
CONSONANTS = ["B", "CH", "D", "DH", "F", "G", "HH", "JH", "K", "L", "M", "N", "NG", "P", "R", "S", "SH", "T",
              "TH", "V", "W", "Y", "Z", "ZH"]


def code(sound):
    m = re.fullmatch(r"([A-Z]+)([012]?)", sound)
    name, stress = m.group(1), m.group(2)
    if name in VOWELS:
        return VOWELS.index(name) * 3 + int(stress or 0)
    return 45 + CONSONANTS.index(name)


def main():
    if len(sys.argv) > 1:
        text = pathlib.Path(sys.argv[1]).read_text(encoding="latin-1")
        licence = None
    else:
        text = urllib.request.urlopen(URL + "cmudict.dict").read().decode("latin-1")
        licence = urllib.request.urlopen(URL + "LICENSE").read().decode("utf-8")

    words = {}
    for line in text.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        word, *sounds = line.split()
        if "(" in word:  # a second pronunciation
            continue
        if not re.fullmatch(r"[a-z][a-z'.\-]*", word) or len(word) > 255:
            continue
        words.setdefault(word, [code(s) for s in sounds])

    entries = sorted(words.items())
    body = bytearray()
    offsets = []
    previous = ""
    for i, (word, sounds) in enumerate(entries):
        raw = word.encode("ascii")
        if i % BLOCK == 0:
            offsets.append(len(body))
            shared = 0
        else:
            shared = 0
            while shared < min(len(previous), len(raw), 255) and previous[shared] == raw[shared]:
                shared += 1
        rest = raw[shared:]
        body += bytes([shared, len(rest)]) + rest + bytes([len(sounds)]) + bytes(sounds)
        previous = raw

    head = b"CMUD" + bytes([1]) + struct.pack("<II", len(entries), len(offsets))
    head += b"".join(struct.pack("<I", o) for o in offsets)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "dictionary.bin").write_bytes(head + bytes(body))
    if licence is not None:
        (OUT / "dictionary-licence.txt").write_text(
            "Diction's dictionary is built from the CMU Pronouncing Dictionary,\n"
            f"https://github.com/cmusphinx/cmudict at {COMMIT}.\n\n" + licence)
    print(f"  dictionary: {len(entries)} words, {len(head) + len(body)} bytes")


main()
