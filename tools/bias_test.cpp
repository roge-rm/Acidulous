// Tests where Bias reads from. A region is a window into a file: a take sung
// across four scenes is one file at four offsets, and an offset a bar out
// puts the vocal on the wrong word. A cell covers bars x repeat, so the
// second pass of a repeated scene must carry on through the take, not
// restart it.
//
// The reel is built from a ramp, so each sample holds its own frame index and
// the output says exactly where it was read from. Everything outside a region
// holds a sentinel value, so reading past the end shows up.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Reel.h>
#include <engine/core/TakeScore.h>
#include <engine/core/Mapping.h>
#include <engine/core/ReelCache.h>
#include <engine/format/WavReader.h>
#include <engine/format/WavWriter.h>
#include <sys/stat.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/machine/bias/Bias.h>

using namespace acidulous;
using namespace acidulous::audio;

namespace {
int checks = 0;
int failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    if (cond) {
        printf("  ok   %-56s %s\n", what, detail.c_str());
    } else {
        ++failures;
        printf("  FAIL %-56s %s\n", what, detail.c_str());
    }
}

constexpr int32_t kRate = 48000;
constexpr int64_t kBarTicks = 4 * kPPQN;   // 960
constexpr int32_t kFramesPerTick = 100;    // at 48 kHz and 120 bpm
constexpr int32_t kBarFrames = static_cast<int32_t>(kBarTicks) * kFramesPerTick; // 96000

/**
 * What frame [i] holds, so a reading says where it came from. Offset by one
 * so frame 0 isn't silence, or a machine that renders nothing would pass.
 */
int16_t ramp(int32_t i) { return static_cast<int16_t>(i % 30000 + 1); }

/** Fills anything a region doesn't cover. Loud, and never produced by the ramp. */
constexpr int16_t kPoison = -30001;

float expectRamp(int32_t frame) { return static_cast<float>(ramp(frame)) / 32768.0f; }

/** A source of [frames], ramped inside [from, to) and poisoned everywhere else. */
std::shared_ptr<Reel::Source> ramped(int32_t frames, int32_t from, int32_t to) {
    auto s = std::make_shared<Reel::Source>();
    // Built through `hold`, like the host does. Sources are read through
    // pointers so the same render code serves takes in memory and takes
    // mapped from a file.
    std::vector<int16_t> planes(static_cast<size_t>(frames), kPoison);
    for (int32_t i = from; i < to && i < frames; ++i) planes[static_cast<size_t>(i)] = ramp(i);
    s->hold(std::move(planes), frames, false);
    return s;
}

/** A source that is one value all the way through, for the summing tests. */
std::shared_ptr<Reel::Source> flat(int32_t frames, int16_t value) {
    auto s = std::make_shared<Reel::Source>();
    s->hold(std::vector<int16_t>(static_cast<size_t>(frames), value), frames, false);
    return s;
}

/** The machine, built the way the rack builds it. */
struct Rig {
    std::unique_ptr<machine::Bias> bias{
        static_cast<machine::Bias *>(MachineRegistry::create("Bias"))};
    Reel reel;
    float L[256]{}, R[256]{};

    Rig() { bias->prepare(kRate); }

    ~Rig() { bias->swapObject(0, nullptr); } // the reel is a member, not the machine's to free

    void mount() { bias->swapObject(0, &reel); }

    /** One block at a place in the cell, and what the left channel held. */
    float readAt(int64_t sceneId, int64_t cycleTick, bool muted = false) {
        bias->onScene(sceneId, cycleTick, true, muted);
        bias->render(L, R, 1);
        return L[0];
    }

    /** The same, having first jumped elsewhere so the cursor cannot coast. */
    float probe(int64_t sceneId, int64_t cycleTick, bool muted = false) {
        bias->onScene(0, 0, true, false); // no cell: invalidates every cursor
        bias->render(L, R, 1);
        return readAt(sceneId, cycleTick, muted);
    }
};

Reel::Region region(std::shared_ptr<const Reel::Source> src, int32_t offset, int32_t frames,
                    int32_t ticks, bool loop = false, int32_t startTick = 0) {
    Reel::Region r;
    r.source = std::move(src);
    r.offset = offset;
    r.frames = frames;
    r.ticks = ticks;
    r.bpm = 120.0f;
    r.loop = loop;
    r.startTick = startTick;
    return r;
}

/** Tight enough to tell one frame from the next (one ramp step is about 3e-5). */
bool near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

// --- the tests ------------------------------------------------------------

/**
 * A take sung across three scenes is one file at three offsets. Get the
 * offset wrong and the Verse cell plays the Intro's words.
 */
void aRegionIsAWindowIntoItsFile() {
    Rig rig;
    const int32_t total = kBarFrames * 24;
    auto src = ramped(total, 0, total);

    // Intro 4 bars x2 -> an 8-bar cell at 0; Verse 8 bars at 8 bars in;
    // Chorus 4 bars x2 at 16 bars in.
    Reel::Cell intro{1, {}};
    intro.lanes[0] = region(src, 0, kBarFrames * 8, kBarTicks * 8);
    Reel::Cell verse{2, {}};
    verse.lanes[0] = region(src, kBarFrames * 8, kBarFrames * 8, kBarTicks * 8);
    Reel::Cell chorus{3, {}};
    chorus.lanes[0] = region(src, kBarFrames * 16, kBarFrames * 8, kBarTicks * 8);
    rig.reel.cells = {intro, verse, chorus};
    rig.mount();

    ok("the Intro's cell starts at the head of the file", near(rig.probe(1, 0), expectRamp(0)));
    ok("the Verse's starts eight bars in", near(rig.probe(2, 0), expectRamp(kBarFrames * 8)),
       "wanted frame " + std::to_string(kBarFrames * 8));
    ok("the Chorus's starts sixteen", near(rig.probe(3, 0), expectRamp(kBarFrames * 16)));
}

/**
 * The second pass of a repeated scene carries on through the take. The
 * scheduler counts the repeats (`rackCycleTick`) and Bias has to read that
 * far into the region.
 */
void aRepeatedSceneCarriesOn() {
    Rig rig;
    const int32_t total = kBarFrames * 8;
    auto src = ramped(total, 0, total);
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(src, 0, kBarFrames * 8, kBarTicks * 8); // 4 bars x2
    rig.reel.cells = {cell};
    rig.mount();

    ok("the first pass reads the first bar", near(rig.probe(1, 0), expectRamp(0)));
    ok("and the fifth bar is five bars in",
       near(rig.probe(1, kBarTicks * 4), expectRamp(kBarFrames * 4)),
       "wanted frame " + std::to_string(kBarFrames * 4));
    // Treating one pass as the whole cycle would read the start of the take
    // again on the second pass.
    ok("and it has not started the take again",
       !near(rig.probe(1, kBarTicks * 4), expectRamp(0)));
}

/** Four lanes sum at their levels, and a muted one is left out. */
void fourLanesSum() {
    Rig rig;
    Reel::Cell cell{1, {}};
    for (int32_t lane = 0; lane < kReelLanes; ++lane) {
        cell.lanes[lane] = region(flat(kBarFrames, static_cast<int16_t>(1000 * (lane + 1))), 0,
                                  kBarFrames, kBarTicks);
    }
    rig.reel.cells = {cell};
    rig.mount();

    const float one = 1000.0f / 32768.0f;
    ok("four lanes are the sum of four lanes", near(rig.probe(1, 0), one * (1 + 2 + 3 + 4)),
       std::to_string(rig.probe(1, 0)));

    rig.bias->params().set(machine::Bias::Mute2, 1.0f);
    rig.bias->params().jumpAll();
    ok("a muted lane is not in it", near(rig.probe(1, 0), one * (1 + 3 + 4)));

    rig.bias->params().set(machine::Bias::Mute2, 0.0f);
    rig.bias->params().set(machine::Bias::Lane3, 0.5f);
    rig.bias->params().jumpAll();
    ok("and a lane at half is in it by half", near(rig.probe(1, 0), one * (1 + 2 + 1.5f + 4)));
}

/** A muted clip is silent however many lanes have something on them. */
void aMutedClipIsSilent() {
    Rig rig;
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(flat(kBarFrames, 8000), 0, kBarFrames, kBarTicks);
    rig.reel.cells = {cell};
    rig.mount();
    ok("unmuted, it sounds", !near(rig.probe(1, 0), 0.0f));
    ok("muted, it does not", near(rig.probe(1, 0, true), 0.0f));
}

/**
 * Past its end a lane goes silent rather than wrapping, and a looping one
 * wraps within its own region rather than at the end of the file.
 */
void pastTheEnd() {
    Rig rig;
    const int32_t total = kBarFrames * 4;
    // The region is one bar of a four-bar file. Everything past it is the sentinel.
    auto src = ramped(total, 0, kBarFrames);
    Reel::Cell once{1, {}};
    once.lanes[0] = region(src, 0, kBarFrames, kBarTicks * 4);
    Reel::Cell looped{2, {}};
    looped.lanes[0] = region(src, 0, kBarFrames, kBarTicks * 4, /*loop=*/true);
    rig.reel.cells = {once, looped};
    rig.mount();

    const float past = rig.probe(1, kBarTicks * 2);
    ok("past its end a region is silent", near(past, 0.0f), std::to_string(past));
    ok("and never reads the poison beyond it", !near(past, static_cast<float>(kPoison) / 32768.0f));

    const float wrapped = rig.probe(2, kBarTicks * 2);
    ok("a looping region wraps at its own length, not the file's",
       near(wrapped, expectRamp(0)), std::to_string(wrapped));
}

