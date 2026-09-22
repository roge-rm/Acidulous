# Effects and mixing
> Two inserts a track, two sends, groups, and the master.

## Inserts

Each track has two insert effect slots. **fx** in the editor's bottom bar shows
them in place of the machine panel.

There are sixteen effects. Each one has the usual controls plus one extra,
shown in the accent colour. Every effect ends with **gain**, an output level
trim, because turning up the wet/dry mix can change the level.

**Each effect has its own page below.**

## Time

- [**Delay**](05-effects-and-mixing/delay.md) - echoes on a note value, and it
  can duck while you play.
- [**Reverb**](05-effects-and-mixing/reverb.md) - a room that can also freeze,
  gate, shimmer, or crush itself down to 8 bits.

## Tone

- [**Eq**](05-effects-and-mixing/eq.md) - three bands and a tilt.
- [**Filter**](05-effects-and-mixing/filter.md) - low, band or high pass, moved
  by an LFO, the signal's level, or another track.
- [**Width**](05-effects-and-mixing/width.md) - wider, narrower, or mono below a
  frequency.

## Drive

- [**Distortion**](05-effects-and-mixing/distortion.md) - four kinds of clipping
  and a bias control.
- [**Amp**](05-effects-and-mixing/amp.md) - a guitar amp with a cabinet you can
  resize.
- [**Bitcrusher**](05-effects-and-mixing/bitcrusher.md) - fewer bits and a lower
  sample rate, with an unsteady clock if you want it.

## Level

- [**Compressor**](05-effects-and-mixing/compressor.md) - the usual controls, a
  sidechain from any track, and a tempo-synced pump.
- [**Gate**](05-effects-and-mixing/gate.md) - a noise gate that can be opened by
  another track.

## Movement

- [**Chorus**](05-effects-and-mixing/chorus.md) - two to four detuned voices
  that drift.
- [**Flanger**](05-effects-and-mixing/flanger.md) - a short sweeping delay, with
  negative feedback for the hollow sound.
- [**Phaser**](05-effects-and-mixing/phaser.md) - two to eight stages.
- [**Tremolo**](05-effects-and-mixing/tremolo.md) - volume on an LFO, or
  auto-pan.

## Pitch

- [**Shifter**](05-effects-and-mixing/shifter.md) - frequency shifting, for
  metallic and detuned sounds.
- [**Harmonizer**](05-effects-and-mixing/harmonizer.md) - adds two voices at
  scale steps so the harmony stays in key.

## Input effects

There are two more effect slots on the **input**. They're at the top of the
recording window's **record** page (under **printed into the recording**), and
on Bias's panel under **printed in**.

- An effect **on the input** runs before anything else hears the audio, so it's
  **recorded into the take**.
- An effect **on a track** runs on playback, so you can change it later and the
  recording stays untouched.

So put an amp on the input if you've decided on the sound, or on the track if
you want to keep your options open. Input effects keep their **mix** control,
unlike sends.

## The mixer

The mixer button opens a strip for each track, plus the master.

- **Fader and meter** for each track, with mute and solo.
- **Two send amounts** per track, going to two effects shared by the whole song.
- **Pan**, and a MIDI row for what the track sends out.
- A **∿ next to a track's name** means something on that channel is automated,
  which is why the control won't stay where you put it. Tap it to see which
  lanes, and to clear them from every clip on the track. Notes aren't touched.

The master strip has the master fader, the limiter's **limit drive**, and the
loudness readout. Under that is a grid of buttons: the two sends on top, the two
master inserts (**fx1**, **fx2**) in the middle, and the limiter (**lim**) and
the click (**♩**) at the bottom. Tap one to turn it on or off, and hold a send
or insert to choose its effect and set it up.

## Master inserts

**fx1** and **fx2** process the whole mix, including the sends, before the
master fader and the limiter. Use them for things that apply to the whole song:
a gentle EQ, a bit of glue compression, some tape-style drive, or narrowing the
low end.

The difference from a send: a send is added alongside the mix, and each track
chooses how much to send. A master insert processes everything equally. So
reverb and delay usually go on sends, and EQ and compression for the whole song
go on the master inserts.

## Groups

A group is a track whose machine is **Bus**. Add one from the machine picker,
under *beyond*. It makes no sound of its own. Once the song has a group, each
strip in the mixer gets an extra row at the bottom: tap it to route that track
to the master or to a group.

Everything routed into a group goes through the group's two inserts and its
fader before the master. For example, put one compressor on all the drums, or
turn a whole section down with one fader.

- A track's **sends** still go straight to the send effects, not through the
  group.
- **Solo** a group to hear all of it. Solo a track inside a group to hear just
  that track, still through the group's effects.
- A group can't go into another group, and it can't be frozen.
- When you export **stems**, a group is one stem with its tracks in it, and those
  tracks don't get their own stems.

## Sidechain

The compressor, gate and filter can react to another track instead of their own
input. Set **sidechain** in the effect to the track you want. The classic use is
a compressor on the bass keyed to the kick, so the bass ducks on every kick.

The sidechain hears the other track before its fader and mute, so turning the
kick down doesn't weaken the ducking, and a muted kick still works as a trigger.

## The two sends

They start as a reverb and a delay, but either can hold any effect. The send
buttons on the master strip show what's on them. Tap to turn one on or off, and
hold to choose and set up the effect. The send sliders on each track are named
after what's on the sends.

A send is always fully wet: only the effect comes back, because the dry sound is
already in the mix. That's why a send's editor has no mix control.

Some effects are fun on a send: a pitch shifter fed a little from several
tracks, or a bitcrusher for a trashed copy of the mix sitting behind it.

## Tempo and song settings

Tap the tempo in the song header to open the song's settings.

- **beats a minute** - type a number or use the buttons either side. **tap**
  sets it from four taps.
- **bar** - the time signature, from 4/4 to 7/8. A scene can have its own.
- **swing** - how late the offbeats are. **triplet** is the classic shuffle.
  **swing on** chooses whether it swings sixteenths or eighths.
- **key** - the song's key and scale. The piano roll shades notes outside it,
  and a new track gets a matching scale. It doesn't change any notes, and
  doesn't override a track with its own scale set.

The **click** and **link** pages are in the same window.

## How swing works

Swing moves the offbeats later without changing the order of notes, so a
loosely played part swings along with everything else.

A **track can have its own swing**, e.g. swung drums over a straight bass.

**What you record is stored straight.** When you play against a swung song, the
swing is taken back out before the notes are written. The roll shows where you
meant the notes, playback puts them where you played them, and if you turn the
swing down later the part is straight rather than lopsided.

The **click is never swung**.
