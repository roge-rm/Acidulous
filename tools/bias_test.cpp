// Bias, asked where it is and made to say what it read.
//
// Two things here are worth a harness rather than an ear. The first is that a
// region is a *window*: a take sung across four scenes is one file and four
// offsets, and an offset that is wrong by a bar is a vocal that comes in on
// the wrong word - inaudible as a bug, obvious as a disaster. The second is
// that a cell covers bars x repeat, so the second pass of a scene played twice
// must carry on through the take rather than start it again.
//
// Both are answerable exactly, because a reel can be built out of a ramp: put
// the frame's own index in the sample and whatever comes out says where it was
// read from. Everything outside a region carries a sentinel, so a read that
// wanders off the end of one announces itself instead of sounding plausible.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include <engine/core/Reel.h>
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
 * What a frame at index [i] holds, so a reading says where it came from.
 *
 * **Offset by one, because frame nought must not be silence.** Ramped from
 * nought, four of the assertions below compared a reading against 0.0 and
 * would have passed just as well on a machine that rendered nothing at all -
 * which is the failure they exist to catch.
 */
int16_t ramp(int32_t i) { return static_cast<int16_t>(i % 30000 + 1); }

/** Anything a region does not cover. Loud, and nothing a ramp can produce. */
constexpr int16_t kPoison = -30001;

float expectRamp(int32_t frame) { return static_cast<float>(ramp(frame)) / 32768.0f; }

/** A source of [frames], ramped inside [from, to) and poisoned everywhere else. */
std::shared_ptr<Reel::Source> ramped(int32_t frames, int32_t from, int32_t to) {
    auto s = std::make_shared<Reel::Source>();
    // Through `hold`, as the host builds it: a source is read through its
    // pointers now, because the same two lines in the render have to serve a
    // take held in memory and one mapped from a converted file.
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

/**
 * Tight enough to tell one frame from the next.
 *
 * A single frame of the ramp is 1/32768, about 3e-5, so the 1e-4 this started
 * at was looser than the thing being measured: it could not have told frame
 * 500 from frame 502, nor either of them from silence.
 */
bool near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

// --- the tests ------------------------------------------------------------

/**
 * A take sung across three scenes is one file at three offsets.
 *
 * The arithmetic this asserts is the whole of "split at the scene lines": get
 * the offset wrong and the Verse cell sings the Intro's words.
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
 * The second pass of a repeated scene carries on through the take.
 *
 * This is the machine's half of `rackCycleTick`: the scheduler counts the
 * repeats, and Bias has to read that far into the region rather than
 * treating the bar as the whole of it.
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
    // The one that catches the mistake rather than the arithmetic: anything
    // that treats an iteration as the whole cycle reads the head of the take
    // again on the second pass, and that is a vocal singing verse one twice.
    ok("and it has not started the take again",
       !near(rig.probe(1, kBarTicks * 4), expectRamp(0)));
}

/** Four lanes sum, at their levels, and a muted one is not in the sum. */
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
    // The region is one bar of a four-bar file: everything past it is poison.
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
 * The colour section, which is what a Bias patch is.
 *
 * Four claims, and the first is the one the whole design rests on: **Direct
 * changes nothing**. A patch here is a way of listening, so there has to be a
 * way of not listening that way, and it has to be exact rather than nearly.
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

    // Nothing at all: bit for bit, which is what the sample-identical stem
    // export depends on.
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

    // A noise floor, and the same noise floor twice: two exports of one song
    // have to match, and a generator seeded from anything that moves is the
    // one way to break that silently.
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

    // The band is most of what tells a telephone from a reel.
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
        // The 9 kHz half should be gone; measure what is left above the
        // fundamental by differencing against a one-sample delay, which is a
        // crude high pass and enough to tell an octave of difference.
        double before = 0.0, after = 0.0;
        for (int32_t i = 256; i < kN; ++i) {
            before += std::fabs(in[i] - in[i - 1]);
            after += std::fabs(l[i] - l[i - 1]);
        }
        ok("a narrow band loses the top", after < before * 0.5,
           std::to_string(after) + " against " + std::to_string(before));
    }

    // The digital media quantise, and a quantiser that does not is a knob
    // that does nothing.
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
 * A take too long to hold, converted and mapped - and reading the same numbers.
 *
 * This is the whole of step 10 in one check. The resident path and the mapped
 * path must be indistinguishable to the render, because the render has two
 * lines and no idea which it is looking at; anything else here would be a
 * fault nobody finds until they record something long.
 */
void aLongTakeIsMappedAndReadsTheSame() {
    printf("- a take too long to hold, converted once and mapped\n");
    const std::string dir = std::string(std::getenv("TMPDIR") != nullptr ? std::getenv("TMPDIR") : "/tmp");
    const std::string wav = dir + "/reelsrc.wav";
    const std::string cache = dir + "/reelsrc.i16";
    std::remove(cache.c_str());

    // Three seconds of something with a shape to it, written as a real file.
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

    // And says what the reader everybody trusts says.
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

    // The right plane is the right plane and not a copy of the left, which a
    // planar layout gets wrong silently if the two offsets are confused.
    ok("the channels are not the same plane", mapped.rp == mapped.lp + frames);

    // Converting again reuses nothing here, but the *name* must be stable for
    // the same file and different once it changes.
    const std::string first = ReelCache::nameFor(wav);
    ok("the cache name is stable for one file", first == ReelCache::nameFor(wav), first);
    ok("and is not the name of another file", first != ReelCache::nameFor(cache));

    std::remove(cache.c_str());
    std::remove(wav.c_str());
}

/**
 * A take that follows the song rather than its own tempo.
 *
 * The claim in one sentence: **a take recorded at one tempo covers the same
 * musical length at any other**, which is the whole of why the stretch is
 * here.
 *
 * One second recorded at 120 bpm is two beats. Played at 60, two beats last
 * two seconds - so off, the take stops half way through and leaves a hole;
 * on, it covers the whole of it and sings the same notes while doing so.
 *
 * **The tick and the frame count have to agree**, or the test proves nothing:
 * an earlier version of this walked the tick fast and rendered few frames, so
 * the stretcher never got near the end of the take and the assertion passed
 * for no reason at all.
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

    // At the tempo it was recorded at, both answers are the same answer.
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

    // And the other way: at twice the tempo the cell is half as long, so a
    // take that used to overrun now ends with it.
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
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