/** A punch-in is silent until the song reaches it. */
void aPunchInWaits() {
    Rig rig;
    auto src = ramped(kBarFrames * 4, 0, kBarFrames * 4);
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(src, 0, kBarFrames * 2, kBarTicks * 4, false, static_cast<int32_t>(kBarTicks * 2));
    rig.reel.cells = {cell};
    rig.mount();
    ok("before the punch-in there is nothing", near(rig.probe(1, kBarTicks), 0.0f));
    ok("and at it the take begins", near(rig.probe(1, kBarTicks * 2), expectRamp(0)));
}

/** A cell with nothing on it, and a scene the reel has never heard of. */
void nothingToPlay() {
    Rig rig;
    Reel::Cell cell{1, {}};
    rig.reel.cells = {cell};
    rig.mount();
    ok("a cell with no lanes is silent", near(rig.probe(1, 0), 0.0f));
    ok("and a scene that is not on the reel is too", near(rig.probe(99, 0), 0.0f));
}

// --- The medium ------------------------------------------------------------------

/**
 * The colour section, which is what a Bias patch sets. Most important,
 * Direct must leave the sound exactly unchanged.
 */
void theMediumColoursAndDirectDoesNot() {
    printf("- the medium: what a patch does, and what Direct does not\n");
    constexpr int32_t kN = 512;
    float in[kN];
    for (int32_t i = 0; i < kN; ++i) {
        // Something with a top end to lose and a transient to squash.
        in[i] = 0.3f * std::sin(6.2831853f * 800.0f * static_cast<float>(i) / kRate) +
                0.2f * std::sin(6.2831853f * 9000.0f * static_cast<float>(i) / kRate);
    }

    machine::bias::Colour colour;
    colour.prepare(static_cast<float>(kRate));

    // Bit for bit unchanged, which the sample-identical stem export relies on.
    {
        float l[kN], r[kN];
        std::memcpy(l, in, sizeof(in));
        std::memcpy(r, in, sizeof(in));
        machine::bias::ColourSpec spec; // every default
        colour.setBlock(spec);
        colour.process(l, r, kN);
        bool same = true;
        for (int32_t i = 0; i < kN; ++i) same = same && l[i] == in[i] && r[i] == in[i];
        ok("Init is bit for bit what went in", same);
    }

    // A noise floor, the same both times, since two exports of one song have
    // to match.
    {
        float a[kN] = {0.0f}, b[kN] = {0.0f}, a2[kN] = {0.0f}, b2[kN] = {0.0f};
        machine::bias::ColourSpec spec;
        spec.hiss = 0.8f;
        spec.any = true;
        colour.reset();
        colour.setBlock(spec);
        colour.process(a, b, kN);
        colour.reset();
        colour.setBlock(spec);
        colour.process(a2, b2, kN);
        double peak = 0.0;
        bool identical = true;
        for (int32_t i = 0; i < kN; ++i) {
            peak = std::max(peak, static_cast<double>(std::fabs(a[i])));
            identical = identical && a[i] == a2[i] && b[i] == b2[i];
        }
        ok("hiss is audible on a silent tape", peak > 0.001, std::to_string(peak));
        ok("and identical after a reset, so two exports match", identical);
    }

    // The bandwidth is most of what makes a telephone sound unlike a reel.
    {
        float l[kN], r[kN];
        std::memcpy(l, in, sizeof(in));
        std::memcpy(r, in, sizeof(in));
        machine::bias::ColourSpec spec;
        spec.highCut = 2000.0f;
        spec.any = true;
        colour.reset();
        colour.setBlock(spec);
        colour.process(l, r, kN);
        // The 9 kHz part should be gone. A one-sample difference is a crude
        // high-pass, enough to see what's left above the fundamental.
        double before = 0.0, after = 0.0;
        for (int32_t i = 256; i < kN; ++i) {
            before += std::fabs(in[i] - in[i - 1]);
            after += std::fabs(l[i] - l[i - 1]);
        }
        ok("a narrow band loses the top", after < before * 0.5,
           std::to_string(after) + " against " + std::to_string(before));
    }

    // The digital media must actually quantise.
    {
        float l[kN], r[kN];
        std::memcpy(l, in, sizeof(in));
        std::memcpy(r, in, sizeof(in));
        machine::bias::ColourSpec spec;
        spec.bits = 5.0f;
        spec.any = true;
        colour.reset();
        colour.setBlock(spec);
        colour.process(l, r, kN);
        const float step = 1.0f / std::pow(2.0f, 4.0f);
        bool onGrid = true;
        for (int32_t i = 0; i < kN; ++i) {
            const float k = l[i] / step;
            onGrid = onGrid && std::fabs(k - std::round(k)) < 1e-4f;
        }
        ok("five bits puts every sample on a sixteenth", onGrid);
    }
}

/**
 * A take too long to hold in memory is converted and mapped, and must read
 * back the same numbers. The render can't tell the two apart, so they have
 * to match exactly.
 */
void aLongTakeIsMappedAndReadsTheSame() {
    printf("- a take too long to hold, converted once and mapped\n");
    const std::string dir = std::string(std::getenv("TMPDIR") != nullptr ? std::getenv("TMPDIR") : "/tmp");
    const std::string wav = dir + "/reelsrc.wav";
    const std::string cache = dir + "/reelsrc.i16";
    std::remove(cache.c_str());

    // Three seconds of a shaped signal, written as a real file.
    constexpr int32_t kFrames = kRate * 3;
    {
        WavWriter w;
        std::string error;
        if (!w.open(wav, kRate, 24, error)) {
            ok("the source wav was written", false, error);
            return;
        }
        std::vector<float> block(1024 * 2);
        for (int32_t at = 0; at < kFrames; at += 1024) {
            const int32_t n = std::min(1024, kFrames - at);
            for (int32_t i = 0; i < n; ++i) {
                const float v = 0.5f * std::sin(6.2831853f * 220.0f *
                                                static_cast<float>(at + i) / kRate);
                block[static_cast<size_t>(i) * 2] = v;
                block[static_cast<size_t>(i) * 2 + 1] = -v; // so the channels differ
            }
            w.write(block.data(), n);
        }
        w.close();
    }

    std::string error;
    bool stereo = false;
    const int64_t frames = ReelCache::convert(wav, cache, stereo, error);
    ok("the conversion says how long it is", frames == kFrames && stereo,
       std::to_string(frames) + (stereo ? " stereo" : " mono") + " " + error);
    if (frames <= 0) return;

    // Planar int16: left plane then right, and nothing else in the file.
    struct stat st {};
    ::stat(cache.c_str(), &st);
    ok("and the file is exactly that, planar int16",
       st.st_size == static_cast<long>(frames) * 2 * static_cast<long>(sizeof(int16_t)),
       std::to_string(static_cast<long long>(st.st_size)));

    auto map = std::make_shared<Mapping>();
    Reel::Source mapped;
    ok("it maps", map->open(cache) && mapped.point(map, static_cast<int32_t>(frames), stereo));
    if (mapped.lp == nullptr) return;

    // Matches what WavReader reads.
    const auto whole = WavReader::read(wav, kRate, error, kMaxSliceSeconds);
    if (!whole) {
        ok("the reader read it too", false, error);
        return;
    }
    double worst = 0.0;
    int64_t worstAt = -1;
    for (int32_t i = 0; i < kFrames; i += 7) {
        const double l = mapped.lp[i] / 32768.0;
        const double r = mapped.rp[i] / 32768.0;
        const double dl = std::fabs(l - whole->left[static_cast<size_t>(i)]);
        const double dr = std::fabs(r - whole->right[static_cast<size_t>(i)]);
        if (std::max(dl, dr) > worst) { worst = std::max(dl, dr); worstAt = i; }
    }
    // One step of sixteen-bit rounding and no more.
    ok("and reads what the reader read, both channels", worst < 1.0 / 32768.0,
       "worst " + std::to_string(worst) + " at frame " + std::to_string(worstAt));

    // The right plane isn't a copy of the left, which happens silently if
    // the two offsets are mixed up.
    ok("the channels are not the same plane", mapped.rp == mapped.lp + frames);

    // The converted file's name must be the same for the same file and
    // change when the file does.
    const std::string first = ReelCache::nameFor(wav);
    ok("the cache name is stable for one file", first == ReelCache::nameFor(wav), first);
    ok("and is not the name of another file", first != ReelCache::nameFor(cache));

    std::remove(cache.c_str());
    std::remove(wav.c_str());
}

/**
 * A take that follows the song's tempo covers the same musical length at any
 * tempo.
 *
 * One second at 120 bpm is two beats. At 60 bpm two beats last two seconds,
 * so without stretching the take stops halfway and leaves a gap. With it the
 * take fills the whole time.
 *
 * The tick and frame count have to agree, or the stretcher never reaches the
 * end of the take and the test proves nothing.
 */
