# Formula
> Type a formula and it shapes the track, a sample at a time.

Formula uses the same small language as the Formulate machine:
whole numbers and the operators of C (`+ - * / % & | ^ << >>`, comparisons,
`? :`) and `sin`, `abs`, `min` and `max`. The track comes in as `x`, from 0
to 255 with silence at 128. Whatever the formula gives, taken as 0 to 255,
goes out. So `x` on its own is the track cut to eight bits, and everything
else bends it from there.

Tap **edit…** above the knobs to write one, or pick an example to start
from. The formula is only applied when you press OK, and if it can't be
read the reason shows in red. With no formula the track passes through
untouched.

## What a formula can read

- **x** - the track, 0 to 255.
- **t** - a counter that goes up at **rate**.
- **a**, **b**, **c** - the three knobs, 0 to 255.
- **r** - a fresh random number, 0 to 255, every sample.

## The controls

- **a**, **b**, **c** - read by the formula, or ignored if it doesn't use them.
- **rate** *(extra)* - how fast `t` counts, 1 to 48 kHz. At 8 kHz most
  formulas written for this kind of thing sound as they were meant to.
- **drive** - pushes the track harder into the formula, up to 24 dB.
- **smooth** *(extra)* - rounds off the steps the formula leaves. At the
  top it's off.
- **mix** - the dry track against the formula's.
- **gain** - the level out.

## Tips

- `x & (255 << (a >> 5))` throws bits away as **a** goes up.
- `t >> 11 & 1 ? x : 128` chops the track on and off with the counter.
- The output is always within full scale, but it can be harsh: start with
  **mix** low or **smooth** down.
