#pragma once
#include <cstdint>
#include <vector>

// Our own wavetables: eight tables of eight frames, band-limited into mip
// levels so a scan never aliases. Built once, on a normal thread, and shared
// by every voice of every Trinity (the data is read-only after construction).
//
// Each frame is defined by a harmonic recipe, so band-limiting is free: a mip
// simply stops summing at the harmonic that would cross Nyquist.
namespace acidulous::dsp {

class WavetableBank {
  public:
    static constexpr int kTables = 8;
    static constexpr int kFrames = 8;
    static constexpr int kMips = 10;
    static constexpr int kSize = 1024;
    static constexpr int kMaxHarmonics = 256;

    // Blocks on first call while the tables are built (~0.2 s); mount-thread only.
    static const WavetableBank &instance();

    static const char *tableName(int table);

    // Which mip a fundamental of `hz` must read from.
    static int mipFor(float hz) {
        int m = 0;
        float limit = 40.0f;
        while (m < kMips - 1 && hz > limit) { limit *= 2.0f; ++m; }
        return m;
    }

    // frame is 0..kFrames-1 with `frac` between it and the next; phase in [0,1).
    float sample(int table, int frame, float frac, int mip, float phase) const {
        const float *a = row(table, frame, mip);
        const float *b = row(table, frame + 1 < kFrames ? frame + 1 : frame, mip);
        const float x = phase * static_cast<float>(kSize);
        int i = static_cast<int>(x);
        if (i < 0) i = 0;
        else if (i >= kSize) i = kSize - 1;
        const float f = x - static_cast<float>(i);
        const float va = a[i] + (a[i + 1] - a[i]) * f;
        const float vb = b[i] + (b[i + 1] - b[i]) * f;
        return va + (vb - va) * frac;
    }

  private:
    WavetableBank();
    /**
     * **Mip outside frame, not the other way round.**
     *
     * `sample` interpolates between two adjacent *frames* at one mip, so those
     * two rows are read together on every sample of every oscillator. Laid out
     * `[table][frame][mip]` they sat `kMips` rows apart - ten times 1025
     * floats, or **41 KB** - which is past the first-level data cache on the
     * phones this has to run on, so the pair could not stay resident and each
     * sample paid for two lines a long way apart. The whole bank is 2.6 MB, so
     * there is no question of it all fitting; the only thing that helps is
     * putting what is read together next to each other.
     *
     * Swapped, adjacent frames are one row apart - 4.1 KB - and the mip, which
     * is held constant for sixteen samples at a time, is the outer index.
     * Identical numbers come out; only their addresses change.
     */
    const float *row(int table, int frame, int mip) const {
        return data.data() + ((static_cast<size_t>(table) * kMips + static_cast<size_t>(mip)) * kFrames +
                              static_cast<size_t>(frame)) * (kSize + 1);
    }
    std::vector<float> data; // [table][mip][frame][kSize + 1], last sample duplicates the first
};

} // namespace acidulous::dsp