float playForSeconds(Rig &rig, double seconds, float songBpm, bool stretch) {
    rig.bias->onBlock(0, 0, songBpm);
    rig.bias->params().set(machine::Bias::Stretch, stretch ? 1.0f : 0.0f);
    rig.bias->params().jumpAll();
    const double perTick = kRate * 60.0 / (songBpm * kPPQN);
    const auto blocks = static_cast<int64_t>(seconds * kRate / 64);
    float last = 0.0f;
    for (int64_t b = 0; b < blocks; ++b) {
        const auto tick = static_cast<int64_t>(static_cast<double>(b * 64) / perTick);
        rig.bias->onScene(7, tick, true, false);
        rig.bias->render(rig.L, rig.R, 64);
        last = 0.0f;
        for (int32_t i = 0; i < 64; ++i) last = std::fabs(rig.L[i]) > std::fabs(last) ? rig.L[i] : last;
    }
    return last;
}

std::unique_ptr<Rig> oneSecondTakeAt120() {
    auto rig = std::make_unique<Rig>();
    rig->reel.cells.emplace_back();
    Reel::Cell &c = rig->reel.cells.back();
    c.sceneId = 7;
    c.lanes[0] = region(flat(kRate, 8000), 0, kRate, 4 * kPPQN);
    c.lanes[0].bpm = 120.0f;
    rig->mount();
    return rig;
}

void aTakeCanFollowTheSong() {
    printf("- a take that follows the song rather than its own tempo\n");
    const float want = 8000.0f / 32768.0f;

    // At the recorded tempo, both give the same result.
    {
        auto rig = oneSecondTakeAt120();
        const float at120 = playForSeconds(*rig, 0.8, 120.0f, true);
        ok("at its own tempo, switching it on changes nothing",
           std::fabs(at120 - want) < want * 0.05f, std::to_string(at120));
    }

    // Half the tempo: two beats now last two seconds.
    {
        auto rig = oneSecondTakeAt120();
        const float off = playForSeconds(*rig, 1.8, 60.0f, false);
        ok("off, the take has run out half way through the bar", std::fabs(off) < 1e-6,
           std::to_string(off));
    }
    {
        auto rig = oneSecondTakeAt120();
        const float on = playForSeconds(*rig, 1.8, 60.0f, true);
        ok("on, it is still sounding at the end of it",
           std::fabs(on - want) < want * 0.25f,
           std::to_string(on) + ", wanted about " + std::to_string(want));
    }

    // At twice the tempo the cell is half as long, and the take ends with it.
    {
        auto rig = oneSecondTakeAt120();
        const float on = playForSeconds(*rig, 0.45, 240.0f, true);
        ok("at twice the tempo it is still sounding at the half-way point",
           std::fabs(on - want) < want * 0.25f, std::to_string(on));
    }
    {
        auto rig = oneSecondTakeAt120();
        const float past = playForSeconds(*rig, 0.8, 240.0f, true);
        ok("and has finished by the end of the two beats", std::fabs(past) < 1e-6,
           std::to_string(past));
    }
}

/**
 * Fades, and crossfades made of two of them. A crossfade must be equal power
 * so the level holds steady. A linear pair dips 3 dB in the middle.
 */
void fadesAreEqualPowerSoACrossfadeHolds() {
    printf("- a fade, and the crossfade that is two of them\n");
    constexpr int32_t kLen = 4800; // 100 ms
    Reel::Region a;
    a.frames = kLen;
    a.fadeOut = kLen;
    Reel::Region b;
    b.frames = kLen;
    b.fadeIn = kLen;

    ok("a fade starts at nothing and ends at one",
       a.fadeAt(kLen) < 1e-6f && std::fabs(a.fadeAt(0) - 1.0f) < 1e-6f,
       std::to_string(a.fadeAt(0)) + " .. " + std::to_string(a.fadeAt(kLen)));

    // The squares sum to one everywhere, which is what equal power means.
    double worst = 0.0;
    int32_t worstAt = -1;
    for (int32_t i = 0; i <= kLen; i += 37) {
        const float x = a.fadeAt(i), y = b.fadeAt(i);
        const double sum = static_cast<double>(x) * x + static_cast<double>(y) * y;
        if (std::fabs(sum - 1.0) > worst) { worst = std::fabs(sum - 1.0); worstAt = i; }
    }
    ok("and two of them crossing hold a steady level", worst < 0.002,
       "worst " + std::to_string(worst) + " at " + std::to_string(worstAt));

    // A take with no fades is untouched.
    Reel::Region plain;
    plain.frames = kLen;
    ok("a take with no fades is not touched",
       plain.fadeAt(0) == 1.0f && plain.fadeAt(kLen / 2) == 1.0f && plain.fadeAt(kLen) == 1.0f);
}


// --- Comps ----------------------------------------------------------------------

/** Renders [frames] of scene 1 from the top, in 64-frame blocks at 120 bpm, left channel. */
std::vector<float> playCell(Rig &rig, int32_t frames, bool stretch = false) {
    rig.bias->onBlock(0, 0, 120.0f);
    rig.bias->params().set(machine::Bias::Stretch, stretch ? 1.0f : 0.0f);
    rig.bias->params().jumpAll();
    std::vector<float> out;
    for (int32_t at = 0; at < frames; at += 64) {
        rig.bias->onScene(1, at / kFramesPerTick, true, false);
        rig.bias->render(rig.L, rig.R, 64);
        out.insert(out.end(), rig.L, rig.L + 64);
    }
    return out;
}

/** A sine at [hz] and [level] of full scale, with silence over [quietFrom, quietTo). */
std::shared_ptr<Reel::Source> sine(int32_t frames, float hz, float level, int32_t quietFrom = 0,
                                   int32_t quietTo = 0) {
    auto s = std::make_shared<Reel::Source>();
    std::vector<int16_t> planes(static_cast<size_t>(frames));
    for (int32_t i = 0; i < frames; ++i) {
        const bool quiet = i >= quietFrom && i < quietTo;
        planes[static_cast<size_t>(i)] =
            quiet ? 0 : toI16(level * std::sin(6.2831853f * hz * static_cast<float>(i) / kRate));
    }
    s->hold(std::move(planes), frames, false);
    return s;
}

/** Each piece plays its own take, at the frames the region says. */
void piecesReadTheirOwnTake() {
    printf("- pieces from a stack of takes\n");
    Rig rig;
    const int32_t total = kBarFrames * 4;
    auto file = ramped(total, 0, total);
    Reel::Cell cell{1, {}};
    Reel::Region &r = cell.lanes[0] = region(file, 0, kBarFrames, kBarTicks);
    // Two takes: the head of the file and two bars into it.
    r.takes = {{file, 0}, {file, kBarFrames * 2}};
    r.pieces = {{0, kBarFrames / 2, 0}, {kBarFrames / 2, kBarFrames, 1}};
    r.settle();
    rig.reel.cells = {cell};
    rig.mount();

    const int64_t quarter = kBarTicks / 4, threeQuarters = kBarTicks * 3 / 4;
    ok("the first piece reads take one", near(rig.probe(1, quarter), expectRamp(kBarFrames / 4)));
    const float second = rig.probe(1, threeQuarters);
    ok("the second reads take two, at the same place in the cell",
       near(second, expectRamp(kBarFrames * 2 + kBarFrames * 3 / 4)),
       "wanted frame " + std::to_string(kBarFrames * 2 + kBarFrames * 3 / 4));
    ok("and never the poison past either", second > 0.0f);

    // Stretch on at another tempo still reads the pieces, not take one whole.
    Rig fast;
    Reel::Cell flatCell{1, {}};
    Reel::Region &f = flatCell.lanes[0] = region(flat(kBarFrames, 1000), 0, kBarFrames, kBarTicks);
    f.takes = {{f.source, 0}, {flat(kBarFrames, 3000), 0}};
    f.pieces = {{0, kBarFrames / 2, 0}, {kBarFrames / 2, kBarFrames, 1}};
    f.settle();
    fast.reel.cells = {flatCell};
    fast.mount();
    fast.bias->onBlock(0, 0, 100.0f);
    fast.bias->params().set(machine::Bias::Stretch, 1.0f);
    fast.bias->params().jumpAll();
    float late = 0.0f;
    const double perTick = kRate * 60.0 / (100.0 * kPPQN);
    for (int32_t at = 0; at < kBarFrames; at += 64) {
        fast.bias->onScene(1, static_cast<int64_t>(at / perTick), true, false);
        fast.bias->render(fast.L, fast.R, 64);
        if (at >= kBarFrames * 3 / 4 && at < kBarFrames * 3 / 4 + 64) late = fast.L[32];
    }
    ok("stretched, the second piece still comes from take two",
       std::fabs(late - 3000.0f / 32768.0f) < 0.005f, std::to_string(late * 32768.0f));
}

