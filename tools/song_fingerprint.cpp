// A fingerprint of a busy song rendered through the whole engine, to prove a
// change to how a block is rendered leaves the sound exactly as it was.
//
//   song_fingerprint [seconds] [workers] [b]
//
// "b" plays the other twelve machines instead, for a ThreadSanitizer run to
// cover every machine.
//
// PACED=1 renders as a sound card asks: four blocks every 5.33 ms, sleeping
// between, so the workers sleep and wake as they would live. It prints how
// long the callbacks took (the middle one, 1 in 100, the worst) against the
// 5.33 ms they have. The hash is the same either way.
//
// With workers, the tracks render on that many threads beside this one, woken
// every block; the hash must be the same for any number.
//
// Twelve tracks of real machines, two scenes of two bars (so the song
// crosses a scene change twice), notes on every track, automation lanes, a
// compressor ducked by the drums, and two tracks whose effects listen to each
// other (one of them hears the other a block late), and a lane on a
// parameter that notes read when they start. Prints one hash of every
// sample and the peak; run it on two builds and compare the hashes.
#include <engine/core/Constants.h>
#include <engine/effect/EffectRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <xmmintrin.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {

constexpr int32_t kBar = 4 * kPPQN;

void prepare(Machine *m) {
    m->prepare(kSampleRate);
    m->reset();
    m->params().jumpAll();
}

void prepare(Effect *e) {
    e->prepare(kSampleRate);
    e->reset();
    e->params().jumpAll();
}

void set(ParamSet &p, const char *name, float value) {
    const int32_t i = p.indexOf(name);
    if (i < 0) { std::fprintf(stderr, "no parameter %s\n", name); std::exit(1); }
    p.jump(i, p.def(i).unmap(value));
}

} // namespace

