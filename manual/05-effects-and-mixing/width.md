# Width
> The stereo image: wider, narrower, mono below a frequency, or turned.

Nothing else in the rack touches the stereo field, which is a strange gap - it is one of the few things you can change about a finished mix that nobody hears as an effect.

## The controls

- **width** - 0 is mono, 1 is unchanged, 2 is twice as wide. It works on the difference between the channels, so widening pushes apart what was already apart and leaves anything centred where it is.
- **below** *(extra)* - everything under this frequency is collapsed to mono, 20 to 500 Hz. The bottom of a mix has no useful stereo information and plenty of harmful stereo information.
- **haas** *(extra)* - one channel delayed by up to 20 ms. A few milliseconds is heard as position rather than as an echo, which makes something wide without touching its tone at all.
- **rotate** - turns the whole image left or right without changing how wide it is, which is not the same as panning it.

## Using it well

**Mono below 120 Hz, always.** Bass that is out of phase between the channels disappears on anything that sums to mono, and a great many things still do. `below` costs nothing and removes a whole class of problem.

**Haas is the widener that survives a mono fold.** Widening by difference goes to nothing when the channels are summed; a delay of a few milliseconds turns into a mild comb instead, which is far less destructive.

**Width on a send does something odd and useful**: a wide, slightly delayed copy of several tracks sitting behind a narrow centre, which is a depth trick rather than a width one.
