// Checks Dice's factory patches play their loop without steps in the audio.
//
// Every patch plays the break and the swing loop straight through, each slice
// where the machine cut it, as audition's demo does. A step is one frame's
// jump that stands well clear of the jumps around it: what a slice starting
// without its fade in, or a voice cut short, sounds like. The loops played on
// their own have none, so any step comes from the machine.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>

#include <engine/machine/dice/Dice.h>

#include "audition_material.h"
#include "patchbank.h"

using namespace acidulous;
using namespace acidulous::audition;
using acidulous::machine::Dice;

namespace {
int gChecks = 0, gFails = 0;
void check(bool ok, const char *what, const char *detail = "") {
    ++gChecks;
    if (!ok) ++gFails;
    std::printf("  %s %s %s\n", ok ? "ok  " : "FAIL", what, detail);
}

constexpr float kSr = 48000.0f;
constexpr int32_t kBlock = 64;

/**
 * Where one frame's jump is more than six times the rms of the jumps on
 * either side of it (the 15 frames before or after, whichever moves more),
 * and over -54 dBFS. So a drum's attack out of a quiet moment doesn't count,
 * since the hit goes on moving after it. Steps within 32 frames count once.
 */
std::vector<int32_t> steps(const std::vector<float> &x) {
    std::vector<int32_t> out;
    const auto n = static_cast<int32_t>(x.size());
    std::vector<float> d(static_cast<size_t>(n), 0.0f);
    for (int32_t i = 1; i < n; ++i) d[static_cast<size_t>(i)] = std::fabs(x[static_cast<size_t>(i)] - x[static_cast<size_t>(i - 1)]);
    constexpr int32_t half = 16;
    for (int32_t i = half + 1; i + half < n; ++i) {
        const float di = d[static_cast<size_t>(i)];
        if (di < 2e-3f) continue;
        double before = 0.0, after = 0.0;
        for (int32_t j = 2; j <= half; ++j) {
            before += static_cast<double>(d[static_cast<size_t>(i - j)]) * d[static_cast<size_t>(i - j)];
            after += static_cast<double>(d[static_cast<size_t>(i + j)]) * d[static_cast<size_t>(i + j)];
        }
        const auto ctx = static_cast<float>(std::sqrt(std::max(before, after) / (half - 1)));
        if (di > 6.0f * ctx && (out.empty() || i - out.back() > 32)) out.push_back(i);
    }
    return out;
}

std::unique_ptr<audio::Take> material(const std::string &kind) {
    return kind == "swing" ? audition::swingLoop() : audition::breakLoop();
}

struct Render {
    std::vector<float> l, r;
};

/**
 * [patch] on [kind], the loop played straight through for about eight
 * seconds at 120 bpm: each slice struck where the machine cut it.
 */
Render playLoop(const Bank &bank, const BankPatch &patch, const std::string &kind) {
    Dice d;
    d.prepare(static_cast<int32_t>(kSr));
    int32_t count = 0;
    const ParamDef *defs = d.paramDefs(count);
    const Resolved res = resolve(patch, defs, count);
    for (int32_t i = 0; i < count; ++i) d.params().set(i, res.norm[static_cast<size_t>(i)]);
    d.params().jumpAll();
    d.swapObject(0, material(kind).release());
    float L[kBlock], R[kBlock];
    d.onBlock(0, 0, 120.0f);
    d.render(L, R, kBlock);

    const ParamSet &ps = d.params();
    const auto value = [&](const char *name) { return ps.target(ps.indexOf(name)); };
    const int32_t n = d.sliceCount();
    const float loopBpm = d.loopBpm();
    const bool follow = value("follow") > 0.5f && loopBpm > 0.0f;
    const float speed = value("rate") * (follow ? 120.0f / loopBpm : 1.0f);
    Render out;
    if (n <= 0) return out;
    const int32_t first = d.sliceStart(0);
    const float period = static_cast<float>(d.loopFrames() - first) / kSr / speed;
    const int passes = std::max(2, static_cast<int>(std::ceil(7.5f / period)));
    std::vector<std::pair<int64_t, int>> hits;
    for (int k = 0; k < passes; ++k) {
        for (int32_t i = 0; i < n; ++i) {
            const float at = static_cast<float>(k) * period + static_cast<float>(d.sliceStart(i) - first) / kSr / speed;
            hits.emplace_back(static_cast<int64_t>(at * kSr), i);
        }
    }
    std::stable_sort(hits.begin(), hits.end());
    const auto total = static_cast<int64_t>((static_cast<float>(passes) * period + 1.5f) * kSr);
    size_t next = 0;
    for (int64_t at = 0; at < total; at += kBlock) {
        d.onBlock(0, 0, 120.0f);
        // Notes land on the block they fall in, as the sequencer's do.
        while (next < hits.size() && hits[next].first < at + kBlock) {
            d.noteOn(static_cast<uint8_t>(Dice::kBaseNote + hits[next].second), 100);
            ++next;
        }
        d.render(L, R, kBlock);
        out.l.insert(out.l.end(), L, L + kBlock);
        out.r.insert(out.r.end(), R, R + kBlock);
    }
    (void)bank;
    return out;
}
} // namespace

int main(int argc, char **argv) {
    const char *root = std::getenv("ACIDULOUS_ROOT");
    const std::string dir = std::string(root != nullptr ? root : ".") + "/tools/banks";
    const bool verbose = argc > 1 && std::strcmp(argv[1], "-v") == 0;
    Bank bank;
    std::string error;
    if (!readBank(dir + "/Dice.bank", bank, error)) {
        std::printf("  FAIL %s\n", error.c_str());
        return 1;
    }

    std::printf("-- steps\n");
    for (const char *kind : {"break", "swing"}) {
        const auto take = material(kind);
        const size_t raw = steps(take->left).size() + steps(take->right).size();
        char detail[64];
        std::snprintf(detail, sizeof(detail), "(%zu)", raw);
        check(raw == 0, (std::string("the ") + kind + " loop on its own has no steps").c_str(), detail);
        if (verbose) {
            for (int32_t at : steps(take->left)) std::printf("       raw %.4f s: %.5f %.5f %.5f\n", at / kSr, take->left[static_cast<size_t>(at - 2)], take->left[static_cast<size_t>(at - 1)], take->left[static_cast<size_t>(at)]);
        }
        int worst = 0, total = 0;
        std::string worstName;
        for (const BankPatch &p : bank.patches) {
            const Render r = playLoop(bank, p, kind);
            const std::vector<int32_t> sl = steps(r.l), sr = steps(r.r);
            const int got = static_cast<int>(sl.size() + sr.size());
            total += got;
            if (got > worst) { worst = got; worstName = p.name; }
            if (verbose && got > 0) {
                std::printf("       %-14s %d at", p.name.c_str(), got);
                for (size_t i = 0; i < sl.size() && i < 6; ++i) std::printf(" %.3f", sl[i] / kSr);
                std::printf("\n");
            }
        }
        std::snprintf(detail, sizeof(detail), "(%d in all, worst %d on %s)", total, worst, worstName.c_str());
        check(total == 0, (std::string("no patch steps on the ") + kind).c_str(), detail);
    }

    std::printf("%d checks, %d failures\n", gChecks, gFails);
    return gFails == 0 ? 0 : 1;
}
