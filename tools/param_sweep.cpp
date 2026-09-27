// Every knob of every machine and effect at its ends, and at random.
//
// Each machine is played with each parameter at 0 and at 1 on its own, then
// with every parameter random, with notes, the mod wheel, pressure and bend.
// Each effect gets noise and a sine the same way. The output must stay a
// number and below +36 dB. A NaN, an infinity or a feedback that runs away
// is what a divide by zero, a log of a negative or a wrapped buffer index
// becomes, and a knob at its end is where those usually hide.
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/core/SampleMap.h>
#include <engine/core/Take.h>
#include <engine/core/Utterance.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/machine/forage/Forage.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 220;  // about 0.3 s per run
constexpr int32_t kRandomRuns = 24;
constexpr float kLimit = 64.0f;   // +36 dB

int checks = 0, failures = 0;

/** What went wrong in one run, or "" if nothing did. */
struct Verdict {
    bool nan = false;
    float peak = 0.0f;
    std::string what() const {
        if (nan) return "not a number";
        if (peak > kLimit) return "peak " + std::to_string(peak);
        return "";
    }
};

void look(const float *L, const float *R, int32_t n, Verdict &v) {
    for (int32_t i = 0; i < n; ++i) {
        if (!std::isfinite(L[i]) || !std::isfinite(R[i])) { v.nan = true; return; }
        v.peak = std::fmax(v.peak, std::fmax(std::fabs(L[i]), std::fabs(R[i])));
    }
}

Verdict play(Machine *m) {
    Verdict v;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks && !v.nan; ++b) {
        if (b == 0) for (uint8_t n : {36, 48, 60, 64, 67}) m->noteOn(n, 110);
        if (b % 12 == 0) m->noteOn(static_cast<uint8_t>(30 + (b * 7) % 70), static_cast<uint8_t>(1 + (b * 13) % 127));
        if (b % 12 == 6) m->noteOff(static_cast<uint8_t>(30 + ((b - 6) * 7) % 70));
        if (b == 150) for (uint8_t n : {36, 48, 60, 64, 67}) m->noteOff(n);
        if (b % 9 == 0) m->controlChange(1, static_cast<uint8_t>((b * 5) % 128));
        if (b % 13 == 0) m->channelPressure(static_cast<uint8_t>((b * 3) % 128));
        if (b % 17 == 0) m->pitchBend(static_cast<int16_t>(((b * 211) % 16384) - 8192));
        const int64_t tick = static_cast<int64_t>(b) * 4;
        m->onBlock(tick, tick + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        look(L, stereo ? R : L, kBlock, v);
    }
    return v;
}

/**
 * Notes at their ends: every key at once, the lowest and highest, the
 * softest and hardest, one key hammered without letting go, note-offs for
 * notes never played, and bend and pressure at their limits.
 */
Verdict playExtremes(Machine *m) {
    Verdict v;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks && !v.nan; ++b) {
        if (b == 0) for (int n = 0; n < 128; ++n) m->noteOn(static_cast<uint8_t>(n), static_cast<uint8_t>(1 + n % 127));
        if (b == 30) for (int n = 0; n < 128; ++n) m->noteOff(static_cast<uint8_t>(n));
        if (b >= 40 && b < 120 && b % 2 == 0) m->noteOn(b % 4 == 0 ? 0 : 127, b % 8 == 0 ? 1 : 127);
        if (b >= 60 && b < 140) m->noteOn(60, 127); // the same key, never let go
        if (b == 140) m->noteOff(60);
        if (b >= 150 && b < 160) m->noteOff(static_cast<uint8_t>(b)); // never played
        if (b == 100) { m->pitchBend(8191); m->channelPressure(127); m->controlChange(1, 127); }
        if (b == 130) { m->pitchBend(-8192); m->channelPressure(0); m->controlChange(1, 0); }
        const int64_t tick = static_cast<int64_t>(b) * 4;
        m->onBlock(tick, tick + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        const bool stereo = m->render(L, R, kBlock);
        look(L, stereo ? R : L, kBlock, v);
    }
    return v;
}

