// How a take starts and ends, through the whole engine: waiting for the first
// note (Transport::startsOnNote) and stopping after one pass
// (Transport::recordsOnce).
#include <engine/core/Constants.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Engine.h>
#include <sequencer/Song.h>

#include <cstdio>
#include <memory>
#include <vector>

using namespace acidulous;
using namespace acidulous::seq;

namespace {

int failures = 0, checks = 0;

void check(bool ok, const char *what) {
    ++checks;
    if (!ok) ++failures;
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what);
}

constexpr int32_t kBar = 4 * kPPQN;

/** One track of Trinity and one scene of two bars, stopped. */
struct Rig {
    std::unique_ptr<Engine> owned = std::make_unique<Engine>();
    Engine &e = *owned;
    std::shared_ptr<SongSnapshot> snap = std::make_shared<SongSnapshot>();
    std::shared_ptr<Clip> clip = std::make_shared<Clip>();
    float out[kBlockFrames * 2];

    Rig() {
        Machine *m = MachineRegistry::create("Trinity");
        m->prepare(kSampleRate);
        m->reset();
        m->params().jumpAll();
        delete e.racks[0].swapMachine(m);
        SceneInfo s;
        s.id = 1;
        s.bars = 2;
        s.repeat = 1;
        s.ticksPerBar = kBar;
        snap->scenes.push_back(s);
        snap->rackCount = 1;
        clip->rev = 1;
        clip->bars = 2;
        clip->ticksPerBar = kBar;
        snap->clips = {clip};
        e.scheduler.swapSnapshot(snap.get());
        e.panicFlag.store(true);
        block();
    }
    ~Rig() { delete e.racks[0].swapMachine(nullptr); }
    void block(int n = 1) { for (int i = 0; i < n; ++i) e.renderBlock(nullptr, out); }
    void note(uint8_t status, uint8_t pitch, uint8_t velocity) {
        MidiMessage m;
        m.status = status;
        m.data1 = pitch;
        m.data2 = velocity;
        e.pushMidi(m);
    }
    std::vector<RecordedEvent> recorded() {
        std::vector<RecordedEvent> all;
        RecordedEvent ev{};
        while (e.recordQueue.pop(ev)) all.push_back(ev);
        return all;
    }
    /** Blocks in [ticks] at the engine's tempo, rounded up. */
    int blocksFor(int64_t ticks) {
        const double perBlock = kBlockFrames / e.clock.samplesPerTickNow();
        return static_cast<int>(static_cast<double>(ticks) / perBlock) + 1;
    }
};

void theFirstNoteStartsTheSong() {
    std::printf("- start on the first note\n");
    Rig r;
    r.e.transport.setCountInBars(2);
    r.e.transport.setRecordModes(true, false);
    r.e.transport.setRecordArmed(true);
    r.block(20);
    check(!r.e.transport.isPlaying(), "armed and waiting, it doesn't start by itself");
    r.note(0x90, 60, 100);
    r.note(0x90, 64, 90);
    r.block(2);
    check(r.e.transport.isPlaying(), "a note starts it");
    check(r.e.transport.countInRemaining() == 0, "with no count-in, though one is set");
    r.block(30);
    r.note(0x80, 60, 0);
    r.note(0x80, 64, 0);
    r.block(2);
    const auto ev = r.recorded();
    int ons = 0, offs = 0;
    bool atStart = true;
    for (const RecordedEvent &x : ev) {
        if (x.cmd == 0x90 && x.p2 > 0) {
            ++ons;
            atStart = atStart && x.tickInIteration == 0;
        } else if (x.cmd == 0x80 || x.cmd == 0x90) {
            ++offs;
        }
    }
    check(ons == 2, "both notes of the chord are recorded");
    check(atStart, "at the start of the take");
    check(offs == 2, "and let go");
}

void playStartsItOtherwise() {
    std::printf("- start on play\n");
    Rig r;
    r.e.transport.setRecordModes(false, false);
    r.e.transport.setRecordArmed(true);
    r.note(0x90, 60, 100);
    r.block(4);
    check(!r.e.transport.isPlaying(), "a note doesn't start it");
    check(r.recorded().empty(), "and isn't recorded while stopped");
}

void oncePassStops() {
    std::printf("- one pass\n");
    Rig r;
    r.e.transport.setRecordModes(false, true);
    r.e.transport.setRecordArmed(true);
    r.e.transport.requestPlay(0);
    const int pass = r.blocksFor(2 * kBar);
    r.block(pass - 4);
    check(r.e.transport.isRecordArmed(), "still recording just before the pass ends");
    r.block(8);
    check(!r.e.transport.isRecordArmed(), "stopped after one pass");
    check(r.e.transport.isPlaying(), "and still playing");
    r.note(0x90, 62, 100);
    r.block(2);
    check(r.recorded().empty(), "a note after it isn't recorded");
}

void loopKeepsGoing() {
    std::printf("- loop\n");
    Rig r;
    r.e.transport.setRecordModes(false, false);
    r.e.transport.setRecordArmed(true);
    r.e.transport.requestPlay(0);
    r.block(r.blocksFor(5 * kBar));
    check(r.e.transport.isRecordArmed(), "still recording after two and a half passes");
}

} // namespace

int main() {
    theFirstNoteStartsTheSong();
    playStartsItOtherwise();
    oncePassStops();
    loopKeepsGoing();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
