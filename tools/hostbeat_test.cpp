// Following a beat handed in by the host (EngineHost::alignBeat), as the
// norns plugin does with its own clock.
//
// Runs the whole Engine against a host clock that is off by part of a beat
// and at a slightly different tempo, and checks our bar comes into line with
// the host's, the tick never jumps (every block starts where the last one
// ended), an arp clocked off those ticks keeps every step and every note-off,
// the song's tempo takes over again on releaseBeat or once the calls stop,
// a play pressed while stopped starts on the host's bar line, and a stopped
// engine jumps its free-running tick onto the host's phase once and holds it.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <vector>

#include <engine/core/Settings.h>
#include <engine/inputmod/InputModRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/inputmod/InputMods.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

using namespace acidulous;
using namespace acidulous::seq;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool good, const char *detail = "") {
    ++checks;
    if (!good) ++failures;
    std::printf("  %s %-56s %s\n", good ? "ok  " : "FAIL", what, detail);
}

constexpr int64_t kBar = 4 * kPPQN;
constexpr double kRate = kSampleRate;

/** Records the arp's notes with the tick of the block they came out in. */
struct Sink : MidiSink {
    int64_t tick = 0;
    struct Ev { int64_t tick; uint8_t status, pitch; };
    std::vector<Ev> events;
    void send(uint8_t status, uint8_t d1, uint8_t) override { events.push_back({tick, status, d1}); }
};

struct Run {
    double finalErrTicks = 0.0;   // ours minus the host's in the bar, the short way
    double worstLateErrTicks = 0; // the worst over the last ten seconds of following
    int64_t worstTicksPerBlock = 0;
    int64_t gaps = 0;             // blocks that didn't start where the last ended
    bool tickWentBack = false;
    int64_t arpOns = 0, arpOffs = 0, arpSpacingFaults = 0, arpStep = 0;
    float bpmAfterStop = 0.0f;
    int64_t ticksAfterStop = 0;
    int32_t sourceAfterStop = -1;
};

std::unique_ptr<Engine> makeEngine(std::shared_ptr<SongSnapshot> &snap) {
    auto engine = std::make_unique<Engine>();
    snap = std::make_shared<SongSnapshot>();
    SceneInfo sc;
    sc.id = 1;
    sc.bars = 2;
    sc.repeat = 1;
    sc.ticksPerBar = static_cast<int32_t>(kBar);
    snap->scenes.push_back(sc);
    snap->rackCount = 1;
    engine->scheduler.swapSnapshot(snap.get());
    engine->clock.requestSongTempo(120.0f);
    return engine;
}

/** What EngineHost::alignBeat does, from the test's thread. */
void alignBeat(Engine &engine, double beat, double bpm, int64_t frame) {
    if (engine.hostTimebase.set(beat, bpm, frame)) engine.transport.takeHost();
}

/** The follower's own phase error, ms, off the sync readout. */
double followerErrorMs(const Engine &engine) {
    return static_cast<int32_t>(engine.transport.syncState() & 0xffffffffLL) / 1000.0;
}

/**
 * Song at 120, host at [hostBpm] and [offsetBeats] ahead of our bar. The host
 * calls five times a second for [followSeconds], then stops calling, or
 * releases the beat if [release].
 */
