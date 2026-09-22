# Formulate

> An 8-bit chip synth, and a waveform you can type in as an equation.

Formulate is a chip synth with pulse, triangle and noise, like the old game
consoles. It can also play a waveform you write as a formula.

## The chip

- **wave** - pulse, triangle, noise, or the formula.
- **duty** - the pulse width. **pwmrate** and **pwmdepth** sweep it.
- **sub** - adds an octave below.
- **noiseshort** - switches the noise between a hiss and a metallic buzz.
- **bits**, **crush** and **smooth** - bit depth, sample rate, and how much the
  steps are smoothed off.

## Tracker tables

There are three, **arp**, **duty** and **vol**, typed in as text and stepped
through per tick like a tracker instrument. An arp table of `0 4 7` played fast
makes a chord from one voice.

## The formula

**formula** is an expression where **x** goes from 0 to 1 over one cycle of the
wave. `sin(x*2*pi)` is a sine, `x*2-1` is a saw, and `(x<0.3)?1:-1` is a pulse
with 30% duty.

**formulamode** chooses whether the formula is worked out once per cycle or
every sample. The three macro knobs **a**, **b** and **c** can be used by name
in the formula, so you can give it controls. If a formula has an error, the
panel tells you.

## Tips

- Use a macro in the formula and automate it. `sin(x*2*pi*a)` with **a** on a
  lane sweeps the tone.
- The tracker tables make a big difference. A plain patch with a good volume
  table often beats a clever formula without one.
