#!/usr/bin/env python3
"""Turns manual/*.md into the manual the app carries.

The manual is Markdown in `manual/`, so it can be read on the git host and
reviewed in a diff, and the app's Help window shows the same words. This
turns the Markdown into `model/Manual.kt` so there's no second copy to keep
in step, the same way gen_param_labels.py builds ParamLabels.kt from the
panels.

It isn't a full Markdown renderer. The manual uses this subset:

    # Title              the section, one per file (and one per sub-page)
    > summary            the line under it in the contents (optional)
    ## Heading           a heading inside the section
    ### Subheading       a heading inside that
    paragraph            run of lines, joined
    - bullet             a list item
    1. step              a numbered item
    `code` and **bold**  left in the text, drawn by the reader

Two files are written from it and neither is edited by hand: the app's
`model/Manual.kt`, and the contents list in `manual/README.md`, so titles and
summaries aren't typed out twice.

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

# A Markdown link. The app's reader can't draw links, so they're resolved
# here: the file keeps the link so section pages can point at their
# sub-pages, and the app just gets the text. The app reaches sub-pages through
# the rows under "in detail".
LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")


def unlink(text):
    """`[Delay](delay.md)` -> `Delay`, for a reader that cannot draw one."""
    return LINK.sub(r"\1", text)


# --- The manual on a computer ----------------------------------------------
#
# The manual is written for the phone ("tap", "this phone", "the share
# sheet"). The desktop build shows it in a computer's words: a second text for
# a block, only where it differs, chosen by the Help window on a desktop. The
# Markdown stays one text, the Android one.
#
# - DESKTOP_WORDS, applied to every block: tap becomes click, phone becomes
#   computer. Other words stay, since "hold" works the same with a mouse
#   button (and right-click, see 01).
# - KEEP, phrases the word rules leave alone: tap tempo, and phones that
#   really are phones (someone else's, or a speaker).
# - TOUCH_ONLY, headings whose blocks are left as they are, since the taps
#   there are on a controller's pads.
# - PHONE_ONLY, headings the desktop leaves out with everything under them:
#   TalkBack and the square screen layout.
# - DESKTOP_SENTENCES, whole sentences a word swap can't fix: two-finger
#   gestures and pinching (the mouse wheel on desktop), and things a desktop
#   doesn't do like the share sheet, AAC and keeping the screen awake. An
#   empty one leaves the sentence out. Each must still be found in the
#   manual, or --check fails, so rewording one can't quietly drop it.
#
# Lines only a computer needs are written in the Markdown as a comment,
# `<!-- desktop: text -->` or `<!-- desktop: - a bullet -->`, which git hosts
# don't show and the phone's manual doesn't include.
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
    # A computer has no fast and slow cores to choose between.
    "**auto** uses all of the phone's fast cores but one, which it leaves for the screen.":
        "**auto** uses half the computer's cores.",
    # Only a browser can clean the input.
    "**mic** is **raw** for an instrument, or **clean** for a voice, with noise suppression and level control.":
        "In a browser, **mic** is **raw** for an instrument, or **clean** for a voice, with noise suppression and level control.",
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
    "The other way works too. Open a MIDI file, a bundle, a shared voice or a sound with Acidulous, or share one to it, and it goes wherever **Import…** would have put it; a voice goes to the voice page in **Sound**.":
        "",
    "dark, light, high contrast or follow the phone.":
        "dark, light, high contrast or follow the system.",
    "**stay awake** - on keeps the screen on while the song plays.":
        "",
    "Acidulous works with built in, USB, or Bluetooth keyboards.":
        "Acidulous works with the computer's keyboard.",
}


def for_desktop(text, headings, used, rules):
    """Returns [text] as the desktop says it, None if it's the same, or "" to leave it out.

    [headings] are the section heading and subheading it's under. For example
    MPE, the Exquis and the Launchpad are all under "Playing from a keyboard".
    [rules] are the language's own: ENGLISH or FRENCH.
    """
    if any(h in rules["phone_only"] for h in headings if h):
        return ""
    if any(h in rules["touch_only"] for h in headings if h):
        return None
    out = text
    kept = {}
    # A replaced sentence is already in desktop words, so keep the word rules
    # off it: "the phone's own encoder" really means the phone.
    for n, (phone, desktop) in enumerate(rules["sentences"].items()):
        if phone in out:
            kept[f"\x01{n}\x01"] = desktop
            out = out.replace(phone, f"\x01{n}\x01")
            used.add(phone)
    for i, phrase in enumerate(rules["keep"]):
        if phrase in out:
            kept[f"\x00{i}\x00"] = phrase
            out = out.replace(phrase, f"\x00{i}\x00")
    for pattern, swap in rules["words"]:
        out = pattern.sub(swap, out)
    for mark, phrase in kept.items():
        out = out.replace(mark, phrase)
    out = out.strip()
    return None if out == text else out


ENGLISH = {"phone_only": PHONE_ONLY, "touch_only": TOUCH_ONLY, "sentences": DESKTOP_SENTENCES, "keep": KEEP, "words": DESKTOP_WORDS}

# The French manual on a computer: "touchez" becomes "cliquez", the phone
# the computer. Headings and sentences are its own (manual/fr/).
FRENCH = {
    "phone_only": {"TalkBack", "Sur un écran carré"},
    "touch_only": {"Jouer d’un clavier", "MPE"},
    "sentences": {
        "**auto** utilise tous les cœurs rapides du téléphone sauf un, qu’il laisse à l’écran.":
            "**auto** utilise la moitié des cœurs de l’ordinateur.",
        "**micro** est **brut** pour un instrument, ou **propre** pour une voix, avec réduction du bruit et contrôle du niveau.":
            "Dans un navigateur, **micro** est **brut** pour un instrument, ou **propre** pour une voix, avec réduction du bruit et contrôle du niveau.",
        "Glissez à deux doigts pour vous déplacer dans la grille et pincez pour agrandir ou réduire les cases. Un doigt ouvre et lance toujours les clips.":
            "La molette déplace la grille, de côté avec Maj enfoncée, et Ctrl avec la molette agrandit ou réduit les cases.",
        "Sur une tablette ou une grande fenêtre, les cases grandissent pour remplir l’écran, jusqu’à trois fois leur taille, tant que vous ne pincez pas.":
            "Dans une grande fenêtre, les cases grandissent pour la remplir, jusqu’à trois fois leur taille, tant que vous ne zoomez pas.",
        "Glissez à **deux doigts** pour défiler et pincez pour zoomer. Un doigt dessine toujours.":
            "La molette fait défiler de haut en bas, et de côté avec Maj enfoncée. Ctrl avec la molette zoome sur le temps, Ctrl, Maj et la molette zooment sur les rangées, et glisser dessine toujours.",
        "Les flèches, ou des balayages sur un pavé tactile, passent d’une commande à l’autre.":
            "Les flèches passent d’une commande à l’autre.",
        "Maintenez rétablir pour entrer en mode affectation.":
            "Maintenez le bouton de la souris sur rétablir, ou faites un clic droit dessus, pour entrer en mode affectation.",
        "Tournez le téléphone et touchez-le de nouveau pour une disposition adaptée à ce sens.":
            "Élargissez ou allongez la fenêtre et cliquez de nouveau dessus pour une disposition adaptée à cette forme.",
        "La lecture s’arrête d’elle-même quand un appel entre, qu’une autre appli se met à jouer ou qu’on débranche les écouteurs.":
            "",
        "Les rapports restent sur le téléphone sauf si vous en partagez un, et le dernier est aussi dans À propos.":
            "Les rapports restent sur cet ordinateur sauf si vous en partagez un, et le dernier est aussi dans À propos.",
        "**MP3** et **AAC** - au débit que vous choisissez.":
            "**MP3** - au débit que vous choisissez. AAC n’existe que sur le téléphone.",
        "Quand une exportation est finie, **Partager** l’envoie tout de suite par le menu de partage du téléphone : courriel, Drive, une conversation ou une autre appli. Les pistes séparées partent avec tous leurs fichiers ensemble.":
            "Quand une exportation est finie, **Partager** ouvre le dossier où elle a été enregistrée, pour l’envoyer de là.",
        "**Partager le morceau…** dans le menu fichier envoie le morceau ouvert en paquet, échantillons compris, pour que quelqu’un d’autre l’ouvre dans Acidulous.":
            "**Partager le morceau…** dans le menu fichier enregistre le morceau ouvert en paquet, échantillons compris, et ouvre son dossier pour que vous puissiez l’envoyer.",
        "L’inverse fonctionne aussi. Ouvrez un fichier MIDI, un paquet, une voix partagée ou un son avec Acidulous, ou partagez-le vers l’appli, et il va là où **Importer…** l’aurait mis; une voix va à la page voix dans **Son**.":
            "",
        "sombre, clair, contraste élevé ou comme le téléphone.":
            "sombre, clair, contraste élevé ou comme le système.",
        "**rester allumé** - activé, l’écran reste allumé pendant la lecture.":
            "",
        "Acidulous fonctionne avec un clavier intégré, USB ou Bluetooth.":
            "Acidulous fonctionne avec le clavier de l’ordinateur.",
    },
    "keep": [],
    "words": [
        (re.compile(r"\b([Tt])ouchez\b"), lambda m: ("C" if m.group(1) == "T" else "c") + "liquez"),
        (re.compile(r"\b([Tt])oucher\b"), lambda m: ("C" if m.group(1) == "T" else "c") + "liquer"),
        (re.compile(r"\b([Tt])ouchez deux fois\b"), lambda m: ("D" if m.group(1) == "T" else "d") + "ouble-cliquez"),
        (re.compile(r"\bce téléphone\b"), lambda m: "cet ordinateur"),
        (re.compile(r"\ble téléphone\b"), lambda m: "l’ordinateur"),
    ],
}

# Each manual: its language code ("" for English), its folder and its desktop rules.
LANGUAGES = [("", SRC, ENGLISH), ("fr", SRC / "fr", FRENCH)]

DESKTOP_LINE = re.compile(r"^<!-- desktop: (.+) -->$")


def parse(path):
    """Parses one file into (title, summary, blocks)."""
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
            # A summary can run over several lines of its blockquote.
            flush()
            summary = (summary + " " + line[2:].strip()).strip()
        elif line.startswith("## "):
            flush()
            blocks.append((HEADING, line[3:].strip(), False))
        elif line.startswith("### "):
            # A subheading.
            flush()
            blocks.append((SUBHEADING, line[4:].strip(), False))
        elif line.startswith("- "):
            flush()
            blocks.append((BULLET, line[2:].strip(), False))
        elif re.match(r"^\d+\. ", line):
            flush()
            blocks.append((STEP, line.split(". ", 1)[1].strip(), False))
        elif line.startswith("  ") and blocks and blocks[-1][0] in (BULLET, STEP) and not para and not blocks[-1][2]:
            # An indented continuation of the item above.
            kind, text, only = blocks[-1]
            blocks[-1] = (kind, text + " " + line.strip(), only)
        else:
            para.append(line.strip())
    flush()
    if title is None:
        sys.exit(f"gen_manual: {path} has no '# Title' line")
    return title, summary, blocks


def children_of(path):
    """Returns a section's sub-pages: `04-the-machines/` next to `04-the-machines.md`.

    Sub-pages are the same Markdown, parsed the same way, and listed in the
    same contents.
    """
    folder = path.with_suffix("")
    if not folder.is_dir():
        return []
    return [(p, parse(p)) for p in sorted(folder.glob("*.md"), key=machine_order)]


def machine_order(path):
    """Machines in the picker's groups, alphabetical in each; other pages by name."""
    kt = pathlib.Path("shared/src/commonMain/kotlin/com/rm/acidulous/model/MachineUi.kt")
    groups = re.findall(r'machines_\w+, listOf\(([^)]*)\)', kt.read_text())
    for i, names in enumerate(groups):
        if path.stem.lower() in [n.lower() for n in re.findall(r'"(\w+)"', names)]:
            return (0, i, path.stem)
    return (1, 0, path.stem)