/** Two pieces that meet crossfade, so there's no step where they join. */
void aJoinCrossfades() {
    printf("- a join crossfades\n");
    Rig rig;
    Reel::Cell cell{1, {}};
    Reel::Region &r = cell.lanes[0] = region(flat(kBarFrames, 1000), 0, kBarFrames, kBarTicks);
    r.takes = {{r.source, 0}, {flat(kBarFrames, 3000), 0}};
    const int32_t seam = kBarFrames / 2;
    r.pieces = {{0, seam, 0}, {seam, kBarFrames, 1}};
    r.settle();
    ok("between two flat takes the join stays where it was put", r.pieces[0].end == seam,
       std::to_string(r.pieces[0].end));
    rig.reel.cells = {cell};
    rig.mount();
    const std::vector<float> out = playCell(rig, kBarFrames);

    float step = 0.0f;
    for (int32_t i = seam - 1000; i < seam + 1000; ++i) {
        step = std::max(step, std::fabs(out[static_cast<size_t>(i + 1)] - out[static_cast<size_t>(i)]));
    }
    // A cut would step by 2000. Over a 240-frame fade the steepest step is
    // about 2000 * pi/2 / 240.
    ok("no step at the join", step * 32768.0f < 40.0f, std::to_string(step * 32768.0f) + " a sample");
    ok("take one before it", near(out[static_cast<size_t>(seam - kJoinFadeFrames)], 1000.0f / 32768.0f));
    ok("take two after it", near(out[static_cast<size_t>(seam + kJoinFadeFrames)], 3000.0f / 32768.0f));
    ok("and both in the middle of it",
       out[static_cast<size_t>(seam)] > 1500.0f / 32768.0f && out[static_cast<size_t>(seam)] < 2900.0f / 32768.0f,
       std::to_string(out[static_cast<size_t>(seam)] * 32768.0f));
}

/** A join moves to a quiet spot nearby, like the breath between two phrases. */
void aJoinFindsAQuietSpot() {
    printf("- a join moves somewhere quiet\n");
    Rig rig;
    const int32_t seam = kBarFrames / 2;
    // Both takes breathe 15-30 ms after where the join was asked for.
    const int32_t from = seam + 720, to = seam + 1440;
    Reel::Cell cell{1, {}};
    Reel::Region &r = cell.lanes[0] = region(sine(kBarFrames, 220.0f, 0.5f, from, to), 0, kBarFrames, kBarTicks);
    r.takes = {{r.source, 0}, {sine(kBarFrames, 330.0f, 0.5f, from, to), 0}};
    r.pieces = {{0, seam, 0}, {seam, kBarFrames, 1}};
    r.settle();
    const int32_t moved = r.pieces[0].end;
    ok("the join moved into the gap", moved - kJoinFadeFrames / 2 >= from && moved + kJoinFadeFrames / 2 <= to,
       "at " + std::to_string(moved - seam) + " frames from where it was asked for");
    ok("and the next piece starts there", r.pieces[1].start == moved);
    rig.reel.cells = {cell};
    rig.mount();
    const std::vector<float> out = playCell(rig, kBarFrames);
    float loudest = 0.0f;
    for (int32_t i = moved - kJoinFadeFrames / 2; i < moved + kJoinFadeFrames / 2; ++i) {
        loudest = std::max(loudest, std::fabs(out[static_cast<size_t>(i)]));
    }
    ok("so the crossfade is over silence", loudest == 0.0f, std::to_string(loudest));

    // An edge into a gap between pieces moves the same way.
    Reel::Region g = region(sine(kBarFrames, 220.0f, 0.5f, from, to), 0, kBarFrames, kBarTicks);
    g.pieces = {{0, seam, 0}, {seam + 4800, kBarFrames, 0}};
    g.takes = {{g.source, 0}, {g.source, 0}};
    g.pieces[1].take = 1;
    g.settle();
    ok("an edge into a gap moves too", g.pieces[0].end - kJoinFadeFrames / 2 >= from &&
                                           g.pieces[0].end + kJoinFadeFrames / 2 <= to,
       std::to_string(g.pieces[0].end - seam));
}

/** A lane with one take, or one piece over all of it, plays exactly as before. */
void oneTakeIsUnchanged() {
    printf("- one take is a plain lane\n");
    const int32_t total = kBarFrames * 2;
    auto file = sine(total, 220.0f, 0.5f);
    auto other = sine(total, 330.0f, 0.5f);
    const auto play = [&](Reel::Region r) {
        Rig rig;
        Reel::Cell cell{1, {}};
        cell.lanes[0] = std::move(r);
        rig.reel.cells = {cell};
        rig.mount();
        return playCell(rig, kBarFrames);
    };
    const std::vector<float> plain = play(region(file, 1000, kBarFrames, kBarTicks));

    Reel::Region whole = region(file, 1000, kBarFrames, kBarTicks);
    whole.pieces = {{0, kBarFrames, 0}};
    whole.settle();
    ok("one piece over the whole region is a plain region", !whole.comped());
    ok("and renders the same, bit for bit", play(whole) == plain);

    Reel::Region same = region(file, 1000, kBarFrames, kBarTicks);
    same.takes = {{file, 1000}, {other, 0}};
    same.pieces = {{0, kBarFrames / 3, 0}, {kBarFrames / 3, kBarFrames, 0}};
    same.settle();
    ok("pieces of one take, end to end, merge into a plain region", !same.comped() && play(same) == plain);

    Reel::Region second = region(file, 1000, kBarFrames, kBarTicks);
    second.takes = {{file, 1000}, {other, 500}};
    second.pieces = {{0, kBarFrames, 1}};
    second.settle();
    ok("one piece of take two plays take two plainly",
       !second.comped() && play(second) == play(region(other, 500, kBarFrames, kBarTicks)));
}

/**
 * Listening through a phrase: a loop of the lane, each pass from the next
 * take, playing instead of the song and whatever the transport is doing.
 */
void listeningThroughPlaysEachTakeInTurn() {
    printf("- listening through\n");
    Rig rig;
    Reel::Cell cell{1, {}};
    cell.lanes[0] = region(flat(kBarFrames, 500), 0, kBarFrames, kBarTicks);
    rig.reel.cells = {cell};
    rig.mount();

    // Three takes, each a flat level, and a phrase in the middle.
    const int32_t from = kBarFrames / 4, to = kBarFrames / 2, lead = 4800;
    auto takes = std::vector<std::shared_ptr<Reel::Source>>{flat(kBarFrames, 1000), flat(kBarFrames, 2000), flat(kBarFrames, 3000)};
    audio::Listen listen;
    listen.lane = 0;
    listen.from = from - lead;
    listen.to = to;
    for (int k = 0; k < 3; ++k) {
        Reel::Region r = region(takes[0], 0, kBarFrames, kBarTicks);
        r.takes = {{takes[0], 0}, {takes[1], 0}, {takes[2], 0}};
        r.pieces = {{0, from, 0}, {from, to, k}, {to, kBarFrames, 0}};
        r.settle();
        listen.passes.push_back(r);
    }
    ok("no listening, no position", rig.bias->listenPlayed() == -1);
    rig.bias->swapObject(1, &listen);
    ok("listening starts at nothing played", rig.bias->listenPlayed() == 0);

    // Stopped transport: the song alone would be silent.
    const int32_t len = listen.length();
    std::vector<float> out;
    for (int64_t done = 0; done < static_cast<int64_t>(len) * 4; done += 256) {
        rig.bias->onScene(1, 0, false, false);
        rig.bias->render(rig.L, rig.R, 256);
        out.insert(out.end(), rig.L, rig.L + 256);
    }
    const auto at = [&](int64_t pass, int32_t inLoop) { return out[static_cast<size_t>(pass * len + inLoop)] * 32768.0f; };
    ok("the lead-in plays the comp as it is", std::fabs(at(0, lead / 2) - 1000.0f) < 0.5f, std::to_string(at(0, lead / 2)));
    ok("pass one plays take one over the phrase", std::fabs(at(0, len - 2000) - 1000.0f) < 0.5f, std::to_string(at(0, len - 2000)));
    ok("pass two plays take two", std::fabs(at(1, len - 2000) - 2000.0f) < 0.5f, std::to_string(at(1, len - 2000)));
    ok("pass three plays take three", std::fabs(at(2, len - 2000) - 3000.0f) < 0.5f, std::to_string(at(2, len - 2000)));
    ok("then round to take one", std::fabs(at(3, len - 2000) - 1000.0f) < 0.5f, std::to_string(at(3, len - 2000)));
    ok("the lead-in of pass two is still the comp", std::fabs(at(1, lead / 2) - 1000.0f) < 0.5f);
    float step = 0.0f;
    for (size_t i = 1; i < out.size(); ++i) step = std::max(step, std::fabs(out[i] - out[i - 1]));
    ok("no step anywhere, the loop's ends and joins included", step * 32768.0f < 60.0f, std::to_string(step * 32768.0f));
    ok("the position counts the frames played", rig.bias->listenPlayed() == static_cast<int64_t>(out.size()));

    // A muted lane is still heard while listening, at the lane's level.
    rig.bias->params().set(machine::Bias::Mute1, 1.0f);
    rig.bias->params().set(machine::Bias::Lane1, 0.5f);
    rig.bias->params().jumpAll();
    rig.bias->swapObject(1, &listen);
    rig.bias->render(rig.L, rig.R, 256);
    rig.bias->render(rig.L, rig.R, 256);
    rig.bias->render(rig.L, rig.R, 256);
    ok("a muted lane is heard at its level", std::fabs(rig.L[100] * 32768.0f - 500.0f) < 0.5f, std::to_string(rig.L[100] * 32768.0f));

    // Stopping hands the listen back and the song plays again.
    rig.bias->params().set(machine::Bias::Mute1, 0.0f);
    rig.bias->params().set(machine::Bias::Lane1, 1.0f);
    rig.bias->params().jumpAll();
    ok("stopping hands back what was listened to", rig.bias->swapObject(1, nullptr) == &listen);
    ok("and there's no position", rig.bias->listenPlayed() == -1);
    ok("the song plays as before", near(rig.probe(1, kBarTicks / 2), 500.0f / 32768.0f));
}


