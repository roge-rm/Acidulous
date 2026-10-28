// Timber's jets on a register: every note of each flute patch's range in
// tune on the octave and the twelfth, the bottom octave included, where the
// vented tube runs past the instrument.
//
//   timber_test.sh                       the checks
//   timber_test.sh measure PATCH REG     cents and level for every note of the range
//   timber_test.sh wav PATCH OUT.wav     the bottom octave at register 0, 1 and 2
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/timber/Timber.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "patchbank.h"

using namespace acidulous;
using namespace acidulous::audition;

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

double noteHz(double note) { return 440.0 * std::pow(2.0, (note - 69.0) / 12.0); }
double cents(double a, double b) { return 1200.0 * std::log2(a / b); }

Bank gBank;

const BankPatch *findPatch(const std::string &name) {
    for (const auto &p : gBank.patches) {
        if (p.name == name) return &p;
    }
    return nullptr;
}

/** A Timber patch from the bank, played block by block. */
struct Wind {
    std::unique_ptr<Machine> m;
    std::vector<float> out;

    Wind(const BankPatch &patch, std::initializer_list<std::pair<const char *, float>> knobs) {
        m.reset(MachineRegistry::create("Timber"));
        m->prepare(kRate);
        m->reset();
        int32_t count = 0;
        const ParamDef *defs = m->paramDefs(count);
        const Resolved res = resolve(patch, defs, count);
        for (int32_t i = 0; i < count; ++i) m->params().set(i, res.norm[static_cast<size_t>(i)]);
        for (const auto &k : knobs) {
            const int32_t i = m->params().indexOf(k.first);
            if (i < 0) { std::printf("no parameter %s\n", k.first); std::exit(2); }
            m->params().set(i, defs[i].unmap(k.second));
        }
        m->params().jumpAll();
    }
    void play(double seconds) {
        float L[kBlock], R[kBlock];
        const int blocks = static_cast<int>(seconds * kRate / kBlock);
        for (int b = 0; b < blocks; ++b) {
            m->onBlock(0, 0, 120.0f);
            m->render(L, R, kBlock);
            out.insert(out.end(), L, L + kBlock);
        }
    }
    /** The level at [hz] over [from]..[to] seconds, dB, Hann windowed. */
    double level(double hz, double from, double to) const {
        const auto a = static_cast<size_t>(from * kRate), b = std::min(out.size(), static_cast<size_t>(to * kRate));
        const double step = 6.283185307179586 * hz / kRate, wstep = 6.283185307179586 / static_cast<double>(b - a);
        // Rotators for the probe and the window, renormalised now and then.
        double pr = std::cos(step * static_cast<double>(a)), pi = -std::sin(step * static_cast<double>(a));
        const double sr = std::cos(step), si = -std::sin(step);
        double wr = 1.0, wi = 0.0;
        const double vr = std::cos(wstep), vi = std::sin(wstep);
        double re = 0.0, im = 0.0, wsum = 0.0;
        for (size_t i = a; i < b; ++i) {
            const double w = 0.5 - 0.5 * wr;
            re += out[i] * w * pr;
            im += out[i] * w * pi;
            wsum += w;
            const double npr = pr * sr - pi * si; pi = pr * si + pi * sr; pr = npr;
            const double nwr = wr * vr - wi * vi; wi = wr * vi + wi * vr; wr = nwr;
            if (((i - a) & 1023) == 1023) {
                const double m = 1.0 / std::sqrt(pr * pr + pi * pi), n = 1.0 / std::sqrt(wr * wr + wi * wi);
                pr *= m; pi *= m; wr *= n; wi *= n;
            }
        }
        return 20.0 * std::log10(std::sqrt(re * re + im * im) / wsum + 1e-12);
    }
    /** The loudest frequency in [lo, hi]: a coarse scan, then a golden-section search to a thousandth of a hertz. */
    double peak(double lo, double hi, double from, double to) const {
        const int steps = 40;
        const double coarse = (hi - lo) / steps;
        double best = lo, bestLevel = -1e9;
        for (int k = 0; k <= steps; ++k) {
            const double f0 = lo + coarse * k;
            const double l = level(f0, from, to);
            if (l > bestLevel) { bestLevel = l; best = f0; }
        }
        double x0 = best - coarse, x1 = best + coarse;
        const double g = 0.6180339887498949;
        double c = x1 - g * (x1 - x0), d = x0 + g * (x1 - x0);
        double lc = level(c, from, to), ld = level(d, from, to);
        while (x1 - x0 > 0.001) {
            if (lc > ld) { x1 = d; d = c; ld = lc; c = x1 - g * (x1 - x0); lc = level(c, from, to); }
            else { x0 = c; c = d; lc = ld; d = x0 + g * (x1 - x0); ld = level(d, from, to); }
        }
        return 0.5 * (x0 + x1);
    }
};

