# The engine

Our own, from 2026-09-10; the vendored third-party subset that got the project
to M3 was removed in favour of this. Everything under `cpp/` is ours; the only
dependency is Oboe.

```
core/       WavReader / WavWriter / Sf2Reader (all ours) · SampleMap (zones + crossfades) · Constants · RtQueue (SPSC, wait-free) · Handover (Mount / Retire + the
            retire worker) · Params (ParamDef tables, 0..1 in, smoothed unit-range
            out) · Messages (MidiMessage, ParamMessage)
dsp/        Math · Osc (PolyBLEP saw/pulse) · Filter (TPT SVF) · MultiFilter (12 slopes +
            drive) · Envelope (decay, ASR) · Adsr (DADSR + repeat) · LfoGen · Wavetable ·
            Biquad · DelayLine · Lfo (note-value phase) · Reverb · Delay · Limiter · Click
machine/    Machine interface · MachineRegistry · subvert/ · trinity/ (the 3-osc poly) ·
            ratio/ (6-op FM, morphing algorithms) · mosaic/ (multisample + grains) ·
            hexbeat/ · forage/
effect/     Effect interface (onBlock for tempo, run() with bypass) · EffectRegistry ·
            Effects: Delay Reverb Eq Distortion Compressor Filter Bitcrusher Phaser Flanger
eventor/    Eventor interface + MidiSink · EventorRegistry · Scales.h (33 scales, 25 chords) ·
            Eventors: Scale Chord Arp
rack/       Rack (clip player -> eventors -> machine -> effects -> channel strip)
            MasterBus (sum, peak; sends + limiter in M5) · Engine (the render loop)
../sequencer/  TickClock · Transport · Clip · ClipPlayer · Song · SceneScheduler · RecordQueue
../platform/   the Oboe stream
```

## Rules the audio thread lives by

- It never allocates, locks, logs, or blocks. Objects arrive through `Mount`
  records built elsewhere and leave through `Retire` records to a worker.
- One mount is applied per block, at the block boundary, after rendering.
- MIDI, parameters and recorded events each cross on their own `RtQueue`.
- A block is `kBlockFrames` (64) at `kSampleRate` (48 000); the Oboe driver
  adapts whatever burst the device wants to that with a carry buffer.
- Parameters are addressed by index into a unit's `ParamDef` table; names are
  resolved on the UI thread (`EngineHost::setParam`). Every parameter is
  smoothed per block.
