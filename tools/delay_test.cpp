// The send delay, read at every position it can ever be read at.
//
// Written after a crash: a tempo that moves every block keeps the read
// position gliding, and a glide eventually lands exactly on the end of the
// buffer, where the wrap-around arithmetic used to leave it. A tempo that
// sits still almost never does, which is why this went years unnoticed and
// then fell over the day the engine started following a Link session.
//
// Built with the address sanitiser: the assertion is not a number, it is
// that nothing reads outside the buffer over several million samples of
// every tempo and every delay time.
#include <cmath>
#include <cstdio>
#include <engine/dsp/Delay.h>

using namespace acidulous::dsp;

/**
 * Every read position the wrap can produce, over the values that actually
 * bite: a write head just short of the delay, at every representable float
 * between one sample and the next. One of these - a fraction of exactly one
 * ULP - is what crashed the app, and it is the reason readIndex exists.
 */
int indexTests() {
    int bad = 0, tried = 0, atEnd = 0;
    const int32_t size = 96000; // two seconds at 48 kHz, as prepare() makes it
    for (int32_t wr : {0, 1, 47999, 48000, 48001, 65535, 65536, 95998, 95999}) {
        // The float grid around wr, a couple of hundred steps either side,
        // which at this magnitude is a step of one ULP.
        for (int step = -256; step <= 256; ++step) {
            float samples = static_cast<float>(wr);
            for (int n = 0; n < (step < 0 ? -step : step); ++n) {
                samples = step < 0 ? std::nextafterf(samples, 0.0f)
                                   : std::nextafterf(samples, 1e9f);
            }
            if (samples < 1.0f || samples > static_cast<float>(size - 2)) continue;
            float frac = -1.0f;
            const int r = Delay::readIndex(wr, samples, size, frac);
            ++tried;
            if (r < 0 || r >= size) ++bad;
            if (frac < 0.0f || frac >= 1.0f) ++bad;
            // Count the ones where the naive expression would have gone off
            // the end, so this test is seen to be testing something.
            float naive = static_cast<float>(wr) - samples;
            while (naive < 0.0f) naive += static_cast<float>(size);
            if (static_cast<int>(naive) >= size) ++atEnd;
        }
    }
    // And the states nothing should survive: a delay that is not a number.
    {
        float frac = -1.0f;
        const int r = Delay::readIndex(1234, std::nanf(""), size, frac);
        if (r < 0 || r >= size || frac < 0.0f || frac >= 1.0f) ++bad;
        ++tried;
    }
    printf("  %s %d read positions in range (%d of them would have gone off the end)\n",
           bad == 0 ? "ok  " : "FAIL", tried, atEnd);
    if (atEnd == 0) {
        printf("  FAIL the grid never reaches the case this is here for\n");
        return 1;
    }
    return bad == 0 ? 0 : 1;
}

int main() {
    if (indexTests() != 0) return 1;
    Delay d;
    d.prepare(48000);

    float in[64], outL[64], outR[64];
    for (int i = 0; i < 64; ++i) in[i] = 0.25f * std::sin(i * 0.1f);

    int blocks = 0;
    // Every delay time, swept from the slowest tempo to the fastest and back,
    // in the steps a Link pull actually takes: a few hundredths of a bpm a
    // block, which is what lands the read position on awkward values.
    for (int t = 0; t < Delay::kTimes; ++t) {
        for (int pass = 0; pass < 2; ++pass) {
            for (int step = 0; step < 4000; ++step) {
                const float x = static_cast<float>(step) / 4000.0f;
                const float sweep = pass == 0 ? x : 1.0f - x;
                const float bpm = 20.0f + sweep * 280.0f; // 20 .. 300
                d.set(t, 0.6f, 0.5f, (t & 1) != 0, bpm);
                for (int i = 0; i < 64; ++i) { outL[i] = 0.0f; outR[i] = 0.0f; }
                d.process(in, outL, outR, 64);
                ++blocks;
                for (int i = 0; i < 64; ++i) {
                    if (!std::isfinite(outL[i]) || !std::isfinite(outR[i])) {
                        printf("  FAIL not a number at block %d\n", blocks);
                        return 1;
                    }
                }
            }
        }
    }

    // And the pathological one: a tempo that jumps rather than glides, which
    // is what a peer joining a Link session at a different tempo looks like.
    for (int step = 0; step < 20000; ++step) {
        d.set(step % Delay::kTimes, 0.9f, 0.2f, false, (step % 2) == 0 ? 240.0f : 30.0f);
        for (int i = 0; i < 64; ++i) { outL[i] = 0.0f; outR[i] = 0.0f; }
        d.process(in, outL, outR, 64);
        ++blocks;
    }

    printf("  ok   %d blocks, %d samples, nothing outside the buffer\n", blocks, blocks * 64);
    printf("\n2 checks, 0 failures\n");
    return 0;
}
