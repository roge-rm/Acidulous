#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

// An interface's own low-latency driver on Windows, through Steinberg's ASIO
// SDK (third_party/asiosdk, taken under the GPL v3). Windows only; the
// desktop AudioDriver plays through one of these instead of miniaudio when
// one is chosen as the output.
//
// **One driver at a time, on one thread of its own.** A driver is a COM
// object and expects to be loaded, started, stopped and let go of on the one
// apartment-threaded thread, which keeps a message loop running for it - so
// every call here is handed to that thread and waited for. The driver calls
// back on its own audio thread, and the SDK's callbacks carry no context, so
// the open stream is a single one here.
//
// The app never names the technology to the person using it (Steinberg's
// trademark rules follow from naming it): a driver is listed under the name
// its maker registered, as the output list's other devices are.
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
 * Open [name] at [sampleRate], its buffer sized by [bursts] (1 the smallest the
 * driver offers, 2 its preferred, more than that a larger one), and start it.
 * [onReset] is called on a thread of its own when the driver asks to be
 * opened again - its buffer size or sample rate changed in its own panel.
 */
bool open(const std::string &name, int32_t sampleRate, int32_t bursts, Process process, std::function<void()> onReset, Stream &stream);
void close();
bool isOpen();
/** Read this input pair from now on; -1 for none. */
void setInputPair(int32_t pair);

} // namespace acidulous::asio
