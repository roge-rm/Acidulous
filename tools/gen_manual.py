#!/usr/bin/env python3
"""Turn manual/*.md into the manual the app carries.

**One source, two places it appears.** The manual is Markdown in `manual/`, so
it is read on the git and reviewed in a diff like everything else here, and the
app's Help window shows the same words. A manual that disagrees with the app is
worse than no manual, and two hand-kept copies disagree within a release - so
the app holds no second copy. This lifts the Markdown into
`model/Manual.kt`, the way gen_param_labels.py lifts the panels' own wording
into ParamLabels.kt, and for the same reason.

Not a Markdown renderer: the app has none and does not need one. The subset the
manual is written in is the subset a manual needs -

    # Title              the section, one per file (and one per sub-page)
    > summary            the line under it in the contents
    ## Heading           a heading inside the section
    ### Subheading       a heading inside that
    paragraph            run of lines, joined
    - bullet             a list item
    1. step              a numbered item
    `code` and **bold**  left in the text, drawn by the reader

Two things are written from it and neither is ever edited by hand: the app's
`model/Manual.kt`, and the contents list in `manual/README.md` - because a
contents page typed out beside the sections it lists is a second copy of every
title and every summary, which is the drift this whole script exists to stop.

Re-run after editing the manual:  python3 tools/gen_manual.py
Check both are in step (this is what all_tests.sh runs):
                                  python3 tools/gen_manual.py --check
"""
import pathlib
import re
import sys

SRC = pathlib.Path("manual")
OUT = pathlib.Path("shared/src/jvmShared/kotlin/com/rm/acidulous/model/Manual.kt")
INDEX = SRC / "README.md"
OPEN, CLOSE = "<!-- contents -->", "<!-- /contents -->"

HEADING, PARA, BULLET, STEP, SUBHEADING = 0, 1, 2, 3, 4

# A Markdown link, which the manual is written with and the app's reader has
# no way to draw.
#
# The reader's subset is deliberately small, and a link is the one piece of
# Markdown where that costs the *source* something: written plainly, a section
# page cannot point at its own sub-pages, so anybody reading the manual as
# files has to guess that `05-effects-and-mixing/delay.md` exists. Written as a
# link it came out in the app as a literal `[Delay](05-...)`, brackets and all.
#
# So the link is resolved here instead: the file keeps it, the app gets the
# text. The app has its own way to reach a sub-page - the tappable rows under
# "in detail" - and does not need the link, only the words.
LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")


def unlink(text):
    """`[Delay](delay.md)` -> `Delay`, for a reader that cannot draw one."""
    return LINK.sub(r"\1", text)


# --- The same manual for a mouse -------------------------------------------
#
# The manual is written for the phone, which is the app's home, and says "tap".
# The desktop build shows the same words with a mouse's in their place: a
# second text for a block, written here only where it differs and chosen by
# the Help window where the pointer is a mouse. The Markdown stays one text.
#
# Three pieces, all small enough to read at a glance:
#
# - MOUSE_WORDS, applied to every block: tap becomes click. Nothing else a
#   finger does changes its name - "hold" is a mouse button held down just
#   the same, and "touching the file" was never about a finger.
# - TOUCH_ONLY, headings whose blocks are left as they are: the taps there are
#   on a controller's own pads, or on the phone's own screen reader.
# - MOUSE_SENTENCES, whole sentences a word could not fix: two fingers and a
#   pinch, which a mouse does with its wheel. Each must still be found in the
#   manual, or --check says so, so rewriting one cannot quietly drop it.
MOUSE_WORDS = [
    (re.compile(r"\b([Dd])ouble tap\b"), lambda m: m.group(1) + "ouble-click"),
    (re.compile(r"\b([Tt])ap(s|ped|ping)?\b"),
     lambda m: ("C" if m.group(1) == "T" else "c") + "lick" + {None: "", "s": "s", "ped": "ed", "ping": "ing"}[m.group(2)]),
]

TOUCH_ONLY = {
    "Playing from a keyboard",  # an Exquis's and a Launchpad's pads, and MPE fingers
    "MPE",
    "TalkBack",
}

# The one tap that is a name: tap tempo is tapped with whatever you have.
KEEP = ["**tap** sets it from four taps."]