Run follow(double hostBpm, double offsetBeats, double followSeconds, bool release) {
    std::shared_ptr<SongSnapshot> snap;
    auto engine = makeEngine(snap);

    // Played before the host's first call, so play doesn't wait for its bar.
    float scratch[kBlockFrames * 2];
    engine->renderBlock(nullptr, scratch);
    engine->transport.requestPlay(0);
    engine->renderBlock(nullptr, scratch);
    // The engine's output frame count, which the host stamps each beat with
    // (EngineHost::framesRendered).
    auto framesNow = [&]() { return engine->framesOut.load(); };
    int64_t frames = framesNow();

    // An arp in free sync, clocked off the engine's own tick range each block,
    // the way a rack runs it.
    std::unique_ptr<InputMod> arp(InputModRegistry::create("Arp"));
    arp->params().jump(modifier::Arp::Sync, 1.0f); // free
    arp->params().jumpAll();
    Sink sink;
    arp->handleMidi(0x90, 60, 100, sink);
    arp->handleMidi(0x90, 64, 100, sink);

    // The host's beat at a frame: [offsetBeats] ahead of where we are now.
    const int64_t f0 = frames;
    const double ourBeat0 = static_cast<double>(engine->clock.position()) / kPPQN;
    auto hostBeat = [&](int64_t frame) {
        return ourBeat0 + offsetBeats + static_cast<double>(frame - f0) * hostBpm / (60.0 * kRate);
    };
    auto errTicks = [&]() {
        // Our position against the host's at the start of the block just
        // rendered, both as a fraction of the bar.
        const double ours = std::fmod(static_cast<double>(engine->clock.blockStart()), kBar) / kBar;
        const double theirs = std::fmod(hostBeat(frames - kBlockFrames) * kPPQN, kBar) / kBar;
        double e = ours - (theirs < 0 ? theirs + 1.0 : theirs);
        if (e > 0.5) e -= 1.0;
        if (e < -0.5) e += 1.0;
        return e * kBar;
    };

    Run r;
    const int64_t callEvery = static_cast<int64_t>(kRate * 0.2) / kBlockFrames;
    const int64_t followBlocks = static_cast<int64_t>(followSeconds * kRate) / kBlockFrames;
    const int64_t lateFrom = followBlocks - static_cast<int64_t>(10.0 * kRate) / kBlockFrames;
    const int64_t stopBlocks = static_cast<int64_t>(3.0 * kRate) / kBlockFrames;
    int64_t lastEnd = engine->clock.position();
    int64_t tickAtStop = 0;
    for (int64_t b = 0; b < followBlocks + stopBlocks; ++b) {
        if (b < followBlocks && b % callEvery == 0) {
            const int64_t at = framesNow();
            alignBeat(*engine, hostBeat(at), hostBpm, at);
        }
        if (release && b == followBlocks) engine->transport.releaseHost(); // EngineHost::releaseBeat
        engine->renderBlock(nullptr, scratch);
        frames = framesNow();
        const int64_t s = engine->clock.blockStart(), e = engine->clock.blockEnd();
        if (s != lastEnd) ++r.gaps;
        if (e < s) r.tickWentBack = true;
        if (e - s > r.worstTicksPerBlock) r.worstTicksPerBlock = e - s;
        lastEnd = e;
        sink.tick = s;
        arp->run(s, e, engine->clock.bpm(), sink);
        if (b < followBlocks) {
            const double err = errTicks();
            r.finalErrTicks = err;
            if (b >= lateFrom && std::fabs(err) > std::fabs(r.worstLateErrTicks)) r.worstLateErrTicks = err;
        }
        if (b == followBlocks + stopBlocks / 3) tickAtStop = e; // a second after the last call, give or take
    }
    r.bpmAfterStop = engine->clock.bpm();
    r.sourceAfterStop = engine->transport.syncSource();
    r.ticksAfterStop = engine->clock.position() - tickAtStop;

    // Every step on the arp's grid, none skipped or doubled, and every note
    // that started has stopped or is the last one still sounding.
    std::vector<int64_t> ons;
    for (const Sink::Ev &ev : sink.events) {
        if ((ev.status & 0xf0) == 0x90) ons.push_back(ev.tick), ++r.arpOns;
        if ((ev.status & 0xf0) == 0x80) ++r.arpOffs;
    }
    if (ons.size() > 2) r.arpStep = ons[1] - ons[0];
    for (size_t i = 1; i < ons.size(); ++i) {
        // A step is emitted in the block holding its tick, so its spacing in
        // block-start ticks is the step, give or take the one tick a block spans.
        const int64_t d = ons[i] - ons[i - 1];
        if (std::llabs(d - r.arpStep) > 1) ++r.arpSpacingFaults;
    }
    return r;
}

