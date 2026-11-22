# Settings
<!-- desktop: On a Mac, **Settings…** and **About…** are in the Acidulous menu too, and **Cmd+,** opens Settings. The **Edit** menu has undo, redo and the note copy and paste, and **Window** has minimise, zoom and full screen. -->

## display

- **theme** - dark, light, high contrast or follow the phone. High contrast is
  white on black, with brighter colours and outlines round every control.
- **language** - the app's language, chosen from a list: the phone's own,
  English, or French as written in Canada or in France (the same words; France
  puts a narrow space before ; ! and ?). Not in a browser, which uses its own.
- **size** - makes everything bigger, in four steps.
- **second screen size** - the same for the second screen. Only there while the device has a second screen.
<!-- desktop: - **screen scale** - how big the whole window is drawn. **system** takes the computer's own setting. -->
- **stay awake** - on keeps the screen on while the song plays.
- **second screen** - on by default. Only there while the device has a second screen. See below.
- **updates** - on looks for a new version once a day, by asking GitHub for the
  latest release, and shows a line at the top of the song screen when there is
  one. GitHub sees the app's version and your internet address, as with any web
  page. Not there when F-Droid installed the app, since F-Droid tells you
  itself, or in the browser.
- **keyboard** - **keys…** opens the list of shortcuts, where you can change
  them and choose how letters play notes. See [A keyboard](11-keyboard.md).

## audio

<!-- desktop: - **output** - which output to play through, or the system default. On Windows an interface's own driver is listed too, marked **low latency**. It's the quickest way to the interface, and while it plays the recorder's inputs are that interface's inputs, two at a time. -->
- **buffer** - tight, balanced or safe. Tight has the lowest latency but may
  crackle on a slower phone, and safe gives the phone more time. If the sound
  still breaks up, the buffer grows by itself, then comes back down to the
  smallest size the device can hold, and remembers it for next time.
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
and whether it starts with a scale set. Pick **None** for the machine and a new
song starts with no tracks at all.

## second screen

On a device with a second built-in screen, like the Retroid Pocket Duo, the app opens on both. The top screen is the app as usual, and the bottom one follows it. Turn **second screen** off in Settings to use the top one alone.

With an editor or a track on top, the bottom screen has tabs along its top edge: **keys** (**pads** on a drum machine), which is the playing strip with the octave, the chord, scale and arp chips and the wheels, and **panel**, which is that machine's panel. With the song screen on top, the tabs are **mix** and **fx**. The bottom screen remembers its tab for each kind of top screen. With a track on top, its patch bar moves to the top of the bottom screen: the patch name, save and browse on the left, and the arrows and the mark that folds the top screen's panel on the right, by the tabs.

On **keys** the **piano | grid** chip, set for each track, picks between a piano, two rows an octave apart to start with, and a grid of pads. The grid's rows are a fourth apart, with the root at the bottom left. While a scale is running, the **in key | all** chip, the same for every track, picks between only the scale's notes and every note with the others dimmed. On the isomorphic grid the pads stay put, and **in key** leaves the pads out of key blank.

Hold **grid** to open the grid window. **columns** and **rows** set how many pads there are, **layout** how a scale in key is laid out. **isomorphic** is a semitone a column, and **row** is how many semitones a row goes up. **scale** has only the scale's notes, and **row** is how many scale notes a row goes up. **piano rows** puts the scale's notes in the lower row and the notes between them above. **reset** puts it back to 6 by 4, isomorphic.

Hold **piano** to open the piano window. Under the editor, hold the octave between ◀ and ▶ instead. **rows** is how many keyboards are stacked, up to four (the editor always has one). **length** is how many octaves each one shows, and **auto** fits as many as are comfortable. **labels** names the C keys, every key or none. Under **rows** each row has a knob for how many octaves it sits above or below the octave you've set, **row 1** at the bottom. **reset** puts it back to two rows an octave apart, auto length and C named. Hold ◀ or ▶ and tap a row to move just that row an octave; a tap on ◀ or ▶ on its own moves all the rows together.

Whatever the bottom screen shows leaves the top one: with **keys** (or **pads**) below, the top editor has no playing strip, so the roll gets that height. With **panel** below, the top keeps the strip and drops the panel, so the roll gets the room, the width or the height. The panel below fills the screen, its cards sharing the room evenly with the knobs sized to fit.

On **mix**, every strip has pan and both sends as knobs, and the master shows its two sends. When the tracks don't fit across, the mixer goes to normal size. On **fx**, the chips **sends**, **master** and the track's name pick what is shown: the two sends, the master's inserts or that track's inserts. Tap the track's chip again for the next track. On a small bottom screen they show one at a time, and the numbers at the right pick which.

The keys and a controller follow the screen you touched last, and play the same track on either. To move them without touching, press **L3** (the left stick) or **Ctrl+Tab** (**Alt+O**). While you type in a box on the bottom screen, the keys stay there until you're done.

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
