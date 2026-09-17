#include "Subvert.h"
#include <engine/core/Constants.h>

namespace acidulous::machine {

// What this machine's signal reaches before its drive stage, and so the level
// that stage should treat as nominal. Measured, not guessed: at the oscillator, before volume x kHouse.
// A nominal above what the signal reaches puts the whole sound on the steep
// part of the curve, where the knob is a volume control again.
constexpr float kNominal = 0.45f;

namespace {

/**
 * What this machine puts out at the volume knob's default, against the rest
 * of the app.
 *
 * Every machine here was levelled against its own bank and none against the
 * others, and the factory came to span twenty-five decibels: a kit was
 * twenty-two louder than a modelled string, so changing machine changed the
 * volume of the song. A bass is meant to sit forward in a mix and this one
 * was doing it by fourteen decibels, which is not forward, it is a different
 * gain structure. The house figure is about -21 dB on the harness's loudness
 * column - the loudest four hundred milliseconds of one note - and every
 * machine's output is scaled so its own default lands there. The knob then
 * means the same thing wherever you are.
 */
constexpr float kHouse = 0.2f;

const ParamDef kDefs[Subvert::Count] = {
    {"wave", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},          // 0 saw, 1 pulse
    {"tune", -12.0f, 12.0f, 0.0f, Curve::Linear, 0, "st"},
    {"cutoff", 40.0f, 12000.0f, 700.0f, Curve::Exponential, 0, "Hz"},
    {"resonance", 0.0f, 1.0f, 0.55f, Curve::Linear, 0, ""},
    {"envmod", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
    {"decay", 30.0f, 3000.0f, 300.0f, Curve::Exponential, 0, "ms"},
    {"accent", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
    {"slide", 5.0f, 500.0f, 60.0f, Curve::Exponential, 0, "ms"},
    {"drive", 0.0f, 1.0f, 0.1f, Curve::Linear, 0, ""},
    // To 1.5, as most machines do. Twenty-nine patches here set drive, and
    // correcting the drive law took the level that law had been adding for
    // free - three of them then sat six decibels under the bank with the
    // knob already against a stop at 1.0.
    {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
    {"pw", 0.05f, 0.95f, 0.5f, Curve::Linear, 0, ""},          // pulse width, pulse wave only
    {"sub", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},           // square an octave down
    {"mode", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},         // 0 lowpass, 1 bandpass
};
} // namespace

Subvert::Subvert() { initParams(); }

const ParamDef *Subvert::paramDefs(int32_t &count) const {
    count = Count;
    return kDefs;
}

void Subvert::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    osc.setSampleRate(sampleRate);
    sub.setSampleRate(sampleRate);
    svf1.setSampleRate(sampleRate);
    svf2.setSampleRate(sampleRate);
    filterEnv.setSampleRate(sampleRate);
    accentEnv.setSampleRate(sampleRate);
    ampEnv.setSampleRate(sampleRate);
    accentEnv.setTimes(0.001f, 0.12f);
    ampEnv.setTimes(0.002f, 0.010f);
    params_.jumpAll();
    reset();
}

void Subvert::reset() {
    stackSize = 0;
    gliding = false;
    accented = false;
    filterEnv.kill();
    accentEnv.kill();
    ampEnv.kill();
    svf1.reset();
    svf2.reset();
    // The oscillators too, and the pitch they were gliding toward.
    //
    // These free-run: nothing restarts them on a note, because an acid box's
    // oscillator does not restart either, and that continuity is part of
    // the sound. But it means a reset that leaves them alone is not a
    // reset - the next note begins part way through a cycle, at whatever
    // point the last one happened to stop. Live that is invisible. It is
    // why rendering the same song twice gave two different files: the
    // notes were identical, and the waveform under them began somewhere
    // else each time.
    osc.reset();
    sub.reset();
    pitch = targetPitch = 48.0f;
    coeffCountdown = 0;
}

void Subvert::startNote(uint8_t note, bool legato, bool accent) {
    targetPitch = static_cast<float>(note);
    if (legato) {
        // Slide: glide there, keep the envelopes running.
        gliding = true;
        glideCoeff = dsp::onePoleCoeff(params_.get(Slide) * 0.001f, sampleRate);
    } else {
        pitch = targetPitch;
        gliding = false;
        accented = accent;
        // An accented note snaps: shorter decay, so the sweep bites and gets out of the way.
        filterEnv.setTimes(0.003f, params_.get(Decay) * 0.001f * (accent ? 0.6f : 1.0f));
        filterEnv.trigger();
        if (accent) accentEnv.trigger();
        ampEnv.gate(true);
    }
}

void Subvert::noteOn(uint8_t note, uint8_t velocity) {
    const bool legato = stackSize > 0;
    // Push (or move to top) on the held-note stack.
    int32_t found = -1;
    for (int32_t i = 0; i < stackSize; ++i) {
        if (stack[i] == note) { found = i; break; }
    }
    if (found >= 0) {
        for (int32_t i = found; i < stackSize - 1; ++i) stack[i] = stack[i + 1];
        --stackSize;
    }
    if (stackSize == kStack) {
        for (int32_t i = 0; i < kStack - 1; ++i) stack[i] = stack[i + 1];
        --stackSize;
    }
    stack[stackSize++] = note;
    startNote(note, legato, velocity >= kAccentVelocity);
}

void Subvert::noteOff(uint8_t note) {
    int32_t found = -1;
    for (int32_t i = 0; i < stackSize; ++i) {
        if (stack[i] == note) { found = i; break; }
    }
    if (found < 0) return;
    const bool wasTop = (found == stackSize - 1);
    for (int32_t i = found; i < stackSize - 1; ++i) stack[i] = stack[i + 1];
    --stackSize;
    if (stackSize == 0) {
        ampEnv.gate(false);
    } else if (wasTop) {
        // Fall back to the previous held note, sliding, as those boxes do.
        startNote(stack[stackSize - 1], true, false);
    }
}

void Subvert::allNotesOff() {
    stackSize = 0;
    ampEnv.gate(false);
}

bool Subvert::render(float *L, float * /*R*/, int32_t frames) {
    params_.tick();
    const bool pulse = params_.get(Wave) >= 0.5f;
    const float tune = params_.get(Tune);
    const float baseCutoff = params_.get(Cutoff);
    const float resonance = params_.get(Resonance);
    const float envMod = params_.get(EnvMod);
    const float accentAmt = params_.get(Accent);
    const float drive = params_.get(Drive);
    const float volume = params_.get(Volume);
    const float pw = params_.get(PulseWidth);
    const float subLevel = params_.get(Sub);
    const bool bandpass = params_.get(Mode) >= 0.5f;

    if (!ampEnv.active()) {
        for (int32_t i = 0; i < frames; ++i) L[i] = 0.0f;
        return false;
    }

    const float driveGain = 1.0f + drive * 7.0f;
    // Normalised on the nominal level rather than by a guessed divisor.
    //
    // `1 / (1 + drive * 1.5)` against a gain of `1 + drive * 7` hands a small
    // signal about eleven decibels at the top of the knob and caps a loud one
    // at four tenths - the same shape Cipher's output drive had. Twenty-nine
    // patches in this bank set drive, more than any machine but Manual, so it
    // is the one where this mattered most.
    const float driveComp = kNominal / dsp::fastTanh(kNominal * driveGain);

    for (int32_t i = 0; i < frames; ++i) {
        if (gliding) {
            pitch += (targetPitch - pitch) * glideCoeff;
            if (std::fabs(targetPitch - pitch) < 0.001f) { pitch = targetPitch; gliding = false; }
        }
        const float hz = dsp::mtof(pitch + tune);
        osc.setFrequency(hz);
        sub.setFrequency(hz * 0.5f);
        float s = pulse ? osc.pulse(pw) : osc.saw();
        if (subLevel > 0.0f) s += sub.pulse(0.5f) * subLevel;

        const float fenv = filterEnv.next();
        const float aenv = accentEnv.next();
        // Envelope sweeps the cutoff in octaves; accent adds up to 1.5 more.
        const float octaves = fenv * envMod * 4.0f * (1.0f + (accented ? accentAmt * 1.5f : 0.0f));
        if (coeffCountdown-- <= 0) {
            const float fc = baseCutoff * std::exp2(octaves);
            svf1.set(fc, resonance);
            svf2.set(fc, resonance * 0.3f);
            coeffCountdown = 3; // every 4 samples is plenty for a sweep
        }
        // Two stages with a touch of saturation between them: the resonance
        // rounds off instead of ringing clean, which is most of the bite.
        s = bandpass ? svf1.bandpass(s) : svf1.lowpass(s);
        s = dsp::fastTanh(s * 1.3f) * 0.77f;
        s = svf2.lowpass(s);
        s = dsp::fastTanh(s * driveGain) * driveComp;

        const float amp = ampEnv.next() * volume * kHouse *
                          (1.0f + (accented ? accentAmt * aenv * 0.6f : 0.0f));
        L[i] = s * amp;
    }
    return false;
}

} // namespace acidulous::machine
