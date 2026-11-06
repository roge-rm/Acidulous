// Divisi's motion.
//
// Each shape on a held chord: it renders the same after a panic and on a
// fresh machine, stays a number, moves the sound, and a fast motion doesn't
// zipper (the sharpest sample-to-sample bend is no worse than with motion
// off). In the whole Engine: a synced shape at a bar is the same whether the
// song played into it from the top or started there, a free one is not
// (so the check can fail), and a song with motion renders the same twice.
//
// Age: it repeats, stays a number, changes the sound without moving the
// level much, lets an idle machine sleep (its hiss is gated), doesn't zipper,
// and a macro reaches it.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include <engine/machine/MachineRegistry.h>
#include <engine/machine/divisi/Divisi.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

using namespace acidulous;
using namespace acidulous::seq;
using machine::Divisi;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-58s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

struct Setting {
    int32_t p;
    float v; // in the param's own units
};

void applySettings(Machine &m, const std::vector<Setting> &s) {
    for (const Setting &x : s) m.params().set(x.p, m.params().def(x.p).unmap(x.v));
    m.params().jumpAll();
}

/** A patch where motion is plain to hear: strings, choir and brass, spread on the stage. */
std::vector<Setting> busy(int shape, int sync, float rate) {
    return {{Divisi::Choir, 0.35f}, {Divisi::Contrabass, 0.4f}, {Divisi::Brass, 0.3f}, {Divisi::Stage, 0.7f},
            {Divisi::Room, 0.3f}, {Divisi::Players, 4.0f}, {Divisi::Drift, 6.0f},
            {Divisi::Motion, static_cast<float>(shape)}, {Divisi::MotionSync, static_cast<float>(sync)},
            {Divisi::MotionRate, rate}, {Divisi::MotionBalance, 1.0f}, {Divisi::MotionVowel, 1.0f},
            {Divisi::MotionTone, 1.0f}, {Divisi::MotionEnsemble, 1.0f}};
}

/** [seconds] of a held chord, interleaved. */
std::vector<float> play(Divisi &d, float seconds) {
    for (uint8_t n : {48, 55, 60, 64, 67}) d.noteOn(n, 100);
    const int32_t blocks = static_cast<int32_t>(seconds * kSampleRate / kBlockFrames);
    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    float L[kBlockFrames], R[kBlockFrames];
    for (int32_t b = 0; b < blocks; ++b) {
        d.render(L, R, kBlockFrames);
        for (int32_t i = 0; i < kBlockFrames; ++i) {
            out[(static_cast<size_t>(b) * kBlockFrames + i) * 2] = L[i];
            out[(static_cast<size_t>(b) * kBlockFrames + i) * 2 + 1] = R[i];
        }
    }
    d.allNotesOff();
    return out;
}

std::unique_ptr<Divisi> fresh(const std::vector<Setting> &s, int32_t rate = kSampleRate) {
    auto d = std::make_unique<Divisi>();
    d->prepare(rate);
    d->reset();
    applySettings(*d, s);
    return d;
}

const char *kShapes[] = {"off", "drift", "swell", "tide", "steps"};

void eachShapeRepeats() {
    printf("- every shape repeats after a panic and on a fresh machine, and stays a number\n");
    for (int shape = 0; shape < Divisi::MotionShapeCount; ++shape) {
        for (int sync : {0, 3}) {
            auto d = fresh(busy(shape, sync, 0.7f));
            const std::vector<float> a = play(*d, 6.0f);
            d->reset();
            const std::vector<float> b = play(*d, 6.0f);
            auto e = fresh(busy(shape, sync, 0.7f));
            const std::vector<float> c = play(*e, 6.0f);
            float peak = 0.0f;
            bool finite = true;
            for (float v : a) {
                finite = finite && std::isfinite(v);
                peak = std::fmax(peak, std::fabs(v));
            }
            char det[96];
            std::snprintf(det, sizeof det, "peak %.2f", peak);
            const std::string name = std::string(kShapes[shape]) + (sync ? " synced" : " free");
            ok(name + ": same after a panic and fresh", a == b && a == c, det);
            // Full depth on every target: a section 6 dB up can peak over full scale, as a louder patch would.
            ok(name + ": a number, under +6 dB", finite && peak < 2.0f, det);
        }
    }
}

