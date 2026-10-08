# A keyboard
Acidulous works with built in, USB, or Bluetooth keyboards.

Press **Shift+/** (or **Alt+Q**) at any time to see the keys that work on the
screen you're on.

## Play mode

Letters either run shortcuts or play notes. **`** (or **Sym**) switches between
the two, and so does tapping the octave number in the editor's keyboard strip.
While letters are notes the number is filled in, the keyboard is outlined
and the notes you play light up on it.

With play mode on:

- **A S D F G H J K L** are white keys, and **W E T Y U O P** the black keys
  between them like on a piano. A is C.
- **Z** and **X** move down and up an octave. In the editor they move the
  on-screen keyboard too.
- **C** and **V** make notes softer and harder.
- Shift plays a note an octave up.

Notes go to the track of the last clip you opened, like a MIDI keyboard, so
they record and the track's chord, scale and arp apply. On a
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
<!-- desktop: - full screen - **F11** -->

In the editor:

- previous / next page - **[ and ]**, or **Alt+B and Alt+N**
- previous / next track - **Shift+[ and Shift+]**
- draw or select - **D**, or **Alt+D**
- step view - **T**, or **Alt+T**
- lock steps - **K**, or **Alt+K**
- generate notes - **G**, or **Alt+G**
- quantise - **Q**, or **Alt+U**
- fold the panel - **P**, or **Alt+V**
- fold the keyboard - **B**, or **Alt+J**

The first key is for a full keyboard and the second for a small one. Keys
without Alt or Ctrl only work as shortcuts when play mode is off.

In a window with tabs, previous and next page step through the tabs.

## Moving around

The arrows, or swipes on a touchpad, move between controls. Tab and Shift+Tab
do too, and a ring shows where you are. **Enter** presses a button or a switch.

On a knob or fader, **Enter** grabs it and the ring turns pink. The arrows
then turn it, Shift+arrows turn it finely, Page Up and Page Down in big steps,
and Home and End go to either end. **Enter** or **Esc** lets go. **+** and
**-** turn a knob without grabbing it.

Anything you'd hold has the same choices on **Alt+Enter** or the Menu key,
like a clip's settings, a scene's menu or a knob's list of values.

A window opened from the keys puts you on its first control, and **Esc**
closes it.

## In the editor

The piano roll, the note lanes under it and the automation lanes each take
**Enter** to start editing, and a pink cursor appears. **Esc** stops.

In the piano roll:

- the arrows move the cursor a grid step, or a semitone. Page Up and Page Down
  move it an octave.
- **Enter** adds a note or takes one away, like a tap.
  **Delete** removes the note under the cursor.
- **Shift+Left** and **Shift+Right** shorten and lengthen the note under the
  cursor.
- **Alt** and an arrow moves the note along with the cursor.

In a note lane, Left and Right walk from note to note, and Up and Down change
the note's value, finely with Shift. Showing words, Enter opens them.

In an automation lane, Left and Right move a grid step, and Up and Down set
the value there, adding a point if there isn't one.

Every change is one step of undo, like it would be by touch.

## A game controller

A controller's buttons work like the keys, so the app can be used without
touching the screen, on a handheld like the Retroid Pocket.

- **d-pad** - moves between controls, and moves the cursor in the editor.
- **A** - presses, like Enter. On a knob it grabs it, the d-pad turns it, and
  **A** again lets go.
- **B** - back: closes a window, or leaves the editor.
- **X** - what a long press does: a control's list of actions.
- **Y** - play / stop.
- **L1** and **R1** - previous and next page in the editor, and a window's
  tabs.
- **L2** and **R2** - previous and next track in the editor.
- **Start** - play mode.
- **Select** - the file menu.

- **left stick** - turns the highlighted knob or fader, faster the further
  it's pushed. One push is one step of undo.
- **right stick** - a fast d-pad: the further it's pushed, the faster it moves.

If nothing is highlighted, the first press of the d-pad or **A** highlights
something to start from.

**Start** switches play mode on and off. In play mode the controller is an
instrument for the track MIDI plays:

- the **d-pad** and the four buttons are eight notes of the track's scale (its
  scale chip, or the song's key, or else major), going round each clockwise
  from the bottom: the d-pad has the first four, the buttons the next four. On
  a drum machine they're its first eight pads.
- **L1** and **R1** move the octave.
- the **right stick** bends the pitch sideways and adds mod upwards, and the
  **left stick** pushed up is pressure.
- pull **R2** or **L2** partway while you play to set how hard notes play.
- **Select** is play / stop, since **Y** is a note.

## Changing the keys

**Settings › display › keyboard › keys…** lists every shortcut with its keys,
and each can have two. Tap a key, or move to it and press Enter, then press the
new key or combination. **+** adds a second key.

If the new key already belonged to something else, it moves, and the window
tells you what lost it. **Alt+Enter** on a key removes it. **reset** puts every
key back as it was.

The **controller** card lists a controller's buttons and what each does. Tap
one to choose from a list: **press**, **back**, **actions list**,
**nothing**, or any shortcut. **reset** puts these back too. Play mode's
notes stay where they are.
