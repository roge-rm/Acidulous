# Settings

## display

- **theme** - dark, light, high contrast or follow the phone. High contrast is
  white on black, with brighter colours and outlines round every control.
- **size** - makes everything bigger, in four steps.
<!-- desktop: - **screen scale** - how big the whole window is drawn. **system** takes the computer's own setting. -->
- **while playing** - whether the screen can turn off while playing.
- **diagnostics** - shows or hides the numbers for tracking down problems: the
  line under the transport, the readings on the audio page, the MIDI send
  counts and the note count in the editor's title.
- **keyboard** - **keys…** opens the list of shortcuts, where you can change
  them and choose how letters play notes. See [A keyboard](11-keyboard.md).

## audio

<!-- desktop: - **output** - which output to play through, or the system default. On Windows an interface's own driver is listed too, marked **low latency**. It's the quickest way to the interface, and while it plays the recorder's inputs are that interface's inputs, two at a time. -->
- **buffer** - tight, balanced or safe. Tight has the lowest latency but may
  crackle on a slower phone, and safe gives the phone more time. If the sound
  still breaks up, the buffer grows by itself, then comes back down to the
  smallest size the device can hold, and remembers it for next time. With
  **diagnostics** on, a line under it shows the buffer size, the latency and how
  many dropouts there have been.
- **worst block** (with **diagnostics** on) - the longest any block of audio
  took to make, against the time it had, and where that time went. The load
  meter is an average and can miss short spikes, so a phone can show a low load
  and still click. **reset** clears it.

  Blocks where the system briefly paused the audio thread aren't counted, and
  **% interrupted** shows how often that happened. If it's low and the worst
  block is high, the song is asking too much, so freeze tracks or use **lean**.
  If it's high, the phone is busy with other things and closing other apps will
  help more.
- **worst track** (with **diagnostics** on) - what each track costs, most
  expensive first, so you know what to freeze. A **❄** means the track was
  frozen when it cost that much, which should be close to nothing.

  It's each track's worst block in a hundred, so one unlucky moment doesn't set
  it. Give it a few seconds of playing before trusting it.
- **voices** - how many notes a track can hold at once. The oldest note is
  dropped first.
- **cores** - how many cores the tracks are made on at once. **auto** uses all
  of the phone's fast cores but one, which it leaves for the screen. **1** makes
  every track on the same core. A light song stays on one core either way.
- **quality** - what to give up when the phone can't keep up. **lean**:
  - runs the **amp** and **distortion** without oversampling (about half the
    amp's cost)
  - halves the **reverb**'s size (about half its cost)
  - halves the partials in **Resonance** patches that use more than twelve
  - halves **Trinity**'s unison stacks (never below two), and lets at most six
    released notes ring at once, fading older tails out quickly. Held notes
    aren't affected
  - halves the number of grains in **Pollen**

  **auto** switches to lean by itself when the phone is struggling. Notes you're
  holding keep the quality they started with, and only tails you've already let
  go of can be cut short.

  Exports and freezing always use full quality.
- **scheduler hint** - whether the phone accepted the app's request to treat the
  audio as time-critical. Some phones refuse, and there's nothing to do about it
  here.
- **audio thread** - on a phone with fast and slow cores, how many fast ones the
  sound is made on.
- **tracks** (with **diagnostics** on) - how many cores the tracks are on, and
  the longest the sound waited for another core to finish a track. It's
  normal for that to be as long as your heaviest track takes. Much longer
  means something else is busy on those cores; try fewer **cores**.

## record

- **depth** - the bit depth for recordings and exports.
- **count-in** - bars of clicks before recording starts.
- **quantise** - moves what you play onto the clip's grid. **off** leaves notes
  exactly where you played them, and **amount** moves them only part of the
  way.
- **take** - **add** puts what you play in with the notes already there.
  **replace** takes out the notes the playhead passes from your first note on,
  so what's left is what you played. Either way the take is one step of undo.
- **start** - **on play** records from when you press play. **first note** waits:
  arm, then play a note and the song starts with it, with no count-in.
- **passes** - **loop** keeps recording round and round until you stop it.
  **once** stops recording after one pass of the clip and leaves it playing.

## songs

What a new song starts with: tempo, time signature, the first track's machine,
and whether it starts with a scale set.

## TalkBack

TalkBack is turned on in the phone's own settings. With it on, every control
says what it is and what it's set to. On a knob or a fader, swipe up or down to
change it. Anything you'd hold to open, like a clip's settings, is in TalkBack's
actions menu.

The piano roll says how many notes a clip has and the lowest and highest, but
not each note. To add notes, record them from the keys.

With TalkBack on, the song grid shows as many scenes as fit and doesn't scroll
sideways. **Previous scenes** and **Next scenes** above it move a page at a
time. When the last page is full, add a scene from a scene's own menu.
