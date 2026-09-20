#include "Molt.h"
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

using dsp::clampf;
using dsp::fastTanh;
using dsp::kTwoPi;
using dsp::mtof;

namespace {

/**
 * Hann, once, sampled by position rather than computed per sample.
 *
 * A grain can be a thousand frames long and one is laid every period, so the
 * window is asked for several times a frame per voice. A table of a thousand
 * is finer than the ear and finer than the grain.
 */
constexpr int32_t kWindowSize = 1024;

const float *hannTable() {
    static float table[kWindowSize];
    static const bool built = [] {
        for (int32_t i = 0; i < kWindowSize; ++i) {
            table[i] = 0.5f - 0.5f * std::cos(kTwoPi * static_cast<float>(i) /
                                              static_cast<float>(kWindowSize - 1));
        }
        return true;
    }();
    (void)built;
    return table;
}

} // namespace

Molt::Molt() { initParams(); }

const ParamDef *Molt::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // The take is a file, mounted like any other machine's material -
        // see the recording window. This machine used to capture into a
        // buffer of its own with `record`, `seconds` and `ingain`, which was
        // a fourth way of recording in an app that already had three.
        {"start", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"loop", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},

        // How hard the take is pulled onto what was written. At nought it
        // keeps the line it was sung with and is merely transposed; at one
        // every moment lands exactly on the note.
        {"tune", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        // How long it takes to get there. Zero is the hard tune everybody
        // knows; forty milliseconds is a singer correcting themselves.
        {"rate", 0.0f, 400.0f, 40.0f, Curve::Linear, 0, "ms"},
        // Every mark takes the written pitch, voiced or not: a monotone with
        // the words still in it.
        {"robot", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},

        // The size of the singer, independent of the note.
        {"formant", -12.0f, 12.0f, 0.0f, Curve::Linear, 0, "st"},
        {"mega", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},

        {"cutoff", 40.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
        {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"filtertype", 0.0f, static_cast<float>(dsp::MultiFilter::TypeCount - 1), 1.0f,
         Curve::Stepped, dsp::MultiFilter::TypeCount, ""},

        {"ampattack", 0.001f, 2.0f, 0.005f, Curve::Exponential, 0, "s"},
        {"ampdecay", 0.001f, 4.0f, 0.2f, Curve::Exponential, 0, "s"},
        {"ampsustain", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"amprelease", 0.001f, 4.0f, 0.08f, Curve::Exponential, 0, "s"},

        {"glide", 0.0f, 2.0f, 0.0f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"velocity", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Molt::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.acc.assign(kAccum, 0.0f);
        v.amp.setSampleRate(sampleRate);
    }
    filter.setSampleRate(sampleRate);
    horn.setSampleRate(sampleRate);
    hannTable();
    reset();
}

void Molt::reset() {
    for (auto &v : voices) {
        v.used = false;
        v.gate = false;
        v.note = 0;
        v.velocity = 1.0f;
        v.bend = 0.0f;
        v.pressure = 0.0f;
        v.logPitch = 0.0f;
        v.primed = false;
        v.untilGrain = 0.0f;
        v.accHead = 0;
        std::fill(v.acc.begin(), v.acc.end(), 0.0f);
        v.amp.reset();
    }
    filter.reset();
    horn.reset();
    head = 0.0;
    running = false;
    channelBend = 0.0f;
}

void Molt::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = nullptr;
    for (auto &c : voices) {
        if (!c.used) { v = &c; break; }
    }
    if (v == nullptr) {
        // Steal the quietest, which for a chord of held notes is the oldest
        // thing still fading.
        v = &voices[0];
        for (auto &c : voices) {
            if (c.amp.value() < v->amp.value()) v = &c;
        }
    }
    v->used = true;
    v->gate = true;
    v->note = note;
    v->velocity = 1.0f - targetOf(VelocityAmount) * (1.0f - static_cast<float>(velocity) / 127.0f);
    v->bend = channelBend;
    v->primed = false;
    v->untilGrain = 0.0f;
    v->amp.retrigger();

    // The first note starts the phrase. Later ones join it where it has got
    // to rather than restarting it - that is what makes a chord harmony, and
    // what lets a melody be written under a line that keeps its own rhythm.
    if (!running) {
        const audio::Utterance *u = source;
        if (u != nullptr && u->usable()) {
            head = static_cast<double>(targetOf(Start)) * static_cast<double>(u->frames);
            running = true;
        }
    }
}

void Molt::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Molt::allNotesOff() {
    for (auto &v : voices) {
        v.gate = false;
        v.amp.release();
    }
    // The words stop with the hands: a transport stop rewinds the phrase.
    running = false;
    head = 0.0;
}

void Molt::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) {
        // Mod is the size of the singer, which is the one thing here worth a
        // wheel: it is continuous, it is musical, and it is the gesture the
        // machine is named for.
        const float v = static_cast<float>(value) / 127.0f;
        params_.set(Formant, 0.5f + v * 0.5f);
    }
}

