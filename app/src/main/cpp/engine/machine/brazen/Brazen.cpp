#include "Brazen.h"
#include <engine/machine/Voices.h>
#include <algorithm>
#include <cmath>
#include <engine/dsp/Math.h>

namespace acidulous::machine {

namespace {
constexpr float kTwoPi = 6.28318530718f;
float mtof(float note) { return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f); }
} // namespace

Brazen::Brazen() { initParams(); }

const ParamDef *Brazen::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // 0 is a tuba's wide slow bore, 1 a trumpet's narrow bright one. It
        // is one knob because it is one physical fact: how much of the wave
        // the bell sends back.
        {"size", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"bell", 0.05f, 0.95f, 0.55f, Curve::Linear, 0, ""},
        {"loss", 0.97f, 1.0f, 0.999f, Curve::Linear, 0, ""},
        {"mute", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"mutetone", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"tension", 0.45f, 1.35f, 1.0f, Curve::Linear, 0, ""},
        {"lipdamp", 0.05f, 0.95f, 0.6f, Curve::Linear, 0, ""},
        {"pressure", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"breath", 0.0f, 1.0f, 0.15f, Curve::Linear, 0, ""},
        {"bite", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"brass", 0.0f, 1.0f, 0.45f, Curve::Linear, 0, ""},
        {"growl", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"growlrate", 10.0f, 120.0f, 45.0f, Curve::Exponential, 0, "Hz"},
        {"players", 1.0f, 4.0f, 1.0f, Curve::Stepped, 4, ""},
        {"spread", 0.0f, 60.0f, 8.0f, Curve::Linear, 0, "cents"},
        {"scatter", 0.0f, 120.0f, 15.0f, Curve::Linear, 0, "ms"},
        {"lock", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"drift", 0.0f, 50.0f, 6.0f, Curve::Linear, 0, "cents"},
        {"width", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        {"attack", 0.002f, 2.0f, 0.05f, Curve::Exponential, 0, "s"},
        {"decay", 0.005f, 4.0f, 0.3f, Curve::Exponential, 0, "s"},
        {"sustain", 0.0f, 1.0f, 0.85f, Curve::Linear, 0, ""},
        {"release", 0.005f, 4.0f, 0.25f, Curve::Exponential, 0, "s"},
        {"vibrato", 0.0f, 60.0f, 10.0f, Curve::Linear, 0, "cents"},
        {"vibratorate", 0.5f, 12.0f, 5.0f, Curve::Exponential, 0, "Hz"},
        {"vibratodelay", 0.0f, 2.0f, 0.35f, Curve::Linear, 0, "s"},
        {"cutoff", 200.0f, 18000.0f, 18000.0f, Curve::Exponential, 0, "Hz"},
        {"resonance", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"filtertype", 0.0f, 11.0f, 1.0f, Curve::Stepped, 12, ""},
        {"mono", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        {"glide", 0.0f, 1.0f, 0.06f, Curve::Linear, 0, "s"},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"fine", -50.0f, 50.0f, 0.0f, Curve::Linear, 0, "cents"},
        {"velocity", 0.0f, 1.0f, 0.6f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"volume", 0.0f, 1.5f, 0.8f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"mpetimbre", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
    };
    count = Count;
    return defs;
}

void Brazen::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (auto &v : voices) {
        v.amp.setSampleRate(sampleRate);
        v.filterL.setSampleRate(sampleRate);
        v.filterR.setSampleRate(sampleRate);
        for (auto &p : v.players) p.bore.prepare(sampleRate);
    }
    reset();
}

void Brazen::reset() {
    for (auto &v : voices) {
        v.used = v.gate = false;
        v.amp.kill();
        v.filterL.reset();
        v.filterR.reset();
        for (auto &p : v.players) { p.bore.clear(); p.rng = Player::kSeed; }
    }
    growlPhase = 0.0f;
    rng = kRngSeed;
}

void Brazen::allNotesOff() {
    for (auto &v : voices) {
        if (!v.used) continue;
        v.gate = false;
        v.amp.release();
    }
}

Brazen::Voice *Brazen::allocate() {
    for (auto &v : voices) if (!v.used) return &v;
    Voice *best = nullptr;
    for (auto &v : voices) {
        if (v.gate) continue;
        if (best == nullptr || v.age < best->age) best = &v;
    }
    if (best != nullptr) return best;
    for (auto &v : voices) if (best == nullptr || v.age < best->age) best = &v;
    return best;
}