// --- C5: suggest, line up and blend ---------------------------------------

/** A piece's shift reads its take that many frames later; zero is the plain read. */
void aShiftedPieceReadsLater() {
    printf("- a shifted piece\n");
    Rig rig;
    const int32_t total = kBarFrames * 2;
    auto file = ramped(total, 0, total);
    Reel::Cell cell{1, {}};
    Reel::Region &r = cell.lanes[0] = region(file, 0, kBarFrames, kBarTicks);
    r.takes = {{file, 0}, {file, 0}};
    r.pieces = {{0, kBarFrames / 2, 0}, {kBarFrames / 2, kBarFrames, 1, -1000}};
    r.settle();
    ok("a shift keeps a whole-take piece from merging", r.pieces.size() == 2, std::to_string(r.pieces.size()));
    rig.reel.cells = {cell};
    rig.mount();
    const int32_t at = kBarFrames * 3 / 4;
    ok("a piece shifted 1000 frames earlier reads 1000 frames on",
       near(rig.probe(1, at / kFramesPerTick), expectRamp(at + 1000)));
    ok("the unshifted piece reads where it is", near(rig.probe(1, kBarTicks / 4), expectRamp(kBarFrames / 4)));

    Reel::Region whole = region(file, 0, kBarFrames, kBarTicks);
    whole.pieces = {{0, kBarFrames, 0, 480}};
    whole.settle();
    ok("one shifted piece over the whole region stays a comp", whole.comped());
}

/** Noise from its own seed, at an RMS of [rms]. */
std::shared_ptr<Reel::Source> noise(int32_t frames, float rms, uint32_t seed) {
    auto s = std::make_shared<Reel::Source>();
    std::vector<int16_t> planes(static_cast<size_t>(frames));
    uint32_t x = seed;
    for (int32_t i = 0; i < frames; ++i) {
        x = x * 1664525u + 1013904223u;
        const float u = static_cast<float>(x >> 8) / 16777216.0f * 2.0f - 1.0f; // rms 1/sqrt(3)
        planes[static_cast<size_t>(i)] = toI16(u * rms * 1.7320508f);
    }
    s->hold(std::move(planes), frames, false);
    return s;
}

float rmsOf(const std::vector<float> &v, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to; ++i) e += static_cast<double>(v[i]) * v[i];
    return static_cast<float>(std::sqrt(e / static_cast<double>(to - from)));
}

/** Blending a stack: every take at once, at about the level of one. */
void aBlendSitsAtOneTakesLevel() {
    printf("- blend\n");
    Rig rig;
    Reel::Cell cell{1, {}};
    Reel::Region &r = cell.lanes[0] = region(noise(kBarFrames, 0.1f, 1), 0, kBarFrames, kBarTicks);
    r.takes = {{r.source, 0}, {noise(kBarFrames, 0.2f, 2), 0}, {noise(kBarFrames, 0.05f, 3), 0}};
    // Take three only sings the second half, so the scale has to move.
    r.takes[2].start = kBarFrames / 2;
    r.takes[2].end = kBarFrames;
    r.pieces = {{0, kBarFrames, 1}};
    r.blend = true;
    r.settle();
    ok("a blend keeps every take", r.comped() && r.takes.size() == 3);
    rig.reel.cells = {cell};
    rig.mount();
    const std::vector<float> out = playCell(rig, kBarFrames);
    const float first = rmsOf(out, 4800, kBarFrames / 2 - 4800);
    const float second = rmsOf(out, kBarFrames / 2 + 4800, kBarFrames - 4800);
    const auto db = [](float v) { return 20.0f * std::log10(v / 0.1f); };
    ok("two takes blend at the middle take's level", std::fabs(db(first)) < 1.0f, std::to_string(db(first)) + " dB");
    ok("three takes too", std::fabs(db(second)) < 1.0f, std::to_string(db(second)) + " dB");
    float step = 0.0f;
    const auto mid = static_cast<size_t>(kBarFrames / 2);
    const float before = rmsOf(out, mid - 2400, mid - 240), after = rmsOf(out, mid + 240, mid + 2400);
    step = std::fabs(db(after) - db(before));
    ok("and the level holds as a take comes in", step < 1.5f, std::to_string(step) + " dB");

    // A blend of one take is the take itself.
    Reel::Region one = region(noise(kBarFrames, 0.1f, 1), 0, kBarFrames, kBarTicks);
    one.blend = true;
    one.settle();
    ok("a blend of one take is a plain lane", !one.comped());

    // Played twice through reset(), it's the same.
    rig.bias->reset();
    ok("it repeats after a reset", playCell(rig, kBarFrames) == out);
}

// Material for the scorers: a sung line with the dirt a phone puts on it.
//
// Notes on the song's eighths at 120 bpm, three phrases of six with a rest
// between, half a second of room tone first, a low rumble under it all and a
// little hiss. Each take can be sharp or flat, late, clipped or noisy.
struct Sung {
    float drift = 0.0f;    // cents a note drifts by its end, alternating sharp and flat by phrase
    int32_t late = 0;      // frames
    float drive = 1.0f;    // gain before a hard clip
    float hiss = 0.002f;   // noise peak
    float detune = 0.0f;   // cents, whole take
    bool bright = true;    // the timbre: a second singer is darker
    uint32_t seed = 7;
    int32_t swing = 0;     // frames the off-beat eighths come late
    float vibrato = 8.0f;  // cents
    std::vector<float> notes; // cents from A 440 per note, instead of the line below
};

constexpr int32_t kEighth = 12000;      // at 120 bpm
constexpr int32_t kLead = 24000;        // half a second of room
constexpr int32_t kPhraseNotes = 6;
constexpr int32_t kPhrases = 3;
constexpr int32_t kPhraseGap = 48000;   // a second's rest
constexpr int32_t kPhraseLen = kPhraseNotes * kEighth;
constexpr int32_t kSungFrames = kLead + kPhrases * (kPhraseLen + kPhraseGap);

int32_t phraseStart(int32_t p) { return kLead + p * (kPhraseLen + kPhraseGap); }

std::vector<float> sungFloat(const Sung &o) {
    std::vector<float> out(static_cast<size_t>(kSungFrames), 0.0f);
    const int midi[kPhraseNotes] = {57, 60, 62, 64, 62, 60};
    double phase = 0.0;
    for (int32_t p = 0; p < kPhrases; ++p) {
        for (int32_t n = 0; n < kPhraseNotes; ++n) {
            const int32_t on = phraseStart(p) + n * kEighth + o.late + (n % 2 == 1 ? o.swing : 0);
            const int32_t len = kEighth - 1920 - o.swing; // 40 ms between notes
            for (int32_t i = 0; i < len && on + i < kSungFrames; ++i) {
                const float t = static_cast<float>(i) / kRate;
                const float frac = static_cast<float>(i) / len;
                const float cents = o.detune + (p % 2 == 0 ? 1.0f : -1.0f) * o.drift * frac +
                                    o.vibrato * std::sin(6.2831853f * 5.5f * t);
                const double note = o.notes.empty() ? (midi[n] - 69) * 100.0 : o.notes[static_cast<size_t>(n) % o.notes.size()];
                const double hz = 440.0 * std::pow(2.0, (note + cents) / 1200.0);
                phase += 6.283185307 * hz / kRate;
                float v = 0.0f;
                for (int k = 1; k <= 8; ++k) {
                    const float amp = o.bright ? 1.0f / k : 1.0f / (k * k);
                    v += amp * static_cast<float>(std::sin(phase * k));
                }
                const float attack = std::min(1.0f, i / 480.0f);
                const float release = std::min(1.0f, (len - i) / 1440.0f);
                out[static_cast<size_t>(on + i)] += 0.3f * v * attack * release;
            }
        }
    }
    uint32_t x = o.seed;
    for (int32_t i = 0; i < kSungFrames; ++i) {
        x = x * 1664525u + 1013904223u;
        const float u = static_cast<float>(x >> 8) / 16777216.0f * 2.0f - 1.0f;
        float v = out[static_cast<size_t>(i)] * o.drive;
        v = std::clamp(v, -1.0f, 1.0f);
        v += 0.02f * std::sin(6.2831853f * 45.0f * i / kRate) + u * o.hiss;
        out[static_cast<size_t>(i)] = v;
    }
    return out;
}

std::shared_ptr<Reel::Source> sung(const Sung &o) {
    const std::vector<float> f = sungFloat(o);
    auto s = std::make_shared<Reel::Source>();
    std::vector<int16_t> planes(f.size());
    for (size_t i = 0; i < f.size(); ++i) planes[i] = toI16(f[i]);
    s->hold(std::move(planes), static_cast<int32_t>(f.size()), false);
    return s;
}

std::vector<int32_t> sungCuts() {
    std::vector<int32_t> cuts = {0};
    for (int32_t p = 1; p < kPhrases; ++p) cuts.push_back(phraseStart(p) - kPhraseGap / 2);
    cuts.push_back(kSungFrames);
    return cuts;
}

