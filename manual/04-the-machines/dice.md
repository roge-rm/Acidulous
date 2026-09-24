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
- **stutter**, with **stutterdiv** - repeat part of it.
- **drop** - play nothing.
- **jump**, with **jumprange** - skip to somewhere else in the loop and carry on.

**seed** decides the rolls and **hold** freezes them. Turn the dice up until you
hear something you like, then hold it.

## Playing

The slices sit on the grid like drum sounds, so you can reorder the loop by
writing a pattern. **rate**, **gate**, **pitch**, **fine** and **accent** control
playback, followed by a filter and drive.

## Tempo

**plays at** decides whether the loop keeps its own tempo or follows the song.
On **song**, which is where it starts, each slice is time-stretched to the
song's tempo without changing its pitch, so a 90 bpm break fits a 126 bpm song
with no gaps between the slices. **pitch** then changes only the pitch, and
**rate** still speeds it up or slows it down.

**bars** is how long the loop is, which is how its tempo is worked out. On
**auto**, it's guessed from the loop's length and where its hits fall, and the
line under the loop's name says what it found, e.g. "2 bars at 90 bpm". If
that's wrong, set it yourself.

A slice played backwards isn't stretched. It's sped up or slowed down like
tape, so it goes a little out of tune when the song is far from the loop's
tempo.

## Tips

- Unheld, Dice changes every bar. Held on a good roll, it becomes a part.
- For a break that swings, cut on the hits rather than evenly.
- The seed makes it repeatable, so an export sounds like what you arranged.
