# Forage

> A sample drum machine: thirteen pads for your own sounds.

Forage plays your own samples on thirteen pads, each with some processing to
help it fit.

## A pad

- **level**, **pan**, **pitch** and **decay**.
- **start** and **end** - which part of the file plays, so one long recording
  can feed several pads.
- **play** - once, loop or hold.
- **reverse** - forwards or backwards.
- **cutoff**, **reso** and **mode** (low or band pass), and **crush** for bit
  reduction.
- **pitch env** and its **decay**, under punch - a pitch drop, so a sample can
  fall like a drum does.
- **choke** - pads in the same choke group cut each other off, like an open hat
  stopping when the closed hat plays.

## Macros

The **pad** page ends with eight macro knobs. A patch can name them (Tight,
Dark, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. The **all** destinations move every pad from where it is,
and **tune**, **level**, **decay** and **cutoff** with a number move one pad
on top of that. Pitch is 24 semitones at 1 on every pad. Start, end, decay,
the pitch env decay, accent and vel are read when a pad is hit, so they
change from the next hit. Play, reverse, choke, pan and the filter mode can't
be reached. Tap a macro's name to rename it.

## Slicing one file

There's a fourteenth slot above the pads for one file shared by the whole
machine. Load a drum break into it, ask for thirteen slices and each pad plays
one piece of it.

## Tips

- Put your hats in a choke group.
- Trim silence off the start of a sample, or it will play late.
- Automate **start** across a bar to turn one hit into a stutter.
- The sound library warns you before deleting a file a track still uses.