/** [a] scored against [b], take-major, phrases from sungCuts. */
std::vector<takescore::Score> scorePair(const std::shared_ptr<Reel::Source> &a,
                                        const std::shared_ptr<Reel::Source> &b, bool grid = true) {
    takescore::Stack st;
    st.takes = {{a.get(), 0, 0, kSungFrames}, {b.get(), 0, 0, kSungFrames}};
    st.cuts = sungCuts();
    st.gridStep = grid ? kEighth / 2.0 : 0.0;
    st.sampleRate = kRate;
    return takescore::scoreStack(st);
}

/** True if take 0 beats take 1 in every phrase, with the scores in [why]. */
bool beatsEveryPhrase(const std::vector<takescore::Score> &s, std::string &why, bool pitched = true) {
    const auto phrases = static_cast<int32_t>(s.size() / 2);
    bool all = true;
    char buf[160];
    for (int32_t p = 0; p < phrases; ++p) {
        const auto &a = s[static_cast<size_t>(p)];
        const auto &b = s[static_cast<size_t>(phrases + p)];
        snprintf(buf, sizeof buf, "[%.0f v %.0f: %.1f/%.1f c %.1f/%.1f ms %g/%g clips %.0f/%.0f dB] ", a.score, b.score,
                 a.pitchCents, b.pitchCents, a.timingMs, b.timingMs, a.clips, b.clips, a.snrDb, b.snrDb);
        why += buf;
        if (!(a.score > b.score)) all = false;
        if (pitched && std::isnan(a.pitchCents)) all = false;
    }
    return all;
}

void theScorersPickTheCleanTake() {
    printf("- scoring takes by phrase\n");
    const auto steady = sung({});
    std::string why;
    ok("a steady take beats one drifting sharp and flat", beatsEveryPhrase(scorePair(steady, sung({35.0f})), why), why);
    why.clear();
    ok("an on-time take beats one 30 ms late", beatsEveryPhrase(scorePair(steady, sung({0.0f, 1440})), why), why);
    why.clear();
    ok("an unclipped take beats a clipped one", beatsEveryPhrase(scorePair(steady, sung({0.0f, 0, 4.0f})), why), why);
    why.clear();
    ok("a clean take beats a noisy one", beatsEveryPhrase(scorePair(steady, sung({0.0f, 0, 1.0f, 0.06f, 0.0f, true, 9})), why), why);
    why.clear();
    // Late against the other take alone, with no grid to measure from.
    ok("with no grid, the late take still loses to the takes that agree", [&] {
        takescore::Stack st;
        const auto late = sung({0.0f, 1440}), again = sung({0.0f, 0, 1.0f, 0.002f, 0.0f, true, 11});
        st.takes = {{again.get(), 0, 0, kSungFrames}, {late.get(), 0, 0, kSungFrames}, {steady.get(), 0, 0, kSungFrames}};
        st.cuts = sungCuts();
        st.sampleRate = kRate;
        const auto s = takescore::scoreStack(st);
        const int32_t n = kPhrases;
        bool all = true;
        for (int32_t p = 0; p < n; ++p) {
            char buf[64];
            snprintf(buf, sizeof buf, "[%.1f/%.1f/%.1f ms] ", s[p].timingMs, s[n + p].timingMs, s[2 * n + p].timingMs);
            why += buf;
            if (!(s[n + p].score < s[p].score && s[n + p].score < s[2 * n + p].score)) all = false;
        }
        return all;
    }(), why);
    why.clear();

    // Drums: no pitch, so they're judged on the rest.
    const auto drums = [](int32_t late, uint32_t seed) {
        auto s = std::make_shared<Reel::Source>();
        std::vector<int16_t> planes(static_cast<size_t>(kSungFrames));
        uint32_t x = seed;
        for (int32_t i = 0; i < kSungFrames; ++i) {
            x = x * 1664525u + 1013904223u;
            const float u = static_cast<float>(x >> 8) / 16777216.0f * 2.0f - 1.0f;
            float v = u * 0.002f + 0.02f * std::sin(6.2831853f * 45.0f * i / kRate);
            for (int32_t p = 0; p < kPhrases; ++p) {
                const int32_t into = i - phraseStart(p) - late;
                if (into < 0 || into >= kPhraseLen) continue;
                const int32_t k = into % (kEighth / 2);
                v += u * 0.5f * std::exp(-static_cast<float>(k) / 1200.0f);
            }
            planes[static_cast<size_t>(i)] = toI16(v);
        }
        s->hold(std::move(planes), kSungFrames, false);
        return s;
    };
    const auto ds = scorePair(drums(0, 3), drums(1440, 5));
    ok("drums on time beat drums late, with no pitch to read", beatsEveryPhrase(ds, why, false) && std::isnan(ds[0].pitchCents), why);
    why.clear();

    // A take missing a phrase can't be chosen there.
    takescore::Stack st;
    st.takes = {{steady.get(), 0, 0, kSungFrames}, {steady.get(), 0, 0, sungCuts()[1]}};
    st.cuts = sungCuts();
    st.sampleRate = kRate;
    const auto part = takescore::scoreStack(st);
    ok("a take with no audio in a phrase has no score there",
       !std::isnan(part[kPhrases].score) && std::isnan(part[kPhrases + 1].score) && std::isnan(part[kPhrases + 2].score));
}

/** A double 30 ms behind its lead moves onto it. */
void aLateDoubleLinesUp() {
    printf("- lining up a double\n");
    const auto lead = sung({});
    const auto dbl = sung({0.0f, 1440, 1.0f, 0.003f, 12.0f, false, 21});
    const Reel::Region l = region(lead, 0, kSungFrames, kBarTicks);
    const Reel::Region d = region(dbl, 0, kSungFrames, kBarTicks);
    const std::vector<float> shifts = takescore::lineUp(l, d, sungCuts(), 2400, kRate);
    bool all = shifts.size() == static_cast<size_t>(kPhrases);
    std::string why;
    for (float s : shifts) {
        why += std::to_string(s) + " ";
        if (std::isnan(s) || std::fabs(s + 1440.0f) > 144.0f) all = false;
    }
    ok("each phrase of a 30 ms late double shifts back to within 3 ms", all, why);

    // Played with those shifts, the double's hits land on the lead's.
    Reel::Region moved = d;
    const auto cuts = sungCuts();
    moved.takes = {{dbl, 0}};
    for (size_t i = 0; i < shifts.size(); ++i) {
        moved.pieces.push_back({cuts[i], cuts[i + 1], 0, static_cast<int32_t>(std::lround(shifts[i]))});
    }
    moved.settle();
    const std::vector<float> again = takescore::lineUp(l, moved, cuts, 2400, kRate);
    float worst = 0.0f;
    for (float s : again) worst = std::isnan(s) ? 1e9f : std::max(worst, std::fabs(s));
    std::string residue;
    for (float s : again) residue += std::to_string(s) + " ";
    ok("and once moved there is nothing left to move", worst < 144.0f, residue);

    // Too far off to be the same performance: left alone.
    const auto far = sung({0.0f, 4800, 1.0f, 0.003f, 12.0f, false, 23});
    const std::vector<float> none = takescore::lineUp(l, region(far, 0, kSungFrames, kBarTicks), cuts, 2400, kRate);
    bool left = true;
    for (float s : none) left = left && (std::isnan(s) || std::fabs(s) <= 2400.0f);
    ok("a double 100 ms off is never moved past the limit", left);
}


// --- C6: word by word, a lined-up blend, the lead's time, tunings, spread ---

// Spoken material: ten words, two of them run on from the word before with
// no gap, each a tone of its own with a quick attack, on room tone with
// rumble and hiss, one word clipped.
constexpr int32_t kWords = 10;
constexpr int32_t kWordLen = 9600;   // 200 ms
const int32_t kGapMs[kWords] = {500, 100, 80, 0, 120, 60, 100, 0, 80, 100}; // before each word
int32_t wordOnset(int32_t i) {
    int32_t at = 0;
    for (int32_t k = 0; k <= i; ++k) at += kGapMs[k] * 48 + (k > 0 ? kWordLen : 0);
    return at;
}
const int32_t kSpokenFrames = wordOnset(kWords - 1) + kWordLen + 24000;

std::vector<float> spokenFloat(const int32_t *lateMs, bool dark, uint32_t seed, float hiss = 0.003f) {
    std::vector<float> out(static_cast<size_t>(kSpokenFrames), 0.0f);
    for (int32_t w = 0; w < kWords; ++w) {
        const int32_t on = wordOnset(w) + (lateMs != nullptr ? lateMs[w] * 48 : 0);
        const bool runOn = w + 1 < kWords && kGapMs[w + 1] == 0;
        const double hz = 180.0 * std::pow(2.0, (w * 5 % 12) / 12.0);
        for (int32_t i = 0; i < kWordLen && on + i < kSpokenFrames; ++i) {
            if (on + i < 0) continue;
            const float attack = std::min(1.0f, i / 240.0f);
            // A word that runs on dips to -20 dB, so the next is a hit with no gap.
            const float tail = std::min(1.0f, (kWordLen - i) / 960.0f);
            const float release = runOn ? 0.1f + 0.9f * tail : tail;
            float v = 0.0f;
            for (int k = 1; k <= 6; ++k) v += (dark ? 1.0f / (k * k) : 1.0f / k) * static_cast<float>(std::sin(6.283185307 * hz * k * i / kRate));
            float x = 0.35f * v * std::min(attack, release);
            if (w == 4) x = std::clamp(x * 3.0f, -0.6f, 0.6f); // clipped
            out[static_cast<size_t>(on + i)] += x;
        }
    }
    uint32_t x = seed;
    for (int32_t i = 0; i < kSpokenFrames; ++i) {
        x = x * 1664525u + 1013904223u;
        const float u = static_cast<float>(x >> 8) / 16777216.0f * 2.0f - 1.0f;
        out[static_cast<size_t>(i)] += 0.03f * std::sin(6.2831853f * 50.0f * i / kRate) + hiss * u;
    }
    return out;
}

