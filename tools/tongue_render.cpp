#include <algorithm>
// Renders Tongue to a WAV file, so tools/tongue_reference/measure.py can read
// it as it read the recordings.
//
//   tongue_render <out.wav> [--note N] [--velocity V] [--seconds S]
//                 [--rate plucks-a-second] [--hold S] [--wheel sweeps-a-second] [--spread V]
//                 [name=value ...]
//
// One pluck by default; --rate plucks again that often, as the recordings are
// played. --hold lets the key go after S seconds (default: held to the end).
// --wheel moves the mod wheel (the mouth) up and down that often. --spread
// varies each pluck's velocity by up to that much either way, and its time by
// up to 15 ms, as a player does.
// name=value sets a parameter by its name, in its own units ("edge=0.8").
#include <engine/core/Constants.h>
#include <engine/format/WavWriter.h>
#include <engine/machine/MachineRegistry.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <xmmintrin.h>

using namespace acidulous;

int main(int argc, char **argv) {
    _mm_setcsr(_mm_getcsr() | 0x8040);
    if (argc < 2) {
        std::fprintf(stderr, "usage: tongue_render <out.wav> [--note N] [--velocity V] [--seconds S] [--rate R] [--hold S] [--wheel R] [--spread V] [name=value ...]\n");
        return 2;
    }
    const std::string out = argv[1];
    int note = 55, velocity = 100;
    float seconds = 3.0f, rate = 0.0f, hold = -1.0f, wheel = 0.0f;
    int spread = 0;
    uint32_t seed = 12345u;
    std::unique_ptr<Machine> m(MachineRegistry::create("Tongue"));
    m->prepare(kSampleRate);
    m->reset();
    ParamSet &p = m->params();
    p.jumpAll();
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::atof(argv[++i]) : 0.0; };
        if (a == "--note") note = static_cast<int>(next());
        else if (a == "--velocity") velocity = static_cast<int>(next());
        else if (a == "--seconds") seconds = static_cast<float>(next());
        else if (a == "--rate") rate = static_cast<float>(next());
        else if (a == "--hold") hold = static_cast<float>(next());
        else if (a == "--wheel") wheel = static_cast<float>(next());
        else if (a == "--spread") spread = static_cast<int>(next());
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
    if (!w.open(out, kSampleRate, 24, error)) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    const int64_t total = static_cast<int64_t>(seconds * kSampleRate);
    const int64_t every = rate > 0.0f ? static_cast<int64_t>(kSampleRate / rate) : total + 1;
    const int64_t release = hold >= 0.0f ? static_cast<int64_t>(hold * kSampleRate) : total + 1;
    float L[kBlockFrames], R[kBlockFrames], inter[kBlockFrames * 2];
    float peak = 0.0f;
    double power = 0.0;
    bool down = false;
    int64_t nextPluck = 0;
    for (int64_t t = 0; t < total; t += kBlockFrames) {
        if (t >= nextPluck) {
            seed = seed * 1664525u + 1013904223u;
            const int64_t jitter = spread > 0 ? static_cast<int64_t>((seed >> 12) % 1441u) - 720 : 0;
            nextPluck += every + jitter;
            const int v = velocity + (spread > 0 ? static_cast<int>((seed >> 8) % static_cast<uint32_t>(2 * spread + 1)) - spread : 0);
            m->noteOn(static_cast<uint8_t>(note), static_cast<uint8_t>(std::clamp(v, 1, 127)));
            down = true;
        }
        if (down && t >= release) { m->noteOff(static_cast<uint8_t>(note)); down = false; }
        if (wheel > 0.0f) {
            const float phase = std::fmod(static_cast<float>(t) / kSampleRate * wheel, 1.0f);
            const float up = phase < 0.5f ? 2.0f * phase : 2.0f - 2.0f * phase;
            m->controlChange(1, static_cast<uint8_t>(std::lround(up * 127.0f)));
        }
        m->render(L, R, kBlockFrames);
        for (int i = 0; i < kBlockFrames; ++i) {
            inter[2 * i] = L[i];
            inter[2 * i + 1] = R[i];
            peak = std::fmax(peak, std::fabs(L[i]));
            power += static_cast<double>(L[i]) * L[i];
        }
        w.write(inter, kBlockFrames);
    }
    w.close();
    std::printf("%s: peak %.1f dBFS, rms %.1f dBFS\n", out.c_str(), 20 * std::log10(peak + 1e-12),
                10 * std::log10(power / static_cast<double>(total) + 1e-20));
    return 0;
}