/** The level in dB of each second, mid and side, for seeing the motion move. */
void levels(const std::vector<float> &x, std::vector<float> &mid, std::vector<float> &side) {
    const size_t per = static_cast<size_t>(kSampleRate) * 2;
    for (size_t at = 0; at + per <= x.size(); at += per) {
        double l = 0.0, r = 0.0;
        for (size_t i = at; i < at + per; i += 2) {
            l += static_cast<double>(x[i]) * x[i];
            r += static_cast<double>(x[i + 1]) * x[i + 1];
        }
        mid.push_back(static_cast<float>(10.0 * std::log10((l + r) / per + 1e-20)));
        side.push_back(static_cast<float>(10.0 * std::log10((l + 1e-20) / (r + 1e-20))));
    }
}

void eachShapeMoves() {
    printf("- every shape moves the sound more than the players alone do\n");
    std::vector<float> m0, s0;
    {
        auto d = fresh(busy(0, 0, 0.25f));
        levels(play(*d, 12.0f), m0, s0);
    }
    const auto spread = [](const std::vector<float> &v) {
        float lo = 1e9f, hi = -1e9f;
        for (size_t i = 2; i < v.size(); ++i) {
            lo = std::fmin(lo, v[i]);
            hi = std::fmax(hi, v[i]);
        }
        return hi - lo;
    };
    const float offLevel = spread(m0), offSide = spread(s0);
    for (int shape = 1; shape < Divisi::MotionShapeCount; ++shape) {
        std::vector<float> m, s;
        auto d = fresh(busy(shape, 0, 0.25f));
        levels(play(*d, 12.0f), m, s);
        char det[128];
        std::snprintf(det, sizeof det, "level %.1f dB, balance %.1f dB (off %.1f, %.1f)", spread(m), spread(s), offLevel, offSide);
        ok(std::string(kShapes[shape]) + ": moves", spread(m) + spread(s) > 1.5f * (offLevel + offSide), det);
    }
}

void fastMotionDoesNotZipper() {
    printf("- a fast motion bends no sharper at any one place in the block than at the others\n");
    // A value stepped once a block shows as a bend at one place in every
    // block: the mean second difference there against the rest.
    const auto atEdges = [](const std::vector<float> &x) {
        // By place in the block, since the ensemble's line delays the sound by a fixed number of samples:
        // the sharpest place against the middle one.
        double bin[kBlockFrames] = {};
        for (size_t f = static_cast<size_t>(kSampleRate) + 2; f < x.size() / 2; ++f) {
            for (int c = 0; c < 2; ++c) {
                bin[f % kBlockFrames] += std::fabs(x[f * 2 + c] - 2.0f * x[(f - 1) * 2 + c] + x[(f - 2) * 2 + c]);
            }
        }
        std::vector<double> v(bin, bin + kBlockFrames);
        std::sort(v.begin(), v.end());
        return static_cast<float>(v.back() / v[kBlockFrames / 2]);
    };
    // Low dark strings, no stage, so a step at a block's edge stands out of the sound.
    const auto plain = [](int shape) {
        std::vector<Setting> s = {{Divisi::Violin, 0.0f}, {Divisi::Viola, 0.0f}, {Divisi::Cello, 0.8f}, {Divisi::Contrabass, 0.8f},
                                  {Divisi::Tone, 0.2f}, {Divisi::Motion, static_cast<float>(shape)},
                                  {Divisi::MotionRate, 2.0f}, {Divisi::MotionBalance, 1.0f}, {Divisi::MotionVowel, 0.0f},
                                  {Divisi::MotionTone, 0.0f}, {Divisi::MotionEnsemble, 1.0f}};
        return s;
    };
    for (int shape = 0; shape < Divisi::MotionShapeCount; ++shape) {
        auto d = fresh(plain(shape));
        const float r = atEdges(play(*d, 6.0f));
        char det[96];
        std::snprintf(det, sizeof det, "%.3f of the rest", r);
        ok(std::string(kShapes[shape]) + " at 2 Hz", r < 1.05f, det);
    }
}