void report(const char *name, double hostBpm, double offsetBeats, double seconds, bool release) {
    std::printf("--- host at %.2f bpm, %.2f beats ahead (%s) ---\n", hostBpm, offsetBeats, name);
    const Run r = follow(hostBpm, offsetBeats, seconds, release);
    char d[128];
    std::snprintf(d, sizeof d, "%.3f ticks, worst over the last 10 s %.3f", r.finalErrTicks, r.worstLateErrTicks);
    ok("our bar line meets the host's", std::fabs(r.worstLateErrTicks) <= 1.5, d);
    std::snprintf(d, sizeof d, "%lld gaps", static_cast<long long>(r.gaps));
    ok("no tick jumps: each block starts where the last ended", r.gaps == 0 && !r.tickWentBack, d);
    std::snprintf(d, sizeof d, "at most %lld", static_cast<long long>(r.worstTicksPerBlock));
    ok("no block covers more than one tick", r.worstTicksPerBlock <= 1, d);
    std::snprintf(d, sizeof d, "%lld on, %lld off, step %lld, %lld off the grid", static_cast<long long>(r.arpOns),
                  static_cast<long long>(r.arpOffs), static_cast<long long>(r.arpStep),
                  static_cast<long long>(r.arpSpacingFaults));
    ok("arp keeps every step and note-off", r.arpOns > 100 && r.arpSpacingFaults == 0 &&
                                                r.arpOffs >= r.arpOns - 1 && r.arpOffs <= r.arpOns, d);
    std::snprintf(d, sizeof d, "%.3f bpm, source %d", r.bpmAfterStop, r.sourceAfterStop);
    ok(release ? "released: the song's tempo again" : "calls stop: the song's tempo again",
       std::fabs(r.bpmAfterStop - 120.0f) < 0.01f && r.sourceAfterStop == Transport::SyncOff, d);
    // Two seconds at the song's tempo, in ticks.
    const double want = 2.0 * 120.0 / 60.0 * kPPQN;
    std::snprintf(d, sizeof d, "%lld ticks, wanted %.0f", static_cast<long long>(r.ticksAfterStop), want);
    ok("and keeps playing", std::fabs(static_cast<double>(r.ticksAfterStop) - want) <= 3.0, d);
}

void captureTests() {
    std::printf("--- HostTimebase on its own ---\n");
    HostTimebase tb;
    tb.setRate(48000);
    tb.setQuantum(4.0);
    ok("before any call: not valid", !tb.capture(0).valid);
    ok("a tempo of nought is refused", !tb.set(1.0, 0.0, 0));
    ok("a beat that isn't a number is refused", !tb.set(NAN, 120.0, 0));
    tb.set(10.0, 120.0, 48000);
    Timebase::State s = tb.capture(72000); // half a second later: a beat on
    char d[96];
    std::snprintf(d, sizeof d, "%.6f", s.beat);
    ok("carried forward at the host's tempo", s.valid && std::fabs(s.beat - 11.0) < 1e-9, d);
    s = tb.capture(48000 + 48001);
    ok("over a second with no call: gone, last tempo kept", !s.valid && s.bpm == 120.0);
    HostTimebase fresh;
    ok("no beat yet: hasBeat is false, so the engine leaves the tempo", !fresh.hasBeat());
}

/**
 * Play pressed while the host is calling and we're stopped: it waits for the
 * host's next bar line and starts on it, and is in phase from the first beat.
 */