void Molt::pitchBend(int16_t value14) {
    channelBend = static_cast<float>(value14) / 8192.0f * paramOf(BendRange);
    for (auto &v : voices) {
        if (v.used) v.bend = channelBend;
    }
}

void Molt::noteBend(uint8_t note, float semitones) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) v.bend = semitones;
    }
}

void Molt::notePressure(uint8_t note, uint8_t value) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) v.pressure = static_cast<float>(value) / 127.0f;
    }
}

void *Molt::swapObject(int32_t slot, void *object) {
    if (slot != 0) return object;
    // Voices hold positions into the old take, so they stop with it.
    for (auto &v : voices) {
        v.used = false;
        v.gate = false;
        v.amp.kill();
        std::fill(v.acc.begin(), v.acc.end(), 0.0f);
    }
    running = false;
    head = 0.0;
    void *old = const_cast<audio::Utterance *>(source);
    source = static_cast<const audio::Utterance *>(object);
    return old;
}

float Molt::layGrain(Voice &v, float formantRatio, float tune, float rateSec, bool robot) {
    const audio::Utterance *u = source;
    const int32_t idx = u->epochAt(static_cast<float>(head));
    if (idx < 0) return sampleRate * 0.005f;
    const audio::Epoch &e = u->epochs[static_cast<size_t>(idx)];
    const float srcPeriod = clampf(e.period, 2.0f, 2000.0f);

    // --- what this grain is pulled to --------------------------------------
    float targetPeriod;
    if (e.voiced || robot) {
        const float noteHz = mtof(static_cast<float>(v.note) +
                                  static_cast<float>(steppedOf(Transpose)) +
                                  12.0f * static_cast<float>(steppedOf(Octave)) + v.bend);
        const float logNote = std::log2(noteHz);
        float want = logNote;
        if (!robot && u->rootHz > 0.0f && e.voiced) {
            // How far this moment is from the take's own root, kept in part:
            // at tune nought the whole shape of the sung line survives and is
            // merely moved to the note; at one it is flattened onto it.
            const float logSrc = std::log2(sampleRate / srcPeriod);
            want = logNote + (1.0f - tune) * (logSrc - std::log2(u->rootHz));
        }
        if (!v.primed) {
            v.logPitch = want;
            v.primed = true;
        } else if (rateSec <= 0.0005f) {
            v.logPitch = want;
        } else {
            // One pole per grain rather than per sample: correction happens
            // at the rate the voice has periods, which is the rate at which
            // there is anything new to correct.
            const float coeff = 1.0f - std::exp(-srcPeriod / (rateSec * sampleRate));
            v.logPitch += (want - v.logPitch) * coeff;
        }
        targetPeriod = sampleRate / std::exp2(v.logPitch);
    } else {
        // A consonant has no pitch to move, and moving it anyway is what
        // makes a cheap shifter sound cheap. It is copied at its own rate.
        targetPeriod = srcPeriod;
    }
    targetPeriod = clampf(targetPeriod, 4.0f, 2000.0f);

    // --- and how it is read ------------------------------------------------
    // Half a grain is the source's own period, and never more: two periods
    // holds exactly one glottal pulse, with its neighbours falling under the
    // window's skirts. Widen it to cover the *target* period instead - which
    // is tempting, because it guarantees the grains overlap - and a grain
    // with the formant shifted up ends up holding two pulses instead of one.
    // The output then has them in pairs and reads an octave down, which is
    // what the harness caught.
    //
    // Overlap is not this function's problem anyway: pitching down makes the
    // head crawl, so `epochAt` hands back the same pulse several times and
    // the gaps fill themselves, which is what PSOLA does.
    const float half = srcPeriod;
    const int32_t n = static_cast<int32_t>(2.0f * half / formantRatio);
    if (n < 2 || n >= kAccum) return targetPeriod;

    // Hann summed at fifty per cent overlap is one; laid tighter it is more
    // and wider it is less, and either way the hop over the half length is
    // the correction.
    //
    // **And it is only a correction while the grains actually overlap.** A
    // grain is two source periods long and they are laid one target period
    // apart, so when the note is more than an octave under the pitch the
    // tracker reports, consecutive grains do not touch and there is nothing
    // to correct for - the right gain is one, and `2*target/n` asks for six.
    // The old cap of 1.6 still let a third of that through, and what it
    // sounds like is a train of isolated bursts rather than a voice.
    //
    // Which is not a corner case, because the tracker is wrong about the
    // octave on about one hop in ten of a real take: one short `srcPeriod`
    // and the grain for that mark is a sixth as long as its own spacing.
    // `Wide Bend` peaked at +12.3 dBFS that way, and only at rate 50 - at 20,
    // 120 and 250 the glide crossed the bad value somewhere the take was
    // quiet. A patch that is eighteen decibels hot because of where a glide
    // happened to be is not a patch anybody can voice.
    //
    // (The comment below about the head crawling and filling the gaps is not
    // true of this machine: the head advances one frame per sample whatever
    // the note is. It fills gaps when the *source* is low, not when the
    // target is.)
    const float gain = clampf(2.0f * targetPeriod / static_cast<float>(n), 0.0f, 1.0f);
    const float *window = hannTable();
    const float wStep = static_cast<float>(kWindowSize - 1) / static_cast<float>(n - 1);
    // **A grain that runs off the end of the take is laid from the middle of
    // its own window, which is a step.**
    //
    // The window is what makes overlap-add seamless: it is nought at both
    // ends, so a grain arrives and leaves without an edge. Reading it from
    // `e.at - half` puts the epoch at its centre, which is right - but a mark
    // less than one period into the take has no audio to fill the first half
    // of its window, and the guard below simply skipped those samples. The
    // grain then began at whatever the window was worth where the audio
    // started, which for the very first mark of a take is its peak. Every
    // note-on reaching for the front of a take emitted a step, and the
    // harness reads a step at the note-on as exactly what it is: a click, at
    // eleven times the sound's own corners, on the patches whose first grain
    // was loudest.
    //
    // Slid rather than skipped. One grain sits a fraction of a period off
    // its epoch at each end of the take and every window is whole.
    const int32_t frames = u->frames;
    const float span = static_cast<float>(n - 1) * formantRatio;
    const float from = clampf(static_cast<float>(e.at) - half, 0.0f,
                              std::max(0.0f, static_cast<float>(frames - 2) - span));

    for (int32_t k = 0; k < n; ++k) {
        const float sp = from + static_cast<float>(k) * formantRatio;
        const int32_t i0 = static_cast<int32_t>(sp);
        if (sp < 0.0f || i0 + 1 >= frames) continue;
        const float frac = sp - static_cast<float>(i0);
        const float s = u->mono[static_cast<size_t>(i0)] * (1.0f - frac) +
                        u->mono[static_cast<size_t>(i0 + 1)] * frac;
        const float w = window[static_cast<int32_t>(static_cast<float>(k) * wStep)];
        v.acc[static_cast<size_t>((v.accHead + k) & (kAccum - 1))] += s * w * gain;
    }
    return targetPeriod;
}

