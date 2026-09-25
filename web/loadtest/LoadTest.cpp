// How much of the engine a browser can run: the load test.
//
// The question the browser build turns on is not whether the engine compiles
// for WebAssembly - it does, untouched - but whether it runs fast enough there,
// and on a phone in particular. This answers it the only way that means
// anything: the demo song's tracks, built here in C++ the way render_test
// builds its fixture, played through the real Engine, and timed.
//
// The same file builds natively (main() at the bottom), so the browser's figure
// can be set beside this machine's own for the same work.
//
// Tracks 1-9 are the demo's nine - the drums, percussion, both acid lines, the
// stabs, the arp, the pad, the bleeps and the riser - with the demo's patches,
// inserts, sends, group and master chain, all playing at once: more than any
// one scene of the demo asks for. 10-16 are more of the dearer ones, to find
// where the headroom ends.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/Settings.h>
#include <engine/dsp/Denormals.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/inputmod/InputModRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cumulus/Cloud.h>
#include <engine/machine/cumulus/Cumulus.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

#include "audition_settings.h"
#include "patchbank.h"
#include "banks.gen.h" // the bank texts, written by build.sh

#ifdef __EMSCRIPTEN__
#include <emscripten/emscripten.h>
#define EXPORT extern "C" EMSCRIPTEN_KEEPALIVE
#else
#define EXPORT extern "C"
#endif

using namespace acidulous;
using namespace acidulous::seq;

namespace {

constexpr int32_t Q = kPPQN, E = Q / 2, S = Q / 4, BAR = 4 * Q;
constexpr int32_t kBars = 4;
constexpr int32_t kMaxTracks = 16;

struct Note { int32_t tick, length; int pitch, velocity; };

std::string bankText(const std::string &unit) {
    for (const auto &b : kBanks) {
        if (unit == b.unit) return b.text;
    }
    return {};
}

/** A patch from the factory banks, resolved against the unit's own table. */
bool findPatch(const std::string &unit, const std::string &name, const ParamDef *defs, int32_t count,
               audition::Resolved &out, std::set<std::string> &named) {
    std::istringstream in(bankText(unit));
    audition::Bank bank;
    std::string error;
    if (!audition::readBankFrom(in, unit, bank, error)) {
        std::fprintf(stderr, "%s\n", error.c_str());
        return false;
    }
    for (const auto &p : bank.patches) {
        if (p.name != name) continue;
        out = audition::resolve(p, defs, count);
        for (const auto &v : p.values) named.insert(v.name);
        return true;
    }
    std::fprintf(stderr, "no patch '%s' in %s\n", name.c_str(), unit.c_str());
    return false;
}

struct Built {
    Engine engine;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> clips;
    std::vector<std::unique_ptr<machine::nexus::Graph>> graphs;
    int32_t tracks = 0;