def kotlin(languages, used):
    """The Kotlin for every language: [(code, sections, desktop rules)]."""
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
        "/** The manual in the language the app is in: English unless there's a translation. */",
        "object Manual {",
        "    val sections: List<ManualSection>",
        "        get() = when (androidx.compose.ui.text.intl.Locale.current.language) {",
    ]
    for code, _, _ in languages:
        if code == "fr":
            # France's French is Canada's with France's spacing: see FrenchTypography.
            out.append('            "fr" -> if (com.rm.acidulous.res.FrenchTypography.inFrance) frFrance else fr')
        elif code:
            out.append(f'            "{code}" -> {code}')
    out += [
        "            else -> en",
        "        }",
    ]
    kinds = {HEADING: "Heading", PARA: "Para", BULLET: "Bullet", STEP: "Step", SUBHEADING: "Subheading"}

    # Each page is a function of its own, and each language's list is made
    # the first time it's asked for. One list holding every page in place
    # outgrows the JVM's 64 KB limit on a method.
    pages = []

    def emit(name, title, summary, blocks, kids):
        body = [f"    private fun {name}() = ManualSection({q(unlink(title))}, {q(unlink(summary))}, listOf("]
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
                # A heading is judged on its own, since PHONE_ONLY names the heading.
                under = (heading, None) if kind == HEADING else (heading, sub)
                desktop = for_desktop(shown, under, used, rules)
            extra = f", {q(desktop)}" if desktop is not None else ""
            body.append(f"        ManualBlock(ManualKind.{kinds[kind]}, {q(shown)}{extra}),")
        desk = for_desktop(unlink(summary), (title,), used, rules)
        tail = f", desktopSummary = {q(desk)}" if desk is not None else ""
        if kids:
            body.append("    ), listOf(")
            for j, (_, (t, s2, b2)) in enumerate(kids):
                emit(f"{name}_{j}", t, s2, b2, [])
                body.append(f"        {name}_{j}(),")
        body.append(f"    ){tail})")
        pages.extend(["", *body])

    for code, sections, rules in languages:
        lang = code or "en"
        names = []
        for i, ((title, summary, blocks), kids) in enumerate(sections):
            emit(f"{lang}{i}", title, summary, blocks, kids)
            names.append(f"{lang}{i}()")
        out += ["", f"    private val {lang}: List<ManualSection> by lazy {{ listOf("]
        out += [f"        {n}," for n in names]
        out.append("    ) }")
    if any(code == "fr" for code, _, _ in languages):
        out += [
            "",
            "    /** The French manual with France's spacing, made the first time it's asked for. */",
            "    private val frFrance: List<ManualSection> by lazy { fr.map(::forFrance) }",
            "",
            "    private fun forFrance(s: ManualSection): ManualSection {",
            "        val f = com.rm.acidulous.res.FrenchTypography::forFrance",
            "        return ManualSection(",
            "            f(s.title), f(s.summary),",
            "            s.blocks.map { ManualBlock(it.kind, f(it.text), it.desktop?.let(f)) },",
            "            s.children.map(::forFrance), s.desktopSummary?.let(f),",
            "        )",
            "    }",
        ]
    out += pages
    out += ["}", ""]
    return "\n".join(out)


