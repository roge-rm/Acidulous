// What the browser's page needs beside the engine's own calls, which are the
// phone's jni_bridge.cpp compiled against platform/web/jni.h: the JNI
// environment to pass them, and the means to carry strings and arrays across
// as the JNI objects they take and return (see EngineNative's wasmJs half,
// written by tools/gen_engine_bridge.py). And the audio's first gesture.

#include "EngineHost.h"
#include <drivers/AudioDriver.h>
#include <jni.h>
#include <emscripten/emscripten.h>
#include <emscripten/proxying.h>
#include <emscripten/threading.h>
#include <string>
#include <thread>

namespace {
acidulous::EngineHost &host() { return acidulous::EngineHost::instance(); }

/** On the page's thread: a handed-over call is done (Jni.kt hears it). */
void doneOnPage(void *ticket) {
    EM_ASM({ globalThis.acidAsyncDone && globalThis.acidAsyncDone($0); }, ticket);
}
} // namespace

// A thread a call, as the phone's IO pool gives each its own: they are file
// decodes and renders, a handful at a time, and never on the audio thread.
int jniweb::runAsync(Arena *arena, std::function<void(Ticket &)> work) {
    auto *ticket = new Ticket{arena};
    useOwn();
    std::thread([ticket, work = std::move(work)] {
        current() = ticket->arena;
        work(*ticket);
        useOwn();
        emscripten_proxy_async(emscripten_proxy_get_system_queue(), emscripten_main_runtime_thread_id(), doneOnPage, ticket);
    }).detach();
    return static_cast<int>(reinterpret_cast<intptr_t>(ticket));
}

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

/** A fresh arena for a call about to be handed over; the page makes its arguments in it. */
EMSCRIPTEN_KEEPALIVE int acid_jni_arena_open() {
    auto *arena = new jniweb::Arena();
    jniweb::current() = arena;
    return static_cast<int>(reinterpret_cast<intptr_t>(arena));
}
// A handed-over call's result, by type, and its end: the arena and the ticket freed.
EMSCRIPTEN_KEEPALIVE int acid_async_i(jniweb::Ticket *t) { return t->i; }
EMSCRIPTEN_KEEPALIVE float acid_async_f(jniweb::Ticket *t) { return t->f; }
EMSCRIPTEN_KEEPALIVE int64_t acid_async_j(jniweb::Ticket *t) { return t->j; }
EMSCRIPTEN_KEEPALIVE double acid_async_d(jniweb::Ticket *t) { return t->d; }
EMSCRIPTEN_KEEPALIVE void acid_async_release(jniweb::Ticket *t) {
    delete t->arena;
    delete t;
}

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
