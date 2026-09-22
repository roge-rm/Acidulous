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

## Tips

- Unheld, Dice changes every bar. Held on a good roll, it becomes a part.
- For a break that swings, cut on the hits rather than evenly.
- The seed makes it repeatable, so an export sounds like what you arranged.