std::shared_ptr<Reel::Source> sourceOf(const std::vector<float> &f) {
    auto s = std::make_shared<Reel::Source>();
    std::vector<int16_t> planes(f.size());
    for (size_t i = 0; i < f.size(); ++i) planes[i] = toI16(f[i]);
    s->hold(std::move(planes), static_cast<int32_t>(f.size()), false);
    return s;
}

/** [r] as it plays, mono, over its whole length. */
std::vector<float> monoPlayed(const Reel::Region &r) {
    std::vector<float> out(static_cast<size_t>(r.frames));
    int32_t hint = 0;
    for (int32_t i = 0; i < r.frames; ++i) {
        float a = 0.0f, b = 0.0f;
        r.soundAt(i, hint, a, b);
        out[static_cast<size_t>(i)] = 0.5f * (a + b);
    }
    return out;
}

/** The worst gap in frames between each word in [got] and the nearest in [want], and whether the counts match. */
int32_t worstOnset(const std::vector<takescore::WordStart> &got, const std::vector<int32_t> &want, std::string &why) {
    int32_t worst = 0;
    for (const int32_t w : want) {
        int32_t near = 1 << 30;
        for (const auto &g : got) near = std::min(near, std::abs(g.onset - w));
        worst = std::max(worst, near);
        why += std::to_string(near) + " ";
    }
    why += "(" + std::to_string(got.size()) + " words)";
    return got.size() == want.size() ? worst : 1 << 30;
}

void wordsAreCutAtHitsAndGaps() {
    printf("- cutting words\n");
    std::vector<int32_t> want;
    for (int32_t w = 0; w < kWords; ++w) want.push_back(wordOnset(w));
    const std::vector<float> f = spokenFloat(nullptr, false, 3);
    std::string why;
    const int32_t worst = worstOnset(takescore::wordsOf(f.data(), kSpokenFrames, kRate), want, why);
    // A word's hit is the steepest point of its attack, a few ms in.
    ok("every word is found within 5 ms, the run-on ones and the clipped one too", worst <= 240, why);
    const auto words = takescore::wordsOf(f.data(), kSpokenFrames, kRate);
    bool quiet = words.size() == static_cast<size_t>(kWords);
    for (size_t i = 1; quiet && i < words.size(); ++i) {
        const bool runOn = kGapMs[i] == 0;
        if (runOn ? words[i].quietBefore != 0 : words[i].quietBefore < 1920) quiet = false;
    }
    ok("a run-on word has no quiet before it, the others their gap", quiet);
    // Louder hiss and rumble don't make words of their own.
    std::vector<float> dirty = f;
    uint32_t x = 99;
    for (float &v : dirty) {
        x = x * 1664525u + 1013904223u;
        v += 0.01f * (static_cast<float>(x >> 8) / 16777216.0f * 2.0f - 1.0f);
    }
    why.clear();
    ok("hiss at -40 dB doesn't add words", worstOnset(takescore::wordsOf(dirty.data(), kSpokenFrames, kRate), want, why) <= 240, why);
}

void wordsLineUpOneByOne() {
    printf("- lining up word by word\n");
    const int32_t late[kWords] = {20, -10, 25, 25, -15, 20, 5, 5, -20, 0};
    const auto lead = sourceOf(spokenFloat(nullptr, false, 3));
    const auto dbl = sourceOf(spokenFloat(late, true, 17));
    const Reel::Region l = region(lead, 0, kSpokenFrames, kBarTicks);
    const Reel::Region d = region(dbl, 0, kSpokenFrames, kBarTicks);
    const std::vector<int32_t> cuts = {0, kSpokenFrames};
    const auto words = takescore::lineUpWords(l, 0, d, cuts, 2400, kRate);
    bool all = true;
    std::string why;
    for (int32_t w = 0; w < kWords; ++w) {
        const int32_t at = wordOnset(w) + late[w] * 48;
        float s = NAN;
        for (const auto &x : words) if (at >= x.start && at < x.end) s = x.shift;
        why += std::to_string(std::isnan(s) ? 9999 : static_cast<int>(std::lround(s / 48.0f))) + " ";
        if (std::isnan(s) || std::fabs(s + late[w] * 48.0f) > 144.0f) all = false;
    }
    ok("each word's shift is within 3 ms of where it was late", all, why);
    bool tiled = !words.empty() && words.front().start == 0 && words.back().end == kSpokenFrames;
    for (size_t i = 1; i < words.size(); ++i) tiled = tiled && words[i].start == words[i - 1].end;
    ok("the words cover the phrase end to end, in order", tiled);

    // Played as pieces, the double's words land on the lead's.
    Reel::Region moved = d;
    moved.takes = {{dbl, 0}};
    for (const auto &w : words) moved.pieces.push_back({w.start, w.end, 0, std::isnan(w.shift) ? 0 : static_cast<int32_t>(std::lround(w.shift))});
    moved.settle();
    bool stayed = true;
    for (size_t i = 0; i < words.size() && i < moved.pieces.size(); ++i) stayed = stayed && moved.pieces[i].start == words[i].start;
    ok("joins between words of one take stay where they were put", stayed && moved.pieces.size() == words.size());
    std::vector<int32_t> want;
    const std::vector<float> leadPlayed = monoPlayed(l);
    for (const auto &w : takescore::wordsOf(leadPlayed.data(), kSpokenFrames, kRate)) want.push_back(w.onset);
    const std::vector<float> played = monoPlayed(moved);
    why.clear();
    ok("played, every word starts within 3 ms of the lead's", worstOnset(takescore::wordsOf(played.data(), kSpokenFrames, kRate), want, why) <= 144, why);
    // No word heard twice: each lead word has one onset near it, checked
    // above by the count. And the joins don't click.
    float step = 0.0f;
    for (size_t i = 1; i < played.size(); ++i) step = std::max(step, std::fabs(played[i] - played[i - 1]));
    ok("no step bigger than the material's own", step < 0.2f, std::to_string(step));
}

void aLinedUpBlendAlignsALateTake() {
    printf("- a lined-up blend\n");
    const auto first = sourceOf(spokenFloat(nullptr, false, 3));
    int32_t late[kWords];
    for (int32_t &v : late) v = 30;
    const auto second = sourceOf(spokenFloat(late, true, 29));
    // And a third as late, with hiss 26 dB under the singing.
    const auto third = sourceOf(spokenFloat(late, false, 41, 0.05f));
    Reel::Region stack = region(first, 0, kSpokenFrames, kBarTicks);
    stack.takes = {{first, 0}, {second, 0}, {third, 0}};
    const Reel::Region ref = region(first, 0, kSpokenFrames, kBarTicks);
    const auto words = takescore::lineUpStack(ref, 0, stack, {0, kSpokenFrames}, 2400, kRate);
    bool ontoItself = words.size() == 3;
    for (const auto &w : words[0]) ontoItself = ontoItself && (std::isnan(w.shift) || std::fabs(w.shift) <= 48.0f);
    ok("take one lines up on itself, as it is", ontoItself);
    bool back = !words[1].empty();
    std::string why;
    for (const auto &w : words[1]) {
        why += std::to_string(std::isnan(w.shift) ? 9999 : static_cast<int>(std::lround(w.shift))) + " ";
        if (w.end > wordOnset(0) && (std::isnan(w.shift) || std::fabs(w.shift + 1440.0f) > 144.0f)) back = false;
    }
    ok("each word of a take 30 ms late moves back within 3 ms", back, why);
    bool noisy = !words[2].empty();
    why.clear();
    for (const auto &w : words[2]) {
        why += std::to_string(std::isnan(w.shift) ? 9999 : static_cast<int>(std::lround(w.shift))) + " ";
        if (w.end > wordOnset(0) && (std::isnan(w.shift) || std::fabs(w.shift + 1440.0f) > 144.0f)) noisy = false;
    }
    ok("a noisy one too: hiss doesn't make it read late", noisy, why);

    for (size_t k = 0; k < 3; ++k) {
        for (const auto &w : words[k]) {
            if (!std::isnan(w.shift)) stack.takes[k].nudges.push_back({w.start, w.end, static_cast<int32_t>(std::lround(w.shift))});
        }
    }
    stack.blend = true;
    stack.settle();
    // Take two through its nudges against take one: no lag left.
    std::vector<float> a(static_cast<size_t>(kSpokenFrames)), b(static_cast<size_t>(kSpokenFrames));
    for (int32_t i = 0; i < kSpokenFrames; ++i) {
        float x = 0.0f, y = 0.0f;
        Reel::Region::nudgedAt(stack.takes[1], i, x, y);
        b[static_cast<size_t>(i)] = x / 32768.0f;
        a[static_cast<size_t>(i)] = static_cast<float>(first->lp[static_cast<size_t>(i)]) / 32768.0f;
    }
    const float lag = takescore::lagOf(a.data(), b.data(), kSpokenFrames, 2400, kRate);
    ok("blended, take two sits on take one", !std::isnan(lag) && std::fabs(lag) <= 96.0f, std::to_string(lag) + " frames");
    // And the blend still plays at about one take's level.
    Rig rig;
    Reel::Cell cell{1, {}};
    cell.lanes[0] = stack;
    cell.lanes[0].ticks = kBarTicks;
    rig.reel.cells = {cell};
    rig.mount();
    const std::vector<float> out = playCell(rig, kBarFrames);
    rig.bias->reset();
    ok("a nudged blend repeats after a reset", playCell(rig, kBarFrames) == out);
}

