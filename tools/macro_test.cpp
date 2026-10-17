// Checks Trinity's macro routes move the destinations they name.
//
// A macro at zero must leave the sound alone, bit for bit. At full, a route
// to amplitude with a known depth must scale the level by a known amount, for
// every macro and every one of its four routes. Another route to pitch must
// move the note by the semitones its depth says.

#include <engine/machine/MachineRegistry.h>
#include <engine/machine/trinity/Trinity.h>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace acidulous;
using T = acidulous::machine::Trinity;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
constexpr int32_t kBlocks = 128;

int checks = 0, failures = 0;

void ok(bool pass, const std::string &what, const std::string &detail) {
    ++checks;
    if (!pass) ++failures;
    std::printf("  %-4s %-58s %s\n", pass ? "ok" : "FAIL", what.c_str(), detail.c_str());
}

struct Route { int macro, route, dest; float depth; float knob; };

void setNorm(ParamSet &p, const std::string &name, float v) {
    const int32_t i = p.indexOf(name.c_str());
    if (i < 0) { std::printf("  FAIL no parameter %s\n", name.c_str()); ++failures; return; }
    p.set(i, v);
}

std::vector<float> play(const Route *r) {
    Machine *m = MachineRegistry::create("Trinity");
    m->prepare(kRate);
    ParamSet &p = m->params();
    if (r != nullptr) {
        const std::string k = "mac" + std::to_string(r->macro);
        setNorm(p, k, r->knob);
        setNorm(p, k + "_d" + std::to_string(r->route), r->dest / (T::DestCount - 1.0f));
        setNorm(p, k + "_a" + std::to_string(r->route), (r->depth + 1.0f) * 0.5f);
    }
    p.jumpAll();
    m->allNotesOff();
    m->reset();
    m->noteOn(57, 100);
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    for (int32_t b = 0; b < kBlocks; ++b) {
        m->onBlock(b * 4, b * 4 + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(L[i]);
    }
    delete m;
    return out;
}

double rms(const std::vector<float> &v, size_t from) {
    double s = 0;
    for (size_t i = from; i < v.size(); ++i) s += double(v[i]) * v[i];
    return std::sqrt(s / double(v.size() - from));
}

/** Rising zero crossings per second over the second half, for pitch. */
double hz(const std::vector<float> &v) {
    int n = 0;
    size_t first = 0, last = 0;
    for (size_t i = v.size() / 2 + 1; i < v.size(); ++i)
        if (v[i - 1] < 0 && v[i] >= 0) { if (n == 0) first = i; last = i; ++n; }
    return n < 2 ? 0 : (n - 1) * double(kRate) / double(last - first);
}

} // namespace

int main() {
    const auto base = play(nullptr);
    ok(rms(base, 0) > 1e-4, "Trinity makes sound", "");
    const double baseRms = rms(base, 0);

    for (int macro = 1; macro <= T::kMacros; ++macro) {
        // At zero the route does nothing, bit for bit.
        Route off{macro, 1, T::DstAmp, -0.5f, 0.0f};
        ok(play(&off) == base, "macro " + std::to_string(macro) + " at zero leaves the sound alone", "");
        for (int route = 1; route <= 4; ++route) {
            // Volume 0.7 minus 0.5 leaves 0.2, so the level is 2/7 of the base.
            Route r{macro, route, T::DstAmp, -0.5f, 1.0f};
            const double ratio = rms(play(&r), 0) / baseRms;
            char buf[64];
            std::snprintf(buf, sizeof(buf), "ratio %.3f (want 0.286)", ratio);
            ok(std::fabs(ratio - 2.0 / 7.0) < 0.02, "macro " + std::to_string(macro) + " route " + std::to_string(route) + " scales the level", buf);
        }
    }

    // Half the knob is half the depth.
    Route half{1, 1, T::DstAmp, -0.5f, 0.5f};
    const double h = rms(play(&half), 0) / baseRms;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "ratio %.3f (want 0.643)", h);
    ok(std::fabs(h - 0.45 / 0.7) < 0.02, "half the knob is half the depth", buf);

    // Pitch: DstPitch is in units of 24 semitones, so 1/24 of depth is one.
    const double f0 = hz(base);
    Route up{3, 2, T::DstPitch, 12.0f / 24.0f, 1.0f};
    const double f1 = hz(play(&up));
    std::snprintf(buf, sizeof(buf), "%.1f Hz -> %.1f Hz", f0, f1);
    ok(f0 > 0 && std::fabs(f1 / f0 - 2.0) < 0.03, "a pitch route moves the note an octave", buf);

    // A route to nothing does nothing.
    Route none{2, 3, T::DstOff, 1.0f, 1.0f};
    ok(play(&none) == base, "a route to off does nothing", "");

    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
