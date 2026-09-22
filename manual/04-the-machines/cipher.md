# Cipher

> A vocoder, where you can rearrange which bands drive which.

Cipher is a vocoder. It analyses one sound in frequency bands and puts that
shape onto another sound. You can also change which analysis band controls
which output band, which is where the unusual sounds come from.

## The two sides

The **modulator** is the sound being analysed, usually a voice from the live
input. The **carrier** is the sound being shaped. Cipher has its own carrier
built in (an oscillator with **detune**, **pw**, **sub**, **noise** and
**cardrive**), so you don't need a second track.

## The bands

- **bands** - how many. Fewer sounds robotic but clear; more is smoother but
  less clear.
- **low**, **high** and **slope** - the range the bands cover and how they're
  spread over it.
- **q** - how narrow each band is.

## The band map

Normally band 1 drives band 1. **remap** lets you change that: reverse the
order so bright sounds come out dark, spread it wider, **freeze** the current
shape while the carrier keeps playing, or **smear** neighbouring bands together.

**role**, **dry** and **wet** set which side is which and how much of each you
hear. **unvoiced** handles consonants so the words stay understandable.

**attack**, **release** and **smear** set how quickly each band follows.
Fast is clear but can chatter; slow is smooth but can slur.

## Modulation

Two envelopes and two LFOs that can sync to the tempo, into eight matrix rows.

Three sources come from the modulator itself: its **loudness**, **brightness**
and **pitch** (pitch needs **track** on). For example, loudness on **smear**
makes the words tighten up as they get louder.

The destinations are the map controls (**shift**, **stretch**, **remap**,
**freeze**, **smear**, **q** and the band edges) plus the carrier's pitch and
mix.

## Tips

- Turn **unvoiced** up until the words are clear, then back off until it stops
  hissing.
- Freeze on a vowel and play chords under it for a vocal pad.
- Use fewer bands than you'd think. Sixteen usually beats forty for words.
