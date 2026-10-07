# Nexus

> A modular synth whose modules are the other machines.

Nexus lets you build your own machine. Its modules are the app's own
instruments, so you can wire a Reflux filter, a Trinity oscillator and a
Resonance mode bank together.

## The patch is text

A Nexus patch is a list of modules and connections written out as text, so you
can read it, copy it and send it to someone. It has its own screen, since a
patch needs room.

Its knobs are normal parameters, so anything in a patch can be automated,
mapped to a controller and recorded.

**fit** tidies the patch to the screen. It lays the modules out in the order
the sound flows through them, left to right, in as many rows as suit the
screen, then zooms to show all of it. Turn the phone and press it again for a
layout that suits that way round. It's one step of undo, so the modules can go
back where they were.

## Modules on the canvas

Each module is a panel on rails, as wide as its knobs and jacks need. Inputs
are the teal jacks and outputs the amber ones, in the dark box at the bottom.
The colour along the top says what kind of module it is, and the light next to
the name shows how hard it's working. Cables take the colour of the module they
come from.

Turn a knob on a panel by dragging up or down. Zoom in for finer moves, and tap
twice to put it back. The same knobs are under the canvas when the module is
selected. In mapping mode, tap a knob on a panel to map it and hold it to clear
the mapping.

## Effects as modules

The insert effects are modules too, so an effect can go anywhere in a patch
and be moved by a cable: **reverb**, **chorus**, **phaser**, **crush**,
**shift**, **drive** and **swell**. Each sounds exactly as it does on a track.
Their second input moves the knob it's named after, like **size** on the
reverb or **amount** on swell. Each runs a fraction of a millisecond late,
which you won't hear.

## Instruments from the other machines

More of the machines are modules, each with the part that makes its sound:

- **bore** - Brazen's horn: lips on a tube with a bell.
- **pipe** - Timber's pipe, with a reed, a double reed or a flute's air jet.
- **reed** - Draw's free reed: harmonica, accordion, melodica, harmonium or
  concertina.
- **jaw** - Tongue's jaw harp reed, plucked by its **trig** input. Put it
  through a **throat** and move the vowel for the twang.
- **piano** - the whole of Hammer, played by a **pitch** and a **gate**.
- **guitar**, **mallets**, **sitar**, **drum**, **pipes**, **bird** and
  **water** - the whole of Fret, Tine, Sympath, Palm, Chanter, Aviary and
  Fathom, played the same way, each with eight of its own knobs. The bird,
  the pipes, the tanpura and the water keep going for as long as the gate's
  held.
- **throat** - Diction's vocal tract as a filter: anything through it becomes
  a vowel, from oo to ee.
- **formula** - Formulate's expressions, as an oscillator or as a shaper of
  what comes into **x**. Select it and press **edit** to type the formula.
- **follow** - Molt's ear: the pitch of whatever's in its input, a gate while
  it's sure of it, and its level. Sing into it to play the patch.

The blown ones (**bore**, **pipe** and **reed**) take their air from the
**breath** input, from an envelope or a pressure source. With nothing in it,
the keys blow them.

## Macros

Eight macros, **macro1** to **macro8**, plus **morph**. The patch decides what
they control, so a big patch can be played with a few knobs.

## MPE

The **touch** module gives each voice the pressure and slide of the finger
playing it, from an MPE controller or a keyboard with poly aftertouch. Patch
its **prs** and **slide** into anything, like a filter's cutoff or a VCA.

## Audio input

There's an audio input module, so anything coming into the phone can go
through a patch.

## Tips

- Keep patches small and name your macros.
- Wire **morph** to the two or three things that change the patch the most.
- Nexus isn't the easiest place to start. The other machines do most things
  more directly, and Nexus is for combinations they don't cover.
