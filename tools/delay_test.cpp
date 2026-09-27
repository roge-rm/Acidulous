// Reads every circular buffer in the engine at every position it can be read
// at. A tempo that changes every block (e.g. following Link) keeps the read
// position gliding, and it can land exactly on the end of the buffer where a
// naive wrap reads out of bounds.
//
// Built with the address sanitiser, so the test is that nothing reads
// outside a buffer over millions of samples at every tempo and delay time.
#include <cmath>
#include <cstdio>
#include <engine/dsp/Delay.h>
#include <engine/dsp/DelayLine.h>
#include <engine/machine/filament/Waveguide.h>

using namespace acidulous::dsp;

/**
 * Every read position the wrap can produce around the risky values: a write
 * head just short of the delay, at every float between one sample and the
 * next. A fraction of one ULP there is what readIndex guards against.
 */
int indexTests() {
    int bad = 0, tried = 0, atEnd = 0;
    const int32_t size = 96000; // two seconds at 48 kHz, as prepare() makes it
    for (int32_t wr : {0, 1, 47999, 48000, 48001, 65535, 65536, 95998, 95999}) {
        // A couple of hundred floats either side of wr, one ULP apart.
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
            // Count the cases where a naive wrap would go past the end, to
            // show the test hits them.
            float naive = static_cast<float>(wr) - samples;
            while (naive < 0.0f) naive += static_cast<float>(size);
            if (static_cast<int>(naive) >= size) ++atEnd;
        }
    }
    // A delay that isn't a number must not read out of bounds either.
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

/**
 * `DelayLine` and `Waveguide`, which also read at fractional positions. A
 * 10 ms reverb pre-delay at 48 kHz is 480.000031 samples, and 480 minus that
 * plus the buffer length is exactly the buffer length, one past the end.
 *
 * Delays are swept across the floats around each write head, since round
 * numbers never hit the bad values.
 */
int bufferTests() {
    int tried = 0, atEnd = 0;
    // Real sizes: a reverb pre-delay, its combs, the shimmer window and a low
    // string's loop.
    for (int32_t cap : {10080, 2314, 5760, 2666, 96}) {
        // Put the write head where the problem happens. From zero the wrap
        // can't land on the length, but the reverb's first read happens with
        // the head a few hundred samples in.
        for (int32_t wr : {1, 2, 3, 480, cap / 4, cap / 2, cap - 2, cap - 1}) {
            if (wr >= cap - 1) continue;
            acidulous::dsp::DelayLine line;
            line.prepare(cap);
            for (int32_t i = 0; i < wr; ++i) line.write(0.01f * static_cast<float>(i % 17));
            for (int step = -300; step <= 300; ++step) {
                float samples = static_cast<float>(wr);
                for (int n = 0; n < (step < 0 ? -step : step); ++n) {
                    samples = step < 0 ? std::nextafterf(samples, 0.0f) : std::nextafterf(samples, 1e9f);
                }
                if (samples < 1.0f || samples > static_cast<float>(cap - 2)) continue;
                float frac = 0.0f;
                const int32_t i0 = acidulous::dsp::wrappedReadIndex(wr, samples, cap, frac);
                ++tried;
                if (i0 < 0 || i0 >= cap || frac < 0.0f || frac >= 1.0f) {
                    printf("  FAIL index %d frac %g out of range for cap %d\n", i0, (double)frac, cap);
                    return 1;
                }
                float naive = static_cast<float>(wr) - samples;
                while (naive < 0.0f) naive += static_cast<float>(cap);
                if (static_cast<int>(naive) >= cap) ++atEnd;
                // Also read through the real class so the sanitiser sees the
                // actual load.
                const float v = line.read(samples);
                if (!std::isfinite(v)) { printf("  FAIL DelayLine returned a non-number\n"); return 1; }
            }
        }
    }
    // The string at every pitch, gliding, since a gliding loop length is what
    // hits the bad value.
    {
        acidulous::machine::Waveguide wg;
        wg.prepare(48000.0f);
        for (int step = 0; step < 4000; ++step) {
            const float hz = 20.0f + static_cast<float>(step) * 1.9f;
            wg.setFrequency(hz);
            wg.setDamping(0.99f, 0.5f);
            wg.setDispersion(0.4f, 4);
            wg.setTension(0.3f);
            if (step % 97 == 0) wg.excite(0.5f);
            for (int i = 0; i < 16; ++i) {
                const float v = wg.step(0.0f);
                if (!std::isfinite(v)) { printf("  FAIL the string returned a non-number\n"); return 1; }
                ++tried;
            }
        }
    }
    printf("  %s %d buffer reads in range (%d would have gone off the end)\n",
           atEnd > 0 ? "ok  " : "FAIL", tried, atEnd);
    if (atEnd == 0) {
        printf("  FAIL the grid never reaches the case this is here for\n");
        return 1;
    }
    return 0;
}

int main() {
    if (indexTests() != 0) return 1;
    if (bufferTests() != 0) return 1;
    Delay d;
    d.prepare(48000);

    float in[64], outL[64], outR[64];
    for (int i = 0; i < 64; ++i) in[i] = 0.25f * std::sin(i * 0.1f);

    int blocks = 0;
    // Every delay time, swept from the slowest tempo to the fastest and back
    // in small steps like Link makes (a few hundredths of a bpm a block).
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

    // A tempo that jumps rather than glides, like a peer joining a Link
    // session at a different tempo.
    for (int step = 0; step < 20000; ++step) {
        d.set(step % Delay::kTimes, 0.9f, 0.2f, false, (step % 2) == 0 ? 240.0f : 30.0f);
        for (int i = 0; i < 64; ++i) { outL[i] = 0.0f; outR[i] = 0.0f; }
        d.process(in, outL, outR, 64);
        ++blocks;
    }

    printf("  ok   %d blocks, %d samples, nothing outside the buffer\n", blocks, blocks * 64);
    printf("\n3 checks, 0 failures\n");
    return 0;
}
