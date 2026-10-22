# Brazen

> Modelled brass, from trumpet to tuba, or a whole section.

Brazen is a model of a brass instrument: lips buzzing against a tube with a bell
on the end.

## Blowing

- **pressure** - how hard you blow. It's the main control, and it isn't a
  volume knob. Too little and nothing sounds, more and the note starts, more
  again and it gets brighter until it overblows.
- **breath** - air noise.
- **bite** and **tension** - how tight the lips are. Together with pressure they
  decide which note of the tube you land on, like a real player's embouchure.
- **lipdamp** - how damped the lips are.

## The tube

- **size** - how long the tube is, from trumpet to tuba.
- **bell** - how the tube flares, which changes how bright it is.
- **loss** - how much energy the tube loses.
- **brass** - the blare you get when brass is played loud.

## A section

**lock** and **drift** turn one player into several who are nearly together.
**growl** and **growlrate** add a growl into the mouthpiece.

## Mono

With **mono** on, a note played while the last one is still held is slurred
onto it, sliding over the **glide** time. Turn **legato** off and it's tongued
instead, unless glide is up. A note played after you let go is tongued, and the
last one rings out under it. With **choke** on it cuts that tail instead, and
slides from it when glide is up.

## Swell and flutter

The **swell** group on the **player** page moves the breath over the start of
each note. Turn **amount** up and the breath starts low and rises to the set
pressure, so the note grows out of almost nothing at 1. Turn it down and the
breath starts above the pressure and falls back to it, an accent. **time** is
how long it takes. A slurred note carries on the swell it joined.

The **flutter** group rolls the tongue at its **rate**, chopping the air and the
lips, the buzz of a flutter-tongued trumpet. It's separate from the growl and
both can run at once.

## Macros

The **play** page has eight macro knobs. A patch can name them (Blow, Brassy,
etc.) and the names show on the knobs, on automation lanes and in the
controller mapping list. They sit at zero when a patch loads, which is the
patch as it was made.

The **macros** page sets what each one does. A macro has four routes, a
destination (**to**) and an amount (**by**, from -1 to 1) for each. Turning the
knob moves the destination by knob times amount, across that control's whole
range at 1. Pitch moves the whole instrument, 24 semitones at 1. The mute
kind, players, filter type and mode switches and the tuning steps can't be
reached. A change to the swell, glide, spread, scatter, vibrato delay or
velocity is heard from the next note. Tap a macro's name to rename it.

## Tips

- Big instruments need more pressure. A tuba is blown hard.
- For a swell on every note use the **swell** group. Automate pressure for one
  across a phrase. Either changes the tone as well as the level.
- Start from a patch and change **size** first.
