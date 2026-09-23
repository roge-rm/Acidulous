// The held effects on the master (engine/rack/Perform.h), sample by sample.
//
// Each check is on the numbers the effect is supposed to produce, not on
// "something changed": a repeat must play back the exact slice it caught, a
// tape stop must reach silence and come back to the live mix bit for bit, and
// with nothing held the mix must come out untouched - which is what keeps
// every song that never used these sounding the way it did.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/rack/Perform.h>

using acidulous::Perform;

namespace {
int checks = 0, failures = 0;
constexpr float kSr = 48000.0f;
constexpr int kBlock = 64;
constexpr float kBpm = 120.0f;
constexpr double kFramesPerTick = kSr * 60.0 / (kBpm * acidulous::kPPQN); // 100

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-60s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

std::string num(double v) { char b[48]; snprintf(b, sizeof b, "%.6g", v); return b; }

/** A deterministic noise source, so every run feeds the same numbers. */
struct Noise {
    uint32_t s = 12345;
    float next() {
        s = s * 1664525u + 1013904223u;
        return static_cast<float>(s >> 8) / 8388608.0f - 1.0f;
    }
};

/**
 * Runs [frames] of [in] through [p] a block at a time, from absolute frame
 * [at], with the transport [playing] and ticks counted from frame 0.
 */
struct Rig {
    Perform p;
    int64_t at = 0;
    bool playing = true;
    Rig() { p.prepare(kSr); }
    void run(const float *inL, const float *inR, float *outL, float *outR, int frames) {
        for (int done = 0; done < frames; done += kBlock) {
            float L[kBlock], R[kBlock];
            for (int i = 0; i < kBlock; ++i) { L[i] = inL[done + i]; R[i] = inR[done + i]; }
            p.setTransport(playing, static_cast<double>(at) / kFramesPerTick);
            p.process(L, R, kBlock, kBpm);
            for (int i = 0; i < kBlock; ++i) { outL[done + i] = L[i]; outR[done + i] = R[i]; }
            at += kBlock;
        }
    }
};

void set(Perform &p, Perform::P which, float v01) { p.params().set(which, v01); }

void untouchedIsBitIdentical() {
    printf("untouched\n");
    Rig rig;
    Noise n;
    const int N = kBlock * 2000;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    rig.run(L.data(), R.data(), oL.data(), oR.data(), N);
    int diff = 0;
    for (int i = 0; i < N; ++i) diff += (oL[i] != L[i]) + (oR[i] != R[i]);
    ok("nothing held: the mix comes out bit for bit", diff == 0, std::to_string(diff) + " samples differ");
}

void repeatPlaysTheSliceItCaught() {
    printf("repeat\n");
    Rig rig;
    Noise n;
    const int N = kBlock * 3000;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    // Rest up to the press, in the middle of a quarter-beat slice.
    const int press = kBlock * 1000; // frame 64000, tick 640: 40 ticks into a 60-tick slice
    rig.run(L.data(), R.data(), oL.data(), oR.data(), press);
    set(rig.p, Perform::Repeat, 3.0f / 5.0f); // 1/4 beat
    const int held = kBlock * 1200;
    rig.run(L.data() + press, R.data() + press, oL.data() + press, oR.data() + press, held);

    const int len = 6000;                         // a quarter beat at 120
    const int sliceStart = (press / len) * len;   // the grid line before the press: 60000
    const int edge = static_cast<int>(0.0015f * kSr);
    // Every full pass after the first plays the slice from the grid line.
    int wrong = 0, compared = 0;
    for (int f = sliceStart + len; f < press + held; ++f) {
        const int pos = (f - sliceStart) % len;
        if (pos < edge || len - pos <= edge) continue; // the declick at each end
        if (f - press < 400) continue;                 // the fade in
        ++compared;
        if (oL[f] != L[sliceStart + pos] || oR[f] != R[sliceStart + pos]) ++wrong;
    }
    ok("each pass plays the slice from the grid line before the press", compared > 0 && wrong == 0,
       std::to_string(wrong) + " of " + std::to_string(compared) + " wrong");
    // The rest of the first pass is the live mix, unchanged.
    int live = 0;
    for (int f = press + 400; f < sliceStart + len - edge; ++f) live += oL[f] != L[f];
    ok("the first pass is the live mix", live == 0, std::to_string(live) + " differ");

    // Shorter while held: now an eighth, from the same line.
    set(rig.p, Perform::Repeat, 4.0f / 5.0f);
    const int from = press + held;
    const int more = kBlock * 600;
    rig.run(L.data() + from, R.data() + from, oL.data() + from, oR.data() + from, more);
    wrong = compared = 0;
    for (int f = from + kBlock; f < from + more; ++f) {
        const int pos = (f - sliceStart) % 3000;
        if (pos < edge || 3000 - pos <= edge) continue;
        ++compared;
        if (oL[f] != L[sliceStart + pos]) ++wrong;
    }
    ok("sliding to a shorter length repeats the first part of the slice", compared > 0 && wrong == 0,
       std::to_string(wrong) + " of " + std::to_string(compared) + " wrong");

    // Let go: after the fade out, live again, bit for bit.
    set(rig.p, Perform::Repeat, 0.0f);
    const int after = from + more;
    const int rest = N - after;
    rig.run(L.data() + after, R.data() + after, oL.data() + after, oR.data() + after, rest);
    int diff = 0;
    for (int f = after + 400; f < N; ++f) diff += (oL[f] != L[f]) + (oR[f] != R[f]);
    ok("let go, the live mix comes back bit for bit", diff == 0, std::to_string(diff) + " differ");
}

void repeatWhileStoppedStartsAtThePress() {
    printf("repeat, stopped\n");
    Rig rig;
    rig.playing = false;
    Noise n;
    const int N = kBlock * 1000;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    const int press = kBlock * 333;
    rig.run(L.data(), R.data(), oL.data(), oR.data(), press);
    set(rig.p, Perform::Repeat, 1.0f / 5.0f); // a whole beat, 24000 frames
    rig.run(L.data() + press, R.data() + press, oL.data() + press, oR.data() + press, N - press);
    const int len = 24000, edge = static_cast<int>(0.0015f * kSr);
    int wrong = 0, compared = 0;
    for (int f = press + len; f < N; ++f) {
        const int pos = (f - press) % len;
        if (pos < edge || len - pos <= edge) continue;
        ++compared;
        if (oL[f] != L[press + pos]) ++wrong;
    }
    ok("stopped, the slice starts where the finger went down", compared > 0 && wrong == 0,
       std::to_string(wrong) + " of " + std::to_string(compared) + " wrong");
}

void tapeStopsAndComesBack() {
    printf("tape stop\n");
    Rig rig;
    Noise n;
    const int N = kBlock * 2500;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    const int press = kBlock * 500;
    rig.run(L.data(), R.data(), oL.data(), oR.data(), press);
    set(rig.p, Perform::Stop, 1.0f); // one beat to stop: 24000 frames
    const int held = kBlock * 600;   // 38400
    rig.run(L.data() + press, R.data() + press, oL.data() + press, oR.data() + press, held);
    // Slowing: the pitch falls, so the level in the second half is lower.
    double early = 0, late = 0;
    for (int f = press; f < press + 6000; ++f) early += oL[f] * oL[f];
    for (int f = press + 18000; f < press + 24000; ++f) late += oL[f] * oL[f];
    ok("slowing down, it fades", late < early * 0.5, "early " + num(early) + " late " + num(late));
    int loud = 0;
    for (int f = press + 24000 + kBlock; f < press + held; ++f) loud += oL[f] != 0.0f || oR[f] != 0.0f;
    ok("stopped, it is silent", loud == 0, std::to_string(loud) + " not silent");

    set(rig.p, Perform::Stop, 0.0f);
    const int from = press + held;
    rig.run(L.data() + from, R.data() + from, oL.data() + from, oR.data() + from, N - from);
    // Spin-up takes half the stop, then 10 ms to fade across to the live mix.
    const int back = from + 12000 + 480 + kBlock * 2;
    int diff = 0;
    for (int f = back; f < N; ++f) diff += (oL[f] != L[f]) + (oR[f] != R[f]);
    ok("let go, it spins up and rejoins the live mix bit for bit", diff == 0, std::to_string(diff) + " differ");
    double spin = 0;
    for (int f = from; f < from + 12000; ++f) spin += oL[f] * oL[f];
    ok("the spin-up is heard", spin > 1.0, num(spin));
}

double rmsOfSine(Rig &rig, float hz, int frames) {
    std::vector<float> L(frames), R(frames), oL(frames), oR(frames);
    for (int i = 0; i < frames; ++i) L[i] = R[i] = std::sin(2.0f * 3.14159265f * hz * i / kSr);
    rig.run(L.data(), R.data(), oL.data(), oR.data(), frames);
    double sum = 0;
    for (int i = frames / 2; i < frames; ++i) sum += oL[i] * oL[i];
    return std::sqrt(sum / (frames / 2));
}

void padFilters() {
    printf("the pad's filter\n");
    Rig rig;
    const double open = rmsOfSine(rig, 5000.0f, kBlock * 400);
    set(rig.p, Perform::X, 0.0f);
    const double low = rmsOfSine(rig, 5000.0f, kBlock * 400);
    ok("hard left, 5 kHz is cut by 30 dB or more", 20 * std::log10(low / open) < -30.0,
       num(20 * std::log10(low / open)) + " dB");
    set(rig.p, Perform::X, 1.0f);
    const double openLow = 0.7071;
    const double high = rmsOfSine(rig, 100.0f, kBlock * 1500);
    ok("hard right, 100 Hz is cut by 30 dB or more", 20 * std::log10(high / openLow) < -30.0,
       num(20 * std::log10(high / openLow)) + " dB");
    set(rig.p, Perform::X, 0.5f);
    rmsOfSine(rig, 440.0f, kBlock * 1000); // let it glide back
    Noise n;
    const int N = kBlock * 200;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    rig.run(L.data(), R.data(), oL.data(), oR.data(), N);
    int diff = 0;
    for (int i = 0; i < N; ++i) diff += (oL[i] != L[i]) + (oR[i] != R[i]);
    ok("back in the middle, bit for bit again", diff == 0, std::to_string(diff) + " differ");
}

void throwEchoesAndSleeps() {
    printf("the pad's throw\n");
    Rig rig;
    const int N = kBlock * 12000; // 16 s
    std::vector<float> L(N, 0.0f), R(N, 0.0f), oL(N), oR(N);
    set(rig.p, Perform::Y, 1.0f);
    rig.run(L.data(), R.data(), oL.data(), oR.data(), kBlock * 20); // the throw fades in
    const int hit = kBlock * 20;
    L[hit] = 1.0f;
    rig.run(L.data() + hit, R.data() + hit, oL.data() + hit, oR.data() + hit, kBlock);
    set(rig.p, Perform::Y, 0.0f);
    const int from = hit + kBlock;
    rig.run(L.data() + from, R.data() + from, oL.data() + from, oR.data() + from, N - from);
    // A dotted eighth at 120 is 18000 frames; the first repeat is on the left,
    // the second answers on the right.
    ok("the first echo is a dotted eighth later, on the left", std::fabs(oL[hit + 18000]) > 0.5f,
       num(oL[hit + 18000]));
    float quiet = 0;
    for (int f = hit + 1; f < hit + 17990; ++f) quiet = std::max(quiet, std::fabs(oL[f]) + std::fabs(oR[f]));
    ok("and nothing before it", quiet < 1e-6f, num(quiet));
    float right = 0;
    for (int f = hit + 35990; f < hit + 36010; ++f) right = std::max(right, std::fabs(oR[f]));
    ok("the second answers on the right", right > 0.05f, num(right));

    // After the tail, asleep: noise goes through untouched.
    Noise n;
    const int M = kBlock * 200;
    std::vector<float> nL(M), nR(M), mL(M), mR(M);
    for (int i = 0; i < M; ++i) { nL[i] = n.next(); nR[i] = n.next(); }
    rig.run(nL.data(), nR.data(), mL.data(), mR.data(), M);
    int diff = 0;
    for (int i = 0; i < M; ++i) diff += (mL[i] != nL[i]) + (mR[i] != nR[i]);
    ok("once the tail has died away, bit for bit again", diff == 0, std::to_string(diff) + " differ");

    // Woken again over silence, it must not play back what was in the buffer
    // a lap ago: while asleep it is written silent.
    const int W = kBlock * 1000;
    std::vector<float> zL(W, 0.0f), zR(W, 0.0f), wL(W), wR(W);
    rig.run(zL.data(), zR.data(), wL.data(), wR.data(), kBlock * 2000 < W ? kBlock * 2000 : W);
    set(rig.p, Perform::Y, 1.0f);
    rig.run(zL.data(), zR.data(), wL.data(), wR.data(), W);
    float stale = 0;
    for (int i = 0; i < W; ++i) stale = std::max(stale, std::fabs(wL[i]) + std::fabs(wR[i]));
    // What it slept under (-120 dB) may come back; a lap of old echoes may not.
    ok("woken over silence, nothing louder than -120 dB comes back", stale < 1e-6f, num(stale));
}

void reversePlaysTheLastBeatBackwards() {
    printf("reverse\n");
    const int edge = static_cast<int>(0.0015f * kSr);
    const int len = 24000; // a beat at 120
    for (bool playing : {true, false}) {
        Rig rig;
        rig.playing = playing;
        Noise n;
        const int N = kBlock * 2500;
        std::vector<float> L(N), R(N), oL(N), oR(N);
        for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
        const int press = kBlock * 1000; // frame 64000: tick 640, 160 ticks into beat three
        rig.run(L.data(), R.data(), oL.data(), oR.data(), press);
        set(rig.p, Perform::Reverse, 1.0f);
        const int held = kBlock * 1000;
        rig.run(L.data() + press, R.data() + press, oL.data() + press, oR.data() + press, held);
        // Playing: the whole beat before the last beat line (frames 24000 to
        // 48000), read backwards from where the press fell in its own beat.
        // Stopped: the beat before the press, from its end.
        const int offset = playing ? 16000 : 0;
        const int from = playing ? 24000 : press - len;
        int wrong = 0, compared = 0;
        for (int f = press + 400; f < press + held; ++f) {
            const int pos = (offset + f - press) % len;
            if (pos < edge || len - pos <= edge) continue;
            ++compared;
            const int back = len - 1 - pos;
            if (oL[f] != L[from + back] || oR[f] != R[from + back]) ++wrong;
        }
        ok(playing ? "playing, the last whole beat backwards, in phase"
                   : "stopped, the beat before the press backwards",
           compared > 0 && wrong == 0, std::to_string(wrong) + " of " + std::to_string(compared) + " wrong");
        set(rig.p, Perform::Reverse, 0.0f);
        const int after = press + held;
        rig.run(L.data() + after, R.data() + after, oL.data() + after, oR.data() + after, N - after);
        int diff = 0;
        for (int f = after + 400; f < N; ++f) diff += (oL[f] != L[f]) + (oR[f] != R[f]);
        if (playing) ok("let go, the live mix comes back bit for bit", diff == 0, std::to_string(diff) + " differ");
    }
}

void gateChopsInTime() {
    printf("gate\n");
    Rig rig;
    Noise n;
    const int N = kBlock * 2000;
    std::vector<float> L(N), R(N), oL(N), oR(N);
    for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
    const int press = kBlock * 333;
    rig.run(L.data(), R.data(), oL.data(), oR.data(), press);
    set(rig.p, Perform::Gate, 2.0f / 5.0f); // sixteenths: open 3000 frames, shut 3000, on the grid
    const int held = kBlock * 1000;
    rig.run(L.data() + press, R.data() + press, oL.data() + press, oR.data() + press, held);
    int openWrong = 0, shutWrong = 0, opens = 0, shuts = 0;
    for (int f = press + 6000; f < press + held; ++f) {
        const int at = f % 6000;
        if (at >= 200 && at <= 2800) { ++opens; openWrong += oL[f] != L[f] || oR[f] != R[f]; }
        if (at >= 3200 && at <= 5800) { ++shuts; shutWrong += oL[f] != 0.0f || oR[f] != 0.0f; }
    }
    ok("sixteenths: open on the first half of each, untouched", opens > 0 && openWrong == 0,
       std::to_string(openWrong) + " of " + std::to_string(opens) + " wrong");
    ok("and silent on the second", shuts > 0 && shutWrong == 0,
       std::to_string(shutWrong) + " of " + std::to_string(shuts) + " wrong");
    set(rig.p, Perform::Gate, 0.0f);
    const int after = press + held;
    rig.run(L.data() + after, R.data() + after, oL.data() + after, oR.data() + after, N - after);
    int diff = 0;
    for (int f = after + 200; f < N; ++f) diff += (oL[f] != L[f]) + (oR[f] != R[f]);
    ok("let go, bit for bit again", diff == 0, std::to_string(diff) + " differ");
}

void resetRepeats() {
    printf("reset\n");
    auto perform = [](Rig &rig, std::vector<float> &oL) {
        Noise n;
        const int N = kBlock * 1700;
        std::vector<float> L(N), R(N), oR(N);
        oL.assign(N, 0.0f);
        for (int i = 0; i < N; ++i) { L[i] = n.next(); R[i] = n.next(); }
        int at = 0;
        auto go = [&](int blocks) {
            rig.run(L.data() + at, R.data() + at, oL.data() + at, oR.data() + at, kBlock * blocks);
            at += kBlock * blocks;
        };
        go(300);
        set(rig.p, Perform::Repeat, 0.4f); go(200);
        set(rig.p, Perform::Repeat, 0.0f); set(rig.p, Perform::Y, 0.8f); set(rig.p, Perform::X, 0.2f); go(200);
        set(rig.p, Perform::Reverse, 1.0f); set(rig.p, Perform::Gate, 0.6f); go(150);
        set(rig.p, Perform::Reverse, 0.0f); set(rig.p, Perform::Gate, 0.0f);
        set(rig.p, Perform::Stop, 1.0f); go(400);
        set(rig.p, Perform::Stop, 0.0f); set(rig.p, Perform::Y, 0.0f); set(rig.p, Perform::X, 0.5f); go(400);
    };
    Rig rig;
    std::vector<float> a, b;
    perform(rig, a);
    rig.p.release();
    rig.p.reset();
    rig.at = 0;
    perform(rig, b);
    int diff = 0;
    for (size_t i = 0; i < a.size(); ++i) diff += a[i] != b[i];
    ok("after a reset, the same performance gives the same output", diff == 0, std::to_string(diff) + " differ");
}
} // namespace

int main() {
    untouchedIsBitIdentical();
    repeatPlaysTheSliceItCaught();
    repeatWhileStoppedStartsAtThePress();
    tapeStopsAndComesBack();
    padFilters();
    throwEchoesAndSleeps();
    reversePlaysTheLastBeatBackwards();
    gateChopsInTime();
    resetRepeats();
    printf("%d checks, %d failed\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
