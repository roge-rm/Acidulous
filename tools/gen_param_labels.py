#!/usr/bin/env python3
"""Collects the wording the machine panels use into model/ParamLabels.kt.

Every control on a machine panel is written like PanelKnob(b, "gpos",
"position") inside a Group("grains"), so the panel source already says what
"gpos" is in the words the user sees. The automation lane list needs the same
words, so this pulls them from ui/MachinePanel.kt instead of keeping a second
table by hand.

Re-run after editing a panel:  python3 tools/gen_param_labels.py
"""
import re, sys, pathlib

SRC = pathlib.Path("shared/src/commonMain/kotlin/com/rm/acidulous/ui/MachinePanel.kt")
OUT = pathlib.Path("shared/src/commonMain/kotlin/com/rm/acidulous/model/ParamLabels.kt")

PANELS = {
    "RefluxPanel": "Reflux", "HexbeatPanel": "Hexbeat", "TrinityPanel": "Trinity",
    "RatioPanel": "Ratio", "MosaicPanel": "Mosaic", "ForagePanel": "Forage",
    "ManualPanel": "Manual", "CipherPanel": "Cipher", "FilamentPanel": "Filament",
    "NexusPanel": "Nexus", "CumulusPanel": "Cumulus", "FormulatePanel": "Formulate",
    "PollenPanel": "Pollen", "ResonancePanel": "Resonance", "DicePanel": "Dice", "GenesisPanel": "Genesis",
    "BrazenPanel": "Brazen", "HammerPanel": "Hammer", "TimberPanel": "Timber", "MoltPanel": "Molt", "DictionPanel": "Diction",
    "DictionConsonantsWindow": "Diction",
    "BiasPanel": "Bias", "TonguePanel": "Tongue", "DrawPanel": "Draw", "FretPanel": "Fret",
    "TinePanel": "Tine",
}
# Some panels name every control for the selected pad, so one panel covers
# all the pads' parameters.
PAD_COUNTS = {"Forage": 13, "Resonance": 8, "Dice": 16}
# The per-pad key prefix for each.
PAD_PREFIX = {"Forage": "p%02d_", "Resonance": "p%02d_", "Dice": "s%02d_"}


def split_args(text, start):
    """Splits the argument list of a call whose '(' is at `start`, at depth 1."""
    depth, args, cur, i = 0, [], "", start
    while i < len(text):
        c = text[i]
        if c == '"':
            j = text.index('"', i + 1)
            cur += text[i:j + 1]
            i = j + 1
            continue
        if c in "([":
            depth += 1
            if depth == 1:
                i += 1
                continue
        elif c in ")]":
            depth -= 1
            if depth == 0:
                args.append(cur.strip())
                return args, i + 1
        if depth == 1 and c == ",":
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    return args, i


def calls(text):
    for m in re.finditer(r'Panel(Knob|Switch|StepKnob|VowelKnob)\(', text):
        args, _ = split_args(text, m.end() - 1)
        if len(args) >= 2:
            yield args


def label_of(args):
    """Returns the first bare string after the name; a list or `accent =` doesn't count."""
    for a in args[2:]:
        if re.fullmatch(r'"[^"]*"', a):
            return a[1:-1]
    return None


# Constants a loop bound may use instead of a number. Listed here instead of
# parsed from Kotlin because there are only a few.
LOOP_CONSTS = {"BIAS_LANES": 4}


def expand(text, var, value):
    # Handles "${lane + 1}" as well as "${lane}", for a loop counting from 0
    # and a control numbered from 1.
    out = re.sub(r"\$\{%s\s*\+\s*(\d+)\}" % var,
                 lambda m: str(value + int(m.group(1))), text)
    out = out.replace("${%s}" % var, str(value)).replace("$" + var, str(value))
    return out.replace("%02d", "%02d" % value).replace("%d", str(value))