struct Reading {
    double cents = 0.0; // from the first four partials, weighted by their energy
    double note = 0.0;  // the fundamental, dB
    double under = 0.0; // the loudest of f/2 and f/3 against the fundamental, dB
    double rms = 0.0;   // the whole sound, dB, so the fundamental against it says the note is the one sounding
};

Reading measure(const BankPatch &patch, int note, int reg) {
    Wind w(patch, {{"register", static_cast<float>(reg)}, {"vibrato", 0.0f}, {"swell", 0.0f}});
    w.m->noteOn(static_cast<uint8_t>(note), 100);
    w.play(1.2);
    const double from = 0.6, to = 1.2;
    const double want = noteHz(note);
    Reading r;
    double num = 0.0, den = 0.0;
    for (int n = 1; n <= 4; ++n) {
        const double h = want * n;
        const double got = w.peak(h * 0.985, h * 1.015, from, to);
        const double l = w.level(got, from, to);
        if (n == 1) r.note = l;
        const double e = std::pow(10.0, l / 10.0);
        num += e * cents(got, h);
        den += e;
    }
    r.cents = num / den;
    const double half = w.level(w.peak(want * 0.47, want * 0.53, from, to), from, to);
    const double third = w.level(w.peak(want * 0.31, want * 0.355, from, to), from, to);
    r.under = std::max(half, third) - r.note;
    double sum = 0.0;
    const auto a = static_cast<size_t>(from * kRate), b = std::min(w.out.size(), static_cast<size_t>(to * kRate));
    for (size_t i = a; i < b; ++i) sum += double(w.out[i]) * w.out[i];
    r.rms = 10.0 * std::log10(sum / static_cast<double>(std::max<size_t>(1, b - a)) + 1e-20);
    return r;
}

bool isJet(const BankPatch &p) {
    for (const auto &v : p.values) {
        if (v.name == "family") return v.stepIndex == 2 || (v.stepIndex < 0 && v.number > 1.5);
    }
    return false;
}

void writeWav(const std::string &path, const std::vector<float> &x) {
    FILE *f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) { std::printf("cannot write %s\n", path.c_str()); std::exit(2); }
    const uint32_t data = static_cast<uint32_t>(x.size() * 4), rate = kRate, bytes = rate * 4;
    const uint32_t riff = 36 + data, fmtLen = 16;
    const uint16_t format = 3, channels = 1, align = 4, bits = 32;
    std::fwrite("RIFF", 1, 4, f); std::fwrite(&riff, 4, 1, f); std::fwrite("WAVEfmt ", 1, 8, f);
    std::fwrite(&fmtLen, 4, 1, f); std::fwrite(&format, 2, 1, f); std::fwrite(&channels, 2, 1, f);
    std::fwrite(&rate, 4, 1, f); std::fwrite(&bytes, 4, 1, f); std::fwrite(&align, 2, 1, f);
    std::fwrite(&bits, 2, 1, f); std::fwrite("data", 1, 4, f); std::fwrite(&data, 4, 1, f);
    std::fwrite(x.data(), 4, x.size(), f);
    std::fclose(f);
}

/** The bottom octave of [patch]'s range up and back, at register 0, then 1, then 2. */
void wav(const BankPatch &patch, const std::string &path) {
    std::vector<float> all;
    for (int reg = 0; reg <= 2; ++reg) {
        Wind w(patch, {{"register", static_cast<float>(reg)}});
        const double note = 0.45, gap = 0.05;
        for (int i = 0; i <= 12; ++i) {
            const int n = patch.low + i;
            w.m->noteOn(static_cast<uint8_t>(n), 100);
            w.play(note);
            w.m->noteOff(static_cast<uint8_t>(n));
            w.play(gap);
        }
        w.play(1.0);
        all.insert(all.end(), w.out.begin(), w.out.end());
    }
    writeWav(path, all);
}

} // namespace

