# Resonance

> Eight struck objects that ring into each other.

Resonance is modal percussion. Each of its eight pads is an object (a drum, a
bar, a bell, etc.) that rings at a set of frequencies, and playing a pad
strikes it. Nothing is sampled.

## A pad

- **modes** - how many partials it has. Few sounds like a woodblock, many like a
  bell.
- **shape** - membrane, bar, plate, tube, bowl or metal. This sets how the
  partials are spaced, which is most of what makes a drum sound different from
  a bar.
- **inharm** - stretches the spacing.
- **decay** and **damp** - how long it rings, and whether the highs die first.
- **hard** - how hard the beater is (how much top end).
- **hit** - where you strike it. Different places bring some partials out and
  hide others, so it changes the sound a lot.
- **bend** and **bendtime** - a drop in pitch after the hit, like a tom.
- **drive**, **pan**, **level** and **couple**.

## Coupling

**coupling** sets how much the pads ring into each other. Hit one and the
others answer quietly at their own pitches, so a kit sounds like objects in one
room. **humanise** varies each hit a little.

## Macros

The **play** page has eight macro knobs. A patch can name them (Tighter, Damp,
etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. The "all" destinations move that control on every pad together.
Pitch moves every pad's tune together, 24 semitones at 1. A tune or decay
change is heard from the next hit. Tap a macro's name to rename it.

## Tips

- Set **hit** and **shape** before tuning.
- Go easy on coupling. A little sounds like a room, and a lot feeds back.
- With long decays you can play melodies on the pads.