def harvest():
    lines = SRC.read_text().splitlines()
    labels, fn, depth, groups, loop, prefix_tpl = {}, None, 0, [], None, None
    for raw in lines:
        line = raw.split("//")[0]
        stripped = line.strip()
        m = re.match(r'private fun (\w+)\(', stripped)
        if m:
            fn, depth, groups, loop, prefix_tpl = PANELS.get(m.group(1)), 0, [], None, None
        if fn:
            fm = re.search(r'for\s*\(\s*(\w+)\s+in\s+(\d+)\.\.(\d+)\s*\)', stripped)
            if fm:
                loop = (fm.group(1), int(fm.group(2)), int(fm.group(3)))
            # `for (lane in 0 until BIAS_LANES)`, the half-open form used for
            # loops over slots.
            um = re.search(r'for\s*\(\s*(\w+)\s+in\s+(\d+)\s+until\s+(\w+)\s*\)', stripped)
            if um:
                hi = LOOP_CONSTS.get(um.group(3))
                if hi is None and um.group(3).isdigit():
                    hi = int(um.group(3))
                if hi is not None:
                    loop = (um.group(1), int(um.group(2)), hi - 1)
            pm = re.search(r'val\s+p\s*=\s*"([^"]*)"', stripped)
            if pm:
                prefix_tpl = pm.group(1)
            for gm in re.finditer(r'Group\(\s*"([^"]*)"', line):
                groups.append((depth, gm.group(1)))
            title = groups[-1][1] if groups else ""
            for args in calls(line):
                name_expr, label = args[1].strip(), label_of(args)
                lit = re.fullmatch(r'"([^"]*)"', name_expr)
                pref = re.fullmatch(r'p\s*\+\s*"([^"]*)"', name_expr)
                pad = re.fullmatch(r'n\("([^"]*)"\)', name_expr)
                if lit:
                    stem = lit.group(1)
                    if "$" in stem and loop:
                        # "e${e}_attack" inside for (e in 1..2): one per pass.
                        var, lo, hi = loop
                        for v in range(lo, hi + 1):
                            # The label is a template too: "macro $i" has to
                            # become "macro 3".
                            put(labels, fn, expand(stem, var, v), expand(title, var, v),
                                expand(label or stem, var, v))
                    else:
                        put(labels, fn, stem, title, label or stem)
                elif pref and prefix_tpl and loop:
                    var, lo, hi = loop
                    for v in range(lo, hi + 1):
                        key = expand(prefix_tpl, var, v) + pref.group(1)
                        put(labels, fn, key, expand(title, var, v), expand(label or pref.group(1), var, v))
                elif pad and fn in PAD_COUNTS:
                    for v in range(PAD_COUNTS[fn]):
                        put(labels, fn, (PAD_PREFIX[fn] % v) + pad.group(1),
                            title, label or pad.group(1), lead="pad %d" % (v + 1))
        depth += line.count("{") - line.count("}")
        while groups and depth <= groups[-1][0]:
            groups.pop()
        if loop and depth <= 2:
            loop, prefix_tpl = None, None
    return labels


def put(labels, fn, stem, title, label, lead=None):
    """Stores the full label's words for the list, and the bare label for the narrow gutter."""
    labels["%s:%s" % (fn, stem)] = (([lead] if lead else []) + join(title, label), label.strip())


def join(title, label):
    # "filter" + "freq" gives "filter freq", "op 3" + "fb" gives "op 3 fb".
    # A label that already starts with the section skips it. Kept as separate
    # words so the app can translate each one.
    title, label = title.strip(), label.strip()
    if not title or title == label or label.startswith(title + " "):
        return [label]
    return [title, label]


def main():
    labels = harvest()
    # A key with a template left in it never matches a real parameter, so it's
    # a parsing failure.
    bad = [k for k in labels if "$" in k or "%" in k]
    # An unexpanded template in a label is just as wrong, and it's what the
    # user reads.
    bad += ["%s -> %s" % (k, v[0]) for k, v in labels.items() if any("$" in w or "%" in w for w in v[0])]
    if bad:
        print("unexpanded: %s" % bad[:6], file=sys.stderr)
        return 1
    if len(labels) < 300:
        print("only %d labels harvested - the panel format probably changed" % len(labels), file=sys.stderr)
        return 1
    body = "\n".join('    "%s" to "%s",' % (k, "|".join(v[0])) for k, v in sorted(labels.items()))
    short = "\n".join('    "%s" to "%s",' % (k, v[1]) for k, v in sorted(labels.items()) if [v[1]] != v[0])
    OUT.write_text('''package com.rm.acidulous.model

// GENERATED by tools/gen_param_labels.py from ui/MachinePanel.kt - do not edit.
//
// A parameter's engine name is short because it is a key ("gpos", "o3_fb").
// The panels already spell those out under a section heading, so this is
// that wording lifted to where the automation list can use it, a word at a
// time so each can be put into the phone's language (ui/PanelText.kt).
// Re-run the script after changing a panel.

/**
 * "Mosaic:gpos" -> "grains|position", for a list with room to read: the
 * words, split at the bar. One string rather than a list of them, because
 * fifteen hundred lists is more than a class initialiser may hold.
 */
val PANEL_LABELS: Map<String, String> = mapOf(
%s
)

/** The same parameter without its section, for the strip's narrow gutter. */
val PANEL_SHORT: Map<String, String> = mapOf(
%s
)
''' % (body, short))
    print("%d labels -> %s" % (len(labels), OUT))
    return 0


sys.exit(main())
