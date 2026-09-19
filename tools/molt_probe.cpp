// What a take is, and what Molt makes of it.
//
// Not a test: it asserts nothing and it cannot fail. It prints the take's own
// numbers beside the numbers of a note rendered from it, in the harness's own
// units, so the two can be compared.
//
// **That comparison is the whole reason it exists.** Molt was reported as
// mishandling a real voice on three counts - almost nothing on the note's
// harmonic series, a loudest partial below the note, and fourteen decibels
// under the rest of the bank - and all three turned out to be true of the
// take *before Molt was given it*. The recording is fifty-six per cent
// sub-seventy-hertz energy and clipped; measured as it was mounted it read
// harm 0.006, part 0.45 and -30.9 dBFS. Molt raised the first and was blamed
// for all of them. Only `click` was the machine's own.
//
// Without a take column there is nothing to notice that with, so this is now
// a standing measurement rather than something reconstructed by hand.
//
// It runs against ACIDULOUS_INPUT_FILE when tools/local.env names one and the
// synthetic phrase otherwise, exactly as the audition harness does, so it is
// committable and still says something where there is no recording.
#include <engine/core/Utterance.h>
#include <engine/machine/molt/Molt.h>

#include "audition_material.h"
#include "audition_measure.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

using namespace acidulous;
using namespace acidulous::audio;

