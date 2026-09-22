# Forage

> A sample drum machine: thirteen pads for your own sounds.

Forage plays your own samples on thirteen pads, each with some processing to
help it fit.

## A pad

- **level**, **pan**, **pitch** and **decay**.
- **start** and **end** - which part of the file plays. One long recording can
  feed several pads this way.
- **mode** - once, loop or hold.
- **dir** - forwards or backwards.
- **cutoff** with a filter type, and **crush** for bit reduction.
- **pdecay** - a pitch drop, so a sample can fall like a drum does.
- **choke** - pads in the same choke group cut each other off, like an open
  hat stopping when the closed hat plays.

## Slicing one file

There's a fourteenth slot above the pads for one file shared by the whole
machine. Load a drum break into it, ask for thirteen slices, and each pad plays
one piece of it.

## Tips

- Put your hats in a choke group.
- Trim silence off the start of a sample, or it will play late.
- Automate **start** across a bar to turn one hit into a stutter.
- The sound library won't let you delete a file a track is still using without
  telling you first.