void macrosReachTheMotion() {
    printf("- a macro reaches each motion knob\n");
    const int dests[] = {Divisi::McMotionBalance, Divisi::McMotionVowel, Divisi::McMotionTone, Divisi::McMotionEnsemble,
                         Divisi::McMotionRate};
    const char *names[] = {"balance", "vowel", "tone", "ensemble", "rate"};
    for (int k = 0; k < 5; ++k) {
        std::vector<Setting> s = busy(Divisi::MotionTide, 0, 0.5f);
        for (Setting &x : s) {
            if (x.p >= Divisi::MotionBalance && x.p <= Divisi::MotionEnsemble) x.v = 0.0f;
        }
        if (dests[k] == Divisi::McMotionRate) s.push_back({Divisi::MotionBalance, 1.0f});
        auto a = fresh(s);
        const std::vector<float> plain = play(*a, 4.0f);
        s.push_back({Divisi::MacroBase, 1.0f});
        s.push_back({Divisi::MacroBase + 1, static_cast<float>(dests[k])});
        s.push_back({Divisi::MacroBase + 5, 1.0f});
        auto b = fresh(s);
        const std::vector<float> moved = play(*b, 4.0f);
        double diff = 0.0, sig = 0.0;
        for (size_t i = 0; i < plain.size(); ++i) {
            diff += (moved[i] - plain[i]) * (moved[i] - plain[i]);
            sig += plain[i] * plain[i];
        }
        char det[64];
        std::snprintf(det, sizeof det, "%.1f dB of change", 10.0 * std::log10(diff / sig + 1e-20));
        ok(std::string("motion ") + names[k], diff > 1e-3 * sig, det);
    }
}

// --- In the Engine ------------------------------------------------------------

constexpr int32_t kBar = kPPQN * 4;
constexpr int32_t kRack = 2;

struct Fixture {
    std::unique_ptr<Engine> owned = std::make_unique<Engine>();
    Engine &engine = *owned;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::vector<std::shared_ptr<const Clip>> keep;
    Divisi *divisi = nullptr;

    Fixture(int shape, int sync) {
        Machine *m = MachineRegistry::create("Divisi");
        m->prepare(kSampleRate);
        m->reset();
        applySettings(*m, busy(shape, sync, 0.3f));
        divisi = static_cast<Divisi *>(m);
        delete engine.racks[kRack].swapMachine(m);
        // Scene 1: three bars; scene 2: two. A chord held through each.
        const int32_t bars[2] = {3, 2};
        for (int32_t s = 0; s < 2; ++s) {
            SceneInfo sc;
            sc.id = s + 1;
            sc.bars = bars[s];
            sc.repeat = 1;
            sc.ticksPerBar = kBar;
            snap->scenes.push_back(sc);
        }
        snap->rackCount = kRack + 1;
        snap->clips.resize(static_cast<size_t>(snap->rackCount) * snap->scenes.size());
        for (int32_t s = 0; s < 2; ++s) {
            auto held = std::make_shared<Clip>();
            held->rev = s + 1;
            held->bars = bars[s];
            held->ticksPerBar = kBar;
            for (uint8_t n : {48, 55, 60, 64}) held->notes.push_back(ClipNote{0, bars[s] * kBar - 1, n, 100});
            keep.push_back(held);
            snap->setClip(kRack, s, held);
        }
        engine.scheduler.swapSnapshot(snap.get());
    }
    ~Fixture() {
        for (int32_t r = 0; r < kRackCount; ++r) delete engine.racks[r].swapMachine(nullptr);
    }
    /** Plays from [scene] for [blocks]: each block's motion, and the mix. */
    void run(int32_t scene, int32_t blocks, std::vector<float> &motion, std::vector<float> &audio) {
        float scratch[kBlockFrames * 2];
        engine.panicFlag.store(true, std::memory_order_release);
        engine.renderBlock(nullptr, scratch);
        engine.transport.requestPlay(scene);
        for (int32_t b = 0; b < blocks; ++b) {
            engine.renderBlock(nullptr, scratch);
            audio.insert(audio.end(), scratch, scratch + kBlockFrames * 2);
            for (int c = 0; c < 7; ++c) motion.push_back(divisi->motionOf(c));
        }
    }
};

