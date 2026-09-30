// Diction, the singer: in tune, the throat apart from the pitch, the
// vowels apart from each other, and legato that doesn't breathe between
// notes.
//
// Everything is measured from the output, as a listener would: the pitch by
// the same tracker Molt uses, the throat by the spectrum's centroid, a vowel by
// where its energy sits.
#include <engine/core/Utterance.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/diction/Diction.h>
#include <engine/format/WavWriter.h>
#include <engine/machine/diction/Cutter.h>
#include <engine/machine/diction/Phones.h>
#include <engine/machine/diction/RecordedVoice.h>
#include <sequencer/ClipPlayer.h>

#include "audition_measure.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <memory>
#include <vector>

using namespace acidulous;
using acidulous::audio::PitchTrack;
using machine::Diction;
using audition::kSr;

namespace {

constexpr int32_t kBlock = 64;
int failures = 0, checks = 0;

void check(bool ok, const char *what, const std::string &detail = "") {
    ++checks;
    std::printf("  %-58s %s %s\n", what, ok ? "ok" : "FAIL", detail.c_str());
    if (!ok) ++failures;
}

float cents(float a, float b) { return 1200.0f * std::log2(a / b); }

/** A singer with the knobs at the given values and everything that wobbles turned off. */
std::unique_ptr<Machine> singer(std::initializer_list<std::pair<int32_t, float>> knobs) {
    std::unique_ptr<Machine> m(MachineRegistry::create("Diction"));
    m->prepare(kSr);
    auto set = [&](int32_t p, float native) { m->params().set(p, m->params().def(p).unmap(native)); };
    set(Diction::Vibrato, 0.0f);
    set(Diction::Drift, 0.0f);
    set(Diction::Breath, 0.0f);
    set(Diction::Glide, 0.0f);
    for (const auto &k : knobs) set(k.first, k.second);
    m->params().jumpAll();
    m->reset();
    return m;
}

/** [seconds] of the machine, mono, with [play] called before each block with the block's index. */
template <typename Play>
std::vector<float> render(Machine &m, float seconds, Play play) {
    std::vector<float> out;
    float L[kBlock], R[kBlock];
    const int32_t blocks = static_cast<int32_t>(seconds * kSr / kBlock);
    for (int32_t b = 0; b < blocks; ++b) {
        play(b);
        std::fill(L, L + kBlock, 0.0f);
        std::fill(R, R + kBlock, 0.0f);
        m.render(L, R, kBlock);
        for (int32_t i = 0; i < kBlock; ++i) out.push_back(0.5f * (L[i] + R[i]));
    }
    return out;
}

std::vector<float> held(Machine &m, uint8_t note, float seconds) {
    return render(m, seconds, [&](int32_t b) { if (b == 0) m.noteOn(note, 100); });
}

/** The median pitch of [x] after its first 0.2 s. */
float pitchOf(const std::vector<float> &x) {
    std::vector<float> tail(x.begin() + std::min<size_t>(x.size(), static_cast<size_t>(kSr * 0.2f)), x.end());
    PitchTrack t;
    t.find(tail, static_cast<int32_t>(tail.size()), kSr);
    std::vector<float> v;
    for (float f : t.hz) if (f > 0.0f) v.push_back(f);
    if (v.empty()) return 0.0f;
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

/** How far the pitch moves over [x] after its first 0.2 s, in cents, from the 10th to the 90th percentile. */
float pitchSpread(const std::vector<float> &x) {
    std::vector<float> tail(x.begin() + std::min<size_t>(x.size(), static_cast<size_t>(kSr * 0.2f)), x.end());
    PitchTrack t;
    t.find(tail, static_cast<int32_t>(tail.size()), kSr);
    std::vector<float> v;
    for (float f : t.hz) if (f > 0.0f) v.push_back(f);
    if (v.size() < 10) return 0.0f;
    std::sort(v.begin(), v.end());
    return cents(v[v.size() * 9 / 10], v[v.size() / 10]);
}

float throat(const std::vector<float> &x) { return audition::centroid(x, static_cast<int32_t>(kSr * 0.3f), 200.0f, 5000.0f); }

/** Energy between [lo] and [hi] Hz, summed over the spectrum's bins. */
float band(const std::vector<float> &x, float lo, float hi) {
    float sum = 0.0f;
    for (float hz = lo; hz <= hi; hz += 25.0f) sum += audition::magnitudeAt(x, static_cast<int32_t>(kSr * 0.3f), hz);
    return sum;
}

float rms(const std::vector<float> &x, size_t from, size_t n) {
    double s = 0.0;
    for (size_t i = from; i < from + n && i < x.size(); ++i) s += static_cast<double>(x[i]) * x[i];
    return static_cast<float>(std::sqrt(s / static_cast<double>(n)));
}

} // namespace

int main() {
    std::printf("\ndiction: in tune\n");
    for (uint8_t n : {45, 52, 57, 64, 69}) {
        auto m = singer({});
        const float want = 440.0f * std::exp2((static_cast<float>(n) - 69.0f) / 12.0f);
        const float got = pitchOf(held(*m, n, 1.2f));
        char what[64];
        std::snprintf(what, sizeof(what), "note %d sings at its pitch", n);
        check(std::fabs(cents(got, want)) < 15.0f, what, std::to_string(got) + " Hz, wanted " + std::to_string(want));
    }

    std::printf("\nthe throat and the pitch, apart\n");
    {
        const auto plain = held(*singer({}), 57, 1.2f);
        const auto up = held(*singer({{Diction::Formant, 7.0f}}), 57, 1.2f);
        const auto down = held(*singer({{Diction::Formant, -7.0f}}), 57, 1.2f);
        const float c0 = throat(plain), cu = throat(up), cd = throat(down);
        check(std::fabs(cents(pitchOf(up), pitchOf(plain))) < 15.0f, "formant up leaves the pitch where it was",
              std::to_string(pitchOf(up)) + " against " + std::to_string(pitchOf(plain)));
        // The first formant moves the whole way: where ah's peaks, on a low
        // note so the harmonics are close enough to show it. A fifth is x1.5.
        auto firstFormant = [](const std::vector<float> &x) {
            float best = 0.0f, at = 0.0f;
            for (float hz = 300.0f; hz <= 1500.0f; hz += 5.0f) {
                const float m = audition::magnitudeAt(x, static_cast<int32_t>(kSr * 0.3f), hz);
                if (m > best) { best = m; at = hz; }
            }
            return at;
        };
        const float f0 = firstFormant(held(*singer({}), 45, 1.2f));
        const float fu = firstFormant(held(*singer({{Diction::Formant, 7.0f}}), 45, 1.2f));
        const float fd = firstFormant(held(*singer({{Diction::Formant, -7.0f}}), 45, 1.2f));
        check(fu / f0 > 1.3f && fu / f0 < 1.7f, "and moves the throat up a fifth",
              "first formant " + std::to_string(f0) + " -> " + std::to_string(fu) + " Hz, centroid x" + std::to_string(cu / c0));
        check(fd / f0 > 0.55f && fd / f0 < 0.77f, "formant down moves it down a fifth",
              "first formant " + std::to_string(f0) + " -> " + std::to_string(fd) + " Hz, centroid x" + std::to_string(cd / c0));
        const float ring0 = band(plain, 2500.0f, 3500.0f), ringDown = band(down, 2500.0f, 3500.0f);
        const float ringDb = 20.0f * std::log10(ringDown / ring0);
        check(ringDb > -6.0f, "and a lowered voice keeps its ring near 3 kHz", std::to_string(ringDb) + " dB");
        const auto octave = held(*singer({}), 69, 1.2f);
        check(std::fabs(cents(pitchOf(octave), pitchOf(plain)) - 1200.0f) < 20.0f, "an octave up is an octave up");
        check(std::fabs(throat(octave) / c0 - 1.0f) < 0.2f, "and keeps the throat, near enough",
              "centroid x" + std::to_string(throat(octave) / c0));
    }

    std::printf("\nthe vowels, apart\n");
    {
        // Where each vowel's second formant sits against its first region.
        auto front = [](const std::vector<float> &x) { return band(x, 1900.0f, 2600.0f) / band(x, 600.0f, 1100.0f); };
        const float oo = front(held(*singer({{Diction::Vowel, 0.0f}}), 52, 1.0f));
        const float ah = front(held(*singer({{Diction::Vowel, 2.0f}}), 52, 1.0f));
        const float ee = front(held(*singer({{Diction::Vowel, 4.0f}}), 52, 1.0f));
        check(ee > ah * 2.0f, "ee is brighter than ah where the second formant is", std::to_string(ee) + " against " + std::to_string(ah));
        (void)oo;
        // oo and ah differ in the first formant: oo's is low, about 300 Hz,
        // and ah's open, about 730.
        auto open = [](const std::vector<float> &x) { return band(x, 600.0f, 900.0f) / band(x, 200.0f, 400.0f); };
        const float ooOpen = open(held(*singer({{Diction::Vowel, 0.0f}}), 52, 1.0f));
        const float ahOpen = open(held(*singer({{Diction::Vowel, 2.0f}}), 52, 1.0f));
        check(ahOpen > ooOpen * 2.0f, "ah is more open than oo in the first formant",
              std::to_string(ahOpen) + " against " + std::to_string(ooOpen));
        const float mid = front(held(*singer({{Diction::Vowel, 3.0f}}), 52, 1.0f));
        check(mid > ah && mid < ee, "eh, between them, is between them", std::to_string(mid));
    }

    std::printf("\nlegato, vibrato and letting go\n");
    {
        auto m = singer({});
        const int32_t change = static_cast<int32_t>(0.8f * kSr / kBlock);
        const auto x = render(*m, 1.6f, [&](int32_t b) {
            if (b == 0) m->noteOn(57, 100);
            if (b == change) m->noteOn(60, 100); // the first still held
        });
        const size_t at = static_cast<size_t>(change) * kBlock;
        const float before = rms(x, at - 4800, 4800);
        float lowest = 1e9f;
        for (size_t i = at; i < at + 9600; i += 480) lowest = std::min(lowest, rms(x, i, 480));
        check(lowest > before * 0.6f, "a note joined to the last doesn't start again", std::to_string(lowest / before));

        const float still = pitchSpread(held(*singer({}), 57, 2.0f));
        const float wide = pitchSpread(held(*singer({{Diction::Vibrato, 50.0f}, {Diction::VibratoDelay, 0.0f}}), 57, 2.0f));
        check(still < 20.0f, "no vibrato holds still", std::to_string(still) + " ct");
        check(wide > 60.0f && wide < 140.0f, "fifty cents of vibrato swings about a hundred", std::to_string(wide) + " ct");

        auto r = singer({{Diction::Release, 0.1f}});
        const auto y = render(*r, 1.5f, [&](int32_t b) {
            if (b == 0) r->noteOn(57, 100);
            if (b == static_cast<int32_t>(0.5f * kSr / kBlock)) r->noteOff(57);
        });
        const float after = rms(y, static_cast<size_t>(kSr * 1.2f), static_cast<size_t>(kSr * 0.3f));
        check(after == 0.0f, "silent once let go and released", std::to_string(after));
    }

    std::printf("\nwords\n");
    {
        // The share of energy above 4 kHz, where an S is and a vowel isn't:
        // through a high-pass against the whole.
        auto hiss = [](const std::vector<float> &x, size_t from, size_t n) {
            const float w = 2.0f * 3.14159265f * 4000.0f / kSr, c = std::cos(w), alpha = std::sin(w) / (2.0f * 0.7071f);
            const float a0 = 1.0f + alpha, b0 = (1.0f + c) / 2.0f / a0, b1 = -(1.0f + c) / a0, a1 = -2.0f * c / a0,
                        a2 = (1.0f - alpha) / a0;
            float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            double high = 0.0, all = 0.0;
            for (size_t i = from; i < from + n && i < x.size(); ++i) {
                const float y = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2;
                x2 = x1; x1 = x[i]; y2 = y1; y1 = y;
                high += static_cast<double>(y1) * y1;
                all += static_cast<double>(x[i]) * x[i];
            }
            return all > 0.0 ? static_cast<float>(high / all) : 0.0f;
        };
        auto m = singer({});
        uint8_t see[8];
        const int32_t n = machine::diction::parsePhones("S IY", see, 8);
        const auto x = render(*m, 0.8f, [&](int32_t b) {
            if (b == 0) {
                m->lyric(see, n);
                m->noteOn(57, 100);
            }
        });
        const float onset = hiss(x, 480, 2400), vowel = hiss(x, 14400, 4800);
        check(onset > 0.5f && vowel < 0.05f, "see starts with its S and holds its ee",
              "above 4 kHz " + std::to_string(onset) + " then " + std::to_string(vowel));

        // Words for a note that never came are forgotten, not sung by the next one.
        auto k = singer({});
        const auto y = render(*k, 0.8f, [&](int32_t b) {
            if (b == 0) k->lyric(see, n);
            if (b == 10) k->noteOn(57, 100);
        });
        check(hiss(y, 10 * kBlock + 480, 2400) < 0.05f, "words a note didn't use aren't kept for the next",
              std::to_string(hiss(y, 10 * kBlock + 480, 2400)));

        // A clip sends each note's words just before it, and nothing for a plain note.
        seq::Clip clip;
        clip.bars = 1;
        clip.ticksPerBar = 4 * kPPQN;
        seq::ClipNote a{};
        a.tick = 0; a.length = 10; a.pitch = 60; a.velocity = 100;
        seq::ClipNote b = a;
        b.tick = 20; b.pitch = 62;
        clip.notes = {a, b};
        clip.phones = {see[0], see[1]};
        clip.noteLyric = {2u, 0u}; // the first note has two phones from 0
        seq::ClipPlayer player;
        player.setClip(&clip);
        std::vector<std::string> events;
        player.process(0, 40, 0, [&](uint8_t c, uint8_t p, uint8_t) {
            if ((c & 0xf0) == 0x90) events.push_back("on " + std::to_string(p));
        }, [&](const uint8_t *ph, int32_t count) { events.push_back("words " + std::to_string(count) + " " + std::to_string(ph[0])); });
        const std::string got = events.size() == 3 ? events[0] + ", " + events[1] + ", " + events[2] : std::to_string(events.size());
        check(got == "words 2 " + std::to_string(see[0]) + ", on 60, on 62", "a clip sends a note's words before it", got);

        // Words ahead: each note's once, across blocks of any size, into the
        // next pass when the clip fits the pass, and not when it doesn't.
        clip.noteLyric = {2u, 2u};
        const int64_t len = clip.lengthTicks();
        auto announced = [&](int64_t loop) {
            seq::ClipPlayer p;
            p.setClip(&clip);
            std::string out;
            for (int64_t t = 0; t < len + 30; t += 7) {
                p.processWordsAhead(t + 100, t + 107, 0, loop, [&](const uint8_t *, int32_t, uint8_t pitch, uint8_t, int64_t tick) {
                    out += std::to_string(pitch) + "@" + std::to_string(tick) + " ";
                });
            }
            return out;
        };
        const std::string next = "60@" + std::to_string(len) + " 62@" + std::to_string(len + 20) + " ";
        check(announced(len) == next, "words ahead: each note once, on into the next pass", announced(len));
        // A pass 10 ticks longer than the clip: 960 is still in it, 980 isn't.
        check(announced(len + 10) == "60@" + std::to_string(len) + " ", "and not past a pass the clip doesn't fit",
              announced(len + 10));
    }

    std::printf("words ahead of their notes\n");
    {
        auto code = [](const char *name) { return static_cast<uint8_t>(machine::diction::phoneCode(name, static_cast<int32_t>(std::strlen(name)))); };
        // "sta" on a note at 0.6 s, its words known half a second before.
        std::vector<uint8_t> sta{code("S"), code("T"), code("AA")};
        const int32_t noteBlock = static_cast<int32_t>(0.6f * kSr / kBlock);
        const int32_t aheadBlock = noteBlock - static_cast<int32_t>(0.5f * kSr / kBlock);
        auto sing = [&](bool ahead) {
            auto m = singer({});
            return render(*m, 1.2f, [&](int32_t b) {
                if (ahead && b == aheadBlock) m->wordsAhead(sta.data(), 3, 57, 100, (noteBlock - b) * kBlock);
                if (b == noteBlock) { m->lyric(sta.data(), 3); m->noteOn(57, 100); }
            });
        };
        // Where the voice (under 1 kHz, where an S has next to nothing) first
        // reaches half its held level.
        auto vowelAt = [&](const std::vector<float> &x) {
            const float heldLevel = rms(x, static_cast<size_t>(kSr * 0.9f), static_cast<size_t>(kSr * 0.2f));
            for (size_t i = 0; i + 480 < x.size(); i += 240) {
                if (rms(x, i, 480) > 0.5f * heldLevel) return static_cast<float>(i) / kSr;
            }
            return -1.0f;
        };
        const float noteAt = static_cast<float>(noteBlock * kBlock) / kSr;
        const float late = vowelAt(sing(false)) - noteAt, onTime = vowelAt(sing(true)) - noteAt;
        check(std::fabs(onTime) < 0.03f, "known ahead, the vowel lands on its note",
              std::to_string(onTime * 1000.0f) + " ms, on time without: " + std::to_string(late * 1000.0f) + " ms");

        // Two notes on one key, the second's words started early: the first
        // letting go mustn't end the second.
        auto m = singer({});
        std::vector<uint8_t> ta{code("T"), code("AA")};
        const int32_t second = static_cast<int32_t>(0.5f * kSr / kBlock);
        const auto x = render(*m, 1.2f, [&](int32_t b) {
            if (b == 0) { m->lyric(ta.data(), 2); m->noteOn(57, 100); }
            if (b == 1) m->wordsAhead(ta.data(), 2, 57, 100, (second - b) * kBlock);
            if (b == second) { m->noteOff(57); m->lyric(ta.data(), 2); m->noteOn(57, 100); }
        });
        const float kept = rms(x, static_cast<size_t>(kSr * 0.9f), static_cast<size_t>(kSr * 0.2f));
        check(kept > 0.02f, "the same key again keeps singing", std::to_string(kept));
    }

    std::printf("a recorded voice\n");
    {
        // A voice recorded from the built-in one, as a singer would record it:
        // held vowels, and an L between two ahs, each cut by the cutter.
        char dirTemplate[] = "/tmp/diction_voice_XXXXXX";
        const std::string dir = mkdtemp(dirTemplate);
        auto code = [](const char *name) { return static_cast<uint8_t>(machine::diction::phoneCode(name, static_cast<int32_t>(std::strlen(name)))); };
        auto take = [&](const char *file, std::initializer_list<const char *> sounds) {
            auto m = singer({});
            std::vector<uint8_t> first{code(*sounds.begin())};
            std::vector<uint8_t> rest;
            for (auto it = sounds.begin() + 1; it != sounds.end(); ++it) rest.push_back(code(*it));
            const auto x = render(*m, 2.5f, [&](int32_t b) {
                if (b == 30) { m->lyric(first.data(), 1); m->noteOn(45, 100); }
                if (!rest.empty() && b == 30 + 700) { m->lyric(rest.data(), static_cast<int32_t>(rest.size())); m->noteOn(45, 100); }
                if (b == 30 + 1500) m->noteOff(45);
            });
            std::vector<float> stereo;
            for (float v : x) { stereo.push_back(v); stereo.push_back(v); }
            const std::string path = dir + "/" + file;
            WavWriter w;
            std::string error;
            w.open(path, kSr, 32, error);
            w.write(stereo.data(), static_cast<int32_t>(x.size()));
            w.close();
            return std::make_pair(path, x);
        };
        machine::diction::RecordedVoice voice;
        const float noteHz = 440.0f * std::exp2((45 - 69) / 12.0f);
        std::string error;
        for (const char *v : {"AA", "IY"}) {
            const auto t = take((std::string(v) + ".wav").c_str(), {v});
            const auto cut = machine::diction::cutTake(t.second, kSr, machine::diction::TakeKind::Held, noteHz);
            voice.addVowel(t.first, code(v), cut.holdFrom, cut.holdTo, kSr, error);
        }
        // A diphthong: ah held, moving to ee at the end, as it's sung.
        const auto ay = take("ay.wav", {"AY"});
        const auto aycut = machine::diction::cutTake(ay.second, kSr, machine::diction::TakeKind::Glide, noteHz);
        voice.addDiphthong(ay.first, code("AY"), aycut.holdFrom, aycut.holdTo, aycut.glideFrom, aycut.glideTo, kSr, error);
        const auto l = take("aa-l.wav", {"AA", "L", "AA"});
        const auto lcut = machine::diction::cutTake(l.second, kSr, machine::diction::TakeKind::Between, noteHz);
        voice.addConsonant(l.first, code("L"), code("AA"), lcut.consonantFrom, lcut.consonantTo, kSr, error);
        // And an L between ees, for choosing between them.
        const auto il = take("iy-l.wav", {"IY", "L", "IY"});
        const auto ilcut = machine::diction::cutTake(il.second, kSr, machine::diction::TakeKind::Between, noteHz);
        voice.addConsonant(il.first, code("L"), code("IY"), ilcut.consonantFrom, ilcut.consonantTo, kSr, error);
        check(voice.vowels.size() == 2 && voice.joins.size() == 2 && voice.diphthongs.size() == 1,
              "two vowels, a diphthong and two Ls go into the voice",
              std::to_string(voice.vowels.size()) + " vowels, " + std::to_string(voice.diphthongs.size()) + " diphthongs, " +
                  std::to_string(voice.joins.size()) + " consonants " + lcut.problem + aycut.problem);

        auto sing = [&](std::initializer_list<const char *> sounds, uint8_t note) {
            auto m = singer({});
            m->swapObject(0, &voice);
            std::vector<uint8_t> words;
            for (const char *s : sounds) words.push_back(code(s));
            auto x = render(*m, 1.2f, [&](int32_t b) {
                if (b == 0) { m->lyric(words.data(), static_cast<int32_t>(words.size())); m->noteOn(note, 100); }
            });
            m->swapObject(0, nullptr);
            return x;
        };
        for (uint8_t n : {45, 52, 57}) {
            const auto x = sing({"AA"}, n);
            const float want = 440.0f * std::exp2((n - 69) / 12.0f);
            const float got = pitchOf(x);
            check(std::fabs(cents(got, want)) < 15.0f, ("sings its ah on the note, " + std::to_string(n)).c_str(),
                  std::to_string(got) + " Hz, wanted " + std::to_string(want));
        }
        const auto ah = sing({"AA"}, 45), ee = sing({"IY"}, 45);
        check(band(ee, 1900, 2800) > band(ah, 1900, 2800) * 2.0f, "its ee is its ee and its ah its ah",
              std::to_string(band(ee, 1900, 2800)) + " against " + std::to_string(band(ah, 1900, 2800)));
        const auto builtIn = held(*singer({}), 45, 1.2f);
        const float ratio = rms(ah, static_cast<size_t>(kSr * 0.4f), static_cast<size_t>(kSr * 0.6f)) /
                            rms(builtIn, static_cast<size_t>(kSr * 0.4f), static_cast<size_t>(kSr * 0.6f));
        check(std::fabs(20.0f * std::log10(ratio)) < 3.0f, "at the built-in voice's level", std::to_string(20.0f * std::log10(ratio)) + " dB");

        // Eye, held and let go: ah while held, ee once it's let go.
        {
            auto m = singer({});
            m->swapObject(0, &voice);
            std::vector<uint8_t> eye{code("AY")};
            const auto x = render(*m, 1.2f, [&](int32_t b) {
                if (b == 0) { m->lyric(eye.data(), 1); m->noteOn(45, 100); }
                if (b == static_cast<int32_t>(0.7f * kSr / kBlock)) m->noteOff(45);
            });
            m->swapObject(0, nullptr);
            std::vector<float> heldPart(x.begin() + static_cast<long>(0.3f * kSr), x.begin() + static_cast<long>(0.6f * kSr));
            std::vector<float> endPart(x.begin() + static_cast<long>(0.72f * kSr), x.begin() + static_cast<long>(0.82f * kSr));
            // Energy between two frequencies, from the start of a stretch.
            auto energy = [](const std::vector<float> &part, float lo, float hi) {
                float sum = 0.0f;
                for (float hz = lo; hz <= hi; hz += 25.0f) sum += audition::magnitudeAt(part, 0, hz);
                return sum;
            };
            const float heldBright = energy(heldPart, 1900, 2800) / energy(heldPart, 500, 1200);
            const float endBright = energy(endPart, 1900, 2800) / energy(endPart, 500, 1200);
            check(endBright > heldBright * 1.5f, "eye holds its ah and moves to its ee when let go",
                  std::to_string(heldBright) + " then " + std::to_string(endBright));
        }

        // Which take an L is formed from, and how loud it is, as the track says.
        {
            std::vector<uint8_t> la{code("L"), code("AA")};
            int32_t fromL = 0;
            for (int32_t k = 0; k < 24; ++k) if (std::string(Diction::kFromOrder[k]) == "L") fromL = Diction::From + k;
            auto lSung = [&](float from, float levelDb) {
                auto m = singer({{fromL, from}, {Diction::ConsonantLevel, levelDb}});
                m->swapObject(0, &voice);
                auto x = render(*m, 0.5f, [&](int32_t b) { if (b == 0) { m->lyric(la.data(), 2); m->noteOn(50, 100); } });
                m->swapObject(0, nullptr);
                return x;
            };
            const auto fromAh = lSung(0.0f, 0.0f), fromEe = lSung(1.0f, 0.0f), quieter = lSung(0.0f, -12.0f);
            float apart = 0.0f;
            for (size_t i = 0; i < std::min(fromAh.size(), fromEe.size()); ++i) apart = std::max(apart, std::fabs(fromAh[i] - fromEe[i]));
            check(apart > 0.01f, "an L formed from the ee take isn't the ah take's", std::to_string(apart));
            const float loud = rms(fromAh, 0, static_cast<size_t>(0.06f * kSr)), soft = rms(quieter, 0, static_cast<size_t>(0.06f * kSr));
            check(soft < loud * 0.6f, "the consonant level turns it down", std::to_string(20.0f * std::log10(soft / loud)) + " dB");
        }

        // The same words twice, with a reset between, come out the same.
        auto m = singer({});
        m->swapObject(0, &voice);
        std::vector<uint8_t> la{code("L"), code("AA")};
        auto phrase = [&]() {
            return render(*m, 0.8f, [&](int32_t b) {
                if (b == 0) { m->lyric(la.data(), 2); m->noteOn(50, 100); }
                if (b == 300) m->noteOff(50);
            });
        };
        const auto first = phrase();
        m->reset();
        const auto second = phrase();
        m->swapObject(0, nullptr);
        check(first == second, "sings the same after a reset");
        const float onset = rms(first, static_cast<size_t>(kSr * 0.01f), static_cast<size_t>(kSr * 0.04f));
        check(onset > 0.0f, "la starts with the recorded L", std::to_string(onset));
        std::system(("rm -rf '" + dir + "'").c_str());
    }

    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
