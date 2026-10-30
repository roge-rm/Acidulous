// Tests capturing a room: a made-up room is played the sweep through
// RoomSweep, block by block as the engine would, with latency, a noise floor,
// rumble, a speaker that distorts and a microphone that clips, and what
// RoomCapture recovers is checked against the room. The same checks are then
// run on a wrong inverse to prove they can fail.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/core/Constants.h>
#include <engine/core/RoomSweep.h>
#include <engine/dsp/Fft.h>
#include <engine/dsp/RoomCapture.h>
#include "venue_impulse.h"

using namespace acidulous;
namespace room = acidulous::dsp::room;

namespace {
int checks = 0, failures = 0;

void ok(const std::string &what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-58s %s\n", cond ? "ok  " : "FAIL", what.c_str(), detail.c_str());
    if (!cond) ++failures;
}

std::string num(double v) {
    char b[32];
    snprintf(b, sizeof b, "%.4g", v);
    return b;
}

constexpr int32_t kRate = kSampleRate;
constexpr int32_t kLatency = 1234;

/** Linear convolution by FFT. */
std::vector<float> convolve(const std::vector<float> &a, const std::vector<float> &b) {
    const size_t len = a.size() + b.size() - 1;
    int32_t n = 1;
    while (static_cast<size_t>(n) < len) n <<= 1;
    dsp::Fft fft(n);
    std::vector<float> ar(n), ai(n), br(n), bi(n);
    std::copy(a.begin(), a.end(), ar.begin());
    std::copy(b.begin(), b.end(), br.begin());
    fft.transform(ar.data(), ai.data(), false);
    fft.transform(br.data(), bi.data(), false);
    for (int32_t i = 0; i < n; ++i) {
        const float re = ar[i] * br[i] - ai[i] * bi[i];
        const float im = ar[i] * bi[i] + ai[i] * br[i];
        ar[i] = re;
        ai[i] = im;
    }
    fft.transform(ar.data(), ai.data(), true);
    ar.resize(len);
    return ar;
}

/** A band like the sweep's, 100 Hz to 8 kHz, so the room and what came back are compared where both have sound. */
std::vector<float> band(const std::vector<float> &x) {
    std::vector<float> y(x);
    auto biquad = [&](double f, bool high) {
        const double w = 2.0 * M_PI * f / kRate, q = 0.7071;
        const double al = std::sin(w) / (2 * q), cw = std::cos(w);
        double b0, b1, b2;
        if (high) { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0; }
        else { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0; }
        const double a0 = 1 + al, a1 = -2 * cw, a2 = 1 - al;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (float &v : y) {
            const double o = (b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1; x1 = v; y2 = y1; y1 = o;
            v = static_cast<float>(o);
        }
    };
    for (int i = 0; i < 2; ++i) { biquad(100.0, true); biquad(8000.0, false); }
    return y;
}

double correlation(const std::vector<float> &a, const std::vector<float> &b, size_t n) {
    double ab = 0, aa = 0, bb = 0;
    for (size_t i = 0; i < n && i < a.size() && i < b.size(); ++i) {
        ab += static_cast<double>(a[i]) * b[i];
        aa += static_cast<double>(a[i]) * a[i];
        bb += static_cast<double>(b[i]) * b[i];
    }
    return aa > 0 && bb > 0 ? ab / std::sqrt(aa * bb) : 0.0;
}

/** When the energy still to come falls [db] below the whole (Schroeder), seconds. */
double decayTo(const std::vector<float> &x, size_t n, double db) {
    std::vector<double> rest(std::min(n, x.size()) + 1, 0.0);
    for (size_t i = rest.size() - 1; i-- > 0;) rest[i] = rest[i + 1] + static_cast<double>(x[i]) * x[i];
    const double target = rest[0] * std::pow(10.0, db / 10.0);
    for (size_t i = 0; i < rest.size(); ++i) {
        if (rest[i] <= target) return static_cast<double>(i) / kRate;
    }
    return static_cast<double>(n) / kRate;
}

struct Room {
    std::vector<float> l, r;
};

/**
 * What the microphones hear: the speaker (soft-clipped, so it adds
 * harmonics) through the room, [kLatency] frames late, with rumble and a
 * noise floor, and the converter clipping.
 */
std::vector<float> hear(const std::vector<float> &out, const Room &rm, uint32_t seed) {
    std::vector<float> speaker(out.size());
    for (size_t i = 0; i < out.size(); ++i) speaker[i] = std::tanh(1.2f * out[i]) / 1.2f;
    const std::vector<float> l = convolve(speaker, rm.l), r = convolve(speaker, rm.r);
    std::vector<float> in(out.size() * 2);
    uint32_t s = seed;
    for (size_t i = 0; i < out.size(); ++i) {
        s = s * 1664525u + 1013904223u;
        const float hiss = 0.0005f * (static_cast<float>(s >> 9) * (2.0f / 8388608.0f) - 1.0f);
        const double t = static_cast<double>(i) / kRate;
        const float rumble = 0.02f * static_cast<float>(std::sin(2 * M_PI * 23.0 * t) + 0.5 * std::sin(2 * M_PI * 51.0 * t));
        const size_t j = i >= static_cast<size_t>(kLatency) ? i - kLatency : SIZE_MAX;
        const float a = j != SIZE_MAX ? l[j] : 0.0f, b = j != SIZE_MAX ? r[j] : 0.0f;
        in[i * 2] = a + hiss + rumble;
        in[i * 2 + 1] = b + hiss * 0.7f + rumble;
    }
    // The converter clips the loudest peaks: at 80% of the loudest.
    float top = 0.0f;
    for (float v : in) top = std::max(top, std::fabs(v));
    size_t clipped = 0;
    for (float &v : in) {
        if (std::fabs(v) > 0.8f * top) ++clipped;
        v = std::clamp(v, -0.8f * top, 0.8f * top);
    }
    printf("  heard: peak %.3f, %.3f%% of samples clipped\n", top, 100.0 * clipped / in.size());
    return in;
}

/** Runs a sweep through RoomSweep in blocks, the room answering, and returns the recording. */
std::vector<float> sweepRoom(const Room &rm, const room::SweepSpec &spec, float level) {
    // First the output, with no input: the room's answer doesn't change what's played.
    RoomSweep player;
    player.prepare(spec, level);
    player.start();
    std::vector<float> out;
    float block[kBlockFrames * 2];
    while (player.current() == RoomSweep::Sweeping) {
        player.process(nullptr, block, kBlockFrames);
        for (int32_t i = 0; i < kBlockFrames; ++i) out.push_back(block[i * 2]);
    }
    const std::vector<float> in = hear(out, rm, 99);
    // Then again with the room's answer as the input, block by block.
    RoomSweep rec;
    rec.prepare(spec, level);
    rec.start();
    size_t at = 0;
    while (rec.current() == RoomSweep::Sweeping) {
        rec.process(in.data() + at * 2, block, kBlockFrames);
        at += kBlockFrames;
    }
    ok("the sweep ran to the end", rec.current() == RoomSweep::Done);
    return rec.recording();
}

struct Verdict {
    double corrL = 0, corrR = 0, t10 = 0, t20 = 0, t10Room = 0, t20Room = 0;
    int32_t latency = 0;
    bool stereo = false;
    double before = 0;
    bool pass() const {
        return corrL > 0.95 && corrR > 0.95 && latency == kLatency && stereo &&
               std::fabs(t10 - t10Room) < 0.1 * t10Room && std::fabs(t20 - t20Room) < 0.1 * t20Room && before < -30.0;
    }
};

Verdict judge(const room::Impulse &got, const Room &rm) {
    Verdict v;
    v.latency = got.latencyFrames;
    v.stereo = got.stereo();
    if (!got.error.empty() || got.frames() == 0) return v;
    const size_t n = std::min(rm.l.size(), static_cast<size_t>(got.frames() - got.directFrame));
    std::vector<float> l(got.left.begin() + got.directFrame, got.left.end());
    std::vector<float> r = got.stereo() ? std::vector<float>(got.right.begin() + got.directFrame, got.right.end()) : l;
    v.corrL = correlation(band(l), band(rm.l), n);
    v.corrR = correlation(band(r), band(rm.r), n);
    v.t10 = decayTo(l, n, -10.0);
    v.t20 = decayTo(l, n, -20.0);
    v.t10Room = decayTo(rm.l, n, -10.0);
    v.t20Room = decayTo(rm.l, n, -20.0);
    // What's kept before the direct sound, against everything.
    double pre = 0, all = 0;
    for (int32_t i = 0; i < got.frames(); ++i) {
        const double e = static_cast<double>(got.left[i]) * got.left[i];
        if (i < got.directFrame - 2) pre += e;
        all += e;
    }
    v.before = 10.0 * std::log10(std::max(pre, 1e-30) / std::max(all, 1e-30));
    return v;
}

void report(const char *what, const Verdict &v) {
    printf("  %s: corr %.4f %.4f, latency %d, T10 %.3f/%.3f s, T20 %.3f/%.3f s, before direct %.1f dB\n", what, v.corrL,
           v.corrR, v.latency, v.t10, v.t10Room, v.t20, v.t20Room, v.before);
}

} // namespace

int main() {
    printf("venue_capture_test\n");
    Room rm;
    venue_harness::synthRoom(1.2, true, 7, rm.l, rm.r);
    // Quieter than the normalised room, as a real one would be.
    for (float &v : rm.l) v *= 0.1f;
    for (float &v : rm.r) v *= 0.1f;

    room::SweepSpec spec;
    spec.rate = kRate;
    spec.sweepFrames = 3 * kRate;
    spec.tailFrames = 3 * kRate;
    spec.repeats = 2;
    const std::vector<float> rec = sweepRoom(rm, spec, 0.5f);

    // The real thing.
    const room::Impulse got = room::deconvolve(rec.data(), static_cast<int64_t>(rec.size() / 2), spec);
    ok("deconvolved", got.error.empty(), got.error);
    const Verdict v = judge(got, rm);
    report("recovered", v);
    ok("the left matches the room", v.corrL > 0.95, num(v.corrL));
    ok("the right matches the room", v.corrR > 0.95, num(v.corrR));
    ok("the latency is found", v.latency == kLatency, std::to_string(v.latency));
    ok("two channels stay two", v.stereo);
    ok("it decays like the room to -10 dB", std::fabs(v.t10 - v.t10Room) < 0.1 * v.t10Room, num(v.t10) + " s");
    ok("and to -20 dB", std::fabs(v.t20 - v.t20Room) < 0.1 * v.t20Room, num(v.t20) + " s");
    ok("the harmonics are cut off before the direct sound", v.before < -30.0, num(v.before) + " dB");
    ok("the noise floor is well down", got.snrDb > 40.0f, num(got.snrDb) + " dB");
    // The room falls 60 dB over its 1.2 s and the hiss sits about there, so
    // the tail should end somewhere in the second half of it.
    const double cut = static_cast<double>(got.autoFrames) / kRate;
    ok("the tail is cut near the noise floor", cut > 0.5 && cut < 1.4, num(cut) + " s");

    // Kept and finished: peak at 0.9, faded to nothing.
    std::vector<float> fl, fr;
    room::finish(got, got.autoFrames, fl, fr);
    float peak = 0;
    for (float s : fl) peak = std::max(peak, std::fabs(s));
    for (float s : fr) peak = std::max(peak, std::fabs(s));
    ok("finished at the length asked for", static_cast<int32_t>(fl.size()) == got.autoFrames);
    ok("finished at 0.9 peak", std::fabs(peak - 0.9f) < 1e-3f, num(peak));
    ok("and faded out", std::fabs(fl.back()) < 1e-4f && (fr.empty() || std::fabs(fr.back()) < 1e-4f));

    // One microphone given to both sides is mono.
    {
        std::vector<float> mono(rec);
        for (size_t i = 0; i < mono.size(); i += 2) mono[i + 1] = mono[i];
        const room::Impulse m = room::deconvolve(mono.data(), static_cast<int64_t>(mono.size() / 2), spec);
        ok("one microphone comes back mono", m.error.empty() && !m.stereo());
    }
    // Nothing in: an error, not an impulse of noise.
    {
        std::vector<float> silent(rec.size(), 0.0f);
        const room::Impulse s = room::deconvolve(silent.data(), static_cast<int64_t>(silent.size() / 2), spec);
        ok("silence is refused", !s.error.empty(), s.error);
    }

    // Wrong inverses must fail the same checks.
    {
        std::vector<float> forward = room::inverseSweep(spec.sweepFrames, kRate);
        std::reverse(forward.begin(), forward.end());
        const Verdict b = judge(room::deconvolve(rec.data(), static_cast<int64_t>(rec.size() / 2), spec, &forward), rm);
        report("not reversed", b);
        ok("a sweep that isn't reversed fails the checks", !b.pass());
    }
    {
        // Reversed but without the 6 dB/oct: the room comes back tilted.
        std::vector<float> flat = room::makeSweep(spec.sweepFrames, kRate, 1.0f);
        std::reverse(flat.begin(), flat.end());
        double e = 0;
        for (float s : flat) e += static_cast<double>(s) * s;
        for (float &s : flat) s /= static_cast<float>(e);
        const Verdict b = judge(room::deconvolve(rec.data(), static_cast<int64_t>(rec.size() / 2), spec, &flat), rm);
        report("no 6 dB/oct", b);
        ok("an inverse without the 6 dB/oct fails the checks", !b.pass());
    }

    printf("venue_capture_test: %d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