void aBarSoundsTheSameWherePlayStarts() {
    printf("- a synced shape at a bar is the same from the top or started there\n");
    // Three bars at 120 is 6 s = 4500 blocks.
    const int32_t firstBlocks = 4500, secondBlocks = 2800;
    struct Case { const char *name; int shape, sync; bool same; };
    const Case cases[] = {{"tide, a bar", Divisi::MotionTide, 3, true},
                          {"steps, a quarter", Divisi::MotionSteps, 1, true},
                          {"drift, two bars", Divisi::MotionDrift, 4, true},
                          {"swell, a bar", Divisi::MotionSwell, 3, true},
                          {"tide, free (should differ)", Divisi::MotionTide, 0, false}};
    for (const Case &c : cases) {
        std::vector<float> songM, songA, aloneM, aloneA;
        {
            Fixture f(c.shape, c.sync);
            f.run(0, firstBlocks + secondBlocks, songM, songA);
        }
        {
            Fixture f(c.shape, c.sync);
            f.run(1, secondBlocks, aloneM, aloneA);
        }
        float worst = 0.0f, moved = 0.0f;
        for (size_t i = 0; i < aloneM.size(); ++i) {
            worst = std::fmax(worst, std::fabs(aloneM[i] - songM[static_cast<size_t>(firstBlocks) * 7 + i]));
            moved = std::fmax(moved, std::fabs(aloneM[i] - aloneM[i % 7]));
        }
        char det[96];
        std::snprintf(det, sizeof det, "differs by %.6f at most, moves %.2f", worst, moved);
        ok(c.name, c.same ? (worst < 1e-4f && moved > 0.5f) : worst > 0.1f, det);
    }
    printf("- a song with motion renders the same twice\n");
    for (int sync : {0, 3}) {
        std::vector<float> m1, a1, m2, a2, m3, a3;
        {
            Fixture f(Divisi::MotionSteps, sync);
            f.run(0, 3000, m1, a1);
            f.run(0, 3000, m2, a2);
        }
        {
            Fixture f(Divisi::MotionSteps, sync);
            f.run(0, 3000, m3, a3);
        }
        const std::string n = sync ? "synced steps" : "free steps";
        ok(n + ": two renders are identical", a1 == a3 && m1 == m3);
        ok(n + ": and the same after a panic", a1 == a2 && m1 == m2);
    }
}
/** Mean square of an interleaved stretch, dB. */
float levelDb(const std::vector<float> &x, size_t from, size_t to) {
    double sum = 0.0;
    for (size_t i = from; i < to; ++i) sum += static_cast<double>(x[i]) * x[i];
    return static_cast<float>(10.0 * std::log10(sum / static_cast<double>(to - from) + 1e-30));
}

