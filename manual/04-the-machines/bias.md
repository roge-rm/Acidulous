# Bias

> A four-track: recordings arranged along the song, four lanes at a time.

Bias is the audio track. Instead of notes, each of its cells plays recordings:
four lanes of them at once. You can mute two takes to pick the third, or unmute
a second lane to double a vocal.

## How it fits the song

A Bias cell is a normal clip, so there's no separate timeline. A take recorded
across four scenes becomes four cells that all point at the same file, each
starting at a different place. Nothing is copied, and it works the same in the
arranger and the clip launcher.

A cell lasts its bars times the scene's repeat count and plays straight
through, so a scene played twice plays one continuous take.

## Recording

Tap the red dot next to a lane, arm record on the transport, and press play. The
song plays while you record, and the other lanes keep playing.

When you stop, the take is cut at the scene lines: one cell per scene, and
cells are created in scenes where the track was empty. The whole recording also
stays in the sound library, so you can undo the split and place it by hand.

One lane records at a time. If the recorder fell behind and there's a gap in the
take, it isn't split (everything after the gap would be misplaced), so it's kept
whole instead.

## The editor

Opening a cell shows the four lanes:

- drag a lane's **body** to move where it starts;
- drag either **end, top half**, to trim it;
- drag either **end, bottom half**, to fade it in or out. Fading one lane out
  while another fades in gives you a smooth crossfade;
- tap the number on the left to **mute** a lane, and the dot below it to arm it.

On a take longer than its cell, both halves of the right edge trim, so you have
an end to fade.

## Levels, mutes and automation

Each lane's level and mute are ordinary machine parameters, so you can automate
them, map them to a controller and record them.

## Tempo

**tempo › takes** chooses whether a take plays at the speed it was recorded at
or follows the song's tempo. When it follows, it's time-stretched without
changing pitch. When it doesn't, it starts on the bar and plays at its own
speed, and the cell turns amber.

A loop added from the library with **audio…** gets its tempo worked out the way
Dice does it, from its length and where its hits fall. It loops round to fill
the cell, and if the loop's tempo isn't the scene's, **tempo › takes** is
switched to follow so it plays at the song's tempo.

## Playing a guitar through it

**monitor**, under **tempo**, feeds the input into this track's output before its
effects, so you can hear an amp in the first insert slot while you play. The
recording itself stays dry, so you can change the amp later.

Monitor is off by default. Use headphones or an interface, because on the phone
speaker it will feed back.

**printed in**, next to it, is for effects you want recorded into the take. They
run before the recorder, so they're committed. These are the same two input
slots the recording window shows.

## Flattening

**comp** mixes the four lanes (with their levels, mutes and fades) into one file
in lane 1. The patch's tape colour isn't baked in. The original recordings stay
in the library.

## Patches

Bias's patches are recording media (cassette, reel, telephone, wax cylinder and
others). They colour the sound on playback but never change the recordings, so
you can try them freely. **Init** plays the file back untouched.

## Tips

- Record first and decide later. The patch, tempo, fades and even the split can
  all be changed afterwards without touching the file.
- Takes can be long. Anything over two minutes is converted once in the
  background and streamed from storage, so a full-length vocal costs about the
  same as a short one.
