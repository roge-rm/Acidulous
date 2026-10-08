// Tests the input modifiers.
//
// Modifiers act once, as a note comes in, and the clip stores what they
// produce. So a live note should come out modified, and a note played from a
// clip shouldn't go through them at all.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/inputmod/InputModRegistry.h>
#include <engine/machine/MachineRegistry.h>
#include <engine/rack/Rack.h>

using namespace acidulous;

namespace {
int checks = 0, failures = 0;

void ok(const char *what, bool cond, const std::string &detail = "") {
    ++checks;
    printf("  %s %-50s %s\n", cond ? "ok  " : "FAIL", what, detail.c_str());
    if (!cond) ++failures;
}

/** Everything that reaches the end of a rack's chain, and how it got there. */
struct Heard : Rack::ModifiedNoteSink {
    struct Note { uint8_t status, pitch, velocity; };
    std::vector<Note> live;
    void onModifiedNote(int32_t, uint8_t status, uint8_t d1, uint8_t d2) override {
        live.push_back({status, d1, d2});
    }
    size_t onsLive() const {
        size_t n = 0;
        for (const Note &x : live) if ((x.status & 0xf0) == 0x90 && x.velocity > 0) ++n;
        return n;
    }
    std::vector<uint8_t> pitchesLive() const {
        std::vector<uint8_t> out;
        for (const Note &x : live) if ((x.status & 0xf0) == 0x90 && x.velocity > 0) out.push_back(x.pitch);
        return out;
    }
};

/** A rack with one modifier in slot 0, set up and ready to be played. */
struct Fixture {
    Rack rack;
    Heard heard;
    InputMod *mod = nullptr;
    explicit Fixture(const char *type) {
        rack.setModifiedNoteSink(&heard);
        if (type != nullptr) {
            mod = InputModRegistry::create(type);
            rack.swapInputMod(0, mod);
        }
    }
    ~Fixture() { delete mod; }
    void set(const char *name, float v01) {
        if (mod == nullptr) return;
        const int32_t i = mod->params().indexOf(name);
        if (i >= 0) mod->params().set(i, v01);
        mod->params().jumpAll();
    }
};

void aPlainNotePassesThrough() {
    printf("- nothing enabled\n");
    Fixture f(nullptr);
    f.rack.handleMidi(0x90, 60, 100);
    ok("one key played is one note out", f.heard.onsLive() == 1,
       std::string("got ") + std::to_string(f.heard.onsLive()));
    ok("and it is the note that was played",
       !f.heard.pitchesLive().empty() && f.heard.pitchesLive()[0] == 60);
}

void aChordIsThreeNotes() {
    printf("- the chord modifier\n");
    Fixture f("Chord");
    // A plain major triad, fixed instead of diatonic, and no strum so all
    // three land at the same time.
    f.set("mode", 0.0f);
    f.set("type", 0.0f);
    f.set("strum", 0.0f);
    f.rack.handleMidi(0x90, 60, 100);
    const auto pitches = f.heard.pitchesLive();
    ok("one key played is three notes out", pitches.size() == 3,
       std::string("got ") + std::to_string(pitches.size()));
    if (pitches.size() == 3) {
        ok("and they are a major triad on the key played",
           pitches[0] == 60 && pitches[1] == 64 && pitches[2] == 67,
           std::to_string(pitches[0]) + " " + std::to_string(pitches[1]) + " " + std::to_string(pitches[2]));
    } else {
        ok("and they are a major triad on the key played", false, "wrong count");
    }
}

void aClipGoesStraightToTheMachine() {
    printf("- what a clip does now\n");
    Fixture f("Chord");
    f.set("mode", 0.0f);
    f.set("type", 0.0f);
    f.set("strum", 0.0f);
    // The same note, coming from a clip instead of a key.
    f.rack.playSequenced(0x90, 60, 100);
    ok("a clip's note does not go through the modifiers", f.heard.onsLive() == 0,
       std::string("the sink saw ") + std::to_string(f.heard.onsLive()));

    // Check the fixture isn't just deaf: the same rack still modifies a live
    // note afterwards.
    f.rack.handleMidi(0x90, 62, 100);
    ok("while a live note on the same rack still is", f.heard.onsLive() == 3,
       std::string("got ") + std::to_string(f.heard.onsLive()));
}

void theScaleModifierCorrectsOnTheWayIn() {
    printf("- the scale modifier\n");
    Fixture f("Scale");
    f.set("key", 0.0f);     // C
    f.set("scale", 0.0f);   // Ionian
    f.set("mode", 0.0f);    // snap
    f.rack.handleMidi(0x90, 61, 100); // C sharp, which isn't in C major
    const auto pitches = f.heard.pitchesLive();
    ok("a note outside the scale is moved into it", pitches.size() == 1 && pitches[0] != 61,
       pitches.empty() ? "nothing" : std::string("came out as ") + std::to_string(pitches[0]));
    // The stored note is the corrected one, so the roll and the sound agree.
    ok("and what is written down is the corrected note",
       !pitches.empty() && (pitches[0] == 60 || pitches[0] == 62));
}

void aBypassedModifierDoesNothing() {
    printf("- switched off\n");
    Fixture f("Chord");
    f.set("mode", 0.0f);
    f.set("type", 0.0f);
    struct Null : MidiSink { void send(uint8_t, uint8_t, uint8_t) override {} } null;
    f.mod->setBypass(true, null);
    f.rack.handleMidi(0x90, 60, 100);
    ok("a bypassed modifier passes the key straight through", f.heard.onsLive() == 1,
       std::string("got ") + std::to_string(f.heard.onsLive()));
}

} // namespace

