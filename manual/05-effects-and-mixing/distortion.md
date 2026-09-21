# Distortion
> Four clipping characters, and a bias that makes the two halves of the wave behave differently.

Drive into a nonlinearity and a tone control after it. What separates one distortion from another is the *shape* of that nonlinearity, so this has four of them rather than one and a knob that pretends.

## The controls

- **drive** - how hard the signal is pushed in, 1 to 40. It scales against the level the signal reaches rather than against full scale, so a quiet part drives the same as a loud one.
- **tone** - a low-pass after the clipping. Distortion makes high harmonics; this decides how many of them you keep.
- **mix** - wet against dry. A little wet under a clean signal is parallel distortion, which thickens without dirtying.
- **mode** *(extra)* - the curve: soft, hard, fold and a diode shape. Soft rounds the peaks, hard squares them off, fold turns the signal back on itself so louder becomes stranger rather than just louder, and the diode one is asymmetric by nature.
- **bias** *(extra)* - offsets the signal before the curve, so the two halves of the wave clip differently. Symmetrical clipping makes odd harmonics and sounds like a fuzz; asymmetrical makes even ones too and sounds like a valve.

## Using it well

**Bias is what "warm" means.** A perfectly symmetrical distortion is a very clean kind of dirty. Turning bias up a little adds the even-order harmonics that make people say a sound is tubey, without adding any more drive.

**Fold is not more distortion, it is a different instrument.** Past a certain input the output starts coming back *down*, so a crescendo turns into a timbre change. It is the one mode where playing harder does not simply mean more.

**Its aliasing is part of it.** This effect is deliberately not oversampled the way the amp is - the ragged high end is half of why it sounds like a pedal, and it is in factory patches that would change if it were cleaned up.