MOUSE_SENTENCES = {
    "Drag with two fingers to move around the grid, and pinch to make the cells bigger or smaller. One finger still opens and launches clips.":
        "The mouse wheel moves around the grid, and sideways with Shift held. Ctrl and the wheel make the cells bigger or smaller.",
    "On a tablet the cells grow to fill the screen, up to twice their size, until you pinch.":
        "In a big window the cells grow to fill it, up to twice their size, until you zoom.",
    "Drag with **two fingers** to scroll and pinch to zoom. One finger always draws.":
        "The mouse wheel scrolls up and down, and sideways with Shift held. Ctrl and the wheel zoom in on time, and Ctrl, Shift and the wheel on the rows. Dragging always draws.",
    "The arrows, or swipes on a touchpad, move between controls.":
        "The arrows move between controls.",
    "Long-press redo to enter mapping mode.":
        "Hold the mouse button down on redo to enter mapping mode.",
}


def for_mouse(text, headings, used):
    """[text] as the desktop says it, or None where it says the same.

    [headings] are the ones it is under, the section's and a subheading's:
    MPE, the Exquis and the Launchpad are all under "Playing from a keyboard".
    """
    if any(h in TOUCH_ONLY for h in headings):
        return None
    out = text
    for touch, mouse in MOUSE_SENTENCES.items():
        if touch in out:
            out = out.replace(touch, mouse)
            used.add(touch)
    kept = {}
    for i, phrase in enumerate(KEEP):
        if phrase in out:
            kept[f"\x00{i}\x00"] = phrase
            out = out.replace(phrase, f"\x00{i}\x00")
    for pattern, swap in MOUSE_WORDS:
        out = pattern.sub(swap, out)
    for mark, phrase in kept.items():
        out = out.replace(mark, phrase)
    return None if out == text else out


def parse(path):
    """One file into (title, summary, blocks)."""
    title, summary, blocks = None, "", []
    para = []

    def flush():
        if para:
            blocks.append((PARA, " ".join(para)))
            para.clear()

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if not line.strip():
            flush()
            continue
        if line.startswith("# "):
            flush()
            title = line[2:].strip()
        elif line.startswith("> "):
            flush()
            summary = line[2:].strip()
        elif line.startswith("## "):
            flush()
            blocks.append((HEADING, line[3:].strip()))
        elif line.startswith("### "):
            # Printed as a paragraph, hashes and all, until this line: ten of
            # them across four pages read "### MPE".
            flush()
            blocks.append((SUBHEADING, line[4:].strip()))
        elif line.startswith("- "):
            flush()
            blocks.append((BULLET, line[2:].strip()))
        elif re.match(r"^\d+\. ", line):
            flush()
            blocks.append((STEP, line.split(". ", 1)[1].strip()))
        elif line.startswith("  ") and blocks and blocks[-1][0] in (BULLET, STEP) and not para:
            # A continuation line of the item above, indented.
            kind, text = blocks[-1]
            blocks[-1] = (kind, text + " " + line.strip())
        else:
            para.append(line.strip())
    flush()
    if title is None:
        sys.exit(f"gen_manual: {path} has no '# Title' line")
    return title, summary, blocks


def children_of(path):
    """The sub-pages of a section: `04-the-machines/` beside `04-the-machines.md`.

    A machine deserves more than a line and the machines page would be
    unreadable at twenty times that length, so a section may have pages of its
    own. Everything else is unchanged: they are the same Markdown, parsed by
    the same parser, and they appear in the same contents.
    """
    folder = path.with_suffix("")
    if not folder.is_dir():
        return []
    return [(p, parse(p)) for p in sorted(folder.glob("*.md"))]


