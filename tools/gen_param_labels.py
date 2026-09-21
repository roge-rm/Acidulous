#!/usr/bin/env python3
"""Harvest the human wording the machine panels already use.

Every control on a machine panel is written as PanelKnob(b, "gpos",
"position") inside a Group("grains"), so the panel source already says, in
the words the app shows a player, what "gpos" is. The automation lane list
needs those same words, and a second hand-written table would drift from the
panels within a release - so this lifts them out of ui/MachinePanel.kt and
writes model/ParamLabels.kt.

Re-run after editing a panel:  python3 tools/gen_param_labels.py
"""
import re, sys, pathlib

SRC = pathlib.Path("app/src/main/java/com/rm/acidulous/ui/MachinePanel.kt")
OUT = pathlib.Path("app/src/main/java/com/rm/acidulous/model/ParamLabels.kt")

PANELS = {
    "SubvertPanel": "Subvert", "HexbeatPanel": "Hexbeat", "TrinityPanel": "Trinity",
    "RatioPanel": "Ratio", "MosaicPanel": "Mosaic", "ForagePanel": "Forage",
    "ManualPanel": "Manual", "CipherPanel": "Cipher", "FilamentPanel": "Filament",
    "NexusPanel": "Nexus", "CumulusPanel": "Cumulus", "FormulatePanel": "Formulate",
    "PollenPanel": "Pollen", "ResonancePanel": "Resonance", "DicePanel": "Dice", "GenesisPanel": "Genesis",
    "BrazenPanel": "Brazen", "TimberPanel": "Timber", "MoltPanel": "Molt",
    "BiasPanel": "Bias",
}
# Some panels name every control for the *selected* pad, so one panel
# describes a whole machine's worth of parameters.
PAD_COUNTS = {"Forage": 13, "Resonance": 8, "Dice": 16}
# And what the per-pad keys are called in each.
PAD_PREFIX = {"Forage": "p%02d_", "Resonance": "p%02d_", "Dice": "s%02d_"}


def split_args(text, start):
    """The argument list of a call whose '(' is at `start`, split at depth 1."""
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
    for m in re.finditer(r'Panel(Knob|Switch|StepKnob)\(', text):
        args, _ = split_args(text, m.end() - 1)
        if len(args) >= 2:
            yield args


def label_of(args):
    """The first bare string after the name; a list or `accent =` is not one."""
    for a in args[2:]:
        if re.fullmatch(r'"[^"]*"', a):
            return a[1:-1]
    return None


# Constants a loop bound may be written as, rather than as a number. Kept here
# rather than parsed out of Kotlin because there are two of them.
LOOP_CONSTS = {"BIAS_LANES": 4}


def expand(text, var, value):
    # "${lane + 1}" as well as "${lane}": a loop that counts from nought and a
    # control that counts from one is the ordinary case, not a special one.
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
            # `for (lane in 0 until BIAS_LANES)` - the half-open form, which is
            # what a loop over slots rather than over musical numbers looks like.
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
                            # become "macro 3", or the list shows the source.
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
                            "pad %d %s" % (v + 1, title), label or pad.group(1))
        depth += line.count("{") - line.count("}")
        while groups and depth <= groups[-1][0]:
            groups.pop()
        if loop and depth <= 2:
            loop, prefix_tpl = None, None
    return labels


def put(labels, fn, stem, title, label):
    """Full name for the list, and the bare label for the folded gutter."""
    labels["%s:%s" % (fn, stem)] = (join(title, label), label.strip())


def join(title, label):
    # "filter" + "freq" reads as "filter freq"; "op 3" + "fb" as "op 3 fb".
    # A label that already repeats the section adds nothing.
    title, label = title.strip(), label.strip()
    if not title or title == label or label.startswith(title + " "):
        return label
    return "%s %s" % (title, label)


def main():
    labels = harvest()
    # A key that still carries a template never matches a real parameter, so
    # it is a parsing failure, not a label.
    bad = [k for k in labels if "$" in k or "%" in k]
    # A label that still carries a template is as wrong as a key that does,
    # and it is the half a player actually reads.
    bad += ["%s -> %s" % (k, v[0]) for k, v in labels.items() if "$" in v[0] or "%" in v[0]]
    if bad:
        print("unexpanded: %s" % bad[:6], file=sys.stderr)
        return 1
    if len(labels) < 300:
        print("only %d labels harvested - the panel format probably changed" % len(labels), file=sys.stderr)
        return 1
    body = "\n".join('    "%s" to "%s",' % (k, v[0]) for k, v in sorted(labels.items()))
    short = "\n".join('    "%s" to "%s",' % (k, v[1]) for k, v in sorted(labels.items()) if v[1] != v[0])
    OUT.write_text('''package com.rm.acidulous.model

// GENERATED by tools/gen_param_labels.py from ui/MachinePanel.kt - do not edit.
//
// A parameter's engine name is short because it is a key ("gpos", "o3_fb").
// The panels already spell those out under a section heading, so this is
// that wording lifted to where the automation list can use it. Re-run the
// script after changing a panel.

/** "Mosaic:gpos" -> "grains position", for a list with room to read. */
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
