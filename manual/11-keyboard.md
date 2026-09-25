# A keyboard
> Playing notes on letters, and working the whole app from keys.

Acidulous works with a phone's own keyboard, like the ones on square phones,
and with USB or Bluetooth keyboards. Everything you can press on screen can be
reached from the keys.

Press **Shift+/** (or **Alt+Q**) at any time to see the keys that work on the
screen you're on.

## Play mode

Letters either run shortcuts or play notes. **`** (or **Sym**) switches between
the two, and so does tapping the octave number in the editor's keyboard strip.
The number is lit while letters are notes.

With play mode on:

- **A S D F G H J K L** are white keys, and **W E T Y U O P** the black keys
  between them, as on a piano. A is C.
- **Z** and **X** move down and up an octave. In the editor they move the
  on-screen keyboard too.
- **C** and **V** make notes softer and harder.
- Shift plays a note an octave up.

Notes go to the track of the last clip you opened, the same as a MIDI
keyboard, so they record, and the track's chord, scale and arp apply. On a
drum machine the keys are its pads, in order.

Space still plays and stops, and anything with Ctrl or Alt still works as a
shortcut.

### Tracker layout

A full keyboard has room for two octaves. Choose **tracker** under **notes**
in the keys window (see **Changing the keys** below):

- **Z** to **/** is the lower octave, with **S D G H J** as its black keys.
- **Q** to **P** is the upper octave, with the number row as its black keys.
- **-** and **=** move the octave, and **[** and **]** change how hard.

## Shortcuts

- play / stop - **Space**
- record - **R**, or **Alt+R**
- loop - **L**, or **Alt+L**
- undo - **Ctrl+Z**, or **Alt+Z**
- redo - **Ctrl+Shift+Z**, or **Alt+Y**
- stop everything - **Ctrl+.**, or **Alt+P**
- keys play notes - **`**, or **Sym**
- save - **Ctrl+S**, or **Alt+S**
- file menu - **F**, or **Alt+F**
- mixer and perform pages - **M**, or **Alt+M**
- manual - **F1**, or **Alt+H**
- the list of keys - **Shift+/**, or **Alt+Q**
- back - **Esc**

In the editor:

- previous / next page - **[ and ]**, or **Alt+B and Alt+N**
- draw or select - **D**, or **Alt+D**
- step view - **T**, or **Alt+T**
- lock steps - **K**, or **Alt+K**
- generate notes - **G**, or **Alt+G**
- quantise - **Q**, or **Alt+U**
- fold the panel - **P**, or **Alt+V**
- fold the keyboard - **B**, or **Alt+J**

The first key is for a full keyboard, the second for a small one. Keys
without Alt or Ctrl only work as shortcuts when play mode is off.

## Moving around

The arrows, or swipes on a touchpad, move between controls. Tab and Shift+Tab
do too. A ring shows where you are. **Enter** presses a button or a switch.

On a knob or fader, **Enter** grabs it and the ring turns pink. The arrows
then turn it, Shift+arrows turn it finely, Page Up and Page Down in big steps,
and Home and End go to either end. **Enter** or **Esc** lets go. **+** and
**-** turn a knob without grabbing it.

Anything that does something when you hold it has the same choices on
**Alt+Enter**, or on the Menu key. This is how you reach a clip's settings, a
scene's menu or a knob's list of values.

A window opened from the keys puts you on its first control. **Esc** closes
it.

## In the editor

The piano roll, the note lanes under it and the automation lanes each take
**Enter** to start editing, and a pink cursor appears. **Esc** stops.

In the piano roll:

- the arrows move the cursor a grid step, or a semitone. Page Up and Page Down
  move it an octave.
- **Enter** does what a tap there would: adds a note, or takes one away.
  **Delete** removes the note under the cursor.
- **Shift+Left** and **Shift+Right** shorten and lengthen the note under the
  cursor.
- **Alt** and an arrow moves the note, with the cursor.

In a note lane, Left and Right walk from note to note, and Up and Down change
the note's value, finely with Shift.

In an automation lane, Left and Right move a grid step, and Up and Down set
the value there, adding a point if there isn't one.

Every change is one step of undo, as it would be by touch.

## Changing the keys

**Settings › display › keyboard › keys…** lists every shortcut with its keys.
Each can have two. Tap a key, or move to it and press Enter, then press the
new key or combination. **+** adds a second key.

If the new key already belonged to something else, it moves, and the window
tells you what lost it. **Alt+Enter** on a key removes it. **reset** puts every
key back as it was.