    ~Built() {
        for (int32_t r = 0; r < kRackCount; ++r) {
            for (int32_t s = 0; s < kEffectSlots; ++s) delete engine.racks[r].swapEffect(s, nullptr);
            for (int32_t s = 0; s < 3; ++s) delete engine.racks[r].swapInputMod(s, nullptr);
            if (Machine *m = engine.racks[r].currentMachine()) {
                if (std::strcmp(m->typeName(), "Cumulus") == 0) {
                    delete static_cast<machine::cumulus::CloudSet *>(m->swapObject(0, nullptr));
                }
            }
            delete engine.racks[r].swapMachine(nullptr);
        }
        for (int32_t s = 0; s < 2; ++s) delete engine.master.swapSend(s, nullptr);
        for (int32_t s = 0; s < 2; ++s) delete engine.master.swapInsert(s, nullptr);
        for (int32_t s = 0; s < 2; ++s) delete engine.master.swapGroupInsert(0, s, nullptr);
    }
};

Effect *effect(const std::string &type, const std::string &patch) {
    Effect *e = EffectRegistry::create(type.c_str());
    if (e == nullptr) return nullptr;
    e->prepare(kSampleRate);
    e->reset();
    int32_t count = 0;
    const ParamDef *defs = EffectRegistry::paramDefs(type.c_str(), count);
    audition::Resolved r;
    std::set<std::string> named;
    if (findPatch("fx." + type, patch, defs, count, r, named)) {
        for (size_t i = 0; i < r.norm.size(); ++i) e->params().set(static_cast<int32_t>(i), r.norm[i]);
    }
    e->params().jumpAll();
    return e;
}

void channel(Engine &engine, int32_t rack, Rack::ChannelParam p, float normalised) {
    ParamMessage m;
    m.rack = rack;
    m.unit = Unit::Channel;
    m.index = p;
    m.value = normalised;
    engine.pushParam(m);
}

void track(Built &b, int32_t rack, const char *machineType, const char *patch, const std::vector<Note> &notes,
           float volume, float pan, float sendReverb, float sendDelay, int32_t output = 0) {
    Engine &engine = b.engine;
    Machine *m = MachineRegistry::create(machineType);
    if (m == nullptr) return;
    m->prepare(kSampleRate);
    m->reset();
    int32_t count = 0;
    const ParamDef *defs = MachineRegistry::paramDefs(machineType, count);
    audition::Resolved r;
    std::set<std::string> named;
    if (findPatch(machineType, patch, defs, count, r, named)) {
        for (size_t i = 0; i < r.norm.size(); ++i) m->params().set(static_cast<int32_t>(i), r.norm[i]);
    }
    m->params().jumpAll();
    if (std::strcmp(machineType, "Nexus") == 0) {
        for (const auto &kv : r.settings) {
            if (kv.first != "nexus") continue;
            b.graphs.emplace_back();
            audition::mountNexusGraph(m, kv.second, kSampleRate, named, b.graphs.back());
        }
    }
    if (std::strcmp(machineType, "Cumulus") == 0) {
        auto *cum = static_cast<machine::Cumulus *>(m);
        auto set = machine::cumulus::buildCloud(cum->spec(), kSampleRate);
        delete static_cast<machine::cumulus::CloudSet *>(cum->swapObject(0, set.release()));
    }
    engine.racks[rack].swapMachine(m);

    // The channel strip, in the engine's normalised units.
    channel(engine, rack, Rack::Gain, volume / 1.5f);
    channel(engine, rack, Rack::Pan, (pan + 1.0f) / 2.0f);
    channel(engine, rack, Rack::SendReverb, sendReverb);
    channel(engine, rack, Rack::SendDelay, sendDelay);
    channel(engine, rack, Rack::Output, static_cast<float>(output) / 16.0f);

    auto c = std::make_shared<Clip>();
    c->rev = rack + 1;
    c->bars = kBars;
    c->ticksPerBar = BAR;
    for (const Note &n : notes) {
        ClipNote cn{};
        cn.tick = n.tick;
        cn.length = std::max(1, n.length);
        cn.pitch = static_cast<uint8_t>(n.pitch);
        cn.velocity = static_cast<uint8_t>(n.velocity);
        c->notes.push_back(cn);
    }
    std::sort(c->notes.begin(), c->notes.end(), [](const ClipNote &a, const ClipNote &z) { return a.tick < z.tick; });
    b.clips.push_back(c);
    b.snap->setClip(rack, 0, c);
}

// --- the demo's drop, note for note where it matters ---------------------------------

std::vector<Note> drums() {
    std::vector<Note> out;
    for (int32_t bar = 0; bar < kBars; ++bar) {
        const int32_t o = bar * BAR;
        for (int beat = 0; beat < 4; ++beat) {
            out.push_back({o + beat * Q, 60, 36, 120});
            if (beat % 2 == 1) out.push_back({o + beat * Q, 60, 38, 100});
            const int hat[4] = {58, 36, 92, 40};
            for (int s = 0; s < 4; ++s) out.push_back({o + beat * Q + s * S, 30, 43, hat[s]});
            out.push_back({o + beat * Q + E, 50, 44, 70});
        }
        out.push_back({o + 2 * Q + E, 60, 46, 60});
    }
    out.push_back({0, 120, 45, 110});
    return out;
}

std::vector<Note> percussion() {
    std::vector<Note> out;
    for (int32_t bar = 0; bar < kBars; ++bar) {
        const int32_t o = bar * BAR;
        out.push_back({o, 40, 48, 90});
        out.push_back({o + Q + E, 40, 48, 84});
        out.push_back({o + 3 * Q, 40, 48, 88});
        out.push_back({o + E, 40, 47, 70});
        out.push_back({o + 2 * Q + E, 40, 47, 76});
        out.push_back({o + 3 * Q + S * 3, 30, 37, 72});
    }
    return out;
}

int pitchOf(const char *name) {
    static const int letters[7] = {9, 11, 0, 2, 4, 5, 7}; // A..G
    return 12 * (name[1] - '0' + 1) + letters[name[0] - 'A'];
}

std::vector<Note> acid(const char *const line[32]) {
    std::vector<Note> out;
    for (int32_t pair = 0; pair < kBars; pair += 2) {
        for (int i = 0; i < 32; ++i) {
            const char *step = line[i];
            if (step == nullptr) continue;
            const std::string s = step;
            const bool slide = s.find('~') != std::string::npos;
            const bool accent = s.find('!') != std::string::npos;
            out.push_back({(pair + i / 16) * BAR + (i % 16) * S, slide ? S + 24 : S - 30, pitchOf(step),
                           accent ? 122 : 82});
        }
    }
    return out;
}

const char *const kLineDrop[32] = {
    "A1!", "A2", "A2~", "C3", nullptr, "A1", "A2!", "G2~", "A2", "C3", "E3!", "A1", "C3~", "D3", "G2!", "E2",
    "A1!", "A1", "A2~", "C3~", "D3", "C3", "A1!", "A2~", "G2", nullptr, "E3!", "D3", "C3~", "A2", "G2!", "E2~",
};
const char *const kAnswer[32] = {
    nullptr, nullptr, "E3!", nullptr, nullptr, "G3~", "A3", nullptr, nullptr, nullptr, "C4!", nullptr, "A3~", "G3", nullptr, nullptr,
    nullptr, nullptr, "E3!", nullptr, "D3~", "E3", nullptr, nullptr, "G3!", nullptr, "A3~", "C4", nullptr, "A3!", nullptr, nullptr,
};

const std::vector<std::vector<int>> kProgression = {
    {57, 60, 64, 67, 71}, {53, 57, 60, 64}, {55, 59, 62, 64}, {52, 55, 59, 62}};
const std::vector<std::vector<int>> kStabs = {
    {57, 60, 64, 67}, {53, 57, 60, 64}, {55, 59, 62, 67}, {55, 59, 62, 64}};

void chord(std::vector<Note> &out, int32_t tick, int32_t length, const std::vector<int> &pitches, int velocity) {
    for (int p : pitches) out.push_back({tick, length, p, velocity});
}

std::vector<Note> stabs() {
    std::vector<Note> out;
    for (int32_t bar = 0; bar < kBars; ++bar) {
        const auto &v = kStabs[static_cast<size_t>(bar) % kStabs.size()];
        chord(out, bar * BAR + E, S, v, 96);
        chord(out, bar * BAR + 2 * Q + E, S, v, 88);
        chord(out, bar * BAR + 3 * Q + E + S, S, v, 76);
    }
    return out;
}

std::vector<Note> held(int velocity) {
    std::vector<Note> out;
    for (int32_t bar = 0; bar < kBars; ++bar) {
        chord(out, bar * BAR, BAR - 20, kProgression[static_cast<size_t>(bar) % kProgression.size()], velocity);
    }
    return out;
}

std::vector<Note> bleeps() {
    return {{0, Q, 76, 100}, {Q, E, 72, 84}, {Q + E, E, 71, 80}, {2 * Q, Q, 69, 92},
            {BAR, Q + E, 72, 96}, {BAR + Q + E, E, 69, 82}, {BAR + 2 * Q, 2 * Q, 64, 90},
            {2 * BAR, Q, 74, 100}, {2 * BAR + Q, E, 71, 84}, {2 * BAR + Q + E, E, 67, 80}, {2 * BAR + 2 * Q, Q, 71, 92},
            {3 * BAR, 2 * Q, 67, 96}};
}

std::vector<Note> riser() { return {{0, 2 * BAR - 20, 60, 100}, {2 * BAR, 2 * BAR - 20, 60, 100}}; }

std::unique_ptr<Built> gBuilt;
float gOut[128 * 2];

} // namespace

