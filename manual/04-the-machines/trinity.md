# Trinity

> Three oscillators, wavetables, two filters and a mod matrix: the one that does everything.

Trinity is the general-purpose polysynth - the machine to reach for when what you want is not a specific idea but a *sound*. Three oscillators into two filters, with a modulation matrix over the top.

## The oscillators

Each of the three has a wavetable rather than a waveform: a table is a row of shapes and **pos** moves through it, so a sweep from a hollow pulse to a full saw is one knob and one automation lane.

- **density** stacks copies of the oscillator, **detune** spreads them apart and **drift** makes each copy wander slowly on its own. Density with a little drift is where a thin oscillator becomes a section.
- **hard** is oscillator sync, and **pw** the pulse width, both of them the classic destinations for an envelope.
- **fm21** and **fm32** are the two FM paths - two into one, three into two - so Trinity can do the metallic end without being an FM machine.
- **noise** with its **colour** sits beside them, which is what a snare tail or a breathy attack is made of.

## The filters

Two of them, and **route** decides whether they are in series, in parallel, or split with **balance** between them. Each has its own type, drive and drive type, plus key tracking and an envelope amount. A low-pass into a band-pass in series is a formant; the same two in parallel is a comb.

## Modulation

Six envelopes and three LFOs, into twelve matrix rows.

Two of the envelopes are spoken for - **a** is the amplitude and **f** the filter - and the other four exist only to be routed. Each LFO has a wave, a rate that can sync to the transport, a delay, a phase, key sync, one-shot and slew.

A **matrix** row is a source, a second source, a destination and a depth. The second source *scales* the first rather than adding to it.

## Using it well

**Start from the oscillator, not the filter.** Trinity's character is in the table position and the density; a patch that only moves the cutoff could have been any synth.

**Two sources in one matrix row is the trick most people miss.** Setting src to an envelope and src2 to the mod wheel gives you an envelope you can fade in with your thumb, which is one row rather than two.

**A stacked patch is the expensive one.** If a song is running out of processor, Trinity with high density is usually where it went - and freezing that clip gives it all back.
