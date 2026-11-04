# Bias

> A four-track: recordings arranged along the song, four lanes at a time.

Bias is the audio track. Instead of notes, each of its cells plays recordings,
four lanes of them at once. Mute two takes to pick the third, or unmute a second
lane to double a vocal.

## How it fits the song

A Bias cell is a normal clip, so there's no separate timeline. A take recorded
across four scenes becomes four cells that all point at the same file, each
starting at a different place. Nothing is copied, and it works the same in the
arranger and the clip launcher.

A cell lasts its bars times the scene's repeats and plays straight through, so
a scene played twice plays one continuous take.

## Recording

Tap the red dot next to a lane, arm record on the transport and press play. The
song plays while you record, and the other lanes keep playing.

When you stop, the take is cut at the scene lines, one cell per scene, with new
cells in scenes where the track was empty. The whole recording also stays in
the sound library, so you can undo the split and place it by hand.

One lane records at a time. If the recorder fell behind and left a gap in the
take, it isn't split and is kept whole instead.

## Loop recording and takes

Record while a scene loops and each pass becomes a take on that lane, stacked
on the ones before. You hear the newest. If you stop part way through a pass,
the new take plays only where it has audio and the one before plays the rest.
Each cell keeps its own stack.

Recording onto a lane that already holds a take adds to its stack too, as long
as it was recorded at the same tempo against the same scene length. Otherwise
it replaces it.

Each lane's card on the page shows which take you hear, like **take 2/3**. Tap
it to hear the next one. Undo takes back a pass or a pick.

## Choosing by phrase

Tap a lane that has more than one take and its takes show as thin rows under
it, take 1 at the top, lined up in time with the lane. The lane itself shows
what you hear. Faint lines cut the lane into phrases where most takes go
quiet, the same phrases for every take.

- tap a **phrase** on the lane to pick it, then tap a **take's row** inside it
  to hear that take there. Tapping a row anywhere picks the phrase under your
  finger
- drag along a **take's row** to hear that take over exactly the stretch you
  drag, for a word or a breath the phrases don't split
- tap a take's **number** for **use everywhere**, which plays it over the whole
  lane, or **delete take**, which takes it off the stack. Where it was heard,
  the take with the most audio there plays instead
- tap the picked phrase again to put the rows away

The joins between takes crossfade by themselves at a nearby quiet point. Each
choice is one undo step. A deleted take's recording stays in the sound
library, as every recording does, so you can delete it there.

## Listening through

With a phrase picked, tap **▶** left of the ruler. The phrase loops with a
second before it, each pass with the next take that has audio there, and the
take playing is lit on its row. Tap any row to keep that take for the phrase,
which stops listening. Tap **■** to stop without changing anything.

Only this lane plays while you listen, at its level, even if it's muted. The
song carries on as it was, and the lane joins it again when you stop. Picking
another phrase, editing the lane or leaving the cell also stops listening.

With a keyboard, move to a lane with takes and press **Enter**. Left and right
pick the phrase, up and down the take, and Enter keeps it. While listening,
Enter keeps the take playing. **Esc** stops.

## Suggest, line up and blend

Under the take rows are the comp tools.

- **suggest** picks the best take in every phrase, as one undo step. Each take
  is scored on how steady its pitch is, how close it hits the beat and the
  other takes, clipping and background noise. The scores show faintly at the
  end of each phrase on the rows, the best one lit. A take that's much quieter
  than the others in a phrase scores 0
- **line up** moves this lane's phrases onto another lane's timing, each phrase
  on its own and by at most 50 ms, for a double that's a little early or late.
  With more than one other lane it asks which. It then reads **lined up**: tap
  it to put the lane back where it was. A lane with one take shows this too,
  when another lane has audio
- **blend** plays every take on the lane together, each at about the same
  level, and the sum at the level of one take. It's a quick double or a small
  choir. Tap it again to go back to your choices, which it keeps

## The editor

Opening a cell shows the four lanes:

- drag a lane's **body** to move where it starts
- drag either **end, top half** to trim it
- drag either **end, bottom half** to fade it in or out. Fading one lane out
  while another fades in makes a crossfade
- tap the number on the left to **mute** a lane, and the dot below it to arm it

On a take longer than its cell, both halves of the right edge trim, so you have
an end to fade.

## Levels, mutes and automation

Each lane's level and mute are normal machine parameters, so you can automate
them, map them to a controller and record them.

## Tempo

**tempo › takes** chooses whether a take plays at the speed it was recorded at
or follows the song's tempo. When it follows, it's time-stretched without
changing pitch. When it doesn't, it starts on the bar and plays at its own
speed, and the cell turns amber.

A loop added from the library with **audio…** gets its tempo worked out the way
Dice does it, from its length and where its hits fall. It loops round to fill
the cell, and if its tempo isn't the scene's, **tempo › takes** switches to
follow so it plays at the song's tempo.

## Playing a guitar through it

**monitor**, under **tempo**, sends the input to this track's output before its
effects, so you can hear an amp in the first insert slot while you play. The
recording stays dry, so you can change the amp later.

Monitor is off by default. Use headphones or an interface, since on the phone
speaker it will feed back.

**printed in**, next to it, is for effects you want recorded into the take.
They run before the recorder, so they're stuck in there. These are the same two
input slots the recording window shows.

## Flattening

**comp** mixes the four lanes (with their levels, mutes, fades and the takes
they play) into one file in lane 1. The patch's tape colour isn't baked in, and the original recordings
stay in the library.

## Patches

Bias's patches are recording media (cassette, reel, telephone, wax cylinder,
etc.). They colour the sound on playback but never change the recordings, so
try them freely. **Init** plays the file back untouched.

## Tips

- Record first and decide later. The patch, tempo, fades and even the split can
  all change afterwards without touching the file.
- Takes can be long. Anything over two minutes is converted once in the
  background and streamed from storage, so a full-length vocal costs about the
  same as a short one.
