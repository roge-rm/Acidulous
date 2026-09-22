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
- **scan** and **scanamt** - move smoothly through the velocity layers with a
  knob instead of velocity. Put scan on the mod wheel to blend a soft and a loud
  sample.

## Playing

**start**, **loop**, **reverse**, **envfrom** and **filemod** decide how each file
is played. After that there's a filter with its own envelope and key tracking,
two envelopes, two LFOs, glide and voice modes.

## Grains

**grain** plays the map as a grain cloud instead, with **gsize**, **gdensity**,
**gpos**, **gspray**, **gpitch** and **grate**.

## Tips

- Set the crossfades before judging how a map sounds.
- Check the root notes. A wrong root puts everything out of tune.
- Big maps use a lot of memory. The sound library tells you what a song is
  using before you delete anything.
