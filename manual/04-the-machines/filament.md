# Filament

> Modelled strings: plucked, picked, struck, bowed or blown.

Filament doesn't play a recording. It simulates a vibrating string, so it reacts
the way a real one does.

## The exciter

**exciter** is what sets the string going: pluck, pick, hammer, bow, breath or
**the live input**, so you can play a string with a microphone.

- **position** - where on the string it's excited. This changes the tone more
  than anything: near the end is thin and nasal, near the middle is round.
- **hardness** - how sharp the hammer is.
- **grit** and **length** - how noisy the excitation is and how long it lasts.

## The string

- **couple**, **detune** and **spread** - two strings per note, slightly apart,
  like a twelve-string or a piano's middle register.
- **damper** - a felt resting on the string at a position you choose, good for
  muted guitar sounds.

## The body

Four resonances stand in for the instrument's body. **size** moves them,
**bodydamp** widens them and **bodymix** sets how much you hear. There are also
**sympathetic strings** that ring along with what you play.

## Modulation

Two envelopes and two LFOs that can sync to the tempo, into eight matrix rows
(source, destination, depth).

The envelopes do nothing until you route them. One of the sources is **level**,
how loudly the string is ringing right now, so the string can react to itself.

Destinations include **sustain**, **tone**, **position**, **pressure**,
**damper**, **tension** and **detune**. **bright** changes the exciter (grit, or
a hammer's hardness), and **rattle** and **volume** are per note. **body**,
**drive** and **sympathy** apply to the whole instrument and follow the latest
note.

## Vibrato

The **vibrato** group on the **mod** page gives each note its own vibrato.
**depth** is in cents and is off at zero, **rate** is how fast it goes and
**delay** is how long it takes to fade in after the note starts, so a held note
can start straight and bloom.

## Macros

The **play** page has eight macro knobs. A patch can name them (Bow Harder,
Brighter, etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves the whole instrument, 24 semitones at 1. The exciter,
stiffness stages, sympathy switch and tuning and the body switch can't be
reached. A change to length or velocity is heard from the next note. Tap a
macro's name to rename it.

## Tips

- Change **position** first, before the filter or body.
- A slow LFO on **position** brings a static pluck to life.
- If a note rings too long, shorten the decay instead of turning things down.
