# Effects and mixing
> Two inserts a track, two sends, a master.

## Inserts

Each track has two insert slots. **fx** in the editor's bottom bar swaps the
panel under the grid for them.

Sixteen effects: delay, reverb, equaliser, distortion, compressor, filter,
bitcrusher, phaser, flanger, chorus, tremolo, width, pitch shifter, harmonizer,
**amp** and **gate**. Each has the controls you expect **and one more that you do not** -
the extra is marked in the accent colour, so the familiar set stays
recognisable and the addition is never a surprise.

## The gate

**Gate** shuts below a level and opens above it. The four you expect are
**threshold**, **attack**, **hold** and **release**; **hyst** is how far *below*
the threshold the signal has to fall before it is willing to shut, which is what
stops a note sitting on the threshold from flapping it open and closed.

Two are not on a pedal:

- **key** filters the *detector*, not the sound. A gate in front of a loud amp
  is listening to a pickup that hears mains hum, a room and a hand as well as
  the string, and all of those are low. Slide `key` up and the gate opens for a
  pick rather than for a building, while the note it passes keeps its bottom
  end.
- **duck** is how far down "closed" is. All the way is a gate; twelve decibels
  is what drums want, where silence between hits is a hole and the room going
  quiet is a tightening.

There is no wet/dry `mix`, on purpose: half a gate is the noise at half level.

**Where to put it.** Before the amp it kills the hiss your pickups bring in;
after the amp it kills the hiss the amp makes, which is usually far more. On an
input slot in the record window it is printed into the take, so the take itself
is quiet.

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

## On the way in, and on the way out

There are two more effect slots that do not belong to a track: **the two on the
input**. They are at the top of the recording window's **record** page, under
**printed into the recording**, and on Bias's panel under **printed in**.

The difference between them and a track's inserts is the whole reason they
exist:

- an effect **on the input** runs before anything hears the audio - before the
  recorder, before the monitor, before any machine that reads the input - so
  **what it does is printed into the take**;
- an effect **on the track** runs on playback, so it can be changed, bypassed
  or swapped afterwards and the recording is untouched.

So: an amp you have decided on goes on the input and is committed to the file.
An amp you want to keep thinking about goes on the track. Both at once means
two amps, which is a legitimate thing to want and easy to do by accident.

Unlike a send, an input effect keeps its **mix**, because it is in series with
the signal rather than beside it.

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

## Tempo, and what else the song is

The tempo is in the song header, and tapping it opens everything the song is
counted in.

- **beats a minute** - a number you can type, with a step either side. Under it
  is **tap**: tap four times in time and it takes the average of the gaps.
- **bar** - the time signature, from 4/4 through 7/8. A scene can be given its
  own; this is the one the rest of the song counts in.
- **swing** - how late the offbeats sit. Straight is straight; **triplet** is
  the shuffle everybody means; the far end is further than any record. **swing
  on** chooses whether it bends pairs of sixteenths or pairs of eighths - a
  groovebox feel or a jazz one.
- **key** - what key the song is in, and which scale. It shades the notes that
  are not in it in the piano roll, and fits a new track with a matching scale.
  It moves nothing already written and overrides no track that has chosen its
  own scale: it is a statement about the song rather than an instruction to it.

The **click** and **link** pages are behind the same window.

### What swing does, and what it does not

Swing bends time rather than delaying notes: each pair of subdivisions is
mapped onto itself with its midpoint moved late. Two notes a tick apart stay a
tick apart and stay in order, so a part played in loosely swings with
everything else instead of scattering.

A **track can disagree** with the song - the drums shuffling while the bass
stays straight is most of what swing is for.

**What you play in is stored straight.** Playing against a swung song means
playing swung times, so those times are put back through the swing before they
are written. The roll shows where you meant the notes, the song plays them
where you played them, and turning the swing down afterwards leaves a straight
part rather than a limping one.

The **click stays straight**, because it is what you are playing against.