def lowered(summary, code="", names=()):
    """Returns " - " (French " : ") and the summary starting in lower case, or
    nothing if the page has none. One that starts with a machine's name and
    's ("Reflux's filter") keeps its capital."""
    if not summary:
        return ""
    sep = "\u00a0: " if code == "fr" else " - "
    m = re.match(r"(\w+)['’]s\b", summary)
    return sep + (summary if m and m.group(1) in names else summary[0].lower() + summary[1:])


def contents(files, sections, code=""):
    """Builds the numbered list that goes between the markers in manual/README.md."""
    names = {kt for _, kids in sections for _, (kt, _, _) in kids}
    rows = []
    for i, (path, ((title, summary, _), kids)) in enumerate(zip(files, sections), 1):
        rows.append(f"{i}. [{title}]({path.name})" + lowered(summary, code, names))
        for kp, (kt, ks, _) in kids:
            here = f"{path.stem}/{kp.name}"
            rows.append(f"    - [{kt}]({here})" + lowered(ks, code, names))
    return "\n".join([OPEN, ""] + rows + ["", CLOSE])


def indexed(index, files, sections, code=""):
    """Returns a manual's README.md with its contents list brought up to date."""
    text = index.read_text(encoding="utf-8")
    a, b = text.find(OPEN), text.find(CLOSE)
    if a < 0 or b < 0:
        sys.exit(f"gen_manual: {index} has no {OPEN} ... {CLOSE} to write into")
    return text[:a] + contents(files, sections, code) + text[b + len(CLOSE):]