Verdict play(Effect *e) {
    Verdict v;
    float L[kBlock], R[kBlock];
    uint32_t n = 0x2468ace1u;
    for (int32_t b = 0; b < kBlocks && !v.nan; ++b) {
        for (int32_t i = 0; i < kBlock; ++i) {
            n = n * 1664525u + 1013904223u;
            const float noise = static_cast<float>(n >> 9) * (2.0f / 8388608.0f) - 1.0f;
            const float sine = std::sin(0.05f * static_cast<float>(b * kBlock + i));
            // Loud, then silent: a tail and a denormal both get their turn.
            const float gain = b < 150 ? 1.0f : 0.0f;
            L[i] = (b % 40 < 20 ? noise : sine) * gain;
            R[i] = -L[i] * 0.7f;
        }
        const int64_t tick = static_cast<int64_t>(b) * 4;
        e->onBlock(tick, tick + 4, 120.0f);
        e->run(L, R, kBlock, true);
        look(L, R, kBlock, v);
    }
    return v;
}

void report(const std::string &who, const std::string &setting, const Verdict &v) {
    ++checks;
    const std::string bad = v.what();
    if (bad.empty()) return;
    ++failures;
    std::printf("  FAIL %-12s %-34s %s\n", who.c_str(), setting.c_str(), bad.c_str());
}

/** Sets every parameter from [values], or to its default where it's empty. */
template <typename Unit>
void setAll(Unit *u, const std::vector<float> &values) {
    ParamSet &p = u->params();
    for (int32_t k = 0; k < p.size(); ++k) p.set(k, values[static_cast<size_t>(k)]);
    p.jumpAll();
}

template <typename Unit>
std::vector<float> defaultsOf(Unit *u) {
    std::vector<float> d;
    for (int32_t k = 0; k < u->params().size(); ++k) d.push_back(u->params().normalized(k));
    return d;
}

/** The same sweep for a machine or an effect. [fresh] puts one back to silence. */
template <typename Unit, typename Fresh>
void sweep(const std::string &name, Unit *u, Fresh fresh) {
    const std::vector<float> defaults = defaultsOf(u);
    fresh();
    setAll(u, defaults);
    report(name, "defaults", play(u));
    for (int32_t k = 0; k < u->params().size(); ++k) {
        for (float end : {0.0f, 1.0f}) {
            std::vector<float> values = defaults;
            values[static_cast<size_t>(k)] = end;
            fresh();
            setAll(u, values);
            report(name, std::string(u->params().def(k).name) + (end == 0.0f ? " = 0" : " = 1"), play(u));
        }
    }
    uint32_t r = 0x9e3779b9u ^ static_cast<uint32_t>(name.size() * 2654435761u);
    for (int32_t run = 0; run < kRandomRuns; ++run) {
        std::vector<float> values(defaults.size());
        for (auto &x : values) {
            r = r * 1664525u + 1013904223u;
            x = static_cast<float>(r >> 8) / 16777216.0f;
        }
        fresh();
        setAll(u, values);
        report(name, "random set " + std::to_string(run), play(u));
    }
}

/**
 * What a machine needs mounted before it plays anything, kept alive while it
 * plays. The sample machines are silent without it, and the reads through a
 * sample are where an index goes past the end.
 */
struct Dressing {
    std::unique_ptr<machine::cumulus::CloudSet> cloud;
    std::vector<std::unique_ptr<SampleData>> pads;
    std::unique_ptr<audio::Take> take;
    std::unique_ptr<audio::Utterance> voice;
    std::unique_ptr<SampleMap> map;
};

/** [seconds] of a tone that decays, at [hz]. Odd lengths, so nothing lines up with a block. */
std::vector<float> tone(float seconds, float hz, float decay) {
    std::vector<float> v(static_cast<size_t>(seconds * kRate) + 37);
    for (size_t i = 0; i < v.size(); ++i) {
        const float t = static_cast<float>(i) / kRate;
        v[i] = 0.6f * std::sin(6.2831853f * hz * t) * std::exp(-t * decay);
    }
    return v;
}

