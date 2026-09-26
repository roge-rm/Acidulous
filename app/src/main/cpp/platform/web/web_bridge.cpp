// What the browser's UI calls in the engine: the phone's jni_bridge.cpp, as
// plain C functions for JavaScript to call on the shared memory the audio
// worklet renders from. For now a handful, enough to prove the engine plays
// in a page; the whole bridge follows.

#include "EngineHost.h"
#include <drivers/AudioDriver.h>
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

} // extern "C"
