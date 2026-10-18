# Reflux

> Acid bass: one oscillator, one filter that screams, and lines you play in.

Reflux is one oscillator, one filter and one envelope, set up so you can play a
bassline in instead of building it step by step.

## Accent and slide

Acid basslines come from accent and slide, and Reflux gets them from how you
play:

- Accent is velocity. Play a note harder and it's louder, brighter and has more
  filter envelope.
- Slide is legato. Overlap two notes and the second glides from the first
  instead of starting again. Leave a gap and it plucks.

So play a line on the keyboard, or draw it in the roll with overlapping notes
for the slides.

## The controls

The **sound** section has most of it:

- **osc** - the **wave**, saw or pulse, with **mix** to fade toward the other
  wave, **pw** for the pulse's width, a **sub** oscillator an octave down and
  **tune**.
- **filter** - **cutoff** and **reso**, **envmod** for how much the envelope
  opens the filter and **decay** for how long it lasts. Short decays are
  plucky and long ones are rubbery. **mode** is low pass or band pass, and in
  low pass **type** picks LP24 (the usual one), LP12, HP or notch. Most of the
  sound is here.
- **touch** - **accent** is how much an accented note adds to level and
  filter, **slide** is how long a slide takes, and **vel** is how much velocity
  sets the level. A note that slides keeps the level of the note it slid from.
- **out** - **drive**, where the scream comes from, and **volume**.

The **lfo** section is one LFO with a **rate**, or a note value with **sync**,
a **shape** (sine, triangle, square, saw or random) and **restart**, free or
from each note. It moves the **cutoff**, **pitch** and **pw**, each either way
from zero. Synced to eighths and restarting on each note, it's a wobble bass.

## Macros

The **play** section has eight macro knobs. A patch can name them (Open,
Squelch, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** section sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch goes 24 semitones at 1 and LFO rate 4 octaves. Tap a macro's
name to rename it.

## Tips

- Resonance and drive together make the classic sound. Most acid patches have
  both up and the cutoff moving.
- Record the cutoff knob as automation while the song plays instead of drawing
  it in.
- Two Refluxes playing the same clip, one plucked and one sliding, panned apart
  with slightly different cutoffs, still works great.

*Reflux was called Subvert in earlier versions, and songs and patches from then open as Reflux.*