int main(int argc, char **argv) {
    _mm_setcsr(_mm_getcsr() | 0x8040);
    const float seconds = argc > 1 ? static_cast<float>(std::atof(argv[1])) : 10.0f;
    const int32_t workers = argc > 2 ? std::atoi(argv[2]) : 0;
    auto engine = std::make_unique<Engine>();
    Engine &e = *engine;
    e.setWorkers(workers);
    e.wakeFloorUs = 0;
    auto snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;

    static const char *kSetA[] = {"Genesis", "Hexbeat", "Reflux", "Reflux", "Ratio", "Trinity",
                                  "Filament", "Hammer", "Timber", "Trinity", "Manual", "Resonance"};
    static const char *kSetB[] = {"Forage", "Dice", "Cumulus", "Formulate", "Nexus", "Brazen",
                                  "Diction", "Mosaic", "Cipher", "Pollen", "Bias", "Molt"};
    const char *const *kMachines = argc > 3 && argv[3][0] == 'b' ? kSetB : kSetA;
    constexpr int32_t kTracks = 12;
    Machine *machines[kTracks];
    for (int32_t r = 0; r < kTracks; ++r) {
        Machine *m = machines[r] = MachineRegistry::create(kMachines[r]);
        prepare(m);
        delete e.racks[r].swapMachine(m);
    }
    // Rack 2's compressor ducked by the drums (rack 0).
    {
        Effect *c = EffectRegistry::create("Compressor");
        prepare(c);
        set(c->params(), "sidechain", 1.0f);
        delete e.racks[2].swapEffect(0, c);
    }
    // Racks 4 and 5 listen to each other: 4 goes first and hears 5 a block late.
    {
        Effect *g = EffectRegistry::create("Gate");
        prepare(g);
        set(g->params(), "sidechain", 6.0f);
        // Fast and with no hold, so a key a block late is heard.
        set(g->params(), "threshold", -20.0f);
        set(g->params(), "hold", 0.0f);
        set(g->params(), "attack", 0.05f);
        set(g->params(), "release", 5.0f);
        delete e.racks[4].swapEffect(0, g);
        Effect *c = EffectRegistry::create("Compressor");
        prepare(c);
        set(c->params(), "sidechain", 5.0f);
        set(c->params(), "threshold", -50.0f);
        set(c->params(), "ratio", 20.0f);
        set(c->params(), "attack", 0.1f);
        set(c->params(), "release", 10.0f);
        delete e.racks[5].swapEffect(0, c);
    }

    for (int64_t id : {1, 2}) {
        SceneInfo s;
        s.id = id;
        s.bars = 2;
        s.repeat = 1;
        s.ticksPerBar = kBar;
        snap->scenes.push_back(s);
    }
    snap->rackCount = kTracks;
    snap->clips.resize(static_cast<size_t>(kTracks) * snap->scenes.size());
    for (int32_t r = 0; r < kTracks; ++r) {
        for (int32_t sc = 0; sc < 2; ++sc) {
            auto c = std::make_shared<Clip>();
            c->rev = r * 10 + sc + 1;
            c->bars = 2;
            c->ticksPerBar = kBar;
            const int32_t base = (r < 2 ? 36 : 40 + (r * 5) % 24) + sc * 3;
            // Sixteenths on the drums, eighths and held chords elsewhere.
            const int32_t step = r < 2 ? kBar / 16 : (r % 3 == 0 ? kBar / 2 : kBar / 8);
            const int32_t length = r % 3 == 0 ? kBar : step / 2;
            for (int32_t t = 0; t < 2 * kBar; t += step) {
                const int32_t voices = r % 3 == 0 ? 3 : 1;
                for (int32_t v = 0; v < voices; ++v) {
                    ClipNote n{};
                    n.tick = t;
                    n.length = length;
                    n.pitch = static_cast<uint8_t>(base + v * 4 + (t / step) % 5);
                    n.velocity = static_cast<uint8_t>(70 + ((t / step) * 13 + r * 7) % 50);
                    c->notes.push_back(n);
                }
            }
            // A lane on the track's volume, falling then rising, on every clip.
            Lane lane;
            lane.unit = Unit::Channel;
            lane.index = 0;
            lane.points = {{0, 0.9f}, {kBar, 0.5f}, {2 * kBar - 1, 0.85f}};
            c->lanes.push_back(lane);
            // On rack 3, a lane on the decay its notes start with, stepping on
            // the notes' own ticks: a lane played after the notes in a block
            // instead of before them is heard.
            if (r == 3 && machines[r]->params().indexOf("decay") >= 0) {
                Lane decay;
                decay.unit = Unit::Machine;
                decay.index = machines[r]->params().indexOf("decay");
                decay.linear = false;
                for (int32_t t = 0; t < 2 * kBar; t += step)
                    decay.points.push_back({t, (t / step) % 2 ? 0.2f : 0.8f});
                c->lanes.push_back(decay);
            }
            keep.push_back(c);
            snap->clips[static_cast<size_t>(r) * snap->scenes.size() + static_cast<size_t>(sc)] = c;
        }
    }
    e.scheduler.swapSnapshot(snap.get());

    float scratch[kBlockFrames * 2];
    e.panicFlag.store(true, std::memory_order_release);
    e.renderBlock(nullptr, scratch);
    e.transport.requestPlay(0);
    const int32_t blocks = static_cast<int32_t>(seconds * kSampleRate / kBlockFrames);
    uint64_t hash = 1469598103934665603ull;
    float peak = 0.0f;
    const bool paced = std::getenv("PACED") != nullptr && std::getenv("PACED")[0] == '1';
    std::vector<double> callbacks;
    auto callbackStart = std::chrono::steady_clock::now();
    const auto period = std::chrono::nanoseconds(static_cast<int64_t>(4.0e9 * kBlockFrames / kSampleRate));
    auto next = callbackStart;
    const auto started = std::chrono::steady_clock::now();
    for (int32_t b = 0; b < blocks; ++b) {
        if (paced && b % 4 == 0) {
            if (b > 0) {
                callbacks.push_back(std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - callbackStart).count());
            }
            next += period;
            std::this_thread::sleep_until(next);
            callbackStart = std::chrono::steady_clock::now();
        }
        e.renderBlock(nullptr, scratch);
        for (float v : scratch) {
            uint32_t bits;
            std::memcpy(&bits, &v, sizeof(bits));
            hash = (hash ^ bits) * 1099511628211ull;
            peak = std::fmax(peak, std::fabs(v));
        }
    }
    const double took = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    std::printf("fingerprint %016llx  peak %.4f  %d blocks  %.0f us a block\n", static_cast<unsigned long long>(hash), peak,
                blocks, took * 1e6 / blocks);
    if (paced && !callbacks.empty()) {
        std::sort(callbacks.begin(), callbacks.end());
        const auto at = [&](double q) { return callbacks[static_cast<size_t>(q * (callbacks.size() - 1))]; };
        const double budget = 4.0e6 * kBlockFrames / kSampleRate;
        const auto late = std::count_if(callbacks.begin(), callbacks.end(), [&](double us) { return us > budget; });
        std::printf("callbacks of 4 blocks: middle %.0f us, 1 in 100 %.0f us, worst %.0f us, of %.0f; %d of %d late\n", at(0.5),
                    at(0.99), callbacks.back(), budget, static_cast<int>(late), static_cast<int>(callbacks.size()));
    }
    e.setWorkers(0);
    for (int32_t r = 0; r < kTracks; ++r) {
        for (int32_t s = 0; s < kEffectSlots; ++s) delete e.racks[r].swapEffect(s, nullptr);
        delete e.racks[r].swapMachine(nullptr);
    }
    return 0;
}
