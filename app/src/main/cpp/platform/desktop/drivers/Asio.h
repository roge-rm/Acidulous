#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

// ASIO drivers on Windows, through Steinberg's ASIO SDK (third_party/asiosdk,
// used under the GPL v3). When one is chosen as the output, the desktop
// AudioDriver plays through it instead of miniaudio.
//
// Only one driver is open at a time. Drivers are COM objects and need to be
// loaded, started and stopped on the same thread, with a message loop, so
// every call here runs on that thread and waits for it. The driver calls back
// on its own audio thread and the SDK's callbacks carry no context, so there's
// only one open stream.
//
// The app never shows the word ASIO (Steinberg's trademark rules). Drivers are
// listed by the name their maker registered, like any other output.
namespace acidulous::asio {

/** The drivers installed, by the names their makers registered. */
std::vector<std::string> driverNames();

/** What an open driver settled on. */
struct Stream {
    int32_t sampleRate = 0;
    int32_t bufferFrames = 0;
    int32_t outputLatency = 0; // frames, as the driver reports it
    int32_t inputLatency = 0;
    std::vector<std::string> inputPairs; // "In 1 / In 2", by the driver's channel names
};

/**
 * The work for one buffer: [in] is the chosen input pair, interleaved stereo,
 * or null when no input is wanted; [out] is interleaved stereo to fill.
 */
using Process = std::function<void(const float *in, float *out, int32_t frames)>;

/**
 * Open [name] at [sampleRate] and start it. [bursts] sets the buffer: 1 is the
 * driver's smallest, 2 its preferred, more is larger. [onReset] runs on its
 * own thread when the driver asks to be reopened, e.g. after its buffer size
 * or sample rate was changed in its own panel.
 */
bool open(const std::string &name, int32_t sampleRate, int32_t bursts, Process process, std::function<void()> onReset, Stream &stream);
void close();
bool isOpen();
/** Read this input pair from now on; -1 for none. */
void setInputPair(int32_t pair);

} // namespace acidulous::asio
