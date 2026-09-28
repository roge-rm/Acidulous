#include "VoiceBank.h"

#include <cmath>
#include <mutex>

namespace acidulous::machine::diction {

namespace {

constexpr double kPi = 3.14159265358979323846;

/**
 * Formants and bandwidths for an adult voice. The first three are the
 * familiar vowel measurements; four and five are where a trained voice
 * gathers its ring, and are much the same for every vowel.
 */
struct Shape { float f[5]; float bw[5]; };
const Shape kShapes[kVowels] = {
    /* oo */ {{300.0f, 870.0f, 2240.0f, 3300.0f, 3750.0f}, {60.0f, 90.0f, 120.0f, 200.0f, 250.0f}},
    /* oh */ {{520.0f, 900.0f, 2400.0f, 3300.0f, 3750.0f}, {70.0f, 90.0f, 130.0f, 200.0f, 250.0f}},
    /* ah */ {{730.0f, 1090.0f, 2440.0f, 3350.0f, 3800.0f}, {80.0f, 100.0f, 130.0f, 200.0f, 250.0f}},
    /* eh */ {{530.0f, 1840.0f, 2480.0f, 3400.0f, 3850.0f}, {70.0f, 100.0f, 140.0f, 200.0f, 250.0f}},
    /* ee */ {{270.0f, 2290.0f, 3010.0f, 3500.0f, 3950.0f}, {60.0f, 110.0f, 170.0f, 200.0f, 250.0f}},
};

/** A two-pole resonance with unity gain at DC, so five in a row keep the vowel's natural balance. */
struct Resonator {
    double a = 0, b = 0, c = 0, y1 = 0, y2 = 0;
    void set(double hz, double bw, double sr) {
        c = -std::exp(-2.0 * kPi * bw / sr);
        b = 2.0 * std::exp(-kPi * bw / sr) * std::cos(2.0 * kPi * hz / sr);
        a = 1.0 - b - c;
    }
    double process(double x) {
        const double y = a * x + b * y1 + c * y2;
        y2 = y1;
        y1 = y;
        return y;
    }
};

/** Second-order low-pass (cookbook, Q 0.707), in double since the bank is built once. */
struct Lowpass {
    double b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    Lowpass(double hz, double sr) {
        const double w = 2.0 * kPi * hz / sr, alpha = std::sin(w) / (2.0 * 0.7071), c = std::cos(w), a0 = 1.0 + alpha;
        b0 = (1.0 - c) / 2.0 / a0; b1 = (1.0 - c) / a0; b2 = b0; a1 = -2.0 * c / a0; a2 = (1.0 - alpha) / a0;
    }
    double process(double x) {
        const double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x; y2 = y1; y1 = y;
        return y;
    }
};

/** Second-order high-pass (cookbook, Q 0.707). */
struct Highpass {
    double b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    Highpass(double hz, double sr) {
        const double w = 2.0 * kPi * hz / sr, alpha = std::sin(w) / (2.0 * 0.7071), c = std::cos(w), a0 = 1.0 + alpha;
        b0 = (1.0 + c) / 2.0 / a0; b1 = -(1.0 + c) / a0; b2 = b0; a1 = -2.0 * c / a0; a2 = (1.0 - alpha) / a0;
    }
    double process(double x) {
        const double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x; y2 = y1; y1 = y;
        return y;
    }
};

/** Deterministic noise, so the bank is the same every time it's built. */
struct Rand {
    uint32_t s;
    explicit Rand(uint32_t seed) : s(seed) {}
    double uniform() { s = s * 1664525u + 1013904223u; return static_cast<double>(s >> 8) / 16777216.0; }
    double bipolar() { return uniform() * 2.0 - 1.0; }
};

/**
 * The glottal flow over one period, 0..1 through it: opening over 40% of the
 * period, closing faster over the next 16%, then shut. It's the closing that
 * excites the throat, so that's where the pitch mark goes.
 */
double flow(double t) {
    constexpr double open = 0.40, close = 0.16;
    if (t < open) return 0.5 * (1.0 - std::cos(kPi * t / open));
    if (t < open + close) return std::cos(kPi * (t - open) / (2.0 * close));
    return 0.0;
}
constexpr double kClosesAt = 0.56;

Unit makeVowel(Vowel v, int32_t sampleRate, float rootHz) {
    const double sr = sampleRate;
    const int32_t frames = static_cast<int32_t>(sr * 1.1);
    Unit unit;
    audio::Utterance &u = unit.sound;
    u.mono.assign(static_cast<size_t>(frames), 0.0f);
    u.frames = frames;
    u.rootHz = rootHz;
    static const char *const kNames[kVowels] = {"oo", "oh", "ah", "eh", "ee"};
    u.name = kNames[v];

    Resonator formant[5];
    for (int k = 0; k < 5; ++k) formant[k].set(kShapes[v].f[k], kShapes[v].bw[k], sr);
    Rand rand(0x51ee7u + static_cast<uint32_t>(v) * 977u);

    // One period at a time, each a little different, as a voice is: about
    // half a percent of jitter in length and three of shimmer in strength.
    double at = 0.0;
    double previousFlow = 0.0;
    double drift = 0.0;
    std::vector<double> out(static_cast<size_t>(frames), 0.0);
    // The breath's source: steady turbulence at the folds. It isn't puffed
    // with each opening here, since the puffs would be at this recording's
    // pitch and the machine sings at others.
    std::vector<double> breath(static_cast<size_t>(frames), 0.0);
    while (true) {
        drift = drift * 0.97 + rand.bipolar() * 0.0015;
        const double period = sr / rootHz * (1.0 + drift + rand.bipolar() * 0.005);
        const double strength = 1.0 + rand.bipolar() * 0.03;
        const auto start = static_cast<int32_t>(at);
        const auto length = static_cast<int32_t>(period);
        if (start + length >= frames) break;
        for (int32_t i = 0; i < length; ++i) {
            const double t = static_cast<double>(i) / period;
            const double g = flow(t) * strength;
            // The mouth radiates the change in flow, not the flow itself.
            double x = g - previousFlow;
            previousFlow = g;
            // A little breath while the folds are open.
            if (t < kClosesAt) x += rand.bipolar() * 0.004;
            out[static_cast<size_t>(start + i)] = x;
            breath[static_cast<size_t>(start + i)] = rand.bipolar();
        }
        audio::Epoch e;
        e.at = start + static_cast<int32_t>(kClosesAt * period);
        e.period = static_cast<float>(period);
        e.voiced = true;
        u.epochs.push_back(e);
        at += period;
    }
    for (auto &s : out) {
        double y = s;
        for (auto &f : formant) y = f.process(y);
        s = y;
    }
    // The breath through the same throat, with its own resonators so the two
    // don't share state, and twice as wide, as an open glottis damps them.
    // Air at the folds has little below a kilohertz, so that's taken out
    // first, steeply. Left in, it rumbled under the first formant, where the
    // voice should be alone, and sounded like a rasp, not a breath.
    {
        Highpass cut1(1000.0, sr), cut2(1000.0, sr);
        Resonator throat[5];
        for (int k = 0; k < 5; ++k) throat[k].set(kShapes[v].f[k], kShapes[v].bw[k] * 2.0, sr);
        for (auto &s : breath) {
            double y = cut2.process(cut1.process(s));
            for (auto &f : throat) y = f.process(y);
            s = y;
        }
    }

    // The same loudness for every vowel, so the knob moves the colour and not
    // the level. Measured over the part that holds.
    unit.holdFrom = static_cast<int32_t>(sr * 0.15);
    unit.holdTo = static_cast<int32_t>(sr * 1.0);
    double power = 0.0;
    for (int32_t i = unit.holdFrom; i < unit.holdTo; ++i) power += out[static_cast<size_t>(i)] * out[static_cast<size_t>(i)];
    const double rms = std::sqrt(power / static_cast<double>(unit.holdTo - unit.holdFrom));
    const double gain = rms > 1e-12 ? 0.18 / rms : 0.0;
    for (int32_t i = 0; i < frames; ++i) u.mono[static_cast<size_t>(i)] = static_cast<float>(out[static_cast<size_t>(i)] * gain);
    // The breath as loud as the voice, over the same part.
    double airPower = 0.0;
    for (int32_t i = unit.holdFrom; i < unit.holdTo; ++i) airPower += breath[static_cast<size_t>(i)] * breath[static_cast<size_t>(i)];
    const double airRms = std::sqrt(airPower / static_cast<double>(unit.holdTo - unit.holdFrom));
    const double airGain = airRms > 1e-12 ? 0.18 / airRms : 0.0;
    unit.air.resize(static_cast<size_t>(frames));
    for (int32_t i = 0; i < frames; ++i) unit.air[static_cast<size_t>(i)] = static_cast<float>(breath[static_cast<size_t>(i)] * airGain);
    splitBands(unit, sampleRate);
    return unit;
}

} // namespace

void splitBands(Unit &unit, int32_t sampleRate) {
    const auto &x = unit.sound.mono;
    const size_t n = x.size();
    // Forwards then backwards, so the low-pass shifts no phase. Taking the low
    // band from the voice then leaves the high band truly high. With the phase
    // shift of one pass, the difference kept much of the low band too, and the
    // first formant followed the high band's smaller move.
    std::vector<double> low(n);
    Lowpass forwards(1500.0, sampleRate), backwards(1500.0, sampleRate);
    for (size_t i = 0; i < n; ++i) low[i] = forwards.process(x[i]);
    for (size_t i = n; i-- > 0;) low[i] = backwards.process(low[i]);
    unit.low.resize(n);
    unit.high.resize(n);
    for (size_t i = 0; i < n; ++i) {
        unit.low[i] = static_cast<float>(low[i]);
        // Whatever isn't low, so the two always add back to the voice.
        unit.high[i] = x[i] - unit.low[i];
    }
}

void formantsOf(Vowel v, float hz[5], float bandwidth[5]) {
    for (int k = 0; k < 5; ++k) {
        hz[k] = kShapes[v].f[k];
        bandwidth[k] = kShapes[v].bw[k];
    }
}

std::unique_ptr<VoiceBank> makeBank(int32_t sampleRate, float rootHz) {
    auto bank = std::make_unique<VoiceBank>();
    for (int32_t v = 0; v < kVowels; ++v) bank->vowels[v] = makeVowel(static_cast<Vowel>(v), sampleRate, rootHz);
    return bank;
}

const VoiceBank &builtInBank(int32_t sampleRate) {
    // One per sample rate, never freed, since every Diction on that rate
    // reads it. Pitched between a man's and a woman's speaking voice.
    static std::vector<std::pair<int32_t, std::unique_ptr<VoiceBank>>> banks;
    static std::mutex building;
    std::lock_guard<std::mutex> hold(building);
    for (const auto &b : banks) if (b.first == sampleRate) return *b.second;
    banks.emplace_back(sampleRate, makeBank(sampleRate, 150.0f));
    return *banks.back().second;
}

} // namespace acidulous::machine::diction