/** Strum keys: keys below the split pick a chord, keys above play its notes up the octaves. */
void strumKeysPlayTheChordsNotes() {
    printf("- strum keys\n");
    auto offs = [](const Heard &h) {
        size_t n = 0;
        for (const auto &x : h.live) if ((x.status & 0xf0) == 0x80 || ((x.status & 0xf0) == 0x90 && x.velocity == 0)) ++n;
        return n;
    };
    {
        Fixture f("Chord");
        f.set("type", 0.0f);
        f.set("play", 0.5f);
        f.set("split", (48.0f - 24.0f) / 72.0f);
        f.set("ring", 0.0f);
        f.rack.handleMidi(0x90, 36, 100); // C below the split: the chord
        ok("a chord key makes no sound", f.heard.onsLive() == 0);
        for (int k = 48; k < 52; ++k) f.rack.handleMidi(0x90, static_cast<uint8_t>(k), 100);
        const auto p = f.heard.pitchesLive();
        ok("four strum keys play C E G C", p == std::vector<uint8_t>{48, 52, 55, 60},
           p.size() == 4 ? std::to_string(p[0]) + " " + std::to_string(p[1]) + " " + std::to_string(p[2]) + " " + std::to_string(p[3]) : "wrong count");
        f.rack.handleMidi(0x80, 48, 0);
        ok("without ring, letting a strum key go stops its note", offs(f.heard) == 1);
        f.rack.handleMidi(0x90, 38, 100); // D: a new chord
        f.rack.handleMidi(0x90, 48, 100);
        ok("a new chord moves the ladder (D major from D3)", f.heard.pitchesLive().back() == 50);
    }
    {
        Fixture f("Chord");
        f.set("type", 0.0f);
        f.set("play", 0.5f);
        f.set("split", (48.0f - 24.0f) / 72.0f);
        f.set("keys", 1.0f);
        f.rack.handleMidi(0x90, 36, 100);
        f.rack.handleMidi(0x90, 49, 100);
        ok("white keys only: a black key plays nothing", f.heard.onsLive() == 0);
        for (int k : {48, 50, 52, 53}) f.rack.handleMidi(0x90, static_cast<uint8_t>(k), 100);
        ok("and the white keys play C E G C", f.heard.pitchesLive() == std::vector<uint8_t>{48, 52, 55, 60});
    }
    {
        Fixture f("Chord");
        f.set("type", 0.0f);
        f.set("play", 0.5f);
        f.set("split", (48.0f - 24.0f) / 72.0f);
        f.set("ring", 1.0f);
        f.rack.handleMidi(0x90, 36, 100);
        // A finger sliding across: the next key down, then the last one up.
        f.rack.handleMidi(0x90, 48, 100);
        f.rack.handleMidi(0x90, 49, 100);
        f.rack.handleMidi(0x80, 48, 0);
        ok("with ring, a swept note keeps sounding while a strum key is down", offs(f.heard) == 0);
        f.rack.handleMidi(0x80, 49, 0);
        ok("and they stop together when the last one's let go", offs(f.heard) == 2,
           std::to_string(offs(f.heard)) + " offs");
    }
    {
        Fixture f("Chord");
        f.set("type", 0.0f);
        f.set("play", 0.5f);
        f.set("split", (48.0f - 24.0f) / 72.0f);
        f.rack.handleMidi(0x90, 36, 100);
        f.rack.handleMidi(0x80, 36, 0);
        f.rack.handleMidi(0x90, 49, 100);
        ok("without latch, once the chord key's up a strum key plays itself", f.heard.pitchesLive().back() == 49);
        f.rack.handleMidi(0x80, 49, 0);
        f.set("latch", 1.0f);
        f.rack.handleMidi(0x90, 36, 100);
        f.rack.handleMidi(0x80, 36, 0);
        f.rack.handleMidi(0x90, 49, 100);
        ok("with latch, the chord stays after its key is let go", f.heard.pitchesLive().back() == 52);
        f.rack.allNotesOff();
        f.heard.live.clear();
        f.rack.handleMidi(0x90, 49, 100);
        ok("panic forgets the latched chord", f.heard.pitchesLive().back() == 49);
    }
}