void Brazen::startVoice(Voice &v, uint8_t note, uint8_t velocity) {
    const float glide = paramOf(Glide);
    const bool gliding = glide > 0.001f && v.used;
    v.glideFrom = gliding ? v.freq : mtof(static_cast<float>(note));
    v.glidePos = gliding ? 0.0f : 1.0f;
    v.freq = v.glideFrom;
    v.used = true;
    v.gate = true;
    v.note = note;
    v.bend = 0.0f;
    v.pressure = v.timbre = -1.0f;
    v.velocity = static_cast<float>(velocity) / 127.0f;
    v.age = ++ageCounter;
    v.vibratoPhase = 0.0f;
    v.vibratoLeft = paramOf(VibratoDelay);

    const int32_t players = std::clamp(steppedOf(Players), 1, kPlayers);
    const float spread = paramOf(Spread);
    const float scatter = paramOf(Scatter) * 0.001f * sampleRate;
    for (int32_t i = 0; i < kPlayers; ++i) {
        Player &p = v.players[i];
        p.rng = rng = rng * 1664525u + 1013904223u;
        const float pos = players == 1 ? 0.0f : (static_cast<float>(i) / (players - 1) * 2.0f - 1.0f);
        // Each player is out by their own amount, and none of them is
        // exactly on the note - which is what a section is.
        p.home = pos * spread * 0.5f + (nextRandom() * 2.0f - 1.0f) * spread * 0.5f;
        p.offsetCents = p.home;
        p.walk = 0.0f;
        p.delayLeft = i == 0 ? 0.0f : nextRandom() * scatter;
        p.breath = 1.0f - nextRandom() * 0.2f;
        p.pan = pos;
        if (!gliding) {
            p.bore.clear();
            // The tongue. Not air poured into the tube - the tube grows its
            // own wave, as it always did - but the lips leant on while it
            // does, so a low note takes about as long to speak as a high one
            // instead of sixteen times as long. Needs the note and a solved
            // loop first, so the frequency goes in here and `tongue` is
            // called after the tune in render.
            p.bore.setFrequency(mtof(static_cast<float>(note)));
            p.tongue = true;
        }
    }
    v.amp.retrigger();
}

void Brazen::noteOn(uint8_t note, uint8_t velocity) {
    Voice *v = steppedOf(Mono) != 0 ? &voices[0] : allocate();
    if (v == nullptr) return;
    startVoice(*v, note, velocity);
}

void Brazen::noteOff(uint8_t note) {
    for (auto &v : voices) {
        if (v.used && v.gate && v.note == note) {
            v.gate = false;
            v.amp.release();
        }
    }
}

void Brazen::controlChange(uint8_t cc, uint8_t value) {
    if (cc == 1) modWheel = static_cast<float>(value) / 127.0f;
}
void Brazen::channelPressure(uint8_t value) { pressure = static_cast<float>(value) / 127.0f; }
void Brazen::pitchBend(int16_t value14) { bend = static_cast<float>(value14) / 8192.0f; }
void Brazen::onBlock(int64_t, int64_t, float) {}

float Brazen::sectionSpreadCents() const { return lastSpreadCents; }

