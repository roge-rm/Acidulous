#!/usr/bin/env python3
"""The words the machine panels show, gathered into string resources.

A panel is written in English - PanelKnob(b, "f_res", "reso") inside
Group("filter") - and stays that way: the English word is the key, and the
panel helpers look it up (ui/PanelText.kt) to show it in the phone's
language. This finds every such word and writes

    app/src/main/res/values/strings_panel_words.xml   the words, to translate
    app/src/main/java/com/rm/acidulous/ui/PanelWords.kt   English -> resource

Where the words come from:
  - the panels: Group titles, the labels of PanelKnob, PanelSwitch and
    PanelStepKnob (or the parameter's own name where no label is given), and
    the lists of choices they are handed;
  - the engine: every effect's, modifier's and Nexus module's parameter and
    jack names, which the app shows as they are (tools/param_names.cpp);
  - the lane names' spelled-out abbreviations (model/LaneNames.kt), and the
    families the factory banks shelve their patches under.

A word built from a number - Group("op $o") - is kept as "op %d", and the
lookup fills the number in.

Re-run after editing a panel:  python3 tools/panel_words.py
Check that nothing is missing: python3 tools/panel_words.py --check
"""
import hashlib, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/rm/acidulous/ui"
MODEL = ROOT / "app/src/main/java/com/rm/acidulous/model"
XML = ROOT / "app/src/main/res/values/strings_panel_words.xml"
KT = UI / "PanelWords.kt"

PANEL_FILES = ["MachinePanel.kt", "SlotsPanel.kt", "SampleDialog.kt", "PatchEditor.kt"]
# Lists of names, not words: note names, scale names, chord symbols.
NOT_WORDS = {"KEY_NAMES", "SCALE_NAMES", "CHORD_NAMES", "NAMES", "NOTE_NAMES"}


def is_word(s):
    """Something a translator would change: it has letters, and is not notation."""
    s = s.strip()
    if not re.search(r"[A-Za-z]", s):
        return False
    # 1/8T, 1/16., 4', C#3, 5+oct is a word though: only pure notation is out.
    if re.fullmatch(r"[\d/.:+\-×x]*[Tt.]?", s):
        return False
    if re.fullmatch(r"[A-G][#b♯♭]?-?\d*", s):
        return False
    # Formulate's example expressions: code, which means the same in any language.
    if re.search(r">>|<<|sin\(", s):
        return False
    return True


def template(lit):
    """A Kotlin string literal's contents, its ${...} and $x turned into %d."""
    t = re.sub(r"\$\{[^}]*\}", "%d", lit)
    t = re.sub(r"\$[A-Za-z_]\w*", "%d", t)
    return t