/** Guitar shapes: the chord laid out on six strings in standard tuning. */
void guitarShapesAreRealShapes() {
    printf("- guitar shapes\n");
    auto shape = [](int type, int note) {
        Fixture f("Chord");
        f.set("type", static_cast<float>(type) / 24.0f);
        f.set("shape", 1.0f);
        f.rack.handleMidi(0x90, static_cast<uint8_t>(note), 100);
        auto p = f.heard.pitchesLive();
        std::sort(p.begin(), p.end());
        return p;
    };
    auto str = [](const std::vector<uint8_t> &p) { std::string s; for (auto x : p) s += std::to_string(x) + " "; return s; };
    auto c = shape(0, 60), g = shape(0, 67), am = shape(1, 69), e7 = shape(9, 64);
    ok("C is x32010", c == std::vector<uint8_t>{48, 52, 55, 60, 64}, str(c));
    ok("G is 320003", g == std::vector<uint8_t>{43, 47, 50, 55, 59, 67}, str(g));
    ok("Am is x02210", am == std::vector<uint8_t>{45, 52, 57, 60, 64}, str(am));
    ok("E7 is 020100", e7 == std::vector<uint8_t>{40, 47, 50, 56, 59, 64}, str(e7));
    auto f = shape(0, 65);
    ok("F has its root in the bass and four strings or more", f.size() >= 4 && f[0] % 12 == 5, str(f));
    auto c5 = shape(0, 72);
    ok("an octave up, the same shape an octave up", c5.size() == 5 && c5[0] == 60, str(c5));
}

/** What the on-screen keys send for a held chord key and a finger dragged across: every note must end. */
void aDraggedStrumEnds() {
    printf("- a dragged strum\n");
    Fixture f("Chord");
    f.set("type", 0.0f);
    f.set("play", 0.5f);
    f.set("split", (48.0f - 24.0f) / 72.0f);
    f.rack.handleMidi(0x90, 36, 100);
    f.rack.handleMidi(0x90, 48, 100);
    for (int k = 49; k < 60; ++k) {
        f.rack.handleMidi(0x80, static_cast<uint8_t>(k - 1), 0);
        f.rack.handleMidi(0x90, static_cast<uint8_t>(k), 100);
    }
    f.rack.handleMidi(0x80, 59, 0);
    f.rack.handleMidi(0x80, 36, 0);
    int balance[128] = {};
    for (const auto &x : f.heard.live) {
        const uint8_t k = x.status & 0xf0;
        if (k == 0x90 && x.velocity > 0) ++balance[x.pitch];
        else if (k == 0x80 || k == 0x90) --balance[x.pitch];
    }
    int stuck = 0, events = static_cast<int>(f.heard.live.size());
    for (int b : balance) if (b != 0) ++stuck;
    ok("every strummed note ends", stuck == 0, std::to_string(stuck) + " stuck, " + std::to_string(events) + " events");
}