Dressing dress(const char *name, Machine *m) {
    Dressing d;
    if (std::strcmp(name, "Cumulus") == 0) {
        auto *c = static_cast<machine::Cumulus *>(m);
        d.cloud = machine::cumulus::buildCloud(c->spec(), kRate);
        m->swapObject(0, d.cloud.get());
    } else if (std::strcmp(name, "Forage") == 0) {
        for (int32_t pad = 0; pad <= machine::Forage::kSharedSlot; ++pad) {
            auto s = std::make_unique<SampleData>();
            s->left = tone(0.05f + 0.07f * static_cast<float>(pad), 110.0f + 30.0f * static_cast<float>(pad), 6.0f);
            s->frames = static_cast<int32_t>(s->left.size());
            if (pad % 2 == 1) { s->right = s->left; s->stereo = true; }
            s->measure();
            m->swapObject(pad, s.get());
            d.pads.push_back(std::move(s));
        }
    } else if (std::strcmp(name, "Dice") == 0 || std::strcmp(name, "Pollen") == 0) {
        d.take = std::make_unique<audio::Take>();
        auto &t = *d.take;
        t.left.assign(static_cast<size_t>(kRate * 4) + 101, 0.0f);
        for (size_t at = 0; at < t.left.size(); at += 12000) {
            const auto hit = tone(0.1f, 180.0f, 30.0f);
            for (size_t i = 0; i < hit.size() && at + i < t.left.size(); ++i) t.left[at + i] = hit[i];
        }
        t.right = t.left;
        t.frames = static_cast<int32_t>(t.left.size());
        t.detect(kRate);
        t.bars = 2.0f;
        m->swapObject(0, d.take.get());
    } else if (std::strcmp(name, "Molt") == 0) {
        d.voice = std::make_unique<audio::Utterance>();
        auto v = tone(1.5f, 180.0f, 0.5f);
        for (size_t i = 0; i < v.size(); ++i) v[i] += 0.3f * std::sin(6.2831853f * 540.0f * static_cast<float>(i) / kRate);
        d.voice->mono = v;
        d.voice->analyse(kRate);
        m->swapObject(0, d.voice.get());
    } else if (std::strcmp(name, "Mosaic") == 0) {
        d.map = std::make_unique<SampleMap>();
        for (int k = 0; k < 2; ++k) {
            SampleData s;
            s.left = tone(0.4f + 0.3f * k, 220.0f, 1.0f);
            s.frames = static_cast<int32_t>(s.left.size());
            s.rate = k == 0 ? kRate : 22050; // a multisample keeps its own rate
            s.measure();
            d.map->samples.push_back(std::move(s));
            MapZone z;
            z.sample = k;
            z.lowKey = k == 0 ? 0 : 64;
            z.highKey = k == 0 ? 63 : 127;
            if (k == 0) { z.loopStart = 1000; z.loopEnd = d.map->samples[0].frames - 1; }
            d.map->zones.push_back(z);
        }
        m->swapObject(0, d.map.get());
    }
    return d;
}

} // namespace

int main() {
    std::printf("param_sweep: every knob at its ends and at random\n\n");
    for (int32_t i = 0; i < MachineRegistry::count(); ++i) {
        const char *name = MachineRegistry::name(i);
        std::unique_ptr<Machine> m(MachineRegistry::create(name));
        if (!m) continue;
        m->prepare(kRate);
        const Dressing dressed = dress(name, m.get());
        const int before = failures;
        const std::vector<float> defaults = defaultsOf(m.get());
        sweep(name, m.get(), [&] { m->reset(); });
        // The notes at their ends, with the knobs back at their defaults.
        m->reset();
        setAll(m.get(), defaults);
        report(name, "notes at their ends", playExtremes(m.get()));
        std::printf("  %s %s\n", failures == before ? "ok  " : "    ", name);
    }
    for (int32_t i = 0; i < EffectRegistry::count(); ++i) {
        const char *name = EffectRegistry::name(i);
        std::unique_ptr<Effect> e(EffectRegistry::create(name));
        if (!e) continue;
        e->prepare(kRate);
        const int before = failures;
        sweep(name, e.get(), [&] { e->reset(); });
        std::printf("  %s %s\n", failures == before ? "ok  " : "    ", name);
    }
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
