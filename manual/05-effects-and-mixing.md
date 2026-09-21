# Effects and mixing
> Two inserts a track, two sends, a master.

## Inserts

Each track has two insert slots. **fx** in the editor's bottom bar swaps the
panel under the grid for them.

Fifteen effects: delay, reverb, equaliser, distortion, compressor, filter,
bitcrusher, phaser, flanger, chorus, tremolo, width, pitch shifter, harmonizer
and **amp**. Each has the controls you expect **and one more that you do not** -
the extra is marked in the accent colour, so the familiar set stays
recognisable and the addition is never a surprise.

## The amp

**Amp** is a guitar amplifier, and it is a chain rather than a distortion: a
preamp that clips lopsidedly, a tone stack whose three controls fight each
other, a power stage whose supply sags when you dig in, and a speaker in a box.
The order is the point - the tone stack sits *between* the two nonlinear stages,
so it shapes what the second one distorts.

- **drive** and **bias** - the preamp. Bias is the tube sense: at nothing the
  clipping is symmetrical, and turning it up makes the two halves of the wave
  behave differently, which is most of what "old and woolly" means.
- **bass**, **mid**, **treble** and **stack**. They interact the way a real
  passive stack does: **bass and treble up scoops the mid**, and turning either
  of them back fills it in again. `stack` - us, uk or modern - changes how hard
  they fight, and also what reaches the first stage and how tightly the two
  stages are coupled, which is far more of the difference between two amps than
  their tone controls are.
- **presence** and **master** - the power stage. Presence sits inside its
  feedback loop rather than after it, so it makes the output stage work harder
  rather than just adding treble.
- **sag** - the supply drooping under load and taking a fifth of a second to
  come back. It is what makes an amp feel alive under the hands, and it is the
  control most simulations bury.
- **cab** with **size**, **cone**, **mic**, **edge** and **room**. The cabinet
  is modelled rather than sampled, and **that is what makes `size` and `cone`
  continuous**: you can sit between a practice combo, a four-by-twelve and a
  bass eight-by-ten, between cabinets that do not exist. `mic` walks off axis,
  `edge` moves from the centre of the cone to its rim, and `room` steps back
  from it. Switch `cab` off if you have your own.

To play a guitar through it, see [Bias](04-the-machines/bias.md): a Bias track
with its **monitor** up puts what is coming in through the track's inserts, so
the amp is in front of you while you play and the recording stays dry.

A slot can be bypassed without being emptied, which is the difference between
comparing and deleting.

## The mixer

The mixer pill opens the strips: one per track and one master.

- **Fader and meter** per track, with mute and solo.
- **Two sends**, to two effects shared by the whole song. Sends are how several
  tracks sit in one room without each paying for its own.
- **Pan**, and a MIDI row for what this track sends out.
- **The master** carries the limiter, and the meter that tells you whether you
  are asking it for too much.

## The two sends

They start as a reverb and a delay, which is what a send is usually for, but
either can hold **any of the fourteen effects**. The two chips in the master
strip say what is on them: tap one to switch it off and on, hold it to choose
the effect and set it up. Every channel's two send sliders take their names from
the same place, so a strip says what it is sending to.

A send is fully wet, always. What comes back is the effect and nothing else,
because the dry sound is already in the mix on its own channel - so there is no
wet/dry control in a send, and that is the one thing its editor does not offer
that an insert's does.

Some things are worth more on a send than in a track's own slot: a pitch shifter
fed a little from several tracks is a chorus of octaves nobody is playing, and a
bitcrusher on a send is a second, ruined copy of the mix sitting behind it.

## Tempo, signature and the click

The tempo is in the song header. Tapping it opens the signature and the
metronome: which sound the click makes, what it counts, how loud it is, whether
it plays always or only while recording, and how many bars to count in before
recording starts.