namespace {

constexpr float kSr = audition::kSr;
/** C3, which is what the bank is rendered at: no note= and no range= in it. */
constexpr int kNote = 48;

float midiHz(int note) { return 440.0f * std::exp2((static_cast<float>(note) - 69.0f) / 12.0f); }

float median(std::vector<float> v) {
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

/** A mono buffer as the interleaved pair `measure` wants. */
std::vector<float> asStereo(const std::vector<float> &mono) {
    std::vector<float> out(mono.size() * 2, 0.0f);
    for (size_t i = 0; i < mono.size(); ++i) {
        out[i * 2] = mono[i];
        out[i * 2 + 1] = mono[i];
    }
    return out;
}

/**
 * The share of energy under [hz], averaged over the take rather than taken
 * from one window - a phrase has silences, and a window in one of them says
 * whatever the noise floor is shaped like.
 */
float belowShare(const std::vector<float> &mono, float hz) {
    const auto n = static_cast<int32_t>(mono.size());
    const int32_t step = static_cast<int32_t>(kSr * 0.25f);
    double num = 0.0, den = 0.0;
    for (int32_t at = 0; at + 8192 < n; at += step) {
        const audition::Spectrum sp = audition::spectrumAt(mono, at);
        if (sp.totalSq <= 0.0) continue;
        // Weighted by the window's own energy, so a loud vowel counts for
        // more than a quiet gap between two words.
        num += static_cast<double>(sp.fractionBelow(hz)) * sp.totalSq;
        den += sp.totalSq;
    }
    return den > 0.0 ? static_cast<float>(num / den) : 0.0f;
}

void printTake(const std::vector<float> &mono, const char *what) {
    double sq = 0.0;
    float peak = 0.0f;
    int32_t clipped = 0;
    for (float v : mono) {
        sq += static_cast<double>(v) * v;
        peak = std::max(peak, std::abs(v));
        if (std::abs(v) >= 0.999f) ++clipped;
    }
    const auto rms = static_cast<float>(std::sqrt(sq / std::max<size_t>(1, mono.size())));
    std::printf("  %-22s %8.2f s\n", what, static_cast<double>(mono.size()) / kSr);
    std::printf("    %-20s %7.1f dBFS   peak %6.1f dBFS   crest %5.1f dB\n", "level",
                static_cast<double>(audition::dB(rms)), static_cast<double>(audition::dB(peak)),
                static_cast<double>(audition::dB(peak) - audition::dB(rms)));
    std::printf("    %-20s %6.1f%% under %g Hz   %6.1f%% under the note\n", "where it sits",
                100.0 * static_cast<double>(belowShare(mono, PitchTrack::kMinHz)),
                static_cast<double>(PitchTrack::kMinHz),
                100.0 * static_cast<double>(belowShare(mono, midiHz(kNote))));
    std::printf("    %-20s %d sample%s at full scale (%.2f%%)\n", "clipping", clipped,
                clipped == 1 ? "" : "s",
                100.0 * static_cast<double>(clipped) / static_cast<double>(std::max<size_t>(1, mono.size())));
}

void printTrack(const std::vector<float> &mono) {
    PitchTrack t;
    // Cleaned already - `analyse` did it. Filtering again is eight poles
    // at kMinHz and takes the fundamental with the room.
    t.find(mono, static_cast<int32_t>(mono.size()), kSr, true);
    std::vector<float> voiced, clarity;
    int32_t jumps = 0, pairs = 0, flaps = 0;
    float last = 0.0f;
    bool wasVoiced = false;
    for (size_t i = 0; i < t.hz.size(); ++i) {
        const float f = t.hz[i];
        const bool isVoiced = f > 0.0f;
        if (isVoiced != wasVoiced) ++flaps;
        wasVoiced = isVoiced;
        if (!isVoiced) continue;
        voiced.push_back(f);
        clarity.push_back(t.clarity[i]);
        if (last > 0.0f) {
            ++pairs;
            // Seven semitones: a voice does not move that far in ten
            // milliseconds, so anything past it is the tracker changing its
            // mind about which octave it is in rather than a sung interval.
            if (std::abs(1200.0f * std::log2(f / last)) > 700.0f) ++jumps;
        }
        last = f;
    }
    const auto hops = static_cast<float>(std::max<size_t>(1, t.hz.size()));
    std::printf("    %-20s %6.1f Hz median   %5.1f%% voiced   clarity %.2f\n", "pitch track",
                static_cast<double>(median(voiced)),
                100.0 * static_cast<double>(voiced.size()) / static_cast<double>(hops),
                static_cast<double>(median(clarity)));
    std::printf("    %-20s %5.1f%% of neighbours   %4.1f flaps/s\n", "octave jumps",
                pairs > 0 ? 100.0 * jumps / pairs : 0.0,
                static_cast<double>(flaps) / (static_cast<double>(mono.size()) / kSr));
}

/**
 * What the grains will be cut from.
 *
 * Three numbers, and the third is the one that decides whether PSOLA can work
 * at all. Overlap-add only adds if consecutive grains are cut at the same
 * point in the cycle: near one they reinforce, near nought they cancel into a
 * hollow phasey voice, and negative means alternate marks landed on opposite
 * polarities and the output is an octave down with a hole in it.
 */
void printEpochs(const Utterance &u) {
    std::vector<float> jitter;
    int32_t voicedMarks = 0, negative = 0;
    for (size_t i = 0; i < u.epochs.size(); ++i) {
        const Epoch &e = u.epochs[i];
        if (!e.voiced) continue;
        ++voicedMarks;
        if (u.mono[static_cast<size_t>(e.at)] < 0.0f) ++negative;
        if (i + 1 < u.epochs.size() && u.epochs[i + 1].voiced && e.period > 1.0f) {
            const float gap = static_cast<float>(u.epochs[i + 1].at - e.at);
            jitter.push_back(std::abs(gap - e.period) / e.period);
        }
    }

    double coherence = 0.0;
    int32_t pairs = 0;
    for (size_t i = 0; i + 1 < u.epochs.size(); ++i) {
        const Epoch &a = u.epochs[i];
        const Epoch &b = u.epochs[i + 1];
        if (!a.voiced || !b.voiced) continue;
        const auto half = static_cast<int32_t>(std::min(a.period, b.period) * 0.5f);
        if (half < 4 || a.at - half < 0 || b.at + half >= u.frames) continue;
        double ab = 0.0, aa = 0.0, bb = 0.0;
        for (int32_t k = -half; k <= half; ++k) {
            const double x = u.mono[static_cast<size_t>(a.at + k)];
            const double y = u.mono[static_cast<size_t>(b.at + k)];
            ab += x * y;
            aa += x * x;
            bb += y * y;
        }
        if (aa < 1e-12 || bb < 1e-12) continue;
        coherence += ab / std::sqrt(aa * bb);
        ++pairs;
    }

    std::printf("    %-20s %zu marks   %5.1f%% voiced   root %.1f Hz\n", "pitch marks",
                u.epochs.size(),
                100.0 * static_cast<double>(voicedMarks) /
                    static_cast<double>(std::max<size_t>(1, u.epochs.size())),
                static_cast<double>(u.rootHz));
    std::printf("    %-20s %5.1f%% of a period   %5.1f%% land negative\n", "spacing jitter",
                100.0 * static_cast<double>(median(jitter)),
                voicedMarks > 0 ? 100.0 * negative / voicedMarks : 0.0);
    std::printf("    %-20s %6.2f  (1 adds, 0 cancels)\n", "grain coherence",
                pairs > 0 ? coherence / pairs : 0.0);
}

/** One note held two seconds, which is what the bank is measured on. */
std::vector<float> renderNote(Utterance &u) {
    machine::Molt m;
    m.prepare(static_cast<int32_t>(kSr));
    int32_t count = 0;
    const ParamDef *defs = m.paramDefs(count);
    auto setp = [&](int32_t p, float value) { m.params().set(p, defs[p].unmap(value)); };
    // Init, near enough: the machine and not a patch of it.
    setp(machine::Molt::Tune, 1.0f);
    setp(machine::Molt::Rate, 0.0f);
    setp(machine::Molt::VelocityAmount, 0.0f);
    m.params().jumpAll();
    m.swapObject(0, &u);

    constexpr int32_t kBlock = 64;
    const int32_t hold = static_cast<int32_t>(kSr * 2.0f);
    const int32_t total = static_cast<int32_t>(kSr * 4.0f);
    std::vector<float> stereo;
    stereo.reserve(static_cast<size_t>(total) * 2);
    m.noteOn(static_cast<uint8_t>(kNote), 100);
    float L[kBlock], R[kBlock];
    for (int32_t at = 0; at < total; at += kBlock) {
        if (at >= hold && at - kBlock < hold) m.noteOff(static_cast<uint8_t>(kNote));
        for (int32_t i = 0; i < kBlock; ++i) { L[i] = 0.0f; R[i] = 0.0f; }
        m.render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) {
            stereo.push_back(L[i]);
            stereo.push_back(R[i]);
        }
    }
    return stereo;
}

void printMeasured(const audition::Measured &m, const char *what) {
    std::printf("    %-20s loud %6.1f   harm %5.3f   part %5.2f   click %5.1fx   low %6.1f dB\n",
                what, static_cast<double>(m.loudnessDb), static_cast<double>(m.harmonicity),
                static_cast<double>(m.partialRatio), static_cast<double>(m.clickRatio),
                static_cast<double>(m.lowDb));
}

} // namespace

int main() {
    const char *path = std::getenv("ACIDULOUS_INPUT_FILE");
    const bool real = path != nullptr && *path != '\0';
    std::printf("molt_probe - %s\n\n", real ? path : "the synthetic phrase (no ACIDULOUS_INPUT_FILE)");

    const std::unique_ptr<Utterance> u = audition::voiceUtterance();
    if (!u->usable()) {
        std::printf("  the take is unusable - %d frames, %zu marks\n", u->frames, u->epochs.size());
        return 1;
    }

    std::printf("the take, as Molt is given it\n");
    printTake(u->mono, real ? "recording" : "synthetic phrase");
    printTrack(u->mono);
    printEpochs(*u);
    // The take measured as if it were a render, so the two rows below are in
    // the same units and the machine's contribution is the difference.
    printMeasured(audition::measure(asStereo(u->mono), static_cast<int64_t>(u->frames), kNote), "as if rendered");

    std::printf("\nthe note, rendered\n");
    const std::vector<float> out = renderNote(*u);
    printMeasured(audition::measure(out, static_cast<int64_t>(kSr * 2.0f), kNote), "C3 held 2 s");
    std::printf("\n");
    return 0;
}
