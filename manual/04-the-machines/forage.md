# Forage

> The sample drum machine: thirteen pads, your own files, and a filter and envelope on each.

Forage is where recordings become drums. Thirteen pads, each holding a file you imported or recorded, and each with enough processing to make it fit.

## A pad

- **level**, **pan**, **pitch** and **decay**.
- **start** and **end** - the part of the file that plays, which is how one long recording becomes several pads.
- **mode** - once, loop or hold.
- **dir** - forwards or reversed.
- **cutoff** with a filter type, and **crush** for bit reduction.
- **pdecay** - a pitch envelope, so a sample can fall the way a drum does.
- **choke** - which group this pad belongs to. Two pads in one choke group cut each other off, which is how an open hat stops when the closed one hits.

## The slice source

There is a fourteenth slot above the pads: **one file for the whole machine**, which the pads take slices of. Load a bar of a break into it, ask for thirteen slices, and every pad is a piece of it - one decode and one copy in memory rather than thirteen.

## Using it well

**Choke groups are not optional.** Hats without one sound like two hats; with one they sound like a hat.

**Trim before you tune.** A sample with silence at the front plays late, and no amount of micro-timing fixes what a trim would have.

**`start` is a performance control.** Automate it across a bar and a single hit becomes a stutter.

**The sound library remembers what a song is using** - the delete page says "a track is playing this" before it lets you remove a file, which is the failure that set exists to prevent.
