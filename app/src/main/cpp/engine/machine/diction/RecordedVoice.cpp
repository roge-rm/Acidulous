#include "RecordedVoice.h"

#include <engine/dsp/Fft.h>
#include <engine/format/WavReader.h>
#include <engine/machine/diction/Phones.h>
#include <engine/machine/diction/Throat.h>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace acidulous::machine::diction {

namespace {

/** A margin either side of the steady part, so a grain at its edge reads real sound. */
constexpr float kMarginSeconds = 0.05f;
/** How much of the vowel either side of a consonant is kept with it. */
constexpr float kJoinSeconds = 0.15f;
/** The loudest a consonant is let be against the vowels either side of it: 3 dB under. */
constexpr float kConsonantCeiling = 0.708f;

/** Where the energy above 2 kHz is centred between [from] and [to], in hertz. */
float centreAbove2k(const std::vector<float> &x, int32_t from, int32_t to, float sampleRate) {
    constexpr int32_t kN = 1024;
    static const dsp::Fft fft(kN);
    std::vector<float> re(kN), im(kN);
    double weighted = 0.0, total = 0.0;
    for (int32_t at = from; at + kN <= to; at += kN / 2) {
        for (int32_t i = 0; i < kN; ++i) {
            const float w = 0.5f - 0.5f * std::cos(6.2831853f * static_cast<float>(i) / (kN - 1));
            re[static_cast<size_t>(i)] = x[static_cast<size_t>(at + i)] * w;
            im[static_cast<size_t>(i)] = 0.0f;
        }
        fft.transform(re.data(), im.data(), false);
        for (int32_t k = 1; k < kN / 2; ++k) {
            const double hz = static_cast<double>(k) * sampleRate / kN;
            if (hz < 2000.0) continue;
            const double p = static_cast<double>(re[static_cast<size_t>(k)]) * re[static_cast<size_t>(k)] +
                             static_cast<double>(im[static_cast<size_t>(k)]) * im[static_cast<size_t>(k)];
            weighted += p * hz;
            total += p;
        }
    }
    return total > 0.0 ? static_cast<float>(weighted / total) : 0.0f;
}

/** A take as mono, or null with [error] set. */
std::unique_ptr<SampleData> readTake(const std::string &path, float sampleRate, std::string &error) {
    std::unique_ptr<SampleData> data = WavReader::read(path, static_cast<int32_t>(sampleRate), error);
    if (data == nullptr || data->frames <= 0) {
        if (error.empty()) error = "couldn't be read";
        return nullptr;
    }
    return data;
}

void copyMono(const SampleData &data, int32_t from, int32_t to, std::vector<float> &out) {
    out.resize(static_cast<size_t>(to - from));
    for (int32_t i = from; i < to; ++i) {
        const auto k = static_cast<size_t>(i);
        out[static_cast<size_t>(i - from)] = data.stereo ? 0.5f * (data.left[k] + data.right[k]) : data.left[k];
    }
}

double powerOver(const std::vector<float> &x, int32_t from, int32_t to) {
    double power = 0.0;
    from = std::max(0, from);
    to = std::min(static_cast<int32_t>(x.size()), to);
    for (int32_t i = from; i < to; ++i) power += static_cast<double>(x[static_cast<size_t>(i)]) * x[static_cast<size_t>(i)];
    return to > from ? power / (to - from) : 0.0;
}

} // namespace

const RecordedVoice::Unit *RecordedVoice::nearest(const float f[3]) const {
    const Unit *best = nullptr;
    float bestDistance = 1e30f;
    for (const Unit &u : vowels) {
        const float d1 = std::log2(f[0] / u.f[0]), d2 = std::log2(f[1] / u.f[1]), d3 = std::log2(f[2] / u.f[2]);
        const float d = d1 * d1 + d2 * d2 + d3 * d3;
        if (d < bestDistance) { bestDistance = d; best = &u; }
    }
    return best;
}

const RecordedVoice::Unit *RecordedVoice::forPhone(uint8_t phone) const {
    int32_t count = 0;
    const Phone *table = phoneTable(count);
    if (phone == 0 || phone >= count) return nullptr;
    // The sounds a voice isn't recorded singing, and the one it sings them with.
    static const char *const kStandIns[][2] = {
        {"AX", "AH"}, {"IHC", "IH"}, {"EHC", "EH"}, {"AEC", "AE"},
        {"OC", "AA"}, {"OR", "AO"},
    };
    const char *name = table[phone].name;
    for (const auto &pair : kStandIns) {
        if (std::strcmp(name, pair[0]) == 0) { name = pair[1]; break; }
    }
    for (const Unit &u : vowels) {
        if (std::strcmp(table[u.phone].name, name) == 0) return &u;
    }
    return nullptr;
}