bool Molt::render(float *L, float *R, int32_t frames) {
    // --- the block's settings ----------------------------------------------
    const audio::Utterance *u = source;
    const bool haveTake = u != nullptr && u->usable();
    const float formantRatio = std::exp2(paramOf(Formant) / 12.0f);
    const float tune = clampf(paramOf(Tune), 0.0f, 1.0f);
    const float rateSec = paramOf(Rate) * 0.001f;
    const bool robot = rawOf(Robot) >= 0.5f;
    const bool loop = rawOf(Loop) >= 0.5f;
    const float startFrame = haveTake ? paramOf(Start) * static_cast<float>(u->frames) : 0.0f;

    for (auto &v : voices) {
        v.amp.set(0.0f, paramOf(AmpAttack), paramOf(AmpDecay), paramOf(AmpSustain),
                  paramOf(AmpRelease), false);
    }
    filter.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType), dsp::MultiFilter::Clean,
               0.0f);
    const float mega = clampf(paramOf(Mega), 0.0f, 1.0f);
    // A tannoy is a band nobody's throat has and enough drive to shout
    // through it. The band narrows as the amount rises.
    horn.set(1500.0f, 0.25f + mega * 0.45f, dsp::MultiFilter::BP12, dsp::MultiFilter::Clip, mega);

    const float drive = paramOf(Drive);
    const float volume = paramOf(Volume);
    const float panKnob = paramOf(Pan);
    const float panL = std::cos((panKnob + 1.0f) * 0.25f * dsp::kPi);
    const float panR = std::sin((panKnob + 1.0f) * 0.25f * dsp::kPi);

    for (int32_t i = 0; i < frames; ++i) {
        if (running && haveTake) {
            head += 1.0;
            if (head >= static_cast<double>(u->frames)) {
                if (loop) {
                    head = static_cast<double>(startFrame);
                } else {
                    running = false;
                }
            }
        }

        float mix = 0.0f;
        for (auto &v : voices) {
            if (!v.used) continue;
            if (running && haveTake) {
                while (v.untilGrain <= 0.0f) {
                    v.untilGrain += layGrain(v, formantRatio, tune, rateSec, robot);
                }
                v.untilGrain -= 1.0f;
            }
            const float s = v.acc[static_cast<size_t>(v.accHead)];
            v.acc[static_cast<size_t>(v.accHead)] = 0.0f;
            v.accHead = (v.accHead + 1) & (kAccum - 1);
            const float env = v.amp.next();
            if (!v.amp.active()) {
                v.used = false;
                continue;
            }
            // Pressure leans on the level, which is what a singer does with it.
            mix += s * env * v.velocity * (1.0f + v.pressure * 0.5f);
        }

        if (mega > 0.0001f) {
            const float band = horn.process(mix);
            mix = mix * (1.0f - mega) + band * mega * 1.8f;
        }
        mix = filter.process(mix);
        if (drive > 0.0001f) mix = fastTanh(mix * (1.0f + drive * 6.0f)) / (1.0f + drive * 1.5f);
        mix *= volume;
        L[i] += mix * panL;
        R[i] += mix * panR;
    }
    return true;
}

} // namespace acidulous::machine