void startTest(double hostBpm, double beatAtPlay) {
    std::printf("--- play while stopped, host at %.2f, beat %.2f ---\n", hostBpm, beatAtPlay);
    std::shared_ptr<SongSnapshot> snap;
    auto engine = makeEngine(snap);
    float scratch[kBlockFrames * 2];
    const int64_t callEvery = static_cast<int64_t>(kRate * 0.2) / kBlockFrames;
    const int64_t playAt = static_cast<int64_t>(kRate * 1.0) / kBlockFrames;
    const int64_t f0 = engine->framesOut.load() + playAt * kBlockFrames;
    auto hostBeat = [&](int64_t frame) {
        return beatAtPlay + static_cast<double>(frame - f0) * hostBpm / (60.0 * kRate);
    };
    int64_t startFrame = -1;
    double errAfter = 1e9;
    for (int64_t b = 0; b < playAt + static_cast<int64_t>(kRate * 4.0) / kBlockFrames; ++b) {
        if (b % callEvery == 0) {
            const int64_t at = engine->framesOut.load();
            alignBeat(*engine, hostBeat(at), hostBpm, at);
        }
        if (b == playAt) engine->transport.requestPlay(0);
        const int64_t before = engine->framesOut.load();
        engine->renderBlock(nullptr, scratch);
        // The playing time starts counting in the block the song starts.
        if (startFrame < 0 && b >= playAt && engine->transport.elapsedMs() > 0) {
            startFrame = before - (engine->transport.elapsedMs() * kSampleRate / 1000 / kBlockFrames) * kBlockFrames;
        }
        // A quarter of a second after the start.
        if (startFrame >= 0 && engine->framesOut.load() - startFrame == (static_cast<int64_t>(kRate / 4) / kBlockFrames) * kBlockFrames)
            errAfter = followerErrorMs(*engine);
    }
    const double startBeat = hostBeat(startFrame);
    double phase = std::fmod(startBeat, 4.0);
    if (phase > 2.0) phase -= 4.0;
    char d[128];
    std::snprintf(d, sizeof d, "host beat %.4f at the start, %.4f beats off its bar line", startBeat, phase);
    // Within a block and a tick: the start is seen a block late and a tick in.
    ok("play waits for the host's bar line", startFrame >= f0 && std::fabs(phase) < 0.02, d);
    std::snprintf(d, sizeof d, "%.3f ms", errAfter);
    ok("in phase a quarter second in", std::fabs(errAfter) < 2.0, d);
}
/**
 * Stopped, never played, the host calling: the free-running tick jumps
 * forward onto the host's bar phase once and is held there, so a free 1/4 arp
 * lands on the host's beats.
 */
