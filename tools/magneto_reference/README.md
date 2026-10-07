# Magneto's reference clip

`test.wav` is 12 seconds at 44.1 kHz, 16-bit stereo: ten seconds of a
chord, a bass, a kick and hats, then two seconds of quiet noise fading out.
`make_test.py` makes it again.

To tune one of Magneto's modes against a real recorder:

1. Record `test.wav` onto the recorder in that mode, with nothing
   normalising or processing it on the way.
2. Bring the track back to the computer and decode it to a 44.1 kHz WAV
   (`ffmpeg -i track.oma track.wav`).
3. Measure it: `tools/magneto_measure.py tools/magneto_reference/test.wav track.wav`.
4. Render the same clip through Magneto in that mode, measure that the same
   way, and move the mode's numbers in `kFormats`
   (`app/src/main/cpp/engine/effect/Magneto.cpp`) until the two agree.

Measurements of real copies, once there are some, go in this directory as
text, one file per mode.