def main():
    languages, indexes, used = [], [], set()
    pages = words = 0
    for code, folder, rules in LANGUAGES:
        files = sorted(p for p in folder.glob("*.md") if p.name != "README.md")
        if not files:
            sys.exit(f"gen_manual: {folder} has no sections")
        sections = [(parse(p), children_of(p)) for p in files]
        languages.append((code, sections, rules))
        indexes.append((folder / "README.md", indexed(folder / "README.md", files, sections, code)))
        if not code:
            words = sum(len(t.split()) for (_, _, bs), _ in sections for _, t, only in bs if not only)
            pages = len(sections)
            for _, kids in sections:
                pages += len(kids)
                words += sum(len(t.split()) for _, (_, _, bs) in kids for _, t, only in bs if not only)
    text = kotlin(languages, used)
    lost = [phone for phone in DESKTOP_SENTENCES if phone not in used]
    if lost:
        print("  FAIL manual: a sentence the desktop wording replaces is no longer in manual/:")
        for phone in lost:
            print(f"       {phone}")
        print("       update DESKTOP_SENTENCES in tools/gen_manual.py to match")
        sys.exit(1)
    outputs = [(OUT, text)] + indexes
    if "--check" in sys.argv:
        for path, want in outputs:
            have = path.read_text(encoding="utf-8") if path.exists() else ""
            if have != want:
                print(f"  FAIL manual: {path} is not what manual/ would produce")
                print("       run: python3 tools/gen_manual.py")
                sys.exit(1)
        print(f"  ok   manual: {pages} pages, {words} words, {len(LANGUAGES)} languages, in step")
        return
    for path, want in outputs:
        path.write_text(want, encoding="utf-8")
    print(f"gen_manual: {pages} pages, {words} words, {len(LANGUAGES)} languages -> {OUT} and the contents")

main()
