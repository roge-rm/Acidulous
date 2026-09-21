# Harmonizer
> Two added voices at scale degrees, so the harmony stays in key.

A pitch shifter that knows what key it is in. Set an interval in *scale degrees* rather than semitones and the added voice moves by a third that is major or minor depending on where the note sits - which is what a harmony part actually does.

## The controls

- **interval** and **interval2** - the two added voices, -7 to +7 scale degrees. A third and a fifth above is the obvious one; an octave down on one and a third up on the other is more interesting.
- **scale** *(extra)* - which of the thirty-three scales the degrees are counted in. The same control the Scale modifier uses, so a track and its harmonizer can be told the same thing.
- **key** *(extra)* - the tonic those degrees are measured from.
- **window** - the grain length the shifting is done with, 10 to 120 ms. Short follows fast material and sounds grainier; long is smoother and smears transients.
- **feedback** - the harmonised output back into the input, which stacks the interval on itself.
- **mix** - wet against dry.

## Using it well

**Set the key, or it is just a pitch shifter.** With the wrong key the third above will be major where the music wants minor, on precisely the notes where that is most audible.

**Window is a material control.** On a sustained vocal or pad, long windows are cleaner. On anything percussive, long windows smear the attack into a flam - come down to 20 ms and accept the roughness.

**A fifth below is more useful than a third above.** The third is the obvious harmony and the one that exposes every tuning and timing error. A fifth or an octave below thickens a line without inviting the comparison.

**Feedback at a small interval builds a chord.** Two degrees with feedback gives you the stack of thirds you would have had to play.
