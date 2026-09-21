#include "Amp.h"
#include <engine/core/Settings.h>

namespace acidulous::effect {

const ParamDef *Amp::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // **The preamp is `drive`, not `gain`.** `Effect::initParams` finds a
        // parameter literally named `gain` and the base class multiplies the
        // output by it afterwards, so a preamp called that would have a hidden
        // trim welded to it. And for an effect the parameter's *name is the
        // knob's label*, so it has to read correctly too.
        {"drive", 0.0f, 1.0f, 0.35f, Curve::Linear, 0, ""},
        {"bias", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bass", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"mid", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"treble", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"stack", 0.0f, 2.0f, 1.0f, Curve::Stepped, 3, ""}, // us, uk, modern
        {"presence", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"master", 0.0f, 1.0f, 0.35f, Curve::Linear, 0, ""},
        {"sag", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"cab", 0.0f, 1.0f, 1.0f, Curve::Stepped, 2, ""},
        // Every voicing knob is a plain 0..1 and every taper lives in the code
        // below, so a taper can be retuned without changing what a stored
        // value means - which after the first factory patch ships is the
        // difference between an improvement and a song changing under somebody.
        {"size", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"cone", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"mic", 0.0f, 1.0f, 0.25f, Curve::Linear, 0, ""},
        {"edge", 0.0f, 1.0f, 0.15f, Curve::Linear, 0, ""},
        {"room", 0.0f, 1.0f, 0.15f, Curve::Linear, 0, ""},
        {"mix", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"gain", -18.0f, 18.0f, 0.0f, Curve::Linear, 0, "dB"},
    };
    count = Count;
    return defs;
}

void Amp::prepare(int32_t sampleRate) {
    sr = static_cast<float>(sampleRate);
    for (auto &c : ch) {
        c.os.prepare();
        c.inHp.setSampleRate(sr * 2.0f);
        c.tone.prepare(sr * 2.0f);
        c.power.prepare(sr * 2.0f);
        c.cab.prepare(sr);
    }
    reset();
}

void Amp::reset() {
    for (auto &c : ch) {
        c.os.reset();
        c.inShelf.reset();
        c.bright.reset();
        c.inHp.reset();
        c.tone.reset();
        c.presence.reset();
        c.power.reset();
        c.cab.reset();
        c.interZ = 0.0f;
        for (auto &v : c.dry) v = 0.0f;
        c.dryAt = 0;
    }
}

bool Amp::process(float *L, float *R, int32_t frames, bool stereoIn) {
    const ParamSet &p = params();
    const auto stack = static_cast<int32_t>(p.get(Stack) + 0.5f);
    const amp::Voicing v = amp::voicingOf(stack);
    const float drive = p.get(Drive);

    g1 = 1.0f + drive * 18.0f;
    const float over = drive > v.stageBFrom ? (drive - v.stageBFrom) / (1.0f - v.stageBFrom) : 0.0f;
    g2 = 1.0f + over * 12.0f;
    stageBOn = over > 0.0f; // a clean setting is genuinely one stage, and cheaper
    biasOff = p.get(Bias) * 0.5f;
    interCoeff = dsp::onePoleCoeff(1.0f / (6.2831853f * v.interHz), sr * 2.0f);
    cabOn = p.get(Cab) >= 0.5f;
    mixNow = p.get(Mix);

    const int chans = stereoIn ? 2 : 1;
    for (int c = 0; c < chans; ++c) {
        Channel &s = ch[c];
        s.inShelf.lowShelf(v.inShelfHz, v.inShelfDb, sr * 2.0f);
        s.bright.highShelf(v.brightHz, v.brightDb, sr * 2.0f);
        s.tone.set(v, p.get(Bass), p.get(Mid), p.get(Treble));
        s.presence.highShelf(2200.0f, p.get(Presence) * 9.0f, sr * 2.0f);
        s.power.set(p.get(Master), p.get(Sag), v.sagScale);
        s.cab.set(p.get(Size), p.get(Cone), p.get(Mic), p.get(Edge), p.get(Room));
    }

    for (int c = 0; c < chans; ++c) {
        Channel &s = ch[c];
        float *buf = c == 0 ? L : R;
        // **The dry path is delayed by the oversampler's latency, always.**
        //
        // Two reasons, and the second is the one that bites. A wet path fifteen
        // samples behind an undelayed dry notches at 1.6 kHz when `mix` is
        // halfway, and nobody attributes that to an oversampler - they say the
        // amp sounds phasey. And delaying it *unconditionally*, rather than
        // only when the oversampling is on, means the latency never changes,
        // so flipping the quality setting mid-stream cannot click.
        float dry[kBlockFrames];
        for (int32_t i = 0; i < frames; ++i) {
            s.dry[static_cast<size_t>(s.dryAt)] = buf[i];
            s.dryAt = s.dryAt + 1 >= kDry ? 0 : s.dryAt + 1;
            dry[i] = s.dry[static_cast<size_t>((s.dryAt + kDry - 1 - dsp::Oversampler::kLatency) % kDry)];
        }
        s.os.up(buf, frames, up);
        for (int32_t i = 0; i < frames * 2; ++i) {
            float x = s.inHp.highpass(up[i]);
            x = s.bright.process(s.inShelf.process(x));
            x = stageA(x, g1, biasOff);
            // The coupling capacitor: what makes a cascade tight rather than
            // flubby, and what stops stage A's bias becoming stage B's offset.
            s.interZ += (x - s.interZ) * interCoeff;
            x -= s.interZ;
            if (stageBOn) x = stageB(x, g2);
            x = s.tone.process(x);
            x = s.presence.process(x);
            up[i] = s.power.process(x);
        }
        s.os.down(up, frames, buf);
        if (cabOn) {
            for (int32_t i = 0; i < frames; ++i) buf[i] = s.cab.process(buf[i]);
        }
        // At nought this is exactly the dry, which is what makes the effect
        // provably transparent when it is asked to be.
        for (int32_t i = 0; i < frames; ++i) buf[i] = dry[i] + (buf[i] - dry[i]) * mixNow;
    }
    if (!stereoIn) return false;
    return true;
}

} // namespace acidulous::effect
