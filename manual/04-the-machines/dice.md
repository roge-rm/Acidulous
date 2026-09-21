# Dice

> A loop cut into pieces, and rolled.

Dice takes one loop and cuts it into slices, then plays those slices back with a probability on every trigger. It is the machine for breaks, and for the thing a break does when it stops being a loop.

## The cut

**slices** is how many pieces, and **cut** is where the cuts fall - from an even division to the loop's own transients. Each slice then has its own **level**, **pan**, **pitch**, **decay** and **dir**, so a slice can be quieter, lower or backwards without touching the others.

## The dice

Five of them, each a probability rolled per trigger:

- **swap** - play a different slice instead of this one.
- **reverse** - play it backwards.
- **stutter**, with **stutterdiv** - repeat a fraction of it.
- **drop** - play nothing.
- **jump**, with **jumprange** - move somewhere else in the loop and carry on from there.

**seed** decides the rolls, and **hold** freezes them - so a roll you liked can be kept rather than lost to the next bar. That pairing is the whole machine: turn the dice up until something good happens, then hold it.

## Playing it

The slices sit on the grid like drum voices, so a pattern of slice numbers is a re-ordering of the loop written out. **rate**, **gate**, **pitch**, **fine** and **accent** are the playback, and there is a filter and drive after.

## Using it well

**Hold is the point.** Unheld, Dice is different every bar and tiring; held on a good roll, it is a part.

**Cut on transients, not evenly, for a real break** - an even cut on a loop that swings puts every slice in slightly the wrong place.

**A seed makes it repeatable**, so an exported song sounds like the one you arranged.
