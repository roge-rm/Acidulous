# Effects and mixing
> Two inserts a track, two sends, a master.

## Inserts

Each track has two insert slots. **fx** in the editor's bottom bar swaps the
panel under the grid for them.

Sixteen effects. Each has the controls you expect **and one more that you do
not** - the extra is marked in the accent colour, so the familiar set stays
recognisable and the addition is never a surprise. Every one ends with **gain**,
a plain output trim in decibels, because a wet/dry mix does not preserve level
and the knob that puts it back should always be in the same place.

**Each has a page of its own below** with what it does and how to get the best from it; this page is for finding the one you want.

## Time

- [**Delay**](05-effects-and-mixing/delay.md) - echoes on a note value, with a duck that gets out of the way
  while you are playing.
- [**Reverb**](05-effects-and-mixing/reverb.md) - a room, and four things a room cannot do: freeze it, gate it,
  send it up an octave, or make it out of eight-bit memory.

## Tone

- [**Eq**](05-effects-and-mixing/eq.md) - three bands, and a tilt that trades top for bottom on one knob.
- [**Filter**](05-effects-and-mixing/filter.md) - low, band or high pass, swept by an LFO on a note value or by
  the signal's own envelope.
- [**Width**](05-effects-and-mixing/width.md) - the stereo image: wider, narrower, mono below a frequency, or
  turned.

## Drive

- [**Distortion**](05-effects-and-mixing/distortion.md) - four clipping characters, and a bias that makes the two
  halves of the wave behave differently.
- [**Amp**](05-effects-and-mixing/amp.md) - a guitar amplifier as a chain, with a **modelled** cabinet you can
  resize continuously.
- [**Bitcrusher**](05-effects-and-mixing/bitcrusher.md) - fewer bits and a lower rate, with a jitter that makes the
  clock unsteady.

## Level

- [**Compressor**](05-effects-and-mixing/compressor.md) - the classic four, a sidechain from any track, and a pump
  in time with the transport.
- [**Gate**](05-effects-and-mixing/gate.md) - shut below a level, with a filter on its own detector so it opens
  for a pick and not for a room.

## Movement

- [**Chorus**](05-effects-and-mixing/chorus.md) - two to four detuned voices, and a drift that stops them agreeing
  about the tuning.
- [**Flanger**](05-effects-and-mixing/flanger.md) - one short sweeping delay, and inverted feedback for the hollow
  version.
- [**Phaser**](05-effects-and-mixing/phaser.md) - allpass notches sweeping, from two stages to eight.
- [**Tremolo**](05-effects-and-mixing/tremolo.md) - amplitude on an LFO, and the same lever turned into an auto-
  pan.

## Pitch

- [**Shifter**](05-effects-and-mixing/shifter.md) - frequency shifting, which moves everything by the same number
  of hertz rather than the same interval.
- [**Harmonizer**](05-effects-and-mixing/harmonizer.md) - two added voices at scale degrees, so the harmony stays in
  key.

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
- A **∿ beside a track's name** means something on that channel is being
  driven by movement you recorded, which is why that control will not stay
  where you put it. Tap the mark to see which, and to clear those lanes from
  every clip on the track - the notes are not touched.

## Groups

A group is a track whose machine is **Bus**. Add one from the machine picker,
under *beyond*. It plays nothing itself. Instead, any track can be routed into
it: once the song has a group, each strip in the mixer gets an extra row at the
bottom - tap it to step between the master and each group.

Everything routed into a group goes through the group's two inserts and its
fader before it reaches the master. Put one compressor on the drums instead of
one per drum, or pull a whole section down with one fader.

- A track's **sends** still go straight to the send buses, not through the
  group.
- **Solo** a group to hear all of it; solo a track inside one to hear just that
  track, through the group's inserts.
- A group can't be routed into another group, and can't be frozen - its
  members are still playing live.
- When exporting **stems**, a group is one stem with its members in it, and
  the members don't get stems of their own.

## Sidechain

The compressor, the gate and the filter can listen to another track instead of
their own input. Set **sidechain** in the effect to the track you want - the
classic use is a compressor on the bass keyed to the kick. The key is taken
before the other track's fader and mute, so turning the kick down doesn't
weaken the duck, and a muted kick still works as a trigger.

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

## What swing does, and what it does not

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
