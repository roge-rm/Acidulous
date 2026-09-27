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
    > summary            the line under it in the contents (optional)
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
OUT = pathlib.Path("shared/src/commonMain/kotlin/com/rm/acidulous/model/Manual.kt")
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


# --- The same manual on a computer -----------------------------------------
#
# The manual is written for the phone, which is the app's home: it says "tap",
# "this phone", "the share sheet". The desktop build shows the same manual in a
# computer's words: a second text for a block, written here only where it
# differs, and chosen by the Help window on a desktop. The Markdown stays one
# text - Android's, as it reads on the git.
#
# Five pieces, all small enough to read at a glance:
#
# - DESKTOP_WORDS, applied to every block: tap becomes click, a phone a
#   computer. Nothing else a finger does changes its name - "hold" is a mouse
#   button held down just the same (and a right-click too: see 01), and
#   "touching the file" was never about a finger.
# - KEEP, phrases the words leave alone: tap tempo is tapped with whatever you
#   have, and a phone that is somebody else's, or a speaker, is still a phone.
# - TOUCH_ONLY, headings whose blocks are left as they are: the taps there are
#   on a controller's own pads.
# - PHONE_ONLY, headings the desktop leaves out, with everything under them:
#   TalkBack, and the square phone's layout.
# - DESKTOP_SENTENCES, whole sentences a word could not fix: two fingers and a
#   pinch, which a mouse does with its wheel, and what a desktop does not do -
#   the share sheet, AAC, keeping the screen awake. An empty one leaves the
#   sentence out. Each must still be found in the manual, or --check says so,
#   so rewriting one cannot quietly drop it.
#
# And lines only a computer needs are written in the Markdown as a comment -
# `<!-- desktop: text -->`, or `<!-- desktop: - a bullet -->` - which the git
# does not show and the phone's manual does not have.
DESKTOP_WORDS = [
    (re.compile(r"\b([Dd])ouble tap\b"), lambda m: m.group(1) + "ouble-click"),
    (re.compile(r"\b([Tt])ap(s|ped|ping)?\b"),
     lambda m: ("C" if m.group(1) == "T" else "c") + "lick" + {None: "", "s": "s", "ped": "ed", "ping": "ing"}[m.group(2)]),
    (re.compile(r"\bphone(s|'s)?\b"), lambda m: "computer" + (m.group(1) or "")),
]

KEEP = [
    "**tap** sets it from four taps.",
    "Handling noise from holding the phone",
    "on a phone speaker",
    "on a phone that has never seen the file",
]

TOUCH_ONLY = {
    "Playing from a keyboard",  # an Exquis's and a Launchpad's pads, and MPE fingers
    "MPE",
}

PHONE_ONLY = {
    "TalkBack",
    "On a square screen",
}

