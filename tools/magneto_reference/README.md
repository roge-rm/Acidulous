# Magneto's reference clip

`test.wav` is kept out of the repository with the other reference
material, and `make_test.py` makes it again in the current folder. It's 12
seconds at 44.1 kHz, 16-bit stereo: ten seconds of a chord, a bass, a kick
and hats, then two seconds of quiet noise fading out.

To tune one of Magneto's modes against a real recorder:

1. Record `test.wav` onto the recorder in that mode, with nothing
   normalising or processing it on the way.
2. Bring the track back to the computer and decode the downloaded track
   to a 44.1 kHz WAV.
3. Measure it: `tools/magneto_measure.py path/to/test.wav track.wav`.
4. Render the same clip through Magneto in that mode, measure that the same
   way, and move the mode's numbers in `kFormats`
   (`app/src/main/cpp/engine/effect/Magneto.cpp`) until the two agree.

`sp.txt`, `lp2.txt` and `lp4.txt` are measurements of real copies, made by
recording `test.wav` to a disc and downloading the tracks back digitally:
SP as the recorder encoded it itself, LP2 and LP4 as the converter's remote
encoder made them. Magneto's SP, LP2 and LP4 are fitted to these. HQ and
XLP have no real copies yet.