def strings_in(text):
    return [m.group(1) for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', text)]


def split_args(text, start):
    """The argument list of a call whose '(' is at `start`, split at depth 1."""
    depth, args, cur, i = 0, [], "", start
    while i < len(text):
        c = text[i]
        if c == '"':
            j = i + 1
            while text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            cur += text[i:j + 1]
            i = j + 1
            continue
        if c in "([{":
            depth += 1
            if depth == 1:
                i += 1
                continue
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append(cur.strip())
                return args
        if depth == 1 and c == ",":
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    return args


def from_panels():
    words = set()
    for name in PANEL_FILES:
        text = (UI / name).read_text()
        # Comments say things too, and none of it is on a screen.
        text = re.sub(r"//[^\n]*", "", text)
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        for m in re.finditer(r"\bGroup\(", text):
            args = split_args(text, m.end() - 1)
            if args and re.fullmatch(r'"[^"]*"', args[0]):
                words.add(template(args[0][1:-1]))
        for m in re.finditer(r"\bPanel(Knob|Switch|StepKnob)\(", text):
            args = split_args(text, m.end() - 1)
            if len(args) < 2:
                continue
            labelled = False
            for a in args[2:]:
                a = re.sub(r"^label\s*=\s*", "", a)
                if re.fullmatch(r'"[^"]*"', a):
                    words.add(template(a[1:-1]))
                    labelled = True
                    break
            if not labelled and re.fullmatch(r'"[^"$]*"', args[1]):
                words.add(args[1][1:-1])
            for a in args[2:]:
                if "listOf(" in a:
                    words.update(template(s) for s in strings_in(a))
        # Lists of choices, wherever they are declared.
        for m in re.finditer(r"(?:val\s+(\w+)\s*=\s*)?listOf\(", text):
            if m.group(1) in NOT_WORDS:
                continue
            args = split_args(text, m.end() - 1)
            words.update(template(a[1:-1]) for a in args if re.fullmatch(r'"[^"]*"', a))
        # "word" to "shorter word", "group title" to listOf(...), "more" to rest.
        for m in re.finditer(r'"([^"]*)"\s+to\b(?:\s+"([^"]*)")?', text):
            words.add(template(m.group(1)))
            if m.group(2):
                words.add(template(m.group(2)))
    return words


def from_engine():
    exe = ROOT / "build/param_names"
    src = ROOT / "tools/param_names.cpp"
    if not exe.exists() or exe.stat().st_mtime < src.stat().st_mtime:
        lib = subprocess.run([str(ROOT / "tools/host_engine.sh")], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(["g++", "-O1", "-std=c++17", "-I", str(ROOT / "app/src/main/cpp"), str(src), lib, "-o", str(exe)], check=True)
    words = set()
    for line in subprocess.run([str(exe)], capture_output=True, text=True, check=True).stdout.splitlines():
        f = line.split("|")
        # A machine's parameters are named by its panel; the rest are shown as they are.
        if f[0] in ("effect", "mod", "nexus"):
            words.add(f[-1])
    return words


def from_model():
    words = set()
    lanes = (MODEL / "LaneNames.kt").read_text()
    words.update(m.group(1) for m in re.finditer(r'"[^"]*"\s+to\s+"([^"]*)"', lanes))
    for m in re.finditer(r'"([^"]*)"', re.sub(r"//[^\n]*|/\*.*?\*/", "", lanes.split("fun laneUnitLabel")[1].split("fun laneShortLabel")[0], flags=re.S)):
        words.add(template(m.group(1)).strip())
    banks = (MODEL / "FactoryBanks.kt").read_text()
    words.update(m.group(1) for m in re.finditer(r'family\s*=\s*"([^"]*)"', banks))
    return words


# Words gen_param_labels.py adds to a lane's name that no panel says itself.
LANE_WORDS = {"pad %d"}


def harvest():
    words = from_panels() | from_engine() | from_model() | LANE_WORDS
    out = set()
    for w in words:
        w = w.strip()
        # A template with nothing but its number is not a word.
        if is_word(w) and re.sub(r"%d|\s", "", w):
            out.add(w)
    return sorted(out, key=lambda s: (s.lower(), s))


def slug(word, taken):
    s = word.replace("%d", "n").lower()
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_") or "w"
    name = "pw_" + s
    if name in taken and taken[name] != word:
        name += "_" + hashlib.sha1(word.encode()).hexdigest()[:6]
    taken[name] = word
    return name


def xml_escape(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if s[:1] in "@?":
        s = "\\" + s
    return s


def render(words):
    taken, rows, kt = {}, [], []
    for w in words:
        name = slug(w, taken)
        # A literal percent in a word that is not a template must not be read as one.
        attr = ' formatted="false"' if "%" in w.replace("%d", "") else ""
        rows.append('    <string name="%s"%s>%s</string>' % (name, attr, xml_escape(w)))
        kt.append('    "%s" to R.string.%s,' % (w.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$"), name))
    # Abbreviations and ranges, which a spelling or typography check would "correct".
    xml = ('<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="Typos,TypographyDashes">\n'
           '    <!-- GENERATED by tools/panel_words.py - do not edit. Every word a machine,\n'
           '         effect or modifier panel shows. The English is the key: translate the\n'
           '         values in a values-xx copy of this file, and keep any %d. -->\n'
           + "\n".join(rows) + '\n</resources>\n')
    code = ('package com.rm.acidulous.ui\n\n'
            'import com.rm.acidulous.R\n\n'
            '// GENERATED by tools/panel_words.py - do not edit. Re-run it after changing a panel.\n\n'
            '/** A panel\'s English word -> the resource that says it; see PanelText.kt. */\n'
            'internal val PANEL_WORDS: Map<String, Int> = mapOf(\n' + "\n".join(kt) + '\n)\n')
    return xml, code


def main():
    words = harvest()
    xml, code = render(words)
    if "--check" in sys.argv:
        stale = [p.name for p, want in ((XML, xml), (KT, code)) if not p.exists() or p.read_text() != want]
        if stale:
            print("stale: %s - run python3 tools/panel_words.py" % ", ".join(stale))
            return 1
        print("%d panel words, up to date" % len(words))
        return 0
    XML.write_text(xml)
    KT.write_text(code)
    print("%d panel words -> %s, %s" % (len(words), XML.relative_to(ROOT), KT.relative_to(ROOT)))
    return 0


sys.exit(main())
