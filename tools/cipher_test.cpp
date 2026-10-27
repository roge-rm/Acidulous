// Cipher, the vocoder: a panic puts it back, and `unvoiced` hisses on a
// consonant and leaves a vowel alone.
//
//   cipher_test.sh
#include <engine/core/InputBus.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/cipher/Cipher.h>

#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

using namespace acidulous;
using acidulous::machine::Cipher;

namespace {

constexpr int32_t kRate = 48000;
constexpr int32_t kBlock = 64;
int failures = 0, checks = 0;

std::string fmt(const char *f, ...) {
    char buf[256];
    va_list a;
    va_start(a, f);
    std::vsnprintf(buf, sizeof(buf), f, a);
    va_end(a);
    return buf;
}

void check(bool ok, const char *what, const std::string &detail = "") {
    ++checks;
    if (!ok) ++failures;
    std::printf("  %s %-58s %s\n", ok ? "ok  " : "FAIL", what, detail.c_str());
}

/**
 * Half a second of a vowel, a buzz at 120 Hz with its energy under 1 kHz,
 * then half a second of an "s", noise above 4 kHz. Both at about -20 dBFS rms.
 */
std::vector<float> vowelThenS() {
    const int32_t half = kRate / 2;
    std::vector<float> out(static_cast<size_t>(half) * 2, 0.0f);
    float lp1 = 0.0f, lp2 = 0.0f, phase = 0.0f;
    for (int32_t i = 0; i < half; ++i) {
        phase += 120.0f / kRate;
        if (phase >= 1.0f) phase -= 1.0f;
        const float saw = 2.0f * phase - 1.0f;
        lp1 += (saw - lp1) * 0.08f;
        lp2 += (lp1 - lp2) * 0.08f;
        out[static_cast<size_t>(i)] = lp2;
    }
    uint32_t r = 12345u;
    float px = 0.0f, py = 0.0f, py2 = 0.0f, px2 = 0.0f;
    const float a = std::exp(-2.0f * 3.14159265f * 4000.0f / kRate);
    for (int32_t i = half; i < 2 * half; ++i) {
        r = r * 1664525u + 1013904223u;
        const float n = static_cast<float>(r >> 9) / 4194304.0f - 1.0f;
        py = a * (py + n - px);
        px = n;
        py2 = a * (py2 + py - px2);
        px2 = py;
        out[static_cast<size_t>(i)] = py2;
    }
    // Each half to -20 dBFS rms.
    for (int h = 0; h < 2; ++h) {
        double s = 0.0;
        for (int32_t i = h * half; i < (h + 1) * half; ++i) s += out[static_cast<size_t>(i)] * out[static_cast<size_t>(i)];
        const float g = 0.1f / static_cast<float>(std::sqrt(s / half) + 1e-12);
        for (int32_t i = h * half; i < (h + 1) * half; ++i) out[static_cast<size_t>(i)] *= g;
    }
    return out;
}

void setParam(Machine *m, const char *name, float value) {
    int32_t n = 0;
    const ParamDef *defs = m->paramDefs(n);
    for (int32_t i = 0; i < n; ++i) {
        if (std::strcmp(defs[i].name, name) == 0) {
            m->params().set(i, defs[i].unmap(value));
            m->params().jumpAll();
            return;
        }
    }
    std::printf("  no parameter %s\n", name);
    ++failures;
}

/** Plays [input] through [m] with a note held, from [offset] frames into it. Mono out. */
std::vector<float> play(Machine *m, const std::vector<float> &input, size_t offset, int32_t frames) {
    std::vector<float> out;
    float L[kBlock], R[kBlock], in[kBlock * 2];
    m->noteOn(48, 100);
    for (int32_t at = 0; at < frames; at += kBlock) {
        for (int32_t i = 0; i < kBlock; ++i) {
            const float v = input[(offset + static_cast<size_t>(at + i)) % input.size()];
            in[i * 2] = in[i * 2 + 1] = v;
        }
        InputBus::get().publish(in, kBlock);
        m->onBlock(at, at + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(0.5f * (L[i] + R[i]));
    }
    m->noteOff(48);
    InputBus::get().publish(nullptr, 0);
    return out;
}

std::unique_ptr<Machine> cipher(float unvoiced) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Cipher"));
    m->prepare(kRate);
    setParam(m.get(), "unvoiced", unvoiced);
    // The sibilance path hisses on an "s" too, and would hide this one.
    setParam(m.get(), "sibilance", 0.0f);
    panicMachine(*m);
    return m;
}

/** Energy above 4 kHz over [from, to), in dB. */
double highDb(const std::vector<float> &x, size_t from, size_t to) {
    const float a = std::exp(-2.0f * 3.14159265f * 4000.0f / kRate);
    float px = 0.0f, py = 0.0f;
    double s = 0.0;
    for (size_t i = 0; i < to && i < x.size(); ++i) {
        py = a * (py + x[i] - px);
        px = x[i];
        if (i >= from) s += static_cast<double>(py) * py;
    }
    return 10.0 * std::log10(s / static_cast<double>(to - from) + 1e-30);
}

void resetIsComplete(float unvoiced) {
    const std::vector<float> input = vowelThenS();
    auto m = cipher(unvoiced);
    const auto a = play(m.get(), input, 0, kRate);
    // Something else first, then a panic: the analysis, the noise and the
    // carrier all carry on from it unless reset() puts them back.
    play(m.get(), input, 7777, kRate / 2);
    m->pitchBend(4000);
    panicMachine(*m);
    const auto b = play(m.get(), input, 0, kRate);
    double worst = 0.0;
    for (size_t i = 0; i < a.size(); ++i) worst = std::fmax(worst, std::fabs(a[i] - b[i]));
    check(worst == 0.0, fmt("a panic puts it back, unvoiced %.1f", unvoiced).c_str(), fmt("worst %.3g", worst));
}

void unvoicedOnConsonants() {
    const std::vector<float> input = vowelThenS();
    auto off = cipher(0.0f), on = cipher(1.0f);
    const auto x = play(off.get(), input, 0, kRate);
    const auto y = play(on.get(), input, 0, kRate);
    const size_t half = kRate / 2;
    // Past the first 100 ms of each half, where the followers settle.
    const size_t settle = kRate / 10;
    const double sOff = highDb(x, half + settle, 2 * half), sOn = highDb(y, half + settle, 2 * half);
    check(sOn - sOff > 6.0, "unvoiced raises the hiss on an \"s\"", fmt("%+.1f dB above 4 kHz", sOn - sOff));
    double diff = 0.0, sig = 0.0;
    for (size_t i = settle; i < half; ++i) {
        diff += static_cast<double>(x[i] - y[i]) * (x[i] - y[i]);
        sig += static_cast<double>(x[i]) * x[i];
    }
    const double rel = 10.0 * std::log10(diff / (sig + 1e-30) + 1e-30);
    check(rel < -60.0, "and leaves a vowel alone", fmt("difference %.0f dB under the vowel", rel));
}

/** [play] from the start of [input], with `unvoiced` jumped to 0 at [dropAt] frames. */
std::vector<float> playDropping(Machine *m, const std::vector<float> &input, int32_t frames, int32_t dropAt) {
    std::vector<float> out;
    float L[kBlock], R[kBlock], in[kBlock * 2];
    m->noteOn(48, 100);
    for (int32_t at = 0; at < frames; at += kBlock) {
        if (at == dropAt) setParam(m, "unvoiced", 0.0f);
        for (int32_t i = 0; i < kBlock; ++i) {
            const float v = input[static_cast<size_t>(at + i) % input.size()];
            in[i * 2] = in[i * 2 + 1] = v;
        }
        InputBus::get().publish(in, kBlock);
        m->onBlock(at, at + 4, 120.0f);
        std::memset(L, 0, sizeof(L));
        std::memset(R, 0, sizeof(R));
        m->render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(0.5f * (L[i] + R[i]));
    }
    m->noteOff(48);
    InputBus::get().publish(nullptr, 0);
    return out;
}

void droppingMidConsonant() {
    // Turned down during the "s": the noise has to fade out, not stop.
    const std::vector<float> input = vowelThenS();
    const int32_t drop = (kRate * 3 / 4) / kBlock * kBlock;
    auto held = cipher(1.0f), dropped = cipher(1.0f);
    const auto y = play(held.get(), input, 0, kRate);
    const auto z = playDropping(dropped.get(), input, kRate, drop);
    // How far it has moved from the held render in the first quarter of a
    // millisecond after the drop, against the level there.
    double diff = 0.0, sig = 0.0;
    for (size_t i = static_cast<size_t>(drop); i < static_cast<size_t>(drop + kRate / 4000); ++i) {
        diff += static_cast<double>(z[i] - y[i]) * (z[i] - y[i]);
        sig += static_cast<double>(y[i]) * y[i];
    }
    const double rel = 10.0 * std::log10(diff / (sig + 1e-30) + 1e-30);
    check(rel < -30.0, "turning it down during an \"s\" doesn't step", fmt("%.1f dB of change in 0.25 ms", rel));
}

} // namespace

int main() {
    std::printf("Cipher\n");
    resetIsComplete(0.0f);
    resetIsComplete(0.5f);
    unvoicedOnConsonants();
    droppingMidConsonant();
    std::printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
