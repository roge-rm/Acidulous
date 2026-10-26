# Dice

> A loop slicer with chance on every slice.

Dice cuts a loop into slices and plays them back, with a chance of something
different happening on each one. It's good for breaks.

## Slicing

**slices** sets how many pieces, and **cut** sets where they fall, from even
divisions to the loop's own hits. Each slice has its own **level**, **pan**,
**pitch**, **decay** and **dir**.

## The dice

Five chances, rolled on every slice:

- **swap** - play a different slice.
- **reverse** - play it backwards.
- **stutter**, with **times** - repeat its start that many times in its own length.
- **drop** - play nothing.
- **jump**, with **up to** - play it up or down by as many semitones.

**seed** decides the rolls and **hold** freezes them. Turn the dice up until you
hear something you like, then hold it.

## Playing

The slices sit on the grid like drum sounds, so you can reorder the loop by
writing a pattern. **rate**, **gate**, **pitch**, **fine** and **accent** control
playback, followed by a filter and drive.

## Tempo

**plays at** decides whether the loop keeps its own tempo or follows the song.
On **song**, the default, each slice is time-stretched to the song's tempo
without changing its pitch, so a 90 bpm break fits a 126 bpm song with no gaps.
**pitch** then only changes the pitch, and **rate** still speeds it up or slows
it down.

**bars** is how long the loop is, which is how its tempo is worked out. On
**auto** it's guessed from the loop's length and where its hits fall, and the
line under the loop's name says what it found, like "2 bars at 90 bpm". Quiet
before the first hit and a cymbal ringing past the last bar are left out. If
that's wrong, set it yourself.

A slice played backwards isn't stretched. It's sped up or slowed down like
tape, so it goes a little out of tune when the song is far from the loop's
tempo.

## Macros

The **play** page has eight macro knobs. A patch can name them (Chaos,
Stutter, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning
the knob moves the destination by knob times amount, across that control's
whole range at 1. Pitch is 24 semitones at 1. The five dice, times, up to,
seed and slices are all there, so one knob can take a loop from straight to
wild, or step through held patterns. Everything but the filter, drive, volume
and pan is read when a slice starts, so it changes from the next slice. The
per-slice settings, cut, hold, plays at, bars and the filter type can't be
reached. Tap a macro's name to rename it.

## Tips

- Unheld, Dice changes every bar. Held on a good roll, it becomes a part.
- For a break that swings, cut on the hits instead of evenly.
- The seed makes it repeatable, so an export sounds like what you arranged.
