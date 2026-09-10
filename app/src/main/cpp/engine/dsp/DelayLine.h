#pragma once
#include <cstdint>
#include <vector>

// A circular buffer with a fractional read. Allocated in prepare().
namespace acidulous::dsp {

class DelayLine {
  public:
    void prepare(int32_t maxSamples) { buf.assign(static_cast<size_t>(maxSamples < 2 ? 2 : maxSamples), 0.0f); wr = 0; }
    void clear() { for (auto &v : buf) v = 0.0f; }
    int32_t capacity() const { return static_cast<int32_t>(buf.size()); }
    void write(float v) { buf[static_cast<size_t>(wr)] = v; if (++wr >= capacity()) wr = 0; }
    // `samples` back from the write position, 1 <= samples < capacity.
    float read(float samples) const {
        const int32_t cap = capacity();
        if (samples < 1.0f) samples = 1.0f;
        if (samples > static_cast<float>(cap - 2)) samples = static_cast<float>(cap - 2);
        float pos = static_cast<float>(wr) - samples;
        while (pos < 0.0f) pos += static_cast<float>(cap);
        const auto i0 = static_cast<int32_t>(pos);
        const float frac = pos - static_cast<float>(i0);
        const int32_t i1 = (i0 + 1) % cap;
        return buf[static_cast<size_t>(i0)] * (1.0f - frac) + buf[static_cast<size_t>(i1)] * frac;
    }

  private:
    std::vector<float> buf;
    int32_t wr = 0;
};

} // namespace acidulous::dsp