void ageWorks() {
    printf("- age repeats, stays a number, changes the sound but not much the level, and sleeps\n");
    for (float stage : {0.0f, 0.7f}) {
        std::vector<Setting> base = busy(Divisi::MotionOff, 0, 0.7f);
        for (Setting &x : base) if (x.p == Divisi::Stage) x.v = stage;
        auto ref = fresh(base);
        const std::vector<float> clean = play(*ref, 4.0f);
        for (float age : {0.5f, 1.0f}) {
            std::vector<Setting> s = base;
            s.push_back({Divisi::Age, age});
            auto d = fresh(s);
            const std::vector<float> a = play(*d, 4.0f);
            // Let go and let it ring out: an idle machine sleeps and is silent, hiss and all.
            float L[kBlockFrames], R[kBlockFrames];
            float tail = 0.0f;
            const int32_t blocks = static_cast<int32_t>(6.0f * kSampleRate / kBlockFrames);
            for (int32_t b = 0; b < blocks; ++b) {
                d->render(L, R, kBlockFrames);
                if (b >= blocks - static_cast<int32_t>(kSampleRate / kBlockFrames)) {
                    for (int32_t i = 0; i < kBlockFrames; ++i) tail = std::fmax(tail, std::fmax(std::fabs(L[i]), std::fabs(R[i])));
                }
            }
            d->reset();
            const std::vector<float> b = play(*d, 4.0f);
            auto e = fresh(s);
            const std::vector<float> c = play(*e, 4.0f);
            float peak = 0.0f, diff = 0.0f;
            bool finite = true;
            for (size_t i = 0; i < a.size(); ++i) {
                finite = finite && std::isfinite(a[i]);
                peak = std::fmax(peak, std::fabs(a[i]));
                diff = std::fmax(diff, std::fabs(a[i] - clean[i]));
            }
            const size_t from = static_cast<size_t>(kSampleRate) * 2, to = a.size();
            const float moved = levelDb(a, from, to) - levelDb(clean, from, to);
            char name[64], det[96];
            std::snprintf(name, sizeof name, "age %.1f, stage %.1f", age, stage);
            std::snprintf(det, sizeof det, "peak %.2f, level %+.2f dB, tail %.1e", peak, moved, tail);
            ok(std::string(name) + ": same after a panic and fresh", a == b && a == c, det);
            ok(std::string(name) + ": a number, changed, level within 1.5 dB", finite && peak < 2.0f && diff > 1e-3f && std::fabs(moved) < 1.5f, det);
            ok(std::string(name) + ": silent once let go", tail == 0.0f, det);
        }
    }

    printf("- age doesn't zipper, and a macro reaches it\n");
    {
        const auto edges = [](const std::vector<float> &x) {
            double bin[kBlockFrames] = {};
            for (size_t f = static_cast<size_t>(kSampleRate) + 2; f < x.size() / 2; ++f) {
                for (int c = 0; c < 2; ++c) bin[f % kBlockFrames] += std::fabs(x[f * 2 + c] - 2.0f * x[(f - 1) * 2 + c] + x[(f - 2) * 2 + c]);
            }
            std::vector<double> v(bin, bin + kBlockFrames);
            std::sort(v.begin(), v.end());
            return static_cast<float>(v.back() / v[kBlockFrames / 2]);
        };
        const std::vector<Setting> s = {{Divisi::Violin, 0.0f}, {Divisi::Viola, 0.0f}, {Divisi::Cello, 0.8f},
                                        {Divisi::Contrabass, 0.8f}, {Divisi::Tone, 0.2f}, {Divisi::Ensemble, 1.0f},
                                        {Divisi::Age, 1.0f}};
        auto d = fresh(s);
        const float r = edges(play(*d, 6.0f));
        char det[96];
        std::snprintf(det, sizeof det, "%.3f of the rest", r);
        ok("age 1 on low strings", r < 1.05f, det);

        std::vector<Setting> m = s;
        m.back().v = 0.0f;
        m.push_back({Divisi::MacroBase, 1.0f});
        m.push_back({Divisi::MacroBase + 1, static_cast<float>(Divisi::McAge)});
        m.push_back({Divisi::MacroBase + 5, 1.0f});
        auto byMacro = fresh(m);
        auto byKnob = fresh(s);
        ok("a macro at full is age 1", play(*byMacro, 2.0f) == play(*byKnob, 2.0f));
    }
}
/** Renders [seconds] into [out], interleaved, appending. */
void renderInto(Divisi &d, float seconds, std::vector<float> &out) {
    const int32_t blocks = static_cast<int32_t>(seconds * kSampleRate / kBlockFrames);
    float L[kBlockFrames], R[kBlockFrames];
    for (int32_t b = 0; b < blocks; ++b) {
        d.render(L, R, kBlockFrames);
        for (int32_t i = 0; i < kBlockFrames; ++i) {
            out.push_back(L[i]);
            out.push_back(R[i]);
        }
    }
}

