#!/usr/bin/env python3
"""Does the milestone table still agree with the tree?

The table in docs/PLAN.md is written by hand, and twice now it has been wrong
in the same direction: a milestone was built, committed and shipped, and its
row was never ticked. M49 and M50 both sat unticked with their own commits in
the log - `0c496d6 M49: read everything we can write` and `41b754b M50: what a
trig is allowed to decide` - and M47 was reported as unstarted when the code
had been in the app for weeks. Each time the mistake was reading the tick
column instead of the tree.

So this reads both and says where they disagree. Two questions:

  **A row with no tick, and a commit that names it.** The strong signal, and
  the one that caught all three: milestone commits here are titled with their
  number, so a search of the log for the milestone's own name is evidence the
  table has fallen behind.

  **A ticked row naming a file that is not there.** The other direction, and
  the one that will bite later: a finished milestone whose row cites
  `ui/RecorderDialog.kt` is a row that becomes a lie the day somebody renames
  it. Paths are taken from the row's own backticks, so the row is checked
  against what it claims rather than against a list kept somewhere else -
  a second hand-written list would drift exactly as the first one did.

docs/ is not in the repository - it is deliberately untracked - so with no
plan to read this says so and passes, which is what lets it live in
all_tests.sh without failing for anybody who does not have one.
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN = os.path.join(ROOT, "docs", "PLAN.md")

checks = 0
failures = 0


def report(ok, what, detail=""):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
    print("  %-4s %-58s %s" % ("ok" if ok else "FAIL", what, detail))


def commits():
    """Every commit subject, once."""
    out = subprocess.run(
        ["git", "-C", ROOT, "log", "--all", "--format=%h %s"],
        capture_output=True, text=True, check=False,
    )
    return out.stdout.splitlines()


def looks_like_a_path(token):
    """`ui/RecorderDialog.kt` yes; `model.Patch` and `family=` no."""
    return "/" in token and re.search(r"\.[a-z]{1,4}$", token) is not None


def main():
    if not os.path.exists(PLAN):
        print("  ....  no docs/PLAN.md here, so nothing to check against")
        return 0

    with open(PLAN, encoding="utf-8") as f:
        lines = f.read().splitlines()

    rows = [(m.group(1), line) for line in lines
            for m in [re.match(r"^\|\s*M(\d+)\s*\|", line)] if m]
    if not rows:
        print("  FAIL no milestone table found in docs/PLAN.md")
        return 1

    log = commits()
    print("%d milestone rows\n" % len(rows))

    # --- the drift that has actually happened -------------------------------
    for number, line in rows:
        if "✅" in line:
            continue
        # `M49:` and `M49 is finished` both count; `M490` does not.
        named = [c for c in log if re.search(r"\bM%s\b" % number, c)]
        report(
            not named,
            "M%s has no tick and no commit claiming it" % number,
            named[0] if named else "",
        )

    # --- and the drift that will --------------------------------------------
    for number, line in rows:
        if "✅" not in line:
            continue
        cited = [t for t in re.findall(r"`([^`]+)`", line) if looks_like_a_path(t)]
        # Most rows name no files at all, and forty lines of "ok, nothing to
        # check" is how a report stops being read.
        if not cited:
            continue
        missing = []
        for path in cited:
            # Rows cite paths relative to whichever source root they are in,
            # so the basename is what can actually be looked for. A file that
            # exists anywhere in the tree counts: this is about a row naming
            # something gone, not about the row's path being exact.
            base = os.path.basename(path)
            found = subprocess.run(
                ["git", "-C", ROOT, "ls-files", "--", "*/" + base, base],
                capture_output=True, text=True, check=False,
            )
            if not found.stdout.strip():
                missing.append(path)
        report(
            not missing,
            "M%s names %d file%s, all of which exist" % (number, len(cited),
                                                         "" if len(cited) == 1 else "s"),
            ("gone: " + ", ".join(missing)) if missing else "",
        )

    print("\n%d checks, %d failures" % (checks, failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
