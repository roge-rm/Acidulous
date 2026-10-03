#include "Hammer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <engine/core/Settings.h>
#include <engine/machine/Voices.h>

namespace acidulous::machine {
using dsp::clampf;

namespace {

/** Brings Init to the house level (audition: loudness -30, as the other machines' banks). */
constexpr float kHouse = 0.230f;
/** The hammer's speed at the softest and hardest velocities, m/s. */
constexpr float kSlowest = 0.3f, kFastest = 6.0f;
/** How long the gain and the dampers take to move: 5 ms and 20 ms. */
constexpr float kGainRamp = 0.005f, kDamperTime = 0.02f;
/** The phantom partials' level at the default tension, and their high-pass (below them, only the difference tones). */
constexpr float kPhantom = 0.0f;
constexpr float kPhantomPole = 0.98f;
/** The knock's level against the note's, before each key's own (Keys.h). */
constexpr float kKnock = 0.1f;
/** A voice let go and this quiet (on its slow follower) for a few blocks is free. */
constexpr float kSilent = 2e-5f;

} // namespace

Hammer::Hammer() { initParams(); }

const ParamDef *Hammer::paramDefs(int32_t &count) const {
    static const ParamDef defs[Count] = {
        // grand, upright, honky, fortepiano, prepared, electric grand, tine,
        // reed, tangent, celesta, toy, dulcimer, cimbalom.
        {"model", 0.0f, 12.0f, 0.0f, Curve::Stepped, 13, ""},
        {"size", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"age", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"seed", 0.0f, 7.0f, 0.0f, Curve::Stepped, 8, ""},

        // The felt: softer to harder, by key, heavier or lighter, where it strikes.
        {"hardness", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"hardkey", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"weight", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"position", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"felt", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"tacks", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"velocity", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},

        // How long the strings ring, as a multiple of the measured times
        // (4^x), and how fast the top goes against the rest.
        {"sustain", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"sustainkey", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"tone", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"tonekey", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"stiffness", 0.0f, 2.0f, 1.0f, Curve::Linear, 0, ""},
        {"stretch", 0.0f, 2.0f, 1.0f, Curve::Linear, 0, ""},
        {"unison", 0.0f, 3.0f, 1.0f, Curve::Linear, 0, ""},
        // auto, 1, 2, 3.
        {"strings", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"couple", 0.0f, 2.0f, 1.0f, Curve::Linear, 0, ""},
        {"polar", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        {"tension", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        {"clang", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},

        {"dampers", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"damp time", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"pedal at", 0.0f, 1.0f, 0.35f, Curve::Linear, 0, ""},
        {"pedal span", 0.0f, 1.0f, 0.4f, Curve::Linear, 0, ""},
        {"una corda", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"sympathy", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"duplex", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"noises", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},

        {"board", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},
        {"lid", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},
        {"tail", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // player, audience, close, room.
        {"mic", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"width", 0.0f, 1.0f, 0.7f, Curve::Linear, 0, ""},

        {"pickup", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        {"offset", -1.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        {"tonebar", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},
        // upper, lower, both, both reversed.
        {"pickups", 0.0f, 3.0f, 0.0f, Curve::Stepped, 4, ""},
        {"mute", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"drive", 0.0f, 1.0f, 0.2f, Curve::Linear, 0, ""},
        {"tremolo", 0.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"tremrate", 0.5f, 10.0f, 4.5f, Curve::Exponential, 0, "Hz"},
        // free, then the tempo-locked note values.
        {"tremsync", 0.0f, 17.0f, 0.0f, Curve::Stepped, 18, ""},
        {"tremwide", 0.0f, 1.0f, 1.0f, Curve::Linear, 0, ""},

        // none, rubber, screw, bolt, paper, mixed.
        {"prep", 0.0f, 5.0f, 0.0f, Curve::Stepped, 6, ""},
        // all, white, black, low, high, random.
        {"prepkeys", 0.0f, 5.0f, 0.0f, Curve::Stepped, 6, ""},
        {"prep at", 0.0f, 1.0f, 0.3f, Curve::Linear, 0, ""},
        {"prepamt", 0.0f, 1.0f, 0.5f, Curve::Linear, 0, ""},

        // auto follows the quality setting; full is full detail whatever it says.
        {"detail", 0.0f, 1.0f, 0.0f, Curve::Stepped, 2, ""},
        // 4, 6, 8, 10, 12, 16, 20, 24, 32 at most.
        {"voices", 0.0f, 8.0f, 6.0f, Curve::Stepped, 9, ""},
        {"volume", 0.0f, 1.5f, 1.0f, Curve::Linear, 0, ""},
        {"pan", -1.0f, 1.0f, 0.0f, Curve::Linear, 0, ""},
        {"bendrange", 0.0f, 24.0f, 2.0f, Curve::Stepped, 25, ""},
        {"octave", -3.0f, 3.0f, 0.0f, Curve::Stepped, 7, ""},
        {"transpose", -12.0f, 12.0f, 0.0f, Curve::Stepped, 25, ""},
        {"fine", -100.0f, 100.0f, 0.0f, Curve::Linear, 0, "cents"},
    };
    count = Count;
    return defs;
}

void Hammer::prepare(int32_t sr) {
    sampleRate = static_cast<float>(sr);
    for (int k = 0; k < kKeys; ++k) keys[k] = hammer::grandKey(clampf(static_cast<float>(k), 21.0f, 108.0f));
    refreshKeys();
    for (Voice &v : voices) v.course.prepare(sampleRate);
    board.prepare(sampleRate);
    reset();
}

void Hammer::refreshKeys() {
    for (int k = 0; k < kKeys; ++k) {
        stiffness[k] = hammer::Felt::stiffnessFor(keys[k].mass, keys[k].exponent, keys[k].contact);
    }
    for (Voice &v : voices) v.designed = false;
}

void Hammer::reset() {
    for (Voice &v : voices) {
        v.used = v.held = v.designed = false;
        v.course.clear();
        v.felt = hammer::Felt();
        v.damp = v.dampTarget = 0.0f;
        v.gain = v.gainTarget = 0.0f;
        v.force = 0.0f;
        v.phantomIn = v.phantomOut = 0.0f;
        std::fill(std::begin(v.knockLine), std::end(v.knockLine), 0.0f);
        v.knockAt = v.knockLive = 0;
        v.age = 0;
        v.quietBlocks = 0;
    }
    board.clear();
    dampersUp = false;
    bend = 0.0f;
    clock = 0;
    lastContact = 0;
    quietBlocks = 0;
    asleep = true;
}

bool Hammer::fullDetail() const { return fullQuality() || steppedTargetOf(Detail) == 1; }

int Hammer::voiceCap() const {
    static constexpr int kCaps[9] = {4, 6, 8, 10, 12, 16, 20, 24, 32};
    const int asked = kCaps[std::clamp(steppedTargetOf(Voices), 0, 8)];
    // Lean, half the voices: a piano's notes are the heaviest thing a track plays.
    return fullDetail() ? asked : std::max(4, std::min(asked, 10));
}

int Hammer::activeVoices() const {
    int n = 0;
    for (const Voice &v : voices) n += v.used ? 1 : 0;
    return n;
}

Hammer::Voice *Hammer::voiceFor(int key) {
    for (Voice &v : voices) if (v.used && v.key == key) return &v;
    int used = 0;
    for (Voice &v : voices) used += v.used ? 1 : 0;
    if (used < voiceCap()) {
        for (Voice &v : voices) if (!v.used) return &v;
    }
    // Full: the quietest let go, or failing that the quietest of all.
    Voice *best = nullptr;
    float quietest = 1e30f;
    for (int pass = 0; pass < 2 && best == nullptr; ++pass) {
        for (Voice &v : voices) {
            if (!v.used || (pass == 0 && v.held)) continue;
            const float loud = v.course.loudness() * v.gain;
            if (loud < quietest) { quietest = loud; best = &v; }
        }
    }
    if (best != nullptr) best->designed = false; // a different key's strings
    return best;
}

void Hammer::noteOn(uint8_t note, uint8_t velocity) {
    const float shifted = static_cast<float>(note) + static_cast<float>(steppedTargetOf(Transpose)) +
                          12.0f * static_cast<float>(steppedTargetOf(Octave));
    const int key = std::clamp(static_cast<int>(std::lround(shifted)), 0, kKeys - 1);
    Voice *v = voiceFor(key);
    if (v == nullptr) return;
    v->note = note;
    const float hz = noteHz(shifted + targetOf(Fine) / 100.0f) *
                     std::exp2(keys[key].stretchCents * targetOf(Stretch) / 1200.0f);
    strike(*v, key, hz, static_cast<float>(velocity) / 127.0f);
    asleep = false;
}

void Hammer::strike(Voice &v, int key, float hz, float velocity01) {
    const hammer::KeySpec &k = keys[key];
    const bool full = fullDetail();
    hammer::Course::Design d;
    d.hz = hz;
    const int asked = steppedTargetOf(Strings);
    int lanes = asked == 0 ? k.strings : asked;
    if (!full) lanes = std::min(lanes, 2);
    d.lanes = lanes;
    // Tuned a hair apart, each key its own way: the seed and the key choose.
    // Up to 1 the unison knob moves them within what rings as one; past it,
    // the piano goes out of tune, in cents.
    const float unison = targetOf(Unison);
    const float within = k.unison * std::fmin(unison, 1.0f);
    const float beyond = unison > 1.0f ? 4.0f * (unison - 1.0f) * (unison - 1.0f) : 0.0f;
    uint32_t h = static_cast<uint32_t>(key) * 2654435761u + static_cast<uint32_t>(steppedTargetOf(Seed)) * 40503u;
    auto jitter = [&]() { h = h * 1664525u + 1013904223u; return 0.7f + 0.6f * static_cast<float>(h >> 8) / 16777216.0f; };
    // Where each string sits against the others: one flat, one about in the
    // middle, one sharp, never quite evenly spaced.
    static constexpr float kShape[3][3] = {{0.0f, 0.0f, 0.0f}, {-0.5f, 0.5f, 0.0f}, {-1.0f, 0.1f, 0.93f}};
    const auto &shape = kShape[std::clamp(lanes, 1, 3) - 1];
    for (int i = 0; i < hammer::Course::kLanes; ++i) {
        const float place = i < lanes ? shape[i] : 0.0f;
        const float j = jitter();
        d.unison[i] = within * place * j;
        d.detune[i] = beyond * place * j;
    }
    // The strings' motion across the board, if there's a lane left for it:
    // tuned a little off the motion into it.
    if (lanes < (full ? hammer::Course::kLanes : 2) && targetOf(Polar) > 0.0f) {
        d.polar = targetOf(Polar);
        d.unison[lanes] = 0.4f * within * jitter();
    }
    d.couple = targetOf(Couple);
    d.B = k.B * targetOf(Stiffness);
    d.bend = k.bend;
    // The stretch is the string's own f0, as measured; its first partial sits
    // a little above that.
    d.hz = hz * std::sqrt(1.0f + d.B);
    // Stiffness stages: the long bass strings need the most to follow.
    d.stages = key < 40 ? 16 : (key < 60 ? 8 : (key < 76 ? 4 : 2));
    if (!full) d.stages = std::max(1, d.stages / 2);
    // Below C2 they need more than stages can give: sections, which follow
    // the stretch up to 5 kHz (all of it at A0 takes 40; lean, about 1.2).
    d.sections = key < 36 ? (full ? 40 : 10) : 0;
    // How long the strings ring, as a multiple of the measured times; tone
    // moves the third partial's ring against the fundamental's.
    const float ring = std::pow(4.0f, targetOf(Sustain) + targetOf(SustainKey) * (static_cast<float>(key) - 64.0f) / 44.0f);
    const float tone = std::pow(2.0f, targetOf(Tone) + targetOf(ToneKey) * (static_cast<float>(key) - 64.0f) / 44.0f);
    hammer::Course::Decay &t = d.decay;
    t.after1 = k.after1 * ring;
    t.after3 = std::fmin(k.after3 * ring * tone, t.after1);
    t.after7 = std::fmin(k.after7 * ring * tone * tone, t.after3);
    t.prompt1 = std::fmin(k.prompt1 * ring, t.after1);
    t.prompt3 = std::fmin(k.prompt3 * ring * tone, t.after3);
    t.prompt7 = std::fmin(k.prompt7 * ring * tone, t.prompt3);
    d.strike = clampf(k.strike * std::exp2(0.5f * targetOf(Position)), 0.03f, 0.3f);
    // Struck again with nothing changed, the strings keep ringing: no retune.
    const bool same = v.designed && v.key == key && std::memcmp(&v.design, &d, sizeof d) == 0;
    if (!v.used || !same) {
        if (!v.used || v.key != key) v.course.clear();
        v.course.tune(d);
        v.design = d;
        v.designed = true;
    }
    v.key = key;
    v.used = true;
    v.held = true;
    // Off the strings: the decay is the design's own, already in the course.
    v.damp = v.dampTarget = 0.0f;
    // The hammer: its felt by key, harder or softer.
    const float hard = targetOf(Hardness) + targetOf(HardKey) * (static_cast<float>(key) - 64.0f) / 44.0f;
    const float speed = kSlowest * std::pow(kFastest / kSlowest, clampf(velocity01, 0.0f, 1.0f));
    // Felt hardens the harder it's thrown, faster than its power law says:
    // its inner layers, and what the strings do at ff, which the course
    // doesn't model yet. As it is against the strings, the contact is
    // their impedance's to set and hardly changes with the blow.
    const float K = stiffness[key] * std::pow(10.0f, 1.5f * hard) * std::pow(speed / 2.0f, k.hardening);
    v.felt.set(k.mass * std::exp2(targetOf(Weight)), k.exponent, K, k.impedance, 1.5e-4f);
    // The strings never take the blow quite equally.
    const float u = d.lanes > 1 ? k.uneven : 0.0f;
    const float takes[hammer::Course::kLanes] = {1.0f + u, 1.0f - (d.lanes > 2 ? 0.6f : 1.0f) * u, 1.0f - 0.4f * u, 1.0f};
    v.felt.strike(speed, sampleRate, takes, d.lanes);
    // The level is the house velocity law; the physics only sets the colour.
    // What reaches the bridge grows with the hammer's speed and the strings'
    // impedance, so both are divided out.
    v.gainTarget = velocityGain(velocity01, targetOf(VelocityAmount)) * kHouse * k.level /
                   (speed * k.impedance * static_cast<float>(d.lanes));
    if (v.gain <= 0.0f) v.gain = v.gainTarget;
    // The knock grows more slowly than the note: against it, 4 to 11 dB
    // louder at velocity 30 than at 124 in the recordings. The key's level
    // is the strings' (the top's quiet strings are brought up to the
    // recordings' by 9x), so it's taken back out of the board's thump.
    v.knock = k.knock * std::sqrt(2.0f / speed) / k.level;
    v.knockDelay = std::clamp(static_cast<int>(v.course.strikeToBridge()), 0, kKnockLine - 1);
    const float pan = clampf(k.pan * targetOf(Width) / 0.7f, -1.0f, 1.0f);
    v.panL = std::cos((pan + 1.0f) * 0.785398f);
    v.panR = std::sin((pan + 1.0f) * 0.785398f);
    v.age = clock;
    v.quietBlocks = 0;
}

void Hammer::noteOff(uint8_t note) {
    for (Voice &v : voices) {
        if (v.used && v.held && v.note == note) {
            v.held = false;
            if (keys[v.key].damper && !dampersUp) v.dampTarget = clampf(targetOf(Dampers), 0.0f, 1.0f);
        }
    }
}

void Hammer::allNotesOff() {
    for (Voice &v : voices) {
        if (!v.used) continue;
        v.held = false;
        if (keys[v.key].damper) v.dampTarget = 1.0f;
    }
}

void Hammer::setDampers(bool lifted) {
    dampersUp = lifted;
    for (Voice &v : voices) {
        if (!v.used || v.held) continue;
        v.dampTarget = (!lifted && keys[v.key].damper) ? clampf(targetOf(Dampers), 0.0f, 1.0f) : 0.0f;
    }
}

void Hammer::pitchBend(int16_t value14) {
    bend = static_cast<float>(value14) / 8192.0f * static_cast<float>(steppedTargetOf(BendRange));
}

void Hammer::applyDamper(Voice &v) {
    const hammer::KeySpec &k = keys[v.key];
    const float e = clampf(v.damp, 0.0f, 1.0f);
    // A damper's T60 against the free string's, blended on a log scale; the
    // top goes first, as a damper only touching takes the highs.
    const float damped = k.dampedT60 * std::pow(4.0f, targetOf(DampTime));
    auto blend = [&](float free, float stopped, float bias) {
        const float t = clampf(e * bias, 0.0f, 1.0f);
        return std::exp(std::log(free) * (1.0f - t) + std::log(std::fmin(stopped, free)) * t);
    };
    const hammer::Course::Decay &free = v.design.decay;
    hammer::Course::Decay t;
    t.after1 = blend(free.after1, damped, 1.0f);
    t.after3 = blend(free.after3, damped * 0.5f, 1.3f);
    t.after7 = blend(free.after7, damped * 0.3f, 1.6f);
    t.prompt1 = blend(free.prompt1, damped, 1.0f);
    t.prompt3 = blend(free.prompt3, damped * 0.5f, 1.3f);
    t.prompt7 = blend(free.prompt7, damped * 0.3f, 1.6f);
    v.course.setDecay(t);
}

bool Hammer::render(float *L, float *R, int32_t frames) {
    params_.tick();
    if (asleep) {
        for (int32_t i = 0; i < frames; ++i) L[i] = R[i] = 0.0f;
        return true;
    }
    for (int32_t i = 0; i < frames; ++i) busL[i] = busR[i] = lowL[i] = lowR[i] = knockBus[i] = 0.0f;
    // The board's share of each blow: the knock under the note.
    const float knockLevel = kKnock * paramOf(BoardLevel) / 0.7f;
    const float ramp = 1.0f / (kGainRamp * sampleRate);
    const float phantom = kPhantom * paramOf(Tension) / 0.2f;
    const float damperStep = static_cast<float>(frames) / (kDamperTime * sampleRate);
    bool any = false;
    for (Voice &v : voices) {
        if (!v.used) continue;
        any = true;
        if (v.damp != v.dampTarget) {
            v.damp = v.damp < v.dampTarget ? std::fmin(v.dampTarget, v.damp + damperStep)
                                           : std::fmax(v.dampTarget, v.damp - damperStep);
            applyDamper(v);
        }
        for (int32_t i = 0; i < frames; ++i) {
            if (v.felt.touching() || v.force != 0.0f || v.knockLive > 0) {
                // The board radiates the change in the blow's force, not the
                // force: a soft blow's long, smooth push is a click and a thud
                // rather than only a thud. It hears it when the string has
                // carried it to the bridge.
                const float f = v.felt.touching() ? v.felt.step(v.course) : 0.0f;
                v.knockLine[v.knockAt] = (f - v.force) * v.gain * v.knock * knockLevel;
                v.force = f;
                if (!v.felt.touching() && f == 0.0f) lastContact = v.felt.contactSamples();
                knockBus[i] += v.knockLine[(v.knockAt - v.knockDelay) & (kKnockLine - 1)];
                v.knockAt = (v.knockAt + 1) & (kKnockLine - 1);
                v.knockLive = (v.felt.touching() || v.force != 0.0f) ? v.knockDelay + 1 : v.knockLive - 1;
            }
            const float s = v.course.step();
            v.gain += clampf(v.gainTarget - v.gain, -ramp * v.gainTarget, ramp * v.gainTarget);
            // The strings stretch as they swing, and their tension follows
            // the square of the motion: tones at the sums of the partials,
            // the bright metallic half of a bass note (1.4 to 2.8 kHz was
            // 10 to 16 dB short without them), growing faster than the note.
            const float sq = s * s * phantom;
            v.phantomOut = sq - v.phantomIn + kPhantomPole * v.phantomOut;
            v.phantomIn = sq;
            const float out = (s + v.phantomOut) * v.gain * keys[v.key].impedance;
            if (keys[v.key].zone == 0) {
                lowL[i] += out * v.panL;
                lowR[i] += out * v.panR;
            } else {
                busL[i] += out * v.panL;
                busR[i] += out * v.panR;
            }
        }
        // Let go and fallen silent: free.
        const float loud = v.course.loudness() * v.gain * keys[v.key].impedance;
        if (!v.held && !v.felt.touching() && loud < kSilent) {
            if (++v.quietBlocks > 8) { v.used = false; v.designed = false; }
        } else {
            v.quietBlocks = 0;
        }
    }
    board.setRoom(paramOf(Tail) / 0.5f, fullDetail());
    const float volume = paramOf(Volume);
    const float pan = paramOf(Pan);
    const float gl = std::cos((pan + 1.0f) * 0.785398f) * 1.41421f, gr = std::sin((pan + 1.0f) * 0.785398f) * 1.41421f;
    for (int32_t i = 0; i < frames; ++i) {
        float l, r;
        float bl = lowL[i], br = lowR[i];
        board.spreadBass(bl, br);
        board.step(busL[i] + bl, busR[i] + br, knockBus[i], l, r);
        L[i] = l * volume * gl;
        R[i] = r * volume * gr;
    }
    clock += frames;
    if (!any && board.loudness() < 1e-6f) {
        if (++quietBlocks > 2) { asleep = true; board.clear(); }
    } else {
        quietBlocks = 0;
    }
    return true;
}

} // namespace acidulous::machine
