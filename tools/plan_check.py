#!/usr/bin/env python3
"""Checks the milestone table in docs/PLAN.md still agrees with the tree.

The table is written by hand and has fallen behind before: milestones built
and committed but never ticked. This reports two things:

  An unticked row with a commit that names it. Milestone commits are titled
  with their number, so a commit naming it means the table is behind.

  A ticked row naming a file that isn't there. Paths are taken from the row's
  own backticks, so a rename that leaves the row out of date is caught.

docs/ is deliberately untracked. With no plan to read this says so and
passes, so all_tests.sh doesn't fail for people who don't have one.
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

    # --- unticked rows with a commit naming them ----------------------------
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

    # --- ticked rows citing files that no longer exist ----------------------
    for number, line in rows:
        if "✅" not in line:
            continue
        cited = [t for t in re.findall(r"`([^`]+)`", line) if looks_like_a_path(t)]
        # Most rows name no files. Skip them to keep the report short.
        if not cited:
            continue
        missing = []
        for path in cited:
            # Rows cite paths relative to different source roots, so look for
            # the basename anywhere in the tree.
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
