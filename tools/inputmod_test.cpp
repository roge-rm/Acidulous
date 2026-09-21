// Modifiers: what they do to what is played, and what they no longer do to
// what is stored.
//
// Until M59 these sat in the *playback* path, so a clip kept the key somebody
// pressed and the chord was made again on every pass. The roll showed one note
// and three were heard. They now act once, on the way in, and what they make
// is what the clip keeps - so the two claims worth asserting are that a live
// note comes out modified, and that a clip's own note does not go through them
// at all.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include <engine/inputmod/InputModRegistry.h>
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
    // A plain major triad, fixed rather than diatonic, and no strum so all
    // three land on the same instant.
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
    // The same note, arriving from a clip rather than from a finger.
    f.rack.playSequenced(0x90, 60, 100);
    ok("a clip's note does not go through the modifiers", f.heard.onsLive() == 0,
       std::string("the sink saw ") + std::to_string(f.heard.onsLive()));

    // And to be sure the fixture is not simply deaf: the same rack still
    // modifies a live note afterwards.
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
    f.rack.handleMidi(0x90, 61, 100); // C sharp, which is not in C major
    const auto pitches = f.heard.pitchesLive();
    ok("a note outside the scale is moved into it", pitches.size() == 1 && pitches[0] != 61,
       pitches.empty() ? "nothing" : std::string("came out as ") + std::to_string(pitches[0]));
    // The point of doing it on the way in: what is written down is the
    // corrected note, so the roll and the sound agree.
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

int main() {
    printf("input modifiers\n");
    aPlainNotePassesThrough();
    aChordIsThreeNotes();
    aClipGoesStraightToTheMachine();
    theScaleModifierCorrectsOnTheWayIn();
    aBypassedModifierDoesNothing();
    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