void ageComesInCleanly() {
    printf("- age turned up from 0 mid-note, or the stage opened with age on, doesn't dip or click\n");
    const std::vector<Setting> strings = {{Divisi::Violin, 0.0f}, {Divisi::Viola, 0.0f}, {Divisi::Cello, 0.8f},
                                          {Divisi::Contrabass, 0.8f}, {Divisi::Tone, 0.2f}, {Divisi::Ensemble, 1.0f}};
    // [p] glides from [from] to [to] after a second of a held chord. Over the first 128 samples of the glide
    // the sound can only have moved a little from the same chord left at [from].
    const auto moves = [&](std::vector<Setting> base, int32_t p, float from, float to) {
        std::vector<Setting> a = base;
        a.push_back({p, from});
        auto moved = fresh(a), stays = fresh(a);
        std::vector<float> xa, xb;
        for (Divisi *d : {moved.get(), stays.get()}) for (uint8_t n : {36, 43, 48, 55}) d->noteOn(n, 100);
        renderInto(*moved, 1.0f, xa);
        renderInto(*stays, 1.0f, xb);
        moved->params().set(p, moved->params().def(p).unmap(to));
        const size_t at = xa.size();
        renderInto(*moved, 0.3f, xa);
        renderInto(*stays, 0.3f, xb);
        float first = 0.0f, peak = 0.0f;
        for (size_t i = at; i < at + 256; ++i) first = std::fmax(first, std::fabs(xa[i] - xb[i]));
        for (size_t i = at - 9600; i < at; ++i) peak = std::fmax(peak, std::fabs(xa[i]));
        return first / peak;
    };
    char det[128];
    const float age = moves(strings, Divisi::Age, 0.0f, 0.3f);
    std::snprintf(det, sizeof det, "moved %.4f of the peak in 128 samples", age);
    ok("age 0 to 0.3 on a held chord", age < 0.02f, det);
    // The stage moves the sound itself as it opens: with age on, no more than with it off.
    std::vector<Setting> aged = strings;
    aged.push_back({Divisi::Age, 0.3f});
    const float clean = moves(strings, Divisi::Stage, 0.0f, 0.7f), old = moves(aged, Divisi::Stage, 0.0f, 0.7f);
    std::snprintf(det, sizeof det, "moved %.4f of the peak in 128 samples (age 0: %.4f)", old, clean);
    ok("stage opened to 0.7 with age 0.3", old < 1.2f * clean + 0.005f, det);
}

void playersKeepTheirSeatsInATail() {
    printf("- a note played into a room's tail doesn't move the players' reads of it\n");
    const std::vector<Setting> s = {{Divisi::Cello, 0.8f}, {Divisi::Viola, 0.5f}, {Divisi::Players, 3.0f},
                                    {Divisi::Scatter, 60.0f}, {Divisi::Room, 0.6f}, {Divisi::Attack, 2.0f},
                                    {Divisi::Release, 0.05f}, {Divisi::VelocityAmount, 1.0f},
                                    {Divisi::Mode, static_cast<float>(Divisi::Poly)}};
    auto a = fresh(s), b = fresh(s);
    std::vector<float> xa, xb;
    for (Divisi *d : {a.get(), b.get()}) for (uint8_t n : {48, 55, 60}) d->noteOn(n, 100);
    renderInto(*a, 1.0f, xa);
    renderInto(*b, 1.0f, xb);
    for (Divisi *d : {a.get(), b.get()}) for (uint8_t n : {48, 55, 60}) d->noteOff(n);
    renderInto(*a, 0.25f, xa);
    renderInto(*b, 0.25f, xb);
    const int keysLeft = a->activeKeys();
    const size_t at = xa.size();
    // So quiet a note that what's heard is the tail.
    a->noteOn(62, 1);
    renderInto(*a, 0.1f, xa);
    renderInto(*b, 0.1f, xb);
    float tail = 0.0f, jump = 0.0f;
    for (size_t i = at; i < xa.size(); ++i) {
        tail = std::fmax(tail, std::fabs(xb[i]));
        jump = std::fmax(jump, std::fabs(xa[i] - xb[i]));
    }
    char det[96];
    std::snprintf(det, sizeof det, "keys left %d, tail %.4f, moved by %.6f", keysLeft, tail, jump);
    ok("3 players, scatter 60 ms, room 0.6, 0.25 s after", keysLeft == 0 && tail > 1e-3f && jump < 0.05f * tail, det);
}

