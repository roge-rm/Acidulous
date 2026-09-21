// Changing how long a take is without changing what it sings.
//
// Four claims, and the first two are the whole of it: the duration moves, and
// the pitch does not. The rest are the ways a stretcher goes wrong quietly -
// a rate of one that is not the input back again, a warble from joining two
// windows at the wrong phase, and a render that does not repeat.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <engine/dsp/Wsola.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-54s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

constexpr int32_t kRate = 48000;

/** A tone, as int16, which is how a reel holds one. */
std::vector<int16_t> tone(float hz, int32_t frames, float amp = 0.5f) {
    std::vector<int16_t> v(static_cast<size_t>(frames));
    for (int32_t i = 0; i < frames; ++i) {
        const float s = amp * std::sin(6.2831853f * hz * static_cast<float>(i) / kRate);
        v[static_cast<size_t>(i)] = static_cast<int16_t>(s * 32767.0f);
    }
    return v;
}

/** The dominant period, by counting rising zero crossings. Pitch, cheaply. */
float pitchOf(const std::vector<float> &x, int32_t from, int32_t to) {
    int crossings = 0;
    int32_t firstAt = -1, lastAt = -1;
    for (int32_t i = from + 1; i < to; ++i) {
        if (x[static_cast<size_t>(i - 1)] <= 0.0f && x[static_cast<size_t>(i)] > 0.0f) {
            if (firstAt < 0) firstAt = i;
            lastAt = i;
            ++crossings;
        }
    }
    if (crossings < 3) return 0.0f;
    return static_cast<float>(crossings - 1) * kRate / static_cast<float>(lastAt - firstAt);
}

double rms(const std::vector<float> &x, int32_t from, int32_t to) {
    double s = 0.0;
    for (int32_t i = from; i < to; ++i) s += static_cast<double>(x[static_cast<size_t>(i)]) * x[static_cast<size_t>(i)];
    return std::sqrt(s / (to - from));
}

std::vector<float> stretched(const std::vector<int16_t> &src, float rate, int32_t want) {
    dsp::Wsola w;
    w.prepare();
    w.seek(0);
    std::vector<float> out(static_cast<size_t>(want), 0.0f);
    // A block at a time, as the machine does it.
    int32_t at = 0;
    while (at < want) {
        const int32_t n = want - at < 64 ? want - at : 64;
        const int32_t got = w.fill(out.data() + at, n, src.data(), 0,
                                   static_cast<int64_t>(src.size()), rate);
        if (got <= 0) break;
        at += got;
    }
    out.resize(static_cast<size_t>(at));
    return out;
}

// --- The claims ------------------------------------------------------------------

/**
 * The pitch does not move, at any rate.
 *
 * This is the claim that separates a stretcher from resampling. Played at half
 * speed by resampling, a 440 Hz tone becomes 220; stretched, it stays 440 and
 * simply lasts twice as long.
 */
void thePitchStaysWhereItWas() {
    printf("- the duration moves and the pitch does not\n");
    const auto src = tone(440.0f, kRate * 4);
    for (const float rate : {0.5f, 0.75f, 1.5f, 2.0f}) {
        const auto out = stretched(src, rate, kRate * 2);
        const float hz = pitchOf(out, kRate / 4, kRate);
        ok(("at " + std::to_string(rate).substr(0, 4) + "x it is still 440 Hz").c_str(),
           std::fabs(hz - 440.0f) < 6.0f, std::to_string(hz) + " Hz");
    }
}

/** Twice the rate consumes twice the source for the same output. */
void theSourceIsConsumedAtTheRate() {
    printf("- the rate is how fast the source is read\n");
    const auto src = tone(220.0f, kRate * 8);
    for (const float rate : {0.5f, 1.0f, 2.0f}) {
        dsp::Wsola w;
        w.prepare();
        w.seek(0);
        std::vector<float> out(static_cast<size_t>(kRate), 0.0f);
        int32_t at = 0;
        while (at < kRate) {
            const int32_t got = w.fill(out.data() + at, 64, src.data(), 0,
                                       static_cast<int64_t>(src.size()), rate);
            if (got <= 0) break;
            at += got;
        }
        // One second of output should have taken `rate` seconds of source,
        // within the search's reach.
        const double used = static_cast<double>(w.sourcePosition()) / kRate;
        ok(("one second out took " + std::to_string(rate).substr(0, 4) + "s of source").c_str(),
           std::fabs(used - rate) < 0.06, std::to_string(used) + "s");
    }
}

/**
 * A rate of one is the take back again.
 *
 * Not bit for bit - the windows still overlap-add - but a stretcher that
 * colours the audio when it is not stretching it is one nobody can leave
 * switched on, and that is how it will be used.
 */
void aRateOfOneIsTransparent() {
    printf("- a rate of one does not colour the take\n");
    const auto src = tone(440.0f, kRate * 3);
    const auto out = stretched(src, 1.0f, kRate * 2);
    const double level = rms(out, kRate / 4, kRate);
    ok("the level is what went in", std::fabs(level - 0.5 * 0.70710678) < 0.03,
       std::to_string(level));
    const float hz = pitchOf(out, kRate / 4, kRate);
    ok("and so is the pitch", std::fabs(hz - 440.0f) < 3.0f, std::to_string(hz) + " Hz");
}

/**
 * It does not warble.
 *
 * The failure a stretcher has when the join is wrong: two windows overlap-added
 * at opposing phase cancel, so the level pumps at the hop rate. Measured as the
 * spread of the level hop by hop.
 */
void itDoesNotWarble() {
    printf("- overlapping windows join in phase rather than cancelling\n");
    const auto src = tone(330.0f, kRate * 6);
    for (const float rate : {0.7f, 1.4f}) {
        const auto out = stretched(src, rate, kRate);
        double lo = 1e9, hi = 0.0;
        for (int32_t at = kRate / 4; at + dsp::Wsola::kHop < static_cast<int32_t>(out.size());
             at += dsp::Wsola::kHop) {
            const double r = rms(out, at, at + dsp::Wsola::kHop);
            lo = r < lo ? r : lo;
            hi = r > hi ? r : hi;
        }
        // Better than 3 dB of pumping across the whole pass.
        const double spread = hi > 0.0 ? 20.0 * std::log10(hi / (lo + 1e-12)) : 99.0;
        ok(("at " + std::to_string(rate).substr(0, 3) + "x the level holds").c_str(), spread < 3.0,
           std::to_string(spread) + " dB");
    }
}

/** Two renders of one take match, or an export does not repeat. */
void itRepeats() {
    printf("- the same take stretched twice is the same audio\n");
    const auto src = tone(261.6f, kRate * 3);
    const auto a = stretched(src, 1.37f, kRate);
    const auto b = stretched(src, 1.37f, kRate);
    bool same = a.size() == b.size();
    for (size_t i = 0; i < a.size() && same; ++i) same = a[i] == b[i];
    ok("bit for bit", same);
}

} // namespace

int main() {
    printf("time-stretch: the duration moves, the pitch does not\n");
    thePitchStaysWhereItWas();
    theSourceIsConsumedAtTheRate();
    aRateOfOneIsTransparent();
    itDoesNotWarble();
    itRepeats();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