def kotlin(sections, used):
    q = lambda s: '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'
    out = [
        "package com.rm.acidulous.model",
        "",
        "// Generated by tools/gen_manual.py from manual/. Do not edit by hand:",
        "// the Markdown in manual/ is the manual, and this is the same words in",
        "// the form the app can draw. Re-run the script after editing it.",
        "",
        "/** What a line of the manual is. Inline `code` and **bold** stay in the text. */",
        "enum class ManualKind { Heading, Para, Bullet, Step, Subheading }",
        "",
        "/** [mouse] is the same words where the pointer is a mouse: \"click\" for \"tap\". Null where they are the same. */",
        "class ManualBlock(val kind: ManualKind, val text: String, val mouse: String? = null) {",
        "    fun text(mouse: Boolean): String = if (mouse) this.mouse ?: text else text",
        "}",
        "",
        "class ManualSection(",
        "    val title: String,",
        "    val summary: String,",
        "    val blocks: List<ManualBlock>,",
        "    /** Pages of this one's own: a machine is more than a line. */",
        "    val children: List<ManualSection> = emptyList(),",
        ")",
        "",
        "object Manual {",
        "    val sections: List<ManualSection> = listOf(",
    ]
    kinds = {HEADING: "Heading", PARA: "Para", BULLET: "Bullet", STEP: "Step", SUBHEADING: "Subheading"}

    def emit(title, summary, blocks, kids, pad):
        out.append(f"{pad}ManualSection({q(unlink(title))}, {q(unlink(summary))}, listOf(")
        heading, sub = title, None
        for kind, text in blocks:
            if kind == HEADING:
                heading, sub = text, None
            elif kind == SUBHEADING:
                sub = text
            mouse = None if kind in (HEADING, SUBHEADING) else for_mouse(unlink(text), (heading, sub), used)
            extra = f", {q(mouse)}" if mouse else ""
            out.append(f"{pad}    ManualBlock(ManualKind.{kinds[kind]}, {q(unlink(text))}{extra}),")
        if not kids:
            out.append(f"{pad})),")
            return
        out.append(f"{pad}), listOf(")
        for _, (t, s2, b2) in kids:
            emit(t, s2, b2, [], pad + "    ")
        out.append(f"{pad})),")

    for (title, summary, blocks), kids in sections:
        emit(title, summary, blocks, kids, "        ")
    out += ["    )", "}", ""]
    return "\n".join(out)


def contents(files, sections):
    """The numbered list, between the markers in manual/README.md."""
    rows = []
    for i, (path, ((title, summary, _), kids)) in enumerate(zip(files, sections), 1):
        rows.append(f"{i}. [{title}]({path.name}) - {summary[0].lower() + summary[1:]}")
        for kp, (kt, ks, _) in kids:
            here = f"{path.stem}/{kp.name}"
            rows.append(f"    - [{kt}]({here}) - {ks[0].lower() + ks[1:]}")
    return "\n".join([OPEN, ""] + rows + ["", CLOSE])


def indexed(files, sections):
    """manual/README.md with its contents list brought up to date."""
    text = INDEX.read_text(encoding="utf-8")
    a, b = text.find(OPEN), text.find(CLOSE)
    if a < 0 or b < 0:
        sys.exit(f"gen_manual: {INDEX} has no {OPEN} ... {CLOSE} to write into")
    return text[:a] + contents(files, sections) + text[b + len(CLOSE):]


def main():
    files = sorted(p for p in SRC.glob("*.md") if p.name != "README.md")
    if not files:
        sys.exit("gen_manual: manual/ has no sections")
    sections = [(parse(p), children_of(p)) for p in files]
    used = set()
    text = kotlin(sections, used)
    lost = [touch for touch in MOUSE_SENTENCES if touch not in used]
    if lost:
        print("  FAIL manual: a sentence the mouse wording replaces is no longer in manual/:")
        for touch in lost:
            print(f"       {touch}")
        print("       update MOUSE_SENTENCES in tools/gen_manual.py to match")
        sys.exit(1)
    index = indexed(files, sections)
    words = sum(len(t.split()) for (_, _, bs), _ in sections for _, t in bs)
    pages = len(sections)
    for _, kids in sections:
        pages += len(kids)
        words += sum(len(t.split()) for _, (_, _, bs) in kids for _, t in bs)
    if "--check" in sys.argv:
        for path, want in ((OUT, text), (INDEX, index)):
            have = path.read_text(encoding="utf-8") if path.exists() else ""
            if have != want:
                print(f"  FAIL manual: {path} is not what manual/ would produce")
                print("       run: python3 tools/gen_manual.py")
                sys.exit(1)
        print(f"  ok   manual: {pages} pages, {words} words, in step")
        return
    OUT.write_text(text, encoding="utf-8")
    INDEX.write_text(index, encoding="utf-8")
    print(f"gen_manual: {pages} pages, {words} words -> {OUT} and {INDEX}")


main()
