#!/usr/bin/env python3
"""Checks that no machine seeds a note from a smoothed parameter.

`paramOf` is the smoothed value. That's right for making audio, since a jump
would click, but wrong for seeding per-note state at note-on (a glide time,
an envelope stage, a voice count, a spread). Seeded from the smoothed value, a
note depends on how long ago the knob moved, so the same song exported twice
can sound different.

Use `Machine::targetOf` and `Machine::steppedTargetOf` for those reads.

tools/reset_test.sh can't catch this because its performance never moves a
parameter. An audio comparison can't catch it either, since a parameter read
per block is supposed to sound different while it's smoothing. So this is a
static check: read the note-on functions and report any smoothed read.

A note-on function is anything named startVoice, noteOn, strike, trigger or
startNote. If a machine seeds per-note state in a function with another name,
add it to FUNCTIONS.
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
    """Returns the balanced braces of the function `match` opens."""
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
