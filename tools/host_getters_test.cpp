// EngineHost's read-only getters after a song has been replayed through engine
// lines: mounted modifiers, sends and inserts, a send's parameters, song loop,
// swing unit and the audio each rack holds. The lines are written here, with a
// Forage pad's sample and a frozen clip beside them, and replayed through
// jni_bridge.cpp as engine_replay does.

#include <EngineHost.h>
#include <drivers/AudioDriver.h>
#include <jni.h>
#include <platform/lines/EngineLines.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

extern "C" {
jboolean Java_com_rm_acidulous_engine_EngineNative_nativeStart(JNIEnv *, jobject);
void Java_com_rm_acidulous_engine_EngineNative_nativeStop(JNIEnv *, jobject);
void Java_com_rm_acidulous_engine_EngineNative_nativePrewarm(JNIEnv *, jobject);
}

using acidulous::EngineHost;

namespace {
int failures = 0;

void check(bool ok, const std::string &what) {
    std::printf("  %s %s\n", ok ? "ok  " : "FAIL", what.c_str());
    if (!ok) ++failures;
}

void waitBlocks(int n) {
    const int64_t until = AudioDriver::blocks() + n;
    for (int i = 0; i < 2000 && AudioDriver::blocks() < until; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

/** A 16-bit mono WAV of [frames] frames at 48 kHz: a decaying tone. */
void writeWav(const std::string &path, int32_t frames) {
    std::ofstream f(path, std::ios::binary);
    auto u32 = [&](uint32_t v) { f.write(reinterpret_cast<const char *>(&v), 4); };
    auto u16 = [&](uint16_t v) { f.write(reinterpret_cast<const char *>(&v), 2); };
    f.write("RIFF", 4);
    u32(36 + static_cast<uint32_t>(frames) * 2);
    f.write("WAVEfmt ", 8);
    u32(16);
    u16(1);
    u16(1);
    u32(48000);
    u32(96000);
    u16(2);
    u16(16);
    f.write("data", 4);
    u32(static_cast<uint32_t>(frames) * 2);
    for (int32_t i = 0; i < frames; ++i) {
        const double v = std::sin(i * 0.03) * std::exp(-i / 4000.0);
        u16(static_cast<uint16_t>(static_cast<int16_t>(v * 20000)));
    }
}

/** Whether [got] is at least [want] and not much past it: vectors may hold a little spare. */
bool about(int64_t got, int64_t want) { return got >= want && got <= want + want / 8 + 4096; }

std::string text(const char *s) { return std::string("\"") + s + "\""; }
} // namespace

int main() {
    char tmpl[] = "/tmp/host_getters_XXXXXX";
    const char *dirp = mkdtemp(tmpl);
    if (dirp == nullptr) return 1;
    const std::string dir = dirp;
    std::system(("mkdir -p " + dir + "/samples " + dir + "/frozen").c_str());
    const int32_t hitFrames = 12000, frozenFrames = 96000;
    writeWav(dir + "/samples/hit.wav", hitFrames);
    writeWav(dir + "/frozen/bass.wav", frozenFrames);

    // A Forage pad with a sample on rack 0, with a chord modifier and a delay
    // insert; a Reflux on rack 1 frozen in scene 7; sends, master and group
    // inserts; no song loop, swing on eighths.
    const std::string lines = "acidulous-engine 4\n"
                              "MountMachine 0 \"Forage\"\n"
                              "LoadSample 0 0 \"zip:samples/hit.wav\" 0\n"
                              "MountInputMod 0 1 \"Chord\"\n"
                              "MountEffect 0 0 \"Delay\"\n"
                              "MountEffect 0 3 \"Chorus\"\n"
                              "SetParam 0 \"effect4\" \"bypass\" 1.0 false 0\n"
                              "MountMachine 1 \"Reflux\"\n"
                              "MountFrozen 1 7 \"zip:frozen/bass.wav\" 120.0 1920 96000 0 0.5\n"
                              "MountSend 0 \"Reverb\"\n"
                              "MountSend 1 \"Delay\"\n"
                              "SetParam 0 \"send1\" \"size\" 0.25 false 0\n"
                              "SetParam 0 \"send2\" \"bypass\" 1.0 false 0\n"
                              "MountMasterInsert 0 \"Compressor\"\n"
                              "SetParam 0 \"master\" \"volume\" 0.6 false 0\n"
                              "SetParam 0 \"master\" \"limiteron\" 0.0 false 0\n"
                              "SetParam 0 \"master\" \"limiterdrive\" 0.75 false 0\n"
                              "SetParam 0 \"master\" \"clickvolume\" 0.3 false 0\n"
                              "SetParam 0 \"master\" \"g2gain\" 0.4 false 0\n"
                              "SetParam 0 \"master1\" \"bypass\" 1.0 false 0\n"
                              "MountGroupInsert 2 1 \"Eq\"\n"
                              "SetLoopSong false\n"
                              "SetSwingUnit 1\n";

    static JNIEnv env;
    Java_com_rm_acidulous_engine_EngineNative_nativePrewarm(&env, nullptr);
    if (!Java_com_rm_acidulous_engine_EngineNative_nativeStart(&env, nullptr)) {
        std::fprintf(stderr, "host_getters_test: the engine didn't start\n");
        return 1;
    }
    EngineHost &h = EngineHost::instance();
    check(h.loopSong(), "the song loops before the lines");
    check(h.swingUnit() == 0, "swing is on sixteenths before the lines");

    acidulous::lines::Replay replay;
    replay.env = &env;
    replay.root = dir;
    const bool read = acidulous::lines::replay(lines, replay);
    waitBlocks(8);
    check(read && replay.skipped == 0 && replay.refused == 0,
          "the lines replay (" + std::to_string(replay.calls) + " calls, " + std::to_string(replay.skipped) +
              " skipped, " + std::to_string(replay.refused) + " refused)");

    check(std::strcmp(h.mountedModifier(0, 1), "Chord") == 0, "rack 0 modifier 2 is Chord");
    check(h.mountedModifier(0, 0)[0] == '\0' && h.mountedModifier(0, 2)[0] == '\0', "its other modifier slots are empty");
    check(h.mountedModifier(-1, 0)[0] == '\0' && h.mountedModifier(0, 9)[0] == '\0', "out of range is empty");
    check(std::strcmp(h.mountedSend(0), "Reverb") == 0 && std::strcmp(h.mountedSend(1), "Delay") == 0,
          "sends are Reverb and Delay");
    check(std::strcmp(h.mountedMasterInsert(0), "Compressor") == 0 && h.mountedMasterInsert(1)[0] == '\0',
          "master insert 1 is Compressor, 2 empty");
    check(std::strcmp(h.mountedGroupInsert(2, 1), "Eq") == 0 && h.mountedGroupInsert(2, 0)[0] == '\0' &&
              h.mountedGroupInsert(0, 1)[0] == '\0',
          "group 3 insert 2 is Eq, the rest empty");
    check(h.mountedGroupInsert(4, 0)[0] == '\0', "group out of range is empty");

    const float size = h.sendParamNormalized(0, "size");
    check(std::fabs(size - 0.25f) < 1e-4f, "send 1 size reads 0.25 (" + std::to_string(size) + ")");
    check(h.sendParamNormalized(1, "bypass") == 1.0f && h.sendParamNormalized(0, "bypass") == 0.0f,
          "send 2 is bypassed, send 1 isn't");
    check(h.sendParamNormalized(0, "nonsense") == -1.0f && h.sendParamNormalized(2, "size") == -1.0f,
          "an unknown name or slot is -1");

    const auto near = [&](float v, float want) { return std::fabs(v - want) < 1e-4f; };
    check(near(h.masterParamNormalized("volume"), 0.6f) && h.masterParamNormalized("limiteron") == 0.0f &&
              near(h.masterParamNormalized("limiterdrive"), 0.75f) && near(h.masterParamNormalized("clickvolume"), 0.3f) &&
              near(h.masterParamNormalized("g2gain"), 0.4f),
          "master volume, limiter, click and group gain read back what lines set");
    check(h.masterParamNormalized("clickon") == 0.0f && h.masterParamNormalized("nonsense") == -1.0f,
          "an untouched master param reads its default, an unknown name -1");
    check(h.masterInsertParamNormalized(0, "bypass") == 1.0f && h.masterInsertParamNormalized(1, "bypass") == -1.0f &&
              h.masterInsertParamNormalized(2, "bypass") == -1.0f,
          "master insert 1 is bypassed, an empty or unknown slot is -1");

    check(std::string(h.mountedEffect(0, 3)) == "Chorus" && h.mountedEffect(0, 2)[0] == '\0' &&
              h.paramNormalized(0, "effect4", "bypass") == 1.0f && h.paramNormalized(0, "effect3", "bypass") == -1.0f,
          "a track's fourth insert mounts and is bypassed by lines, the third reads empty");
    check(!h.loopSong(), "the song doesn't loop");
    check(h.swingUnit() == 1, "swing is on eighths");

    // Mono pad sample: one channel. Mono frozen clip: copied to both.
    const int64_t pad = h.bytesHeld(0), frozen = h.bytesHeld(1);
    check(about(pad, int64_t{hitFrames} * 4), "rack 0 holds its sample (" + std::to_string(pad) + " bytes)");
    check(about(frozen, int64_t{frozenFrames} * 8), "rack 1 holds its frozen clip (" + std::to_string(frozen) + " bytes)");
    check(h.bytesHeld(2) == 0 && h.bytesHeld(-1) == 0 && h.bytesHeld(16) == 0, "an empty or unknown rack holds nothing");

    // Unmounting lets the sample go.
    acidulous::lines::Replay clear;
    clear.env = &env;
    clear.root = dir;
    acidulous::lines::replay("acidulous-engine 4\nLoadSample 0 0 null 0\nMountInputMod 0 1 \"\"\nMountSend 1 \"\"\n", clear);
    waitBlocks(8);
    check(h.bytesHeld(0) == 0, "a cleared pad holds nothing (" + std::to_string(h.bytesHeld(0)) + ")");
    check(h.mountedModifier(0, 1)[0] == '\0' && h.mountedSend(1)[0] == '\0', "cleared slots read empty");

    Java_com_rm_acidulous_engine_EngineNative_nativeStop(&env, nullptr);
    std::system(("rm -rf " + dir).c_str());
    std::printf("host getters: %s (%d failures)\n", failures == 0 ? "ok" : "FAILED", failures);
    return failures == 0 ? 0 : 1;
}