void latePlayersAreHeard() {
    printf("- a staccato note isn't cut off before its latest player has played it\n");
    for (int players : {2, 3}) {
        // Short notes, the players as far apart as they go, the violins at the front and the basses at the back.
        const std::vector<Setting> s = {{Divisi::Violin, 0.8f}, {Divisi::Viola, 0.0f}, {Divisi::Cello, 0.0f},
                                        {Divisi::Contrabass, 0.8f}, {Divisi::Stage, 1.0f}, {Divisi::Players, static_cast<float>(players)},
                                        {Divisi::Scatter, 80.0f}, {Divisi::Drift, 25.0f}, {Divisi::Vibrato, 60.0f},
                                        {Divisi::Vibrate, 2.0f}, {Divisi::Attack, 0.002f}, {Divisi::Release, 0.02f},
                                        {Divisi::VelocityAmount, 1.0f}, {Divisi::Mode, static_cast<float>(Divisi::Poly)}};
        // At 96 kHz, where the lines hold twice the samples; even so a gap long enough to have
        // slept on takes seats and drifts near their ends, so many deals. Times below are at 48 kHz.
        constexpr int kDeals = 40, kRate = 96000;
        float worst = 0.0f, peak = 0.0f;
        int cut = 0;
        for (int deal = 0; deal < kDeals; ++deal) {
            // The same note alone, and with a far quieter key held after it that keeps the machine awake.
            // Notes before it, let ring out, deal the players new seats and let them drift.
            auto a = fresh(s, kRate), b = fresh(s, kRate);
            std::vector<float> xa, xb;
            for (int k = 0; k < deal; ++k) {
                for (Divisi *d : {a.get(), b.get()}) d->noteOn(64, 100);
                renderInto(*a, 0.0014f, xa);
                renderInto(*b, 0.0014f, xb);
                for (Divisi *d : {a.get(), b.get()}) d->noteOff(64);
                renderInto(*a, 0.6f, xa);
                renderInto(*b, 0.6f, xb);
            }
            xa.clear();
            xb.clear();
            for (Divisi *d : {a.get(), b.get()}) d->noteOn(64, 100);
            b->noteOn(30, 1);
            renderInto(*a, 0.0014f, xa);
            renderInto(*b, 0.0014f, xb);
            for (Divisi *d : {a.get(), b.get()}) d->noteOff(64);
            renderInto(*a, 0.3f, xa);
            renderInto(*b, 0.3f, xb);
            float w = 0.0f;
            for (size_t i = 0; i < xa.size(); ++i) {
                w = std::fmax(w, std::fabs(xa[i] - xb[i]));
                peak = std::fmax(peak, std::fabs(xa[i]));
            }
            worst = std::fmax(worst, w);
            if (w > 0.02f * peak) ++cut;
        }
        char name[64], det[96];
        std::snprintf(name, sizeof name, "%d players, scatter 80 ms, drift 25 cents, 96 kHz", players);
        std::snprintf(det, sizeof det, "peak %.3f, differs by %.5f, %d of %d cut", peak, worst, cut, kDeals);
        ok(name, cut == 0, det);
    }
}
} // namespace

int main() {
    eachShapeRepeats();
    eachShapeMoves();
    fastMotionDoesNotZipper();
    macrosReachTheMotion();
    aBarSoundsTheSameWherePlayStarts();
    ageWorks();
    ageComesInCleanly();
    playersKeepTheirSeatsInATail();
    latePlayersAreHeard();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
