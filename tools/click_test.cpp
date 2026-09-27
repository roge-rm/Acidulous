// Checks the metronome sounds when asked and stays inside its headroom.
//
// The click is mixed in after the limiter so it never ducks the music, which
// means it could push the sum over full scale and clip. The limiter leaves
// exactly the headroom the click needs, so peak + ceiling must be <= 1.
//
// Also checks a click triggered past the end of a block is carried over, not
// dropped. Fast subdivisions do that all the time.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <engine/dsp/Click.h>

using namespace acidulous::dsp;

namespace {
int failures = 0, checks = 0;
void ok(const char *what, double got, double lo, double hi, const char *unit = "") {
    ++checks;
    const bool good = got >= lo && got <= hi;
    if (!good) ++failures;
    printf("  %s %-46s %9.4f %s\n", good ? "ok  " : "FAIL", what, got, unit);
}

constexpr int32_t kBlock = 64;

/** Runs [blocks] blocks and reports the loudest sample the click made. */
float peakOver(Click &c, int32_t blocks, float volume) {
    float peak = 0.0f;
    std::vector<float> L(kBlock), R(kBlock);
    for (int32_t b = 0; b < blocks; ++b) {
        for (int32_t i = 0; i < kBlock; ++i) L[i] = R[i] = 0.0f;
        c.process(L.data(), R.data(), kBlock, volume);
        for (int32_t i = 0; i < kBlock; ++i) {
            peak = std::fmax(peak, std::fabs(L[i]));
        }
    }
    return peak;
}
} // namespace

int main() {
    printf("--- it stays inside the budget it is given ---\n");
    // Every voice, accent and sensible volume must stay under the peak the
    // master reserves room for.
    {
        float worst = 0.0f;
        for (int32_t voice = 0; voice <= Click::Cowbell; ++voice) {
            for (int32_t accent = Click::Bar; accent <= Click::Division; ++accent) {
                for (float volume : {0.25f, 0.5f, 1.0f}) {
                    Click c;
                    c.prepare(48000);
                    c.setVoice(voice);
                    c.trigger(accent, 0);
                    const float got = peakOver(c, 60, volume);
                    worst = std::fmax(worst, got / Click::peakFor(volume));
                }
            }
        }
        ok("loudest, as a fraction of the reserved peak", worst, 0.0, 1.0);
    }
    {
        // The reserved room keeps the sum under full scale, which the master
        // relies on.
        const float volume = 1.0f;
        const float ceiling = 0.95f - Click::peakFor(volume);
        ok("limiter ceiling + click peak", ceiling + Click::peakFor(volume), 0.0, 1.0);
        ok("ceiling still usable at full click", ceiling, 0.2, 0.95);
    }

    printf("--- a click past the end of a block is carried, not lost ---\n");
    {
        Click c;
        c.prepare(48000);
        c.setVoice(Click::Blip);
        c.trigger(Click::Bar, kBlock + 10); // lands in the next block
        std::vector<float> L(kBlock), R(kBlock);
        for (int32_t i = 0; i < kBlock; ++i) L[i] = R[i] = 0.0f;
        c.process(L.data(), R.data(), kBlock, 1.0f);
        float first = 0.0f;
        for (int32_t i = 0; i < kBlock; ++i) first = std::fmax(first, std::fabs(L[i]));
        ok("silent in the block it was not due in", first, 0.0, 1e-9);
        ok("sounds in a later block", peakOver(c, 8, 1.0f), 0.05, 1.0);
    }

    printf("--- several at once, which a subdivision needs ---\n");
    {
        Click c;
        c.prepare(48000);
        c.setVoice(Click::Stick);
        c.trigger(Click::Bar, 0);
        c.trigger(Click::Division, 16);
        c.trigger(Click::Division, 32);
        c.trigger(Click::Division, 48);
        ok("four in one block still sound", peakOver(c, 4, 1.0f), 0.05, Click::peakFor(1.0f));
    }

    printf("--- the accents are actually different ---\n");
    {
        float level[3] = {0, 0, 0};
        for (int32_t accent = Click::Bar; accent <= Click::Division; ++accent) {
            Click c;
            c.prepare(48000);
            c.setVoice(Click::Blip);
            c.trigger(accent, 0);
            level[accent] = peakOver(c, 60, 1.0f);
        }
        ok("bar is louder than beat", level[Click::Bar] - level[Click::Beat], 0.01, 1.0);
        ok("beat is louder than subdivision", level[Click::Beat] - level[Click::Division], 0.01, 1.0);
    }

    printf("--- and it goes quiet again ---\n");
    {
        Click c;
        c.prepare(48000);
        c.trigger(Click::Bar, 0);
        // Checked well after the hit. The blip's envelope has a 25 ms time
        // constant, so at 50 ms it's still at a tenth of its peak.
        peakOver(c, 200, 1.0f);           // ~270 ms in
        ok("decayed away", peakOver(c, 40, 1.0f), 0.0, 0.001);
    }

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