bool Brazen::render(float *L, float *R, int32_t frames) {
    params_.tick();
    for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;

    const float dt = 1.0f / sampleRate;
    const int32_t players = std::clamp(steppedOf(Players), 1, kPlayers);
    const float size = paramOf(Size);
    // A wide slow tube sends back nearly everything and only the low end
    // of it; a narrow bright one loses more and lets more out. The tube
    // has to be close to lossless or the harmonics have no gain to live on
    // and the horn plays a flute's note instead of a trumpet's.
    const float bellCut = paramOf(Bell) * (0.15f + size * 0.55f);
    const float reflect = 0.99f - size * 0.04f;
    const float loss = paramOf(Loss);
    const float tension = paramOf(Tension);
    const float lipDamp = paramOf(LipDamp);
    const float mouth = paramOf(Pressure);
    const float breathNoise = paramOf(Breath);
    const float bite = paramOf(Bite);
    const float brass = paramOf(Brassiness);
    const float growl = paramOf(Growl), growlRate = paramOf(GrowlRate);
    const float lock = paramOf(Lock), drift = paramOf(Drift);
    const float width = paramOf(Width);
    const float vibrato = paramOf(Vibrato), vibratoRate = paramOf(VibratoRate);
    const float glide = paramOf(Glide);
    const float bendMul = std::pow(2.0f, bend * paramOf(BendRange) / 12.0f);
    const float tune = std::pow(2.0f, (paramOf(Octave) * 12.0f + paramOf(Transpose) + paramOf(Fine) * 0.01f) / 12.0f);
    const float velAmount = paramOf(VelocityAmount);
    const int32_t mute = steppedOf(MuteKind);
    const float muteTone = paramOf(MuteTone);
    // A mute passes a band and swallows the rest. Tone slides the whole
    // window up and down, because that is what a player does with it.
    const float muteShift = std::pow(2.0f, muteTone * 2.0f - 1.0f);
    const float muteHpHz = (mute == Straight ? 700.0f : mute == Cup ? 220.0f : 1100.0f) * muteShift;
    const float muteLpHz = (mute == Straight ? 6000.0f : mute == Cup ? 2000.0f : 3600.0f) * muteShift;
    const float muteGain = mute == Straight ? 1.6f : mute == Cup ? 1.3f : 1.9f;
    const float muteHp = std::min(1.0f, kTwoPi * muteHpHz / sampleRate);
    const float muteLp = std::min(1.0f, kTwoPi * muteLpHz / sampleRate);
    const float drive = paramOf(Drive), volume = paramOf(Volume);
    const float panKnob = paramOf(Pan);
    const float panL = std::cos((panKnob + 1.0f) * 0.25f * 3.14159265f);
    const float panR = std::sin((panKnob + 1.0f) * 0.25f * 3.14159265f);

    growlPhase += growlRate * dt * static_cast<float>(frames);
    while (growlPhase >= 1.0f) growlPhase -= 1.0f;

    for (auto &v : voices) {
        if (!v.used) continue;
        v.amp.set(0.0f, paramOf(Attack), paramOf(Decay), paramOf(Sustain), paramOf(Release), false);
        v.filterL.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType), dsp::MultiFilter::Clean, 0.0f);
        v.filterR.set(paramOf(Cutoff), paramOf(Resonance), steppedOf(FilterType), dsp::MultiFilter::Clean, 0.0f);

        if (v.glidePos < 1.0f) {
            v.glidePos = std::min(1.0f, v.glidePos + (glide > 0.001f ? dt * frames / glide : 1.0f));
            v.freq = v.glideFrom + (mtof(static_cast<float>(v.note)) - v.glideFrom) * v.glidePos;
        } else {
            v.freq = mtof(static_cast<float>(v.note));
        }

        // --- the section listens to itself --------------------------------
        // Each player has their own idea of where the note is, wanders off
        // it slowly, and is pulled back toward wherever everybody else has
        // got to. At lock 0 nobody is listening and the section is as wide
        // as its players are stubborn; at 1 they give way to the average
        // within a breath and the four of them are one horn. Everything
        // between is what a section sounds like on the way into tune, and
        // it is the one thing a sampled brass library cannot do, because
        // samples cannot hear each other.
        float mean = 0.0f;
        for (int32_t i = 0; i < players; ++i) mean += v.players[i].offsetCents;
        mean /= static_cast<float>(players);
        const float rate = std::min(0.5f, static_cast<float>(frames) / (0.08f * sampleRate));
        float spreadNow = 0.0f;
        for (int32_t i = 0; i < players; ++i) {
            Player &p = v.players[i];
            p.rng = p.rng * 1664525u + 1013904223u;
            const float r = static_cast<float>(p.rng >> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f;
            p.walk = std::clamp(p.walk + r * drift * 0.02f * rate * 8.0f, -drift, drift);
            const float ownPull = (p.home + p.walk) - p.offsetCents;
            const float sectionPull = (mean - p.offsetCents) * lock * 8.0f;
            p.offsetCents += (ownPull + sectionPull) * rate;
            spreadNow = std::max(spreadNow, std::fabs(p.offsetCents - mean));
        }
        lastSpreadCents = spreadNow * 2.0f;

        const float vibratoDepth = v.vibratoLeft > 0.0f ? 0.0f : vibrato * (0.35f + modWheel * 0.65f);
        if (v.vibratoLeft > 0.0f) v.vibratoLeft -= dt * frames;
        v.vibratoPhase += vibratoRate * dt * frames;
        while (v.vibratoPhase >= 1.0f) v.vibratoPhase -= 1.0f;
        const float vib = std::sin(v.vibratoPhase * kTwoPi) * vibratoDepth;

        const float vel = 1.0f - velAmount + velAmount * v.velocity;
        const float growlNow = growl * (0.5f + 0.5f * std::sin(growlPhase * kTwoPi));
        const float growlAm = 1.0f - growlNow * 0.35f;
        const float env0 = v.amp.value();

        // Everything the horn is made of, once a block. tune() is trigonometry
        // and the note does not change inside a block worth hearing.
        for (int32_t pi = 0; pi < players; ++pi) {
            Player &p = v.players[pi];
            const float hz = v.freq * tune * bendMul * noteBendMul(v) *
                             std::pow(2.0f, (p.offsetCents + vib) / 1200.0f);
            const float prs = v.pressure >= 0.0f ? v.pressure : pressure;
            const float push = mouth * env0 * vel * p.breath * (1.0f - growlNow * 0.5f) + prs * 0.3f;
            p.bore.setFrequency(hz);
            // Lips tighten as the player leans in, which is most of what an
            // attack is: the note arrives before the tone does.
            // The growl is a player humming against their own note: it
            // leans on the lips and on the air at once, which is why it
            // buzzes rather than simply wobbling.
            // Slide leans on the lips, which is what a player's embouchure does.
            const float slide = v.timbre >= 0.0f ? v.timbre : 0.0f;
            p.bore.setLips(tension * (0.94f + env0 * bite * 0.12f) * (1.0f + growlNow * 0.06f) *
                               (1.0f + slide * paramOf(MpeTimbre) * 0.35f),
                           lipDamp);
            p.bore.setLipGain(0.7f + bite * 0.6f);
            p.bore.setBell(reflect, bellCut);
            // Brassiness closes the lips as well as bending the line: a
            // narrow pulse is half of why a loud horn is a bright one.
            p.bore.setRest(0.35f);
            p.bore.setBite(bite * 0.9f + brass * 0.5f);
            // The steepening is a property of how loud the wave is, so it
            // follows the player and not the envelope generator.
            p.bore.setBrass(brass * std::min(1.0f, push * 1.4f));
            p.bore.setLoss(loss);
            p.bore.setPressure(push);
            p.bore.tune();
            p.push = push;
            if (p.tongue) {
                // After the loop has been solved for this note: `tongue`
                // sizes the lift from the gain the solve arrived at.
                p.bore.tune();
                p.bore.tongue();
                p.tongue = false;
            }
        }

        for (int32_t i = 0; i < frames; ++i) {
            const float env = v.amp.next();
            if (env <= 0.0000005f && !v.gate) { v.used = false; break; }
            float l = 0.0f, r = 0.0f;
            for (int32_t pi = 0; pi < players; ++pi) {
                Player &p = v.players[pi];
                if (p.delayLeft > 0.0f) { p.delayLeft -= 1.0f; continue; }
                rng = rng * 1664525u + 1013904223u;
                const float hiss = (static_cast<float>(rng >> 8) * (1.0f / 16777216.0f) * 2.0f - 1.0f) * breathNoise * 0.25f;
                const float s = p.bore.step(p.push, hiss * p.push) * growlAm;
                const float pan = std::clamp(p.pan * width, -1.0f, 1.0f);
                const float angle = (pan + 1.0f) * 0.25f * 3.14159265f;
                l += s * std::cos(angle);
                r += s * std::sin(angle);
            }
            // The mute. A cup over the bell is not a volume knob: it is a
            // box with its own resonance, letting a band through and
            // sending the rest back down the tube. Straight is bright and
            // thin, cup is dark and close, harmon is the nasal one.
            if (mute != Open) {
                v.muteLpL += (l - v.muteLpL) * muteHp;
                v.muteLpR += (r - v.muteLpR) * muteHp;
                l -= v.muteLpL;
                r -= v.muteLpR;
                v.muteHpL += (l - v.muteHpL) * muteLp;
                v.muteHpR += (r - v.muteHpR) * muteLp;
                l = v.muteHpL * muteGain;
                r = v.muteHpR * muteGain;
            }
            L[i] += v.filterL.process(l);
            R[i] += v.filterR.process(r);
        }
    }

    for (int32_t i = 0; i < frames; ++i) {
        float l = L[i] * volume * 2.0f, r = R[i] * volume * 2.0f;
        if (drive > 0.0001f) {
            const float k = 1.0f + drive * 8.0f;
            l = dsp::fastTanh(l * k) / std::sqrt(k);
            r = dsp::fastTanh(r * k) / std::sqrt(k);
        }
        L[i] = l * panL * 1.4142f;
        R[i] = r * panR * 1.4142f;
    }
    return true;
}

void Brazen::noteBend(uint8_t note, float semitones) {
    if (Voice *v = voiceForNote(voices, note)) v->bend = semitones;
}

void Brazen::notePressure(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->pressure = static_cast<float>(value) / 127.0f;
}

void Brazen::noteTimbre(uint8_t note, uint8_t value) {
    if (Voice *v = voiceForNote(voices, note)) v->timbre = static_cast<float>(value) / 127.0f;
}

} // namespace acidulous::machine
