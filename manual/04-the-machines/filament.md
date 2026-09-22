# Filament

> Modelled strings: plucked, picked, struck, bowed or blown.

Filament is a physical model of a string. It doesn't play a recording; it
simulates a vibrating string, so it reacts the way a real one does.

## The exciter

**exciter** is what sets the string going: pluck, pick, hammer, bow, breath, or
**the live input**, so you can play a string with a microphone.

- **position** - where on the string it's excited. This changes the tone more
  than anything else: near the end is thin and nasal, near the middle is round.
- **hardness** - how sharp the hammer is.
- **grit** and **length** - how noisy the excitation is and how long it lasts.

## The string

- **couple**, **detune** and **spread** - two strings per note, slightly apart,
  like a twelve-string or a piano's middle register.
- **damper** - a felt resting on the string at a position you choose. Good for
  muted guitar sounds.

## The body

Four resonances stand in for the instrument's body. **size** moves them,
**bodydamp** widens them, and **bodymix** sets how much you hear. There are also
**sympathetic strings** that ring along with what you play.

## Modulation

Two envelopes and two LFOs that can sync to the tempo, into eight matrix rows
(source, destination, depth).

The envelopes do nothing until you route them. One of the sources is **level**,
how loudly the string is ringing right now, so you can make the string react to
itself.

Destinations include **damping**, **tone**, **position**, **pressure**,
**damper**, **tension** and **detune**. **brightness** changes the exciter
(grit, or a hammer's hardness), and **rattle** and **volume** are per note.
**body**, **drive** and **sympathy** apply to the whole instrument and follow the
most recent note.

## Tips

- Change **position** first, before touching the filter or body.
- A slow LFO on **position** makes a static pluck come alive.
- If a note rings too long, shorten the decay rather than turning things down.