DESKTOP_SENTENCES = {
    # The pointer.
    "Drag with two fingers to move around the grid and pinch to make the cells bigger or smaller. One finger still opens and launches clips.":
        "The mouse wheel moves around the grid, sideways with Shift held, and Ctrl and the wheel make the cells bigger or smaller.",
    "On a tablet or a big window the cells grow to fill the screen, up to three times their size, until you pinch.":
        "In a big window the cells grow to fill it, up to three times their size, until you zoom.",
    "Drag with **two fingers** to scroll and pinch to zoom. One finger always draws.":
        "The mouse wheel scrolls up and down, and sideways with Shift held. Ctrl and the wheel zoom in on time, Ctrl, Shift and the wheel zoom the rows, and dragging always draws.",
    "The arrows, or swipes on a touchpad, move between controls.":
        "The arrows move between controls.",
    "Long-press redo to enter mapping mode.":
        "Hold the mouse button down on redo, or right-click it, to enter mapping mode.",
    "Turn the phone and press it again for a layout that suits that way round.":
        "Make the window wider or taller and press it again for a layout that suits that shape.",
    # What a computer does not do, or does its own way.
    "Playing stops by itself when a call comes in, another app starts playing or headphones are unplugged.":
        "",
    "Reports stay on the phone unless you share one, and the last one is also in About.":
        "Reports stay on this computer unless you share one, and the last one is also in About.",
    "**MP3** and **AAC** - at the bitrate you choose.":
        "**MP3** - at the bitrate you choose. AAC is only on the phone.",
    "When an export finishes, **Share** sends it straight on through the phone's share sheet: email, Drive, a chat or another app. Stems go as all their files together.":
        "When an export finishes, **Share** opens the folder it was saved in, to send it on from there.",
    "**Share song…** in the file menu sends the open song as a bundle, samples included, for someone else to open in Acidulous.":
        "**Share song…** in the file menu saves the open song as a bundle, samples included, and opens its folder so you can send it to someone.",
    "The other way works too. Open a MIDI file, a bundle or a sound with Acidulous, or share one to it, and it goes wherever **Import…** would have put it.":
        "",
    "dark, light, high contrast or follow the phone.":
        "dark, light, high contrast or follow the system.",
    "**while playing** - whether the screen can turn off while playing.":
        "",
    "**scheduler hint** - whether the phone accepted the app's request to treat the audio as time-critical. Some phones refuse, and there's nothing to do about it here.":
        "**scheduler hint** - only on phones. On a computer it always says not available.",
    "Acidulous works with built in, USB, or Bluetooth keyboards.":
        "Acidulous works with the computer's keyboard.",
}


def for_desktop(text, headings, used):
    """[text] as the desktop says it, or None where it says the same; "" leaves it out.

    [headings] are the ones it is under, the section's and a subheading's:
    MPE, the Exquis and the Launchpad are all under "Playing from a keyboard".
    """
    if any(h in PHONE_ONLY for h in headings if h):
        return ""
    if any(h in TOUCH_ONLY for h in headings if h):
        return None
    out = text
    kept = {}
    # A replaced sentence is already the desktop's words, and the word rules
    # must not have it: "the phone's own encoder" means the phone.
    for n, (phone, desktop) in enumerate(DESKTOP_SENTENCES.items()):
        if phone in out:
            kept[f"\x01{n}\x01"] = desktop
            out = out.replace(phone, f"\x01{n}\x01")
            used.add(phone)
    for i, phrase in enumerate(KEEP):
        if phrase in out:
            kept[f"\x00{i}\x00"] = phrase
            out = out.replace(phrase, f"\x00{i}\x00")
    for pattern, swap in DESKTOP_WORDS:
        out = pattern.sub(swap, out)
    for mark, phrase in kept.items():
        out = out.replace(mark, phrase)
    out = out.strip()
    return None if out == text else out


DESKTOP_LINE = re.compile(r"^<!-- desktop: (.+) -->$")


