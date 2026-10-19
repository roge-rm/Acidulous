# Formulate

> An 8-bit chip synth, and a waveform you can type in as a formula.

Formulate is a chip synth with pulse, triangle and noise, like the old game
consoles. It can also play a waveform you write as a formula.

## The chip

- **wave** - pulse, triangle, saw, noise or off.
- **duty** - the pulse width. **pwmrate** and **pwmdepth** sweep it.
- **sub** - adds an octave below.
- **noiseshort** - switches the noise between a hiss and a metallic buzz.
- **bits**, **crush** and **smooth** - bit depth, sample rate and how much the
  steps are smoothed off.

## Tracker tables

There are three, **arp**, **duty** and **vol**, typed in as text and stepped
through per tick like a tracker instrument. An arp table of `0 4 7` played fast
makes a chord from one voice.

## The formula

**formula** is a whole-number expression worked out every sample for each
voice, written like C. It can read **t**, a counter that runs faster for higher
notes when **t follows** is on keyed, **x**, the chip's own sample from 0 to
255 with 128 as the centre, the note **n**, its frequency **f**, the velocity
**v**, the table step **s** and **a**, **b** and **c**. `rnd()` gives a random
number and `sin()` a sine from -127 to 127 every 256 steps of **t**. The
result is kept to 0 to 255, so a formula that goes below 0 wraps round.
`sin(t) + 128` is a sine, `t & 255` is a saw and `x & (a | 15)` takes bits
out of the chip's own wave. With **rate** at 0.293 a 256-step formula plays
the note you press.

**against x** sets how the formula meets the chip's sample: off, replace,
ring (the two multiplied), gate (the chip plays while the formula is over
half) or xor. **amount** blends from the chip alone to the result. If a
formula has an error, the panel tells you.

## Filter envelope

The **shape** section has an envelope on the filter. **amount** moves the
cutoff up to 6 octaves at the top of the envelope, up or down, and **A**, **D**,
**S** and **R** shape it. At 0 the filter stays where **cutoff** puts it. Start
with a low cutoff, an amount of 3 or 4 and a short decay for a plucked bass.

## Macros

The **chip** section starts with eight macro knobs in a **play** card. A patch
can name them (Tone, Crunch, etc.) and the names show on the knobs, on
automation lanes and in the controller mapping list. They sit at zero when a
patch loads, which is the patch as it was made. They're separate from **a**,
**b** and **c**, though a macro can turn those too.

The **macros** section sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch goes 24 semitones at 1. Tap a macro's name to rename it.

## Tips

- Use **a** in the formula and automate it. `x & a` with **a** on a lane
  sweeps the tone.
- The tracker tables make a big difference. A plain patch with a good volume
  table often beats a clever formula without one.
