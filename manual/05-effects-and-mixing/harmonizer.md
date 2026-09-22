# Harmonizer
> Adds two voices at scale steps, so the harmony stays in key.

A pitch shifter that knows the key. You set the intervals in scale steps rather
than semitones, so a third comes out major or minor depending on the note, like
a real harmony part.

## The controls

- **interval** and **interval2** - the two added voices, -7 to +7 scale steps.
- **scale** *(extra)* - which scale the steps are counted in. The same list as
  the Scale modifier.
- **key** *(extra)* - the key.
- **window** - 10 to 120 ms. Short follows fast parts but sounds grainier; long
  is smoother but blurs attacks.
- **feedback** - feeds the output back in, stacking the interval on itself.
- **mix** - wet against dry.

## Tips

- Set the key, or it's just a pitch shifter and some thirds will be wrong.
- On drums or anything percussive, use a short window (around 20 ms).
- A fifth or an octave below is often more useful than a third above, and
  exposes tuning less.
- Two steps with feedback stacks up a chord.
