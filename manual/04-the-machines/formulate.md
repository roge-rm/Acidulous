# Formulate

> The 8-bit machine, and an equation you can type into it.

Formulate is a chip synth: pulse, triangle and noise, the hardware that made game music, with the crunch left in. And then the extra - **a waveform is something you write**.

## The hardware

- **wave** chooses pulse, triangle, noise or the written formula.
- **duty** is the pulse width, with **pwmrate** and **pwmdepth** sweeping it - the sound of every chiptune lead ever made.
- **sub** adds an octave below, and **noiseshort** switches the noise between its long and short periods, which is the difference between a hiss and a metallic buzz.
- **bits**, **crush** and **smooth** are the converter: how many bits, how often it updates, and how much of the resulting staircase is filtered off. All the way down is a fault; part of the way down is the sound.

## The tracker tables

Three of them - **arp**, **duty** and **vol** - written as text and stepped through per tick, the way a tracker instrument does it. An arpeggio table of `0 4 7` played fast is a chord from one voice, and that is how those records were made.

## The formula

`formula` is an expression with **x** in scope, where x runs from 0 to 1 across one cycle of the waveform. `sin(x*2*pi)` is a sine. `x*2-1` is a saw. `(x<0.3)?1:-1` is a pulse with a 30% duty. Anything you can write, the oscillator plays.

**formulamode** decides whether it is evaluated per cycle or per sample, and the three **macros** `a`, `b` and `c` are knobs you can refer to by name inside the expression - so a formula can have controls.

## Using it well

**Write the formula against a macro, then automate the macro.** `sin(x*2*pi*a)` with `a` on a lane is a sweep no wavetable contains.

**The tracker tables are where the personality is.** A flat patch with a good volume table sounds better than a clever formula with none.

**A typed formula that fails says so** on the panel rather than going quiet, which is the one thing worse than a wrong note.
