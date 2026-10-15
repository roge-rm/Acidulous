// Replays engine lines and renders the song to a WAV, the way the app's
// export does: engine_replay.sh unpacks a song zip and runs this on it.
//
//   engine_replay <folder with engine.txt> <out.wav> [seconds] [tail seconds] [bits]
//
// seconds 0 renders to the end of the song. bits 32 is float. The calls go
// through jni_bridge.cpp exactly as the app's do (see platform/lines).

#include <drivers/AudioDriver.h>
#include <jni.h>
#include <platform/lines/EngineLines.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>

extern "C" {
jboolean Java_com_rm_acidulous_engine_EngineNative_nativeStart(JNIEnv *, jobject);
void Java_com_rm_acidulous_engine_EngineNative_nativeStop(JNIEnv *, jobject);
void Java_com_rm_acidulous_engine_EngineNative_nativePrewarm(JNIEnv *, jobject);
jstring Java_com_rm_acidulous_engine_EngineNative_nativeRenderSong(JNIEnv *, jobject, jstring, jfloat, jint, jint, jint,
                                                                   jfloat);
}

namespace {
/** Waits until the driver's thread has rendered [n] more blocks, so what's queued has been taken. */
void waitBlocks(int n) {
    const int64_t until = AudioDriver::blocks() + n;
    while (AudioDriver::blocks() < until) std::this_thread::sleep_for(std::chrono::microseconds(200));
}
} // namespace

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: engine_replay <folder with engine.txt> <out.wav> [seconds] [tail] [bits]\n");
        return 2;
    }
    const std::string dir = argv[1];
    const std::string out = argv[2];
    const float seconds = argc > 3 ? std::strtof(argv[3], nullptr) : 0.0f;
    const float tail = argc > 4 ? std::strtof(argv[4], nullptr) : 2.0f;
    const int bits = argc > 5 ? std::atoi(argv[5]) : 32;

    std::ifstream file(dir + "/engine.txt", std::ios::binary);
    if (!file) {
        std::fprintf(stderr, "engine_replay: no engine.txt in %s\n", dir.c_str());
        return 1;
    }
    std::stringstream text;
    text << file.rdbuf();

    static JNIEnv env;
    Java_com_rm_acidulous_engine_EngineNative_nativePrewarm(&env, nullptr);
    if (!Java_com_rm_acidulous_engine_EngineNative_nativeStart(&env, nullptr)) {
        std::fprintf(stderr, "engine_replay: the engine didn't start\n");
        return 1;
    }

    acidulous::lines::Replay replay;
    replay.env = &env;
    replay.root = dir;
    // The parameter queue holds 512 and the audio thread empties it every
    // block, so let it catch up now and then.
    replay.afterLine = [](int line) {
        if (line % 128 == 0) waitBlocks(2);
    };
    if (!acidulous::lines::replay(text.str(), replay)) return 1;
    waitBlocks(4);
    std::fprintf(stderr, "engine_replay: %d calls, %d skipped, %d answered false\n", replay.calls, replay.skipped,
                 replay.refused);

    jstring path = env.NewStringUTF(out.c_str());
    jstring error =
        Java_com_rm_acidulous_engine_EngineNative_nativeRenderSong(&env, nullptr, path, tail, 0, bits, 0, seconds);
    const bool ok = error == nullptr || error->text.empty();
    if (!ok) std::fprintf(stderr, "engine_replay: render failed: %s\n", error->text.c_str());
    jniweb::arena().clear();
    Java_com_rm_acidulous_engine_EngineNative_nativeStop(&env, nullptr);
    return ok && replay.skipped == 0 ? 0 : 1;
}