/** Builds the song with its first [tracks] tracks, stopped. */
EXPORT int lt_build(int tracks) {
    dsp::flushDenormalsOnce();
    gBuilt.reset();
    auto b = std::make_unique<Built>();
    b->tracks = std::clamp(tracks, 1, kMaxTracks);
    Engine &engine = b->engine;

    SceneInfo scene;
    scene.id = 1;
    scene.bars = kBars;
    scene.ticksPerBar = BAR;
    scene.bpmOverride = 126.0f;
    b->snap->scenes.push_back(scene);

    struct Spec {
        const char *machine, *patch;
        std::vector<Note> (*notes)();
        float volume, pan, reverb, delay;
        int32_t output;
    };
    static const auto acidDrop = [] { return acid(kLineDrop); };
    static const auto answer = [] { return acid(kAnswer); };
    static const auto arpChords = [] { return held(80); };
    static const auto padChords = [] { return held(66); };
    const Spec specs[kMaxTracks] = {
        {"Genesis", "Straight", drums, 0.84f, 0.0f, 0.06f, 0.0f, 1},
        {"Hexbeat", "Tight", percussion, 0.46f, -0.2f, 0.18f, 0.10f, 1},
        {"Reflux", "Squelch", +acidDrop, 0.76f, 0.0f, 0.06f, 0.10f, 0},
        {"Reflux", "Wasp", +answer, 0.40f, 0.35f, 0.16f, 0.20f, 0},
        {"Ratio", "Sync Stab", stabs, 0.40f, 0.2f, 0.26f, 0.20f, 0},
        {"Trinity", "Pluck Wide", +arpChords, 0.34f, -0.3f, 0.24f, 0.18f, 0},
        {"Cumulus", "Deep Wash", +padChords, 0.44f, -0.1f, 0.30f, 0.0f, 0},
        {"Nexus", "Subtractive", bleeps, 0.36f, 0.25f, 0.28f, 0.40f, 0},
        {"Trinity", "Noise Sweep", riser, 0.34f, 0.0f, 0.36f, 0.0f, 0},
        // More of the dearer ones, quieter so sixteen tracks do not simply clip.
        {"Trinity", "Pluck Wide", +arpChords, 0.2f, 0.3f, 0.2f, 0.1f, 0},
        {"Ratio", "Sync Stab", stabs, 0.2f, -0.2f, 0.2f, 0.1f, 0},
        {"Cumulus", "Deep Wash", +padChords, 0.2f, 0.1f, 0.2f, 0.0f, 0},
        {"Reflux", "Squelch", +acidDrop, 0.2f, -0.3f, 0.1f, 0.1f, 0},
        {"Nexus", "Subtractive", bleeps, 0.2f, -0.25f, 0.2f, 0.2f, 0},
        {"Trinity", "Pluck Wide", +arpChords, 0.2f, 0.0f, 0.2f, 0.1f, 0},
        {"Ratio", "Sync Stab", stabs, 0.2f, 0.4f, 0.2f, 0.1f, 0},
    };
    for (int32_t r = 0; r < b->tracks; ++r) {
        const Spec &s = specs[r];
        track(*b, r, s.machine, s.patch, s.notes(), s.volume, s.pan, s.reverb, s.delay, s.output);
    }
    // The arp tracks: a held chord, turned into sixteenths on the way in.
    for (int32_t r : {5, 9, 14}) {
        if (r >= b->tracks) continue;
        InputMod *arp = InputModRegistry::create("Arp");
        if (arp != nullptr) { arp->reset(); arp->params().jumpAll(); }
        engine.racks[r].swapInputMod(2, arp);
    }
    // Acid gets its distortion and delay, the answer its ping-pong.
    if (b->tracks > 2) {
        engine.racks[2].swapEffect(0, effect("Distortion", "Warm"));
        engine.racks[2].swapEffect(1, effect("Delay", "Eighth Sync"));
    }
    if (b->tracks > 3) engine.racks[3].swapEffect(0, effect("Delay", "Ping Pong"));
    if (b->tracks > 12) {
        engine.racks[12].swapEffect(0, effect("Distortion", "Warm"));
        engine.racks[12].swapEffect(1, effect("Delay", "Eighth Sync"));
    }
    // The master: two sends, two inserts, and the rhythm group's glue.
    delete engine.master.swapSend(0, effect("Reverb", "Room"));
    delete engine.master.swapSend(1, effect("Delay", "Eighth Sync"));
    delete engine.master.swapInsert(0, effect("Eq", "Warmer"));
    delete engine.master.swapInsert(1, effect("Compressor", "Bus"));
    delete engine.master.swapGroupInsert(0, 0, effect("Compressor", "Glue"));
    engine.master.params().set(MasterBus::Volume, 0.64f / 1.5f);
    engine.master.params().jumpAll();

    b->snap->rackCount = b->tracks;
    engine.scheduler.swapSnapshot(b->snap.get());
    engine.panicFlag.store(true, std::memory_order_release);
    engine.renderBlock(nullptr, gOut);
    gBuilt = std::move(b);
    return gBuilt->tracks;
}