void theLeadIsTheTime() {
    printf("- the lead's hits as the time\n");
    // A swung lead, a take that follows it and one on the straight grid.
    Sung sw;
    sw.swing = 1920;
    const auto lead = sung(sw);
    Sung follows = sw;
    follows.seed = 31;
    follows.bright = false;
    Sung straight;
    straight.seed = 37;
    straight.bright = false;
    const auto a = sung(follows), b = sung(straight);
    takescore::Stack st;
    st.takes = {{a.get(), 0, 0, kSungFrames}, {b.get(), 0, 0, kSungFrames}};
    st.cuts = sungCuts();
    st.gridStep = kEighth / 2.0;
    st.sampleRate = kRate;
    const auto grid = takescore::scoreStack(st);
    std::string why;
    bool gridPrefersStraight = true;
    for (int32_t p = 0; p < kPhrases; ++p) {
        why += std::to_string(grid[static_cast<size_t>(p)].timingMs) + "/" + std::to_string(grid[static_cast<size_t>(kPhrases + p)].timingMs) + " ";
        if (!(grid[static_cast<size_t>(kPhrases + p)].timingMs < grid[static_cast<size_t>(p)].timingMs)) gridPrefersStraight = false;
    }
    ok("on the grid, the straight take times better", gridPrefersStraight, why);
    const std::vector<float> lf = sungFloat(sw);
    st.lead.assign(lf.begin(), lf.end());
    why.clear();
    ok("against a swung lead, the take that swings with it wins every phrase", beatsEveryPhrase(takescore::scoreStack(st), why), why);
}

void pitchIsJudgedInTheSongsTuning() {
    printf("- pitch in the song's tuning\n");
    // 19 equal from C, and a just scale from C, against twelve equal.
    takescore::Tuning nineteen;
    nineteen.refHz = 261.6256f;
    nineteen.period = 1200.0f;
    for (int k = 0; k < 19; ++k) nineteen.degrees.push_back(k * 1200.0f / 19.0f);
    takescore::Tuning just;
    just.refHz = 261.6256f;
    just.period = 1200.0f;
    just.degrees = {0.0f, 111.73f, 203.91f, 315.64f, 386.31f, 498.04f, 582.51f, 701.96f, 813.69f, 884.36f, 968.83f, 1088.27f};
    ok("a note of the tuning is 0 cents off", takescore::centsOff(261.6256f * std::pow(2.0f, 3.0f * 2.0f / 19.0f), nineteen) < 0.01f);
    ok("twelve equal is unchanged", std::fabs(takescore::centsOff(440.0f * std::pow(2.0f, 0.1f / 12.0f), takescore::Tuning{}) - 10.0f) < 0.01f);

    const auto run = [&](const std::vector<float> &in, const std::vector<float> &out, const takescore::Tuning &t, const char *what) {
        Sung x;
        x.vibrato = 3.0f;
        x.notes = in;
        Sung y = x;
        y.notes = out;
        const auto sa = sung(x), sb = sung(y);
        takescore::Stack st;
        st.takes = {{sa.get(), 0, 0, kSungFrames}, {sb.get(), 0, 0, kSungFrames}};
        st.cuts = sungCuts();
        st.gridStep = kEighth / 2.0;
        st.sampleRate = kRate;
        st.tuning = t;
        std::string why;
        ok(what, beatsEveryPhrase(takescore::scoreStack(st), why), why);
    };
    // From C as cents from A 440.
    std::vector<float> in19, near12, inJust, equal;
    for (int step : {0, 3, 6, 8, 11, 6}) {
        const float c = step * 1200.0f / 19.0f;
        in19.push_back(c - 900.0f);
        near12.push_back(100.0f * std::round(c / 100.0f) - 900.0f);
    }
    for (float c : {0.0f, 203.91f, 386.31f, 498.04f, 701.96f, 386.31f}) {
        inJust.push_back(c - 900.0f);
        equal.push_back(100.0f * std::round(c / 100.0f) - 900.0f);
    }
    run(in19, near12, nineteen, "in 19 equal, a take in tune with it beats one in twelve equal");
    run(inJust, equal, just, "with a just scale, a take in tune with it beats one in twelve equal");
    run(equal, inJust, takescore::Tuning{}, "and in twelve equal the other way round");
}

void spreadKeepsTheLevel() {
    printf("- spread\n");
    const auto play = [](float spread, int takes) {
        Rig rig;
        Reel::Cell cell{1, {}};
        Reel::Region &r = cell.lanes[0] = region(noise(kBarFrames, 0.1f, 1), 0, kBarFrames, kBarTicks);
        r.takes.push_back({r.source, 0});
        for (int k = 1; k < takes; ++k) r.takes.push_back({noise(kBarFrames, 0.1f, 2 + k), 0});
        r.blend = true;
        r.spread = spread;
        r.settle();
        rig.reel.cells = {cell};
        rig.mount();
        rig.bias->onBlock(0, 0, 120.0f);
        rig.bias->params().jumpAll();
        std::vector<float> l, rr;
        for (int64_t done = 0; done < kBarFrames; done += 256) {
            rig.bias->onScene(1, done / kFramesPerTick, true, false);
            rig.bias->render(rig.L, rig.R, 256);
            l.insert(l.end(), rig.L, rig.L + 256);
            rr.insert(rr.end(), rig.R, rig.R + 256);
        }
        return std::make_pair(l, rr);
    };
    const auto power = [](const std::vector<float> &l, const std::vector<float> &r) {
        double e = 0.0;
        for (size_t i = 4800; i < l.size() - 4800; ++i) e += static_cast<double>(l[i]) * l[i] + static_cast<double>(r[i]) * r[i];
        return 10.0 * std::log10(e);
    };
    const auto mono = [](const std::vector<float> &l, const std::vector<float> &r) {
        double e = 0.0;
        for (size_t i = 4800; i < l.size() - 4800; ++i) {
            const double m = 0.5 * (l[i] + r[i]);
            e += m * m;
        }
        return 10.0 * std::log10(e);
    };
    for (int takes : {2, 3, 5}) {
        const auto centred = play(0.0f, takes);
        const auto wide = play(1.0f, takes);
        const auto half = play(0.5f, takes);
        const double d = power(wide.first, wide.second) - power(centred.first, centred.second);
        const double h = power(half.first, half.second) - power(centred.first, centred.second);
        const double m = mono(wide.first, wide.second) - mono(centred.first, centred.second);
        ok((std::to_string(takes) + " takes spread wide keep their level within 0.1 dB").c_str(), std::fabs(d) < 0.1 && std::fabs(h) < 0.1,
           std::to_string(d) + " / " + std::to_string(h) + " dB");
        ok((std::to_string(takes) + " takes summed to mono lose at most 3 dB and never cancel").c_str(), m > -3.02 && m <= 0.01,
           std::to_string(m) + " dB");
        ok("spread zero is the same both sides", centred.first == centred.second);
    }
    // Two takes spread fully: the first is all left, the second all right.
    Reel::Region r = region(noise(kBarFrames, 0.1f, 1), 0, kBarFrames, kBarTicks);
    r.takes = {{r.source, 0}, {noise(kBarFrames, 0.1f, 2), 0}};
    r.blend = true;
    r.spread = 1.0f;
    r.settle();
    ok("two takes spread fully sit at the edges", std::fabs(r.takes[0].panR) < 1e-6f && std::fabs(r.takes[1].panL) < 1e-6f &&
                                                     std::fabs(r.takes[0].panL - 1.41421356f) < 1e-5f);
}
} // namespace

int main() {
    printf("- a region is a window\n");
    aRegionIsAWindowIntoItsFile();
    printf("- repeats\n");
    aRepeatedSceneCarriesOn();
    printf("- four lanes\n");
    fourLanesSum();
    printf("- mute\n");
    aMutedClipIsSilent();
    printf("- ends\n");
    pastTheEnd();
    printf("- punch-in\n");
    aPunchInWaits();
    printf("- nothing\n");
    nothingToPlay();
    theMediumColoursAndDirectDoesNot();
    aLongTakeIsMappedAndReadsTheSame();
    aTakeCanFollowTheSong();
    fadesAreEqualPowerSoACrossfadeHolds();
    piecesReadTheirOwnTake();
    aJoinCrossfades();
    aJoinFindsAQuietSpot();
    oneTakeIsUnchanged();
    listeningThroughPlaysEachTakeInTurn();
    aShiftedPieceReadsLater();
    aBlendSitsAtOneTakesLevel();
    theScorersPickTheCleanTake();
    aLateDoubleLinesUp();
    wordsAreCutAtHitsAndGaps();
    wordsLineUpOneByOne();
    aLinedUpBlendAlignsALateTake();
    theLeadIsTheTime();
    pitchIsJudgedInTheSongsTuning();
    spreadKeepsTheLevel();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