def parse(path):
    """One file into (title, summary, blocks)."""
    title, summary, blocks = None, "", []
    para = []

    def flush():
        if para:
            blocks.append((PARA, " ".join(para), False))
            para.clear()

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if not line.strip():
            flush()
            continue
        only = DESKTOP_LINE.match(line.strip())
        if only:
            flush()
            inner = only.group(1).strip()
            if inner.startswith("- "):
                blocks.append((BULLET, inner[2:].strip(), True))
            else:
                blocks.append((PARA, inner, True))
        elif line.startswith("# "):
            flush()
            title = line[2:].strip()
        elif line.startswith("> "):
            flush()
            summary = line[2:].strip()
        elif line.startswith("## "):
            flush()
            blocks.append((HEADING, line[3:].strip(), False))
        elif line.startswith("### "):
            # Printed as a paragraph, hashes and all, until this line: ten of
            # them across four pages read "### MPE".
            flush()
            blocks.append((SUBHEADING, line[4:].strip(), False))
        elif line.startswith("- "):
            flush()
            blocks.append((BULLET, line[2:].strip(), False))
        elif re.match(r"^\d+\. ", line):
            flush()
            blocks.append((STEP, line.split(". ", 1)[1].strip(), False))
        elif line.startswith("  ") and blocks and blocks[-1][0] in (BULLET, STEP) and not para and not blocks[-1][2]:
            # A continuation line of the item above, indented.
            kind, text, only = blocks[-1]
            blocks[-1] = (kind, text + " " + line.strip(), only)
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
        "/**",
        " * [desktop] is the same words on a computer - \"click\" for \"tap\", no share",
        " * sheet - or null where they are the same. Empty text is a line the one",
        " * platform leaves out: a computer's own lines are empty on the phone.",
        " */",
        "class ManualBlock(val kind: ManualKind, val text: String, val desktop: String? = null) {",
        "    fun text(desktop: Boolean): String = if (desktop) this.desktop ?: text else text",
        "}",
        "",
        "class ManualSection(",
        "    val title: String,",
        "    val summary: String,",
        "    val blocks: List<ManualBlock>,",
        "    /** Pages of this one's own: a machine is more than a line. */",
        "    val children: List<ManualSection> = emptyList(),",
        "    /** [summary] on a computer, where it differs. */",
        "    val desktopSummary: String? = null,",
        ") {",
        "    fun summary(desktop: Boolean): String = if (desktop) desktopSummary ?: summary else summary",
        "}",
        "",
        "object Manual {",
        "    val sections: List<ManualSection> = listOf(",
    ]
    kinds = {HEADING: "Heading", PARA: "Para", BULLET: "Bullet", STEP: "Step", SUBHEADING: "Subheading"}

    def emit(title, summary, blocks, kids, pad):
        out.append(f"{pad}ManualSection({q(unlink(title))}, {q(unlink(summary))}, listOf(")
        heading, sub = title, None
        for kind, text, only in blocks:
            if kind == HEADING:
                heading, sub = text, None
            elif kind == SUBHEADING:
                sub = text
            if only:
                shown, desktop = "", unlink(text)
            else:
                shown = unlink(text)
                # A heading is judged by itself: PHONE_ONLY names the heading.
                under = (heading, None) if kind == HEADING else (heading, sub)
                desktop = for_desktop(shown, under, used)
            extra = f", {q(desktop)}" if desktop is not None else ""
            out.append(f"{pad}    ManualBlock(ManualKind.{kinds[kind]}, {q(shown)}{extra}),")
        desk = for_desktop(unlink(summary), (title,), used)
        tail = f", desktopSummary = {q(desk)}" if desk is not None else ""
        if not kids:
            out.append(f"{pad}){tail}),")
            return
        out.append(f"{pad}), listOf(")
        for _, (t, s2, b2) in kids:
            emit(t, s2, b2, [], pad + "    ")
        out.append(f"{pad}){tail}),")

    for (title, summary, blocks), kids in sections:
        emit(title, summary, blocks, kids, "        ")
    out += ["    )", "}", ""]
    return "\n".join(out)


def lowered(summary):
    """ - and the summary, begun in lower case; nothing where a page has none (Dan took the chapters' out)."""
    return f" - {summary[0].lower() + summary[1:]}" if summary else ""


def contents(files, sections):
    """The numbered list, between the markers in manual/README.md."""
    rows = []
    for i, (path, ((title, summary, _), kids)) in enumerate(zip(files, sections), 1):
        rows.append(f"{i}. [{title}]({path.name})" + lowered(summary))
        for kp, (kt, ks, _) in kids:
            here = f"{path.stem}/{kp.name}"
            rows.append(f"    - [{kt}]({here})" + lowered(ks))
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
    lost = [phone for phone in DESKTOP_SENTENCES if phone not in used]
    if lost:
        print("  FAIL manual: a sentence the desktop wording replaces is no longer in manual/:")
        for phone in lost:
            print(f"       {phone}")
        print("       update DESKTOP_SENTENCES in tools/gen_manual.py to match")
        sys.exit(1)
    index = indexed(files, sections)
    words = sum(len(t.split()) for (_, _, bs), _ in sections for _, t, only in bs if not only)
    pages = len(sections)
    for _, kids in sections:
        pages += len(kids)
        words += sum(len(t.split()) for _, (_, _, bs) in kids for _, t, only in bs if not only)
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
