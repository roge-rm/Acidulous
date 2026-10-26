# Mosaic

> A multisample player: zones across the keyboard and velocity, plus grain clouds.

Mosaic plays maps of samples. Each sample (a zone) covers a range of keys and
velocities, so together they make a playable instrument.

## The map

A **zone** is a file, a key range, a velocity range, a root note and a tuning.
Build zones by hand from your own recordings, or load a **SoundFont** to get a
whole map at once.

- **keyfade** and **velfade** - crossfades between neighbouring zones so you
  don't hear the joins.
- **scan** and **scanamt** - move through the velocity layers with a knob
  instead of velocity. Put scan on the mod wheel to blend a soft and a loud
  sample.

## Playing

**start**, **loop**, **reverse**, **envfrom** and **filemod** decide how each file
is played. After that there's a filter with its own envelope and key tracking,
two envelopes, two LFOs, glide and voice modes.

## Grains

**grain** plays the map as a grain cloud instead, with **gsize**, **gdensity**,
**gpos**, **gspray**, **gpitch** and **grate**.

## Macros

The **play** page has eight macro knobs. A patch can name them (Brighter,
Swell, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch is 24 semitones at 1. **mod 1** to **mod 8** are the
depths of the mod page's rows, so a macro can bring in a row a patch has set
up at zero. Scan, start and the envelopes are read when a note starts, so
they change from the next note; the rest move at once. The switches, loop,
filter type, voice mode, tuning, pan, the mod envelopes and the LFO shapes
can't be reached. Tap a macro's name to rename it.

## Tips

- Set the crossfades before judging how a map sounds.
- Check the root notes. A wrong root puts everything out of tune.
- Big maps use a lot of memory. The sound library shows what a song is using
  before you delete anything.
