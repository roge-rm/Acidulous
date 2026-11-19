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

## Counting

The partials can play in rhythm, from the song's clock, so bar 5 sounds the
same whether you start at bar 1 or at bar 5. **counting** is the pattern:
**off**, **up**, **down**, **up-down**, **odd/even** (low half and high half
in turn), **fifths** (each step a fifth up, folded into two octaves) or
**random** (new every bar, the same every time you play it).

- **pattern** - **counting**, **rate** (1/32T to a bar; it follows the track's
  swing) and **steps**, how many steps before the pattern starts over.
- **lit** - **from** is the lowest partial the pattern plays on. The ones
  under it always sound, so the note keeps its pitch. **width** is how many
  partials are lit on a step. **depth** is how far the others drop, 0 is no
  change and 1 is silent. **glide** is how much of a step is spent fading
  from the last step's partials.

With the song stopped it keeps counting at the tempo. **count depth** and **count glide** can be macro
destinations.

## Slide

Each partial can travel to its pitch on its own time. Play a note while
another is held, or just let go of one, and the new note's partials start at
the old note's pitch and arrive one after another, so a chord of partials
rolls into place instead of gliding as one.

- **slide** - **time** is the longest a partial takes, up to 4 s; the bottom
  of the knob is off. **spread** is how far apart the times are: at 0 every
  partial takes the whole time, at 1 the first takes a tenth of it. **order**
  is who arrives first: **low first**, **high first**, **scatter** (a new
  order for every note, the same every time you play it) or **centre out**.
  **when** is **always** or **legato only**, which slides only when the last
  key is still down, or was let go of a moment ago.
- **strum** - at the start of a note the partials come in one by one, in the
  **order** above, over this long. 0 is off.

The first note after a rest has nothing to slide from. **glide** is left out
on a note that slides. Per-note bends, count and borrow all work while it
slides. **slide time**, **slide spread** and **strum** can be macro
destinations. The slide's time and order are taken when the note starts.

## Borrow

The partials can take their levels from another sound as it plays: another
track, or a file. Your notes keep their pitch and the other sound gives them
its colour and its loudness, so a pad can talk with a vocal or breathe with a
drum track.

- **borrow** - **from** is **off**, **track** or **file**. **read** is how
  the sound is read. **envelope** takes its overall colour, its formants and
  its brightness, and lays it over your partials by frequency. **partials**
  follows its pitch and takes its harmonics by number, its 3rd onto your 3rd,
  which needs a sound with one clear pitch. **amount** blends from the
  partials' own levels to the borrowed ones.
- **follow** - **smooth** is how fast the borrowed levels follow, short for
  speech, longer for a softer pad. **freeze** set to **hold** keeps what it has
  and stops listening.
- **track** - which track to borrow from. With none chosen the partials play
  their own levels.
- **file** - **import…** opens a file, **samples…** picks one you have, and
  **voice** is a sung line that comes with the app. **place** is where in the
  file it reads, and **scan** moves through it, 1 at the file's own speed,
  negative backward, looping at the ends. A file is read up to a minute.

Rumble under 70 Hz is left out, so a track's hum doesn't colour the partials.
When the other track goes quiet so does Abacus. **borrow amt**, **borrow
smooth**, **file place** and **file scan** can be macro destinations.

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
bells, Depth and Glide on the count patches, Amount, Smooth, Place and Scan
on the borrow ones, and Slide, Spread and Strum on the slide ones. A lead has
Glide for its pitch and a bass has Release. They sit at zero when a patch
loads, and where one changes the level it also turns the volume to make up.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch moves every note, 24 semitones at 1. The partials,
the comb's spacing, the law, the spread mode, the LFO waves and clocks, bend,
octave and semis, and the count's pattern, rate, steps, from and width,
can't be reached. Tap a macro's name to rename it.

## Tips

- A patch that holds has no decay, so **damp** does nothing until you bring
  **decay** down. The Fade macro does both.
- For a struck sound turn **morph env** up with a short **time** and give
  **far end** a brighter slope.
- The patches come in nine families: classic, organ, vocal, strings,
  mallets, bells, count, borrow and slide.
- The borrow patches that read a track have no track chosen: pick one on the
  **borrow** page, a drum loop or a vocal works well.
