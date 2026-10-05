// Renders Draw to a WAV file, so tools/draw_reference/measure.py can read it
// as it read the recordings.
//
//   draw_render <out.wav> [--note N[,N...]] [--velocity V] [--seconds S]
//               [--hold S] [--pressure P] [name=value ...]
// One note (or a chord) held for --hold seconds (default: to the end), then
// let go. --pressure sends that channel pressure (0 to 127) all along.
#include <engine/core/Constants.h>
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>
#include <xmmintrin.h>

using namespace acidulous;

namespace {
constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
} // namespace

int main(int argc, char **argv) {
    _mm_setcsr(_mm_getcsr() | 0x8040);
    if (argc < 2) {
        std::fprintf(stderr, "usage: draw_render <out.wav> [--note N[,N]] [--velocity V] [--seconds S] [--hold S] [--pressure P] [name=value ...]\n");
        return 2;
    }
    const std::string out = argv[1];
    std::vector<int> notes = {60};
    int velocity = 100, pressure = -1;
    float seconds = 3.0f, hold = -1.0f;
    std::unique_ptr<Machine> m(MachineRegistry::create("Draw"));
    m->prepare(kRate);
    m->reset();
    ParamSet &p = m->params();
    p.jumpAll();
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::atof(argv[++i]) : 0.0; };
        if (a == "--note" && i + 1 < argc) {
            notes.clear();
            std::string all = argv[++i];
            for (size_t from = 0; from < all.size();) {
                notes.push_back(std::atoi(all.c_str() + from));
                const size_t comma = all.find(',', from);
                if (comma == std::string::npos) break;
                from = comma + 1;
            }
        } else if (a == "--velocity") velocity = static_cast<int>(next());
        else if (a == "--seconds") seconds = static_cast<float>(next());
        else if (a == "--hold") hold = static_cast<float>(next());
        else if (a == "--pressure") pressure = static_cast<int>(next());
        else if (a.find('=') != std::string::npos) {
            const std::string name = a.substr(0, a.find('='));
            const float value = static_cast<float>(std::atof(a.c_str() + a.find('=') + 1));
            const int idx = p.indexOf(name.c_str());
            if (idx < 0) { std::fprintf(stderr, "no parameter %s\n", name.c_str()); return 2; }
            p.jump(idx, p.def(idx).unmap(value));
        }
    }
    WavWriter w;
    std::string error;
    if (!w.open(out, kRate, 24, error)) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    const int64_t total = static_cast<int64_t>(seconds * kRate);
    const int64_t release = hold >= 0.0f ? static_cast<int64_t>(hold * kRate) : total + 1;
    if (pressure >= 0) m->channelPressure(static_cast<uint8_t>(pressure));
    for (int n : notes) m->noteOn(static_cast<uint8_t>(n), static_cast<uint8_t>(velocity));
    float L[kBlock], R[kBlock], inter[kBlock * 2];
    float peak = 0.0f;
    double power = 0.0;
    bool down = true;
    for (int64_t t = 0; t < total; t += kBlock) {
        if (down && t >= release) {
            for (int n : notes) m->noteOff(static_cast<uint8_t>(n));
            down = false;
        }
        m->render(L, R, kBlock);
        for (int i = 0; i < kBlock; ++i) {
            inter[2 * i] = L[i];
            inter[2 * i + 1] = R[i];
            peak = std::fmax(peak, std::fabs(L[i]));
            power += static_cast<double>(L[i]) * L[i];
        }
        w.write(inter, kBlock);
    }
    w.close();
    std::printf("%s: peak %.1f dBFS, rms %.1f dBFS\n", out.c_str(), 20 * std::log10(peak + 1e-12),
                10 * std::log10(power / static_cast<double>(total) + 1e-20));
    return 0;
}