void stoppedTest(double hostBpm, double offsetBeats) {
    std::printf("--- stopped, host at %.2f bpm, %.2f beats ahead ---\n", hostBpm, offsetBeats);
    std::shared_ptr<SongSnapshot> snap;
    auto engine = makeEngine(snap);
    float scratch[kBlockFrames * 2];
    engine->renderBlock(nullptr, scratch);

    std::unique_ptr<InputMod> arp(InputModRegistry::create("Arp"));
    arp->params().jump(modifier::Arp::Sync, 1.0f);
    arp->params().jump(modifier::Arp::Rate, 3.0f / 9.0f); // 1/4, normalised
    arp->params().jumpAll();
    Sink sink;
    arp->handleMidi(0x90, 60, 100, sink);

    // A second with no host, so the arp is running when the tick jumps.
    const int64_t hostFrom = static_cast<int64_t>(kRate * 1.0) / kBlockFrames;
    const int64_t f0 = engine->framesOut.load() + hostFrom * kBlockFrames;
    const double ourBeat0 = static_cast<double>(hostFrom * kBlockFrames) * 120.0 / (60.0 * kRate);
    // [offsetBeats] ahead of us, so the jump is longer than an arp step.
    auto hostBeat = [&](int64_t frame) {
        return ourBeat0 + offsetBeats + static_cast<double>(frame - f0) * hostBpm / (60.0 * kRate);
    };
    const int64_t callEvery = static_cast<int64_t>(kRate * 0.2) / kBlockFrames;
    const int64_t settle = hostFrom + static_cast<int64_t>(kRate * 1.0) / kBlockFrames;
    const int64_t blocks = hostFrom + static_cast<int64_t>(kRate * 12.0) / kBlockFrames;
    int64_t lastEnd = engine->clock.position(), jumps = 0, widest = 0, onsBefore = 0, mostOns = 0;
    bool back = false;
    double worstErr = 0.0, worstArp = 0.0;
    int64_t ons = 0;
    for (int64_t b = 0; b < blocks; ++b) {
        if (b >= hostFrom && (b - hostFrom) % callEvery == 0) {
            const int64_t at = engine->framesOut.load();
            alignBeat(*engine, hostBeat(at), hostBpm, at);
        }
        const int64_t before = engine->framesOut.load();
        engine->renderBlock(nullptr, scratch);
        const int64_t s = engine->clock.blockStart(), e = engine->clock.blockEnd();
        if (s < lastEnd || e < s) back = true;
        if (s != lastEnd) ++jumps;
        widest = std::max(widest, e - s);
        lastEnd = e;
        sink.tick = s;
        const size_t had = sink.events.size();
        arp->run(s, e, engine->clock.bpm(), sink);
        int64_t onsHere = 0;
        for (size_t i = had; i < sink.events.size(); ++i) onsHere += (sink.events[i].status & 0xf0) == 0x90;
        mostOns = std::max(mostOns, onsHere);
        if (b < hostFrom) onsBefore += onsHere;
        if (b < settle) continue;
        // Our phase at the end of the block against the host's there.
        const double theirs = std::fmod(hostBeat(engine->framesOut.load()) * kPPQN, kBar);
        double err = std::fmod(engine->clock.phase(), kBar) - theirs;
        if (err > kBar / 2) err -= kBar;
        if (err < -kBar / 2) err += kBar;
        worstErr = std::max(worstErr, std::fabs(err));
        // A note-on comes out in the block holding its step, so the host's
        // phase at that block's start is up to a tick short of a beat.
        for (size_t i = had; i < sink.events.size(); ++i) {
            if ((sink.events[i].status & 0xf0) != 0x90) continue;
            ++ons;
            double off = std::fmod(hostBeat(before) * kPPQN, static_cast<double>(kPPQN));
            if (off > kPPQN / 2) off -= kPPQN;
            worstArp = std::max(worstArp, std::fabs(off));
        }
    }
    char d[128];
    std::snprintf(d, sizeof d, "worst %.3f ticks after the first second", worstErr);
    ok("the free tick sits on the host's phase", worstErr <= 1.0, d);
    std::snprintf(d, sizeof d, "%lld jumps, widest block %lld ticks", static_cast<long long>(jumps),
                  static_cast<long long>(widest));
    ok("one jump forward, never back, no wide block", jumps == 1 && !back && widest <= 1, d);
    std::snprintf(d, sizeof d, "%lld notes before the host, at most %lld in a block", static_cast<long long>(onsBefore),
                  static_cast<long long>(mostOns));
    ok("the jump lands mid-arp and plays no skipped steps", onsBefore >= 2 && mostOns == 1, d);
    std::snprintf(d, sizeof d, "%lld notes, worst %.3f ticks off a host beat", static_cast<long long>(ons), worstArp);
    ok("a free 1/4 arp lands on the host's beats", ons >= 10 && worstArp <= 1.5, d);
}

/**
 * An export while the host has the tempo renders exactly what it would with no
 * host: the song's tempo, play at once, no pull. Two engines with the same live
 * history, one following a host, then the steps EngineHost::renderTargets takes.
 */
