# Abacus

> An additive synth: every note is a row of up to 64 sine partials, each with
> its own level.

Abacus builds a note out of sine waves. Each one is a partial, and you set
how loud each is with a shape: a slope, a peak, a vowel, a comb. The beads on
the page show the partials as they stand, one rod each, higher up when
louder. The picture is for a middle C and doesn't move with the envelope or
the shimmer.

## Spectrum

- **spectrum** - **slope** is how fast the partials fall away, in dB an
  octave. -6 is a saw, 0 is bright and buzzy, -24 is nearly a sine. **odd/even**
  runs from only the even partials, through all, to only the odd ones (a
  square). **partials** - 16, 32 or 64. Fewer is lighter on the processor.
- **peak** - a boost around **centre**, by **gain** dB, **width** in octaves.
- **vowel** - **a e i o u** picks the vowel and **amount** blends it in. It
  puts two peaks where the voice has them.
- **comb** - takes out every nth partial, by **depth**. 1 at every 3rd gives
  a third pulse.
- **far end** - a second spectrum. Its knobs are offsets from the first.
  **morph** moves from one to the other.

## Partials

- **law** - where the partials sit: **harmonic**, a stiff **string**, a free
  **bar**, a **bell** or a square **plate**. The last three aren't whole
  multiples of the note, so they sound like struck things.
- **stretch** - pulls the law out of tune with itself. On harmonic and string
  it's a slight stretch like a piano's. On bar, bell and plate -1 is harmonic
  and 1 is twice as far away as the law.
- **damp** and **decay** - each partial dies away. **decay** is the first
  partial's time, 30 s holds. **damp** makes the high ones die faster.
- **spread** - **width** places the partials across the stereo field. **mode**
  is **odd/even** (odd on one side, even on the other), **scatter** or
  **low-high**. At 0 it's mono.
- **shimmer** - each partial drifts on its own in level and pitch, by
  **amount** and **rate**.

## Motion

- **morph env** - **depth** and **time**: a note starts at the far end and
  falls back to the near one (negative goes the other way). A short time
  gives a bright strike.
- **lfo 1** - **wave**, **rate**, **clock** (free or synced to the song),
  then how far it moves **pitch** and **morph**.
- **lfo 2** - the same, moving **bright** (the slope) and **pan**.
- **voice** - **vel bright** (how much a harder key opens the slope),
  **glide**, **bend**, **slide** and **press** (what an MPE finger does).
- **tune** - **octave**, **semis** and **fine**.

## Play

The **play** page has the eight macro knobs, the amp envelope (**A**, **D**,
**S**, **R**), **velocity**, **volume** and **pan**. Pressure opens the slope
and lifts the level. Takes the song's tuning, and with MPE each finger bends
and brightens its own note.

## Macros

The patches name their macros after what they do: Bright, Hollow, Morph, Fade,
Shimmer, Wide, Slow and Comb on the classic, string and organ patches (organ
has Click and Vibrato in place of Fade and Slow), Vowel and Voice on the vocal
ones, Strike, Ring, Damp and Bar on the mallets, Stretch and Swell on the
bells. They sit at zero when a patch loads.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch moves every note, 24 semitones at 1. The partials,
the comb's spacing, the law, the spread mode, the LFO waves and clocks, bend,
octave and semis can't be reached. Tap a macro's name to rename it.

## Tips

- A patch that holds has no decay, so **damp** does nothing until you bring
  **decay** down. The Fade macro does both.
- For a struck sound turn **morph env** up with a short **time** and give
  **far end** a brighter slope.
- The patches come in six families: classic, organ, vocal, strings, mallets
  and bells.
