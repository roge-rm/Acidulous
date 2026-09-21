# Delay
> Echoes on a note value, with a duck that gets out of the way while you are playing.

An echo, timed to the song rather than in milliseconds: **time** is a note value, so a delay set to a dotted eighth stays a dotted eighth when the tempo moves. That is most of what a delay is for in a sequencer, and it is the one thing a millisecond control cannot do.

## The controls

- **time** - the note value between repeats, from a thirty-second to a whole bar, including the dotted and triplet ones. The dotted eighth is the famous setting.
- **feedback** - how much of each echo goes back in. Up to 0.95, which is a long way but not infinite: a delay that self-oscillates is a thing to build on purpose, not to arrive at by accident.
- **tone** - a low-pass in the feedback path, so each repeat is duller than the last. This is what makes a long delay sit behind a part instead of competing with it.
- **pingpong** - how far the repeats alternate left and right. At nothing they stay where the source is; at full they bounce.
- **mix** - how much of the wet is in the output.
- **duck** *(extra)* - the repeats are pulled down while the dry signal is loud and come back up in the gaps. A delay you can leave on a vocal or a lead without it turning the part into mud.
- **wobble** *(extra)* - the delay time drifts slowly, as a tape machine's does when its motor is not quite steady. At a little it warms the repeats; further up it detunes them.

## Using it well

**Tone is the difference between an echo and a mess.** Long feedback with the tone wide open builds a copy of the part on top of itself. Bring the tone down to a few kilohertz and the same feedback becomes a tail that fades into the background.

**Duck instead of a shorter mix.** The usual way to stop a delay swamping a part is to turn it down, which also makes it inaudible in the gaps where you wanted it. Duck keeps the level and moves it out of the way instead.

**On a send it is fully wet**, so `mix` is forced and the two send sliders on each channel are how much of each track goes to it. One delay shared by four tracks is the same room; four delays on four inserts are four rooms.
