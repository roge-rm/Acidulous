# Acid
> Reflux's filter on any track, swept by the track's hits or a pattern.

## The controls

- **cutoff** - where the filter rests, 40 Hz to 12 kHz.
- **resonance** - how much it rings. Near the top it screams.
- **env** - how far each sweep opens it, up to four octaves.
- **decay** - how fast it falls back, 30 ms to 3 s.
- **accent** *(extra)* - how much further an accented sweep goes, and how
  much louder it is.
- **pattern** *(extra)* - what starts the sweep. **track** is the track
  itself: each hit opens it, and a loud one is an accent. The other eight are
  sixteen-step patterns on the tempo, with accents.
- **drive** - saturation after the filter.
- **mode** - low pass or band pass.
- **mix** - the dry sound against the filtered one.
- **gain** - the level out.

### Key

- **follow** *(extra)* - how far the cutoff moves from its knob onto a note
  in key. At the top, with the resonance up, the filter sings the note and
  each sweep rises from it. At 0 the key does nothing.
- **from** - where the notes come from. **scale** picks a note each step or
  hit from **key** and **scale**, mostly the root, as a bass line does.
  **notes** uses the track's played notes: with **pattern** at **track** it's
  the note just played, on a pattern each step takes the next held note up.
  Only a track's insert has **from**; on a group, the master or the input
  it's always the scale.
- **key** and **scale** - the scale's root and its kind.
- **octave** - moves the note up or down, -2 to +3.

### Slide

- **glide** - how long a slide takes, 10 ms to 1 s.
- **slides** *(extra)* - how many steps or hits glide to their note instead
  of jumping, each pattern's own slide steps first. With **from** at
  **notes** and the track's hits, a note played while another is held always
  slides.

## Tips

- Put it on a held chord with **pattern** at **16ths** for an acid line out
  of a pad.
- On a drum loop with **pattern** at **track**, every hit barks.
- Band pass with the track as the pattern is a wah that follows the playing.
- For a drum loop that screams a minor line, set **follow** to the top,
  **resonance** near it and **env** low, so the note is heard over the sweep.