std::vector<float> exportAfter(bool followHost, int32_t &sourceAfter, float &bpmAfter) {
    std::shared_ptr<SongSnapshot> snap;
    auto engine = makeEngine(snap);
    engine->racks[0].swapMachine(MachineRegistry::create("Reflux"));
    if (Machine *m = engine->racks[0].currentMachine()) {
        m->prepare(kSampleRate);
        m->reset();
        m->params().jumpAll();
    }
    auto clip = std::make_shared<Clip>();
    clip->rev = 1;
    clip->bars = 2;
    clip->ticksPerBar = static_cast<int32_t>(kBar);
    for (int32_t i = 0; i < 16; ++i) {
        ClipNote n{};
        n.tick = i * (kBar / 8);
        n.length = kBar / 16;
        n.pitch = static_cast<uint8_t>(36 + (i % 5));
        n.velocity = static_cast<uint8_t>(70 + (i % 4) * 15);
        clip->notes.push_back(n);
    }
    snap->setClip(0, 0, clip);

    float scratch[kBlockFrames * 2];
    engine->renderBlock(nullptr, scratch);
    engine->transport.requestPlay(0);
    const int64_t live = static_cast<int64_t>(kRate * 3.0) / kBlockFrames;
    for (int64_t b = 0; b < live; ++b) {
        if (followHost && b % 150 == 0) {
            const int64_t at = engine->framesOut.load();
            alignBeat(*engine, 1.37 + static_cast<double>(at) * 127.0 / (60.0 * kRate), 127.0, at);
        }
        engine->renderBlock(nullptr, scratch);
    }

    EngineSettings::get().offlineRender.store(true);
    engine->transport.requestStop();
    engine->renderBlock(nullptr, scratch);
    engine->panicFlag.store(true, std::memory_order_release);
    engine->renderBlock(nullptr, scratch);
    engine->transport.requestPlay(0);
    // Ten seconds, so a render at the wrong tempo or a late start can't hide.
    const int64_t blocks = static_cast<int64_t>(kRate * 10.0) / kBlockFrames;
    std::vector<float> out(static_cast<size_t>(blocks) * kBlockFrames * 2);
    for (int64_t b = 0; b < blocks; ++b) engine->renderBlock(nullptr, out.data() + b * kBlockFrames * 2);
    engine->transport.requestStop();
    engine->renderBlock(nullptr, scratch);
    EngineSettings::get().offlineRender.store(false);
    // Back to live: a few blocks with no new beat yet.
    for (int32_t b = 0; b < 20; ++b) engine->renderBlock(nullptr, scratch);
    sourceAfter = engine->transport.syncSource();
    bpmAfter = engine->clock.bpm();
    delete engine->racks[0].swapMachine(nullptr);
    return out;
}

void exportTest() {
    std::printf("--- an export while following the host ---\n");
    int32_t srcHost = -1, srcNone = -1;
    float bpmHost = 0.0f, bpmNone = 0.0f;
    const std::vector<float> a = exportAfter(true, srcHost, bpmHost);
    const std::vector<float> b = exportAfter(false, srcNone, bpmNone);
    size_t differ = 0;
    float peak = 0.0f;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i] != b[i]) ++differ;
        peak = std::max(peak, std::fabs(b[i]));
    }
    char d[128];
    std::snprintf(d, sizeof d, "%zu of %zu samples differ, peak %.3f", differ, a.size(), peak);
    ok("bit-identical to the same export with no host", differ == 0 && peak > 0.01f, d);
    std::snprintf(d, sizeof d, "source %d, %.3f bpm", srcHost, bpmHost);
    ok("the host still has the tempo after, at its own", srcHost == Transport::SyncHost &&
                                                             std::fabs(bpmHost - 127.0f) < 0.01f, d);
}
} // namespace

int main() {
    std::printf("hostbeat_test\n");
    captureTests();
    report("a fraction of a beat", 121.0, 0.3, 40.0, false);
    report("behind, then released", 119.5, -0.45, 40.0, true);
    // Close to half a bar: the pull is capped, so this takes about a minute.
    report("most of a bar out", 120.5, 1.9, 120.0, false);
    startTest(121.0, 2.37);
    startTest(96.0, 7.9);
    stoppedTest(90.0, 1.37);
    stoppedTest(140.0, 2.81);
    exportTest();
    std::printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