const RecordedVoice::Join *RecordedVoice::consonant(uint8_t phone, const float vowel[3], From from) const {
    int32_t count = 0;
    const Phone *table = phoneTable(count);
    if (phone == 0 || phone >= count) return nullptr;
    const char *name = std::strcmp(table[phone].name, "DX") == 0 ? "D" : table[phone].name;
    const char *carrier = from == From::Ah ? "AA" : from == From::Ee ? "IY" : from == From::Oo ? "UW" : nullptr;
    if (carrier != nullptr) {
        for (const Join &j : joins) {
            if (std::strcmp(table[j.phone].name, name) == 0 && std::strcmp(table[j.carrier].name, carrier) == 0) return &j;
        }
    }
    const Join *best = nullptr;
    float bestDistance = 1e30f;
    for (const Join &j : joins) {
        if (std::strcmp(table[j.phone].name, name) != 0) continue;
        // By how far forward the vowel is (its second formant), which is
        // what the way into and out of a consonant carries: by all three,
        // your's vowel went with the ah take, and your came out ya.
        const float d = std::fabs(std::log2(vowel[1] / j.f[1]));
        if (d < bestDistance) { bestDistance = d; best = &j; }
    }
    return best;
}

bool RecordedVoice::addConsonant(const std::string &path, uint8_t phone, uint8_t carrier, int32_t from, int32_t to,
                                 float sampleRate, std::string &error) {
    int32_t count = 0;
    const Phone *table = phoneTable(count);
    if (phone == 0 || phone >= count || carrier == 0 || carrier >= count) { error = "no such sound"; return false; }
    const std::unique_ptr<SampleData> data = readTake(path, sampleRate, error);
    if (data == nullptr) return false;
    const auto keep = static_cast<int32_t>(kJoinSeconds * sampleRate);
    const int32_t start = std::clamp(from - keep, 0, data->frames);
    const int32_t end = std::clamp(to + keep, start, data->frames);
    if (to <= from || end - start < 2) { error = "too short"; return false; }

    Join join;
    join.phone = phone;
    join.carrier = carrier;
    std::copy(table[carrier].f, table[carrier].f + 3, join.f);
    copyMono(*data, start, end, join.sound.mono);
    join.sound.frames = end - start;
    join.sound.name = table[phone].name;
    join.sound.analyse(sampleRate);
    if (!join.sound.usable()) { error = "no pitch found"; return false; }
    join.from = std::clamp(from, start, end) - start;
    join.to = std::clamp(to, start, end) - start;
    // Its level from the vowels either side, clear of the consonant, so a
    // quiet consonant stays as quiet against them as it was sung.
    const auto clear = static_cast<int32_t>(0.03f * sampleRate);
    const double before = powerOver(join.sound.mono, 0, join.from - clear);
    const double after = powerOver(join.sound.mono, join.to + clear, join.sound.frames);
    const auto rms = static_cast<float>(std::sqrt(std::max(before, after)));
    join.gain = rms > 1e-5f ? Throat::kLevel / rms : 0.0f;
    const auto loudest = static_cast<float>(std::sqrt(powerOver(join.sound.mono, join.from, join.to)));
    const float ceiling = rms * kConsonantCeiling;
    join.consonantGain = loudest > ceiling && loudest > 0.0f ? ceiling / loudest : 1.0f;
    // How bright a sibilant is: where its energy above 2 kHz is centred. Only
    // an S, Z, SH or ZH, whose hiss has a peak; an F or TH is flat to the
    // top and measures bright whoever sings it.
    const char *name = table[phone].name;
    const bool sibilant = std::strcmp(name, "S") == 0 || std::strcmp(name, "Z") == 0 || std::strcmp(name, "SH") == 0 ||
                          std::strcmp(name, "ZH") == 0;
    if (sibilant) join.bright = centreAbove2k(join.sound.mono, join.from, join.to, sampleRate) > 8500.0f;
    // A stop's burst: the sharpest rise in level inside it, 1 ms against the
    // 5 ms before. After an S a stop is only its closure and burst; the
    // breath after the burst belongs to a stop that starts a word.
    const Kind kind = table[phone].kind;
    if (kind == Kind::Stop || kind == Kind::Affricate) {
        const auto ms = static_cast<int32_t>(0.001f * sampleRate);
        float bestRise = -1e30f;
        for (int32_t i = join.from + 5 * ms; i + ms <= join.to; i += ms) {
            const double now = powerOver(join.sound.mono, i, i + ms);
            const double before = powerOver(join.sound.mono, i - 5 * ms, i);
            const auto rise = static_cast<float>(10.0 * std::log10((now + 1e-12) / (before + 1e-12)));
            if (rise > bestRise) { bestRise = rise; join.burst = i; }
        }
    }
    joins.push_back(std::move(join));
    return true;
}

