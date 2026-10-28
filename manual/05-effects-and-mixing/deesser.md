# Deesser
> Turns down a band when it gets too loud: an s, a cymbal.

Listens to a band of frequencies and turns it down when it goes over the
threshold. Made for the hiss of an s on a voice, and just as good on harsh
cymbals.

## The controls

- **freq** - the middle of the band, 2 to 12 kHz.
- **threshold** - where the band starts to come down.
- **range** - the most it comes down.
- **mode** - **split** turns down only the band. **wide** turns down the whole
  sound, which is gentler on a voice with a lot of air.
- **listen** - plays only the band, so you can find where the s sits.
- **relative** *(extra)* - measures the band against the rest of the sound
  instead of against full level, so a quiet line and a loud one are treated
  the same. The threshold is then how far under the rest the band can come
  up: an s is nearly all band, a vowel has it 20 dB or more down. At -36 it
  is 10 dB, each 1 dB lower allows 1 dB less.
- **gain** - the output level.

## Tips

- Turn on **listen**, move **freq** until the s is loudest, then turn
  **listen** off.
- With **relative** on, the singer can move off the microphone and it still
  catches the same s.