EXPORT void lt_play(int play) {
    if (!gBuilt) return;
    if (play) gBuilt->engine.transport.requestPlay(0);
    else gBuilt->engine.transport.requestStop();
}

/** 128 frames, interleaved stereo: what one AudioWorklet callback wants. */
EXPORT float *lt_render128() {
    // As the app's audio thread does. On WebAssembly this does nothing - it has
    // no flush-to-zero mode - which is one of the things this test measures.
    dsp::flushDenormalsOnce();
    if (!gBuilt) {
        std::fill(std::begin(gOut), std::end(gOut), 0.0f);
        return gOut;
    }
    static_assert(128 % kBlockFrames == 0, "a callback is a whole number of blocks");
    for (int32_t at = 0; at < 128; at += kBlockFrames) gBuilt->engine.renderBlock(nullptr, gOut + at * 2);
    return gOut;
}

EXPORT int lt_tracks() { return gBuilt ? gBuilt->tracks : 0; }

#ifndef __EMSCRIPTEN__
// The native side of the comparison: the same song, timed the same way the
// browser's benchmark times it - wall clock per 128-frame callback, on a
// thread with nothing else to do.
int main(int argc, char **argv) {
    const int blocks = argc > 1 ? std::atoi(argv[1]) : 3000;
    const double budgetUs = 1e6 * 128.0 / kSampleRate;
    std::printf("tracks   mean us   p99 us   p99 %% of budget (%.0f us)\n", budgetUs);
    for (int n : {1, 4, 9, 12, 16}) {
        lt_build(n);
        lt_play(1);
        for (int i = 0; i < 200; ++i) lt_render128();
        std::vector<double> t;
        for (int i = 0; i < blocks; ++i) {
            const auto t0 = std::chrono::steady_clock::now();
            lt_render128();
            t.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count());
        }
        double mean = 0;
        for (double v : t) mean += v;
        mean /= static_cast<double>(t.size());
        std::sort(t.begin(), t.end());
        const double p99 = t[t.size() * 99 / 100];
        std::printf("%6d %9.0f %8.0f %8.0f%%\n", n, mean, p99, 100.0 * p99 / budgetUs);
    }
    return 0;
}
#endif
