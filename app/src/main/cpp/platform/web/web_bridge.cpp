// What the browser's page needs beside the engine's own calls, which are the
// phone's jni_bridge.cpp compiled against platform/web/jni.h: the JNI
// environment to pass them, and the means to carry strings and arrays across
// as the JNI objects they take and return (see EngineNative's wasmJs half,
// written by tools/gen_engine_bridge.py). And the audio's first gesture.

#include "EngineHost.h"
#include <drivers/AudioDriver.h>
#include <jni.h>
#include <emscripten/emscripten.h>
#include <string>

namespace {
acidulous::EngineHost &host() { return acidulous::EngineHost::instance(); }
} // namespace

extern "C" {

EMSCRIPTEN_KEEPALIVE int acid_start() { return host().start() ? 1 : 0; }
EMSCRIPTEN_KEEPALIVE void acid_resume() { AudioDriver::resume(); }
EMSCRIPTEN_KEEPALIVE int acid_mount_machine(int rack, const char *type) {
    return host().mountMachine(rack, std::string(type)) ? 1 : 0;
}
EMSCRIPTEN_KEEPALIVE void acid_note_on(int rack, int note, int velocity) {
    host().noteOn(rack, static_cast<uint8_t>(note & 0x7f), static_cast<uint8_t>(velocity & 0x7f));
}
EMSCRIPTEN_KEEPALIVE void acid_note_off(int rack, int note) {
    host().noteOff(rack, static_cast<uint8_t>(note & 0x7f));
}
EMSCRIPTEN_KEEPALIVE float acid_peak() { return host().peakLevel(); }
EMSCRIPTEN_KEEPALIVE double acid_frames() { return AudioDriver::liveFrames(); }
EMSCRIPTEN_KEEPALIVE int acid_state() { return AudioDriver::liveState(); }

// --- JNI objects, for the page -------------------------------------------------

EMSCRIPTEN_KEEPALIVE JNIEnv *acid_jni_env() {
    static JNIEnv env;
    return &env;
}
/** Free everything made for and by the last call: see platform/web/jni.h. */
EMSCRIPTEN_KEEPALIVE void acid_jni_release() { jniweb::arena().clear(); }

EMSCRIPTEN_KEEPALIVE jstring acid_jni_string(const char *utf8) { return acid_jni_env()->NewStringUTF(utf8); }
EMSCRIPTEN_KEEPALIVE const char *acid_jni_chars(jstring s) { return s != nullptr ? s->text.c_str() : nullptr; }
EMSCRIPTEN_KEEPALIVE int acid_jni_length(jarray a) { return a != nullptr ? a->size() : 0; }
EMSCRIPTEN_KEEPALIVE jobject acid_jni_object_at(jobjectArray a, int i) { return a->items[static_cast<size_t>(i)]; }
EMSCRIPTEN_KEEPALIVE jobjectArray acid_jni_objects(int n) { return acid_jni_env()->NewObjectArray(n, nullptr, nullptr); }
EMSCRIPTEN_KEEPALIVE void acid_jni_set_object(jobjectArray a, int i, jobject v) { a->items[static_cast<size_t>(i)] = v; }

EMSCRIPTEN_KEEPALIVE jfloatArray acid_jni_floats(int n) { return acid_jni_env()->NewFloatArray(n); }
EMSCRIPTEN_KEEPALIVE float acid_jni_float_at(jfloatArray a, int i) { return a->items[static_cast<size_t>(i)]; }
EMSCRIPTEN_KEEPALIVE void acid_jni_set_float(jfloatArray a, int i, float v) { a->items[static_cast<size_t>(i)] = v; }

EMSCRIPTEN_KEEPALIVE jintArray acid_jni_ints(int n) { return acid_jni_env()->NewIntArray(n); }
EMSCRIPTEN_KEEPALIVE int acid_jni_int_at(jintArray a, int i) { return a->items[static_cast<size_t>(i)]; }
EMSCRIPTEN_KEEPALIVE void acid_jni_set_int(jintArray a, int i, int v) { a->items[static_cast<size_t>(i)] = v; }

EMSCRIPTEN_KEEPALIVE jlongArray acid_jni_longs(int n) { return acid_jni_env()->NewLongArray(n); }
EMSCRIPTEN_KEEPALIVE int64_t acid_jni_long_at(jlongArray a, int i) { return a->items[static_cast<size_t>(i)]; }
EMSCRIPTEN_KEEPALIVE void acid_jni_set_long(jlongArray a, int i, int64_t v) { a->items[static_cast<size_t>(i)] = v; }

} // extern "C"