/** Split: chords below the split, single notes from it up. */
void splitPlaysChordsAndMelody() {
    printf("- split\n");
    Fixture f("Chord");
    f.set("type", 0.0f);
    f.set("play", 1.0f); // the third choice of three
    f.set("split", (60.0f - 24.0f) / 72.0f);
    f.rack.handleMidi(0x90, 48, 100);
    ok("a key below the split is a chord", f.heard.onsLive() == 3);
    f.rack.handleMidi(0x90, 67, 100);
    ok("a key above it is one note, itself", f.heard.onsLive() == 4 && f.heard.pitchesLive().back() == 67);
}

/** Two chords sharing a note, through the scale after them: every note must still end. */
void aSharedNoteThroughTwoModifiersEnds() {
    printf("- a shared note through two modifiers\n");
    Fixture f("Chord");
    f.set("type", 0.0f);
    InputMod *scale = InputModRegistry::create("Scale");
    f.rack.swapInputMod(1, scale);
    f.rack.handleMidi(0x90, 60, 100); // C: 60 64 67
    f.rack.handleMidi(0x90, 64, 100); // E: 64 68 71, sharing the E
    f.rack.handleMidi(0x80, 60, 0);
    f.rack.handleMidi(0x80, 64, 0);
    int balance[128] = {};
    for (const auto &x : f.heard.live) {
        const uint8_t k = x.status & 0xf0;
        if (k == 0x90 && x.velocity > 0) ++balance[x.pitch];
        else if (k == 0x80 || k == 0x90) --balance[x.pitch];
    }
    int stuck = 0;
    for (int b : balance) if (b != 0) ++stuck;
    ok("no note is left sounding", stuck == 0, std::to_string(stuck) + " stuck");
    f.rack.swapInputMod(1, nullptr);
    delete scale;
}

/** Strum keys high up still play: the ladder climbs three octaves and starts again. */
void highStrumKeysStillPlay() {
    printf("- high strum keys\n");
    Fixture f("Chord");
    f.set("type", 0.0f);
    f.set("play", 0.5f);
    f.set("split", (60.0f - 24.0f) / 72.0f);
    f.rack.handleMidi(0x90, 59, 100); // B, below the split
    int sounded = 0;
    for (int k = 60; k < 96; ++k) {
        const size_t before = f.heard.onsLive();
        f.rack.handleMidi(0x90, static_cast<uint8_t>(k), 100);
        if (f.heard.onsLive() > before) ++sounded;
        f.rack.handleMidi(0x80, static_cast<uint8_t>(k), 0);
    }
    ok("every key from C4 to B6 plays a note", sounded == 36, std::to_string(sounded) + " of 36");
    const auto p = f.heard.pitchesLive();
    ok("and none above three octaves over the split", !p.empty() && *std::max_element(p.begin(), p.end()) < 60 + 3 * 12 + 12);
}

