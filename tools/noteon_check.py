#!/usr/bin/env python3
"""Does anything seed a note from a smoothed parameter?

`paramOf` is the smoothed value. It is the right thing to make audio from -
a cutoff that jumped to its new value on the block a knob moved would click,
which is what the smoother is for - and the wrong thing to read **once, at
note-on, to seed per-note state**: a glide time, an envelope stage, a voice
count, a spread. Seeded from the smoothed value, a note depends on how long
ago the knob moved, so the same song exported twice can differ - once from a
panic, once carrying on from whatever was played before it.

`Machine::targetOf` and `Machine::steppedTargetOf` are where a parameter is
going, and are what those reads must use.

**This exists because `tools/reset_test.sh` cannot see the fault.** That
harness renders, panics, renders the same performance again and requires the
two to be identical - which is exactly the shape of this bug - but its
performance never moves a *parameter*, so the smoothed value and the target
are equal throughout and both its passes agree. The harness that exists for
this class of bug is blind to this instance of it, which is how five reads in
Brazen survived from 2026-09-13 to 2026-09-20.

Teaching reset_test to move a knob mid-render does not fix that. A parameter
read per block is *supposed* to sound different while its smoother is in
flight, so an audio comparison cannot tell a bad seed from a good smooth.
Hence a static check: read the note-on bodies, and say so if a smoothed read
appears in one.

A body is anything named startVoice, noteOn, strike, trigger or startNote.
That is where per-note state gets seeded; if a machine grows another such
function under a different name, add it to FUNCTIONS.
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MACHINES = os.path.join(ROOT, "app", "src", "main", "cpp", "engine", "machine")

FUNCTIONS = ("startVoice", "noteOn", "strike", "trigger", "startNote")

SMOOTHED = re.compile(r"\b(paramOf|steppedOf)\s*\(\s*([A-Za-z_][\w:]*)")

OPENS = re.compile(
    r"^[^\S\n]*(?:[\w:<>,\s&*]+?)\b(" + "|".join(FUNCTIONS) + r")\s*\([^;{]*\)\s*\{",
    re.M,
)


def body_of(src, match):
    """The braces of the function `match` opens, balanced."""
    start = src.rindex("{", match.start(), match.end())
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i], start
    return src[start:], start


def main():
    files = sorted(
        set(glob.glob(os.path.join(MACHINES, "**", "*.h"), recursive=True))
        | set(glob.glob(os.path.join(MACHINES, "**", "*.cpp"), recursive=True))
    )
    if not files:
        print("  FAIL no machine sources found under %s" % MACHINES)
        return 1

    bad = []
    bodies = 0
    for path in files:
        with open(path, encoding="utf-8") as f:
            src = f.read()
        for m in OPENS.finditer(src):
            body, start = body_of(src, m)
            bodies += 1
            for read in SMOOTHED.finditer(body):
                line = src.count("\n", 0, start + read.start()) + 1
                bad.append(
                    (os.path.relpath(path, ROOT), line, m.group(1),
                     "%s(%s)" % (read.group(1), read.group(2)))
                )

    print("%d note-on bodies across %d machine sources\n" % (bodies, len(files)))
    if not bad:
        print("  ok   every note-on read is of the target, not the smoother")
        print("\n1 check, 0 failures")
        return 0

    for path, line, fn, read in bad:
        print("  FAIL %s:%d  %s reads %s" % (path, line, fn, read))
    print(
        "\n%d smoothed read%s at note-on. Use targetOf / steppedTargetOf:\n"
        "a note seeded from a smoother depends on how long ago a knob moved."
        % (len(bad), "" if len(bad) == 1 else "s")
    )
    print("\n1 check, 1 failure")
    return 1


if __name__ == "__main__":
    sys.exit(main())