int main(int argc, char **argv) {
    const char *root = std::getenv("ACIDULOUS_ROOT");
    std::string error;
    if (!readBank(std::string(root != nullptr ? root : ".") + "/tools/banks/Timber.bank", gBank, error)) {
        std::printf("%s\n", error.c_str());
        return 2;
    }
    if (argc >= 4 && std::strcmp(argv[1], "measure") == 0) {
        const BankPatch *p = findPatch(argv[2]);
        if (p == nullptr) { std::printf("no patch %s\n", argv[2]); return 2; }
        const int reg = std::atoi(argv[3]);
        const int hi = argc >= 5 ? std::min(p->high, std::atoi(argv[4])) : p->high;
        for (int n = p->low; n <= hi; ++n) {
            const Reading r = measure(*p, n, reg);
            std::printf("%3d %+7.1f c  note %6.1f dB  under %+6.1f dB  rms %6.1f dB\n", n, r.cents, r.note, r.under, r.rms);
        }
        return 0;
    }
    if (argc >= 6 && std::strcmp(argv[1], "one") == 0) {
        const BankPatch *p = findPatch(argv[2]);
        if (p == nullptr) { std::printf("no patch %s\n", argv[2]); return 2; }
        Wind w(*p, {{"register", static_cast<float>(std::atoi(argv[4]))}, {"vibrato", 0.0f}, {"swell", 0.0f}});
        w.m->noteOn(static_cast<uint8_t>(std::atoi(argv[3])), 100);
        w.play(1.2);
        writeWav(argv[5], w.out);
        return 0;
    }
    if (argc >= 4 && std::strcmp(argv[1], "wav") == 0) {
        const BankPatch *p = findPatch(argv[2]);
        if (p == nullptr) { std::printf("no patch %s\n", argv[2]); return 2; }
        wav(*p, argv[3]);
        return 0;
    }

    // Every jet patch on the octave and the twelfth, eight at a time.
    struct Job {
        const BankPatch *patch;
        int reg;
        int bodyNote = 0;
        std::vector<Reading> notes;
    };
    std::vector<Job> jobs;
    for (const auto &p : gBank.patches) {
        if (!isJet(p) || p.low < 0) continue;
        for (int reg = 1; reg <= 2; ++reg) jobs.push_back({&p, reg});
    }
    std::atomic<size_t> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < 8; ++t) {
        pool.emplace_back([&] {
            for (size_t j; (j = next++) < jobs.size();) {
                Job &job = jobs[j];
                Wind w(*job.patch, {});
                const ParamSet &ps = w.m->params();
                job.bodyNote = static_cast<int>(std::ceil(69.0 + 12.0 * std::log2(ps.target(ps.indexOf("body")) / 440.0) - 0.01));
                for (int n = job.patch->low; n <= job.patch->high; ++n) job.notes.push_back(measure(*job.patch, n, job.reg));
            }
        });
    }
    for (auto &t : pool) t.join();

    std::printf("- jets in tune on every register\n");
    for (const Job &job : jobs) {
        const BankPatch &p = *job.patch;
        double worst = 0.0;
        int at = -1, quiet = -1;
        for (size_t i = 0; i < job.notes.size(); ++i) {
            const Reading &r = job.notes[i];
            const int n = p.low + static_cast<int>(i);
            const bool speaks = r.note - r.rms > -6.0;
            // The bottom octave, where the vented tube is longer than the
            // instrument, has to speak on the register.
            if (!speaks && n < job.bodyNote + 12 && quiet < 0) quiet = n;
            if (speaks && std::fabs(r.cents) > std::fabs(worst)) { worst = r.cents; at = n; }
        }
        check(std::fabs(worst) < 6.0, fmt("%s, register %d, within 6 cents", p.name.c_str(), job.reg).c_str(),
              fmt("worst %+.1f cents at %d", worst, at));
        check(quiet < 0, fmt("%s, register %d, the bottom octave speaks", p.name.c_str(), job.reg).c_str(),
              quiet < 0 ? "" : fmt("not at %d", quiet));
    }
    std::printf("%d of %d checks failed\n", failures, checks);
    return failures == 0 ? 0 : 1;
}