/** Dan's Fret track: strum keys and the scale, a finger run up and down the keys, then let go. It must go quiet. */
void fretTrackGoesQuiet() {
    printf("- Dan's Fret track\n");
    Fixture f("Chord");
    f.set("play", 0.5f);
    f.set("strum", 0.33374512f);
    f.set("velspread", 0.31030273f);
    InputMod *scale = InputModRegistry::create("Scale");
    f.rack.swapInputMod(1, scale);
    Machine *fret = MachineRegistry::create("Fret");
    fret->prepare(48000);
    fret->reset();
    // Dan's: both pickups, bright up a little.
    fret->params().set(fret->params().indexOf("pickup"), 0.5f);
    fret->params().set(fret->params().indexOf("bright"), 0.55f);
    fret->params().jumpAll();
    Machine *old = f.rack.swapMachine(fret);
    int64_t tick = 0;
    auto blocks = [&](int n, double *sumSq) {
        for (int b = 0; b < n; ++b) {
            f.rack.onBlock(tick, tick + 5, 120.0f);
            tick += 5;
            f.rack.render(kBlockFrames);
            if (sumSq) for (int i = 0; i < kBlockFrames; ++i) *sumSq += double(f.rack.bufL[i]) * f.rack.bufL[i];
        }
    };
    int prev = -1;
    const int path[] = {60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 79, 77, 76, 74, 72, 71, 69, 67, 65, 64, 62};
    for (int round = 0; round < 12; ++round) {
        for (int n : path) {
            if (prev >= 0) f.rack.handleMidi(0x80, static_cast<uint8_t>(prev), 0);
            f.rack.handleMidi(0x90, static_cast<uint8_t>(n), static_cast<uint8_t>(60 + (n * 7) % 67));
            prev = n;
            blocks(10, nullptr);
        }
    }
    f.rack.handleMidi(0x80, static_cast<uint8_t>(prev), 0);
    blocks(48000 * 6 / kBlockFrames, nullptr);
    double sum = 0.0;
    const int last = 48000 / kBlockFrames;
    blocks(last, &sum);
    const double rmsDb = 10.0 * std::log10(sum / (last * kBlockFrames) + 1e-20);
    ok("six seconds after letting go it's quiet", rmsDb < -70.0, std::to_string(rmsDb) + " dB");
    f.rack.swapMachine(old);
    delete fret;
    f.rack.swapInputMod(1, nullptr);
    delete scale;
}

/** Strum patterns: a held chord key strums on the pattern's sixteenths, and stops when let go. */
void strumPatternsPlayInTime() {
    printf("- strum patterns\n");
    Fixture f("Chord");
    f.set("type", 0.0f);
    f.set("rhythm", 2.0f / 10.0f); // down eighths
    f.set("humanise", 0.0f);
    f.set("strum", 0.0f);
    int64_t tick = 0;
    f.rack.onBlock(0, 5, 120.0f);
    f.rack.handleMidi(0x90, 60, 100);
    for (tick = 5; tick < 960; tick += 5) f.rack.onBlock(tick, tick + 5, 120.0f);
    ok("a bar of down eighths is eight strokes of three notes", f.heard.onsLive() == 24, std::to_string(f.heard.onsLive()) + " ons");
    f.rack.handleMidi(0x80, 60, 0);
    for (int i = 0; i < 40; ++i, tick += 5) f.rack.onBlock(tick, tick + 5, 120.0f);
    int balance[128] = {};
    for (const auto &x : f.heard.live) {
        const uint8_t k = x.status & 0xf0;
        if (k == 0x90 && x.velocity > 0) ++balance[x.pitch];
        else if (k == 0x80 || k == 0x90) --balance[x.pitch];
    }
    int stuck = 0;
    for (int b : balance) if (b != 0) ++stuck;
    const size_t before = f.heard.onsLive();
    ok("let go, every note ends and no more strokes come", stuck == 0 && before == 24, std::to_string(stuck) + " stuck");
}

int main() {
    printf("input modifiers\n");
    aPlainNotePassesThrough();
    aChordIsThreeNotes();
    aClipGoesStraightToTheMachine();
    theScaleModifierCorrectsOnTheWayIn();
    aBypassedModifierDoesNothing();
    strumKeysPlayTheChordsNotes();
    guitarShapesAreRealShapes();
    aDraggedStrumEnds();
    splitPlaysChordsAndMelody();
    aSharedNoteThroughTwoModifiersEnds();
    highStrumKeysStillPlay();
    fretTrackGoesQuiet();
    strumPatternsPlayInTime();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