const RecordedVoice::Unit *RecordedVoice::diphthong(uint8_t phone) const {
    int32_t count = 0;
    const Phone *table = phoneTable(count);
    if (phone == 0 || phone >= count) return nullptr;
    static const char *const kVersions[][2] = {
        {"OWP", "OW"}, {"EYP", "EY"}, {"AYC", "AY"}, {"AWC", "AW"}, {"AWP", "AW"}, {"EG", "EY"},
    };
    const char *name = table[phone].name;
    for (const auto &pair : kVersions) {
        if (std::strcmp(name, pair[0]) == 0) { name = pair[1]; break; }
    }
    for (const Unit &u : diphthongs) {
        if (std::strcmp(table[u.phone].name, name) == 0) return &u;
    }
    return nullptr;
}

bool RecordedVoice::addDiphthong(const std::string &path, uint8_t phone, int32_t holdFrom, int32_t holdTo,
                                 int32_t glideFrom, int32_t glideTo, float sampleRate, std::string &error) {
    // As a vowel held from holdFrom to holdTo, but kept on to past the move.
    const int32_t end = std::max(holdTo, glideTo + static_cast<int32_t>(0.06f * sampleRate));
    if (!addVowel(path, phone, holdFrom, end, sampleRate, error)) return false;
    Unit unit = std::move(vowels.back());
    vowels.pop_back();
    const int32_t start = unit.from; // holdFrom, in the unit's frames
    const int32_t shift = holdFrom - start;
    unit.to = std::clamp(holdTo - shift, unit.from + 1, unit.sound.frames);
    unit.glideFrom = std::clamp(glideFrom - shift, unit.to, unit.sound.frames - 1);
    unit.glideTo = std::clamp(glideTo - shift, unit.glideFrom + 1, unit.sound.frames);
    // Its level from the held vowel alone.
    const auto rms = static_cast<float>(std::sqrt(powerOver(unit.sound.mono, unit.from, unit.to)));
    unit.gain = rms > 1e-5f ? Throat::kLevel / rms : 0.0f;
    diphthongs.push_back(std::move(unit));
    return true;
}

bool RecordedVoice::addVowel(const std::string &path, uint8_t phone, int32_t holdFrom, int32_t holdTo, float sampleRate,
                             std::string &error) {
    int32_t count = 0;
    const Phone *table = phoneTable(count);
    if (phone == 0 || phone >= count) { error = "no such sound"; return false; }
    const std::unique_ptr<SampleData> data = readTake(path, sampleRate, error);
    if (data == nullptr) return false;
    const auto margin = static_cast<int32_t>(kMarginSeconds * sampleRate);
    const int32_t from = std::clamp(holdFrom - margin, 0, data->frames);
    const int32_t to = std::clamp(holdTo + margin, from, data->frames);
    if (holdTo - holdFrom < static_cast<int32_t>(0.1f * sampleRate) || to - from < 2) { error = "too short"; return false; }

    Unit unit;
    unit.phone = phone;
    std::copy(table[phone].f, table[phone].f + 3, unit.f);
    auto &mono = unit.sound.mono;
    copyMono(*data, from, to, mono);
    unit.sound.frames = to - from;
    unit.sound.name = table[phone].name;
    unit.sound.analyse(sampleRate);
    if (!unit.sound.usable()) { error = "no pitch found"; return false; }
    unit.from = std::clamp(holdFrom, from, to) - from;
    unit.to = std::clamp(holdTo, from, to) - from;

    // Its level over the steady part, brought to the built-in voice's.
    const auto rms = static_cast<float>(std::sqrt(powerOver(mono, unit.from, unit.to)));
    unit.gain = rms > 1e-5f ? Throat::kLevel / rms : 0.0f;
    vowels.push_back(std::move(unit));
    return true;
}

} // namespace acidulous::machine::diction
