#include "Asio.h"

#include <windows.h>

#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <future>
#include <mutex>
#include <thread>

#include "asiosys.h"
#include "asio.h"
#include "asiodrivers.h"

#define LOG_TAG "Acidulous.Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AsioDrivers *asioDrivers;
bool loadAsioDriver(char *name);

namespace acidulous::asio {
namespace {

/**
 * The driver's thread: apartment-threaded COM with a message loop, which is
 * what drivers expect. Everything that touches the driver outside its own
 * callbacks runs here and is waited for. Lives as long as the app.
 */
class Home {
  public:
    static Home &get() {
        static Home *home = new Home(); // never torn down
        return *home;
    }

    template <typename T> T call(std::function<T()> work) {
        auto task = std::make_shared<std::packaged_task<T()>>(std::move(work));
        std::future<T> done = task->get_future();
        {
            std::lock_guard<std::mutex> lock(mutex);
            queue.emplace_back([task] { (*task)(); });
        }
        PostThreadMessageW(threadId, WM_APP, 0, 0);
        return done.get();
    }

  private:
    Home() {
        std::promise<DWORD> started;
        auto id = started.get_future();
        thread = std::thread([this, &started] { run(started); });
        threadId = id.get();
    }

    void run(std::promise<DWORD> &started) {
        CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        MSG msg;
        PeekMessageW(&msg, nullptr, WM_USER, WM_USER, PM_NOREMOVE); // create the queue before anyone posts
        started.set_value(GetCurrentThreadId());
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            if (msg.message == WM_APP && msg.hwnd == nullptr) {
                std::deque<std::function<void()>> now;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    now.swap(queue);
                }
                for (auto &work : now) work();
            } else {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        CoUninitialize();
    }

    std::thread thread;
    DWORD threadId = 0;
    std::mutex mutex;
    std::deque<std::function<void()>> queue;
};

// The one open stream. The SDK's callbacks carry no context, so they find it here.
constexpr int32_t kMaxInputs = 32;

struct Open {
    bool on = false;
    Process process;
    std::function<void()> onReset;
    std::vector<ASIOBufferInfo> buffers; // inputs first, then the two outputs
    std::vector<ASIOSampleType> types;
    int32_t inputs = 0;
    int32_t frames = 0;
    bool postOutput = false;
    std::vector<float> in;  // the chosen pair, interleaved
    std::vector<float> out; // what the engine made, interleaved
};
Open live;
std::atomic<int32_t> inputPair{-1};
ASIOCallbacks callbacks{};

float sampleIn(const void *buffer, ASIOSampleType type, int32_t i) {
    switch (type) {
        case ASIOSTInt16LSB: return static_cast<const int16_t *>(buffer)[i] / 32768.0f;
        case ASIOSTInt24LSB: {
            const auto *b = static_cast<const uint8_t *>(buffer) + i * 3;
            const int32_t v = (static_cast<int32_t>(b[2]) << 24 | b[1] << 16 | b[0] << 8) >> 8;
            return v / 8388608.0f;
        }
        case ASIOSTInt32LSB: return static_cast<float>(static_cast<const int32_t *>(buffer)[i] / 2147483648.0);
        case ASIOSTInt32LSB16: return static_cast<const int32_t *>(buffer)[i] / 32768.0f;
        case ASIOSTInt32LSB18: return static_cast<const int32_t *>(buffer)[i] / 131072.0f;
        case ASIOSTInt32LSB20: return static_cast<const int32_t *>(buffer)[i] / 524288.0f;
        case ASIOSTInt32LSB24: return static_cast<const int32_t *>(buffer)[i] / 8388608.0f;
        case ASIOSTFloat32LSB: return static_cast<const float *>(buffer)[i];
        case ASIOSTFloat64LSB: return static_cast<float>(static_cast<const double *>(buffer)[i]);
        default: return 0.0f;
    }
}

void sampleOut(void *buffer, ASIOSampleType type, int32_t i, float value) {
    const double v = std::clamp(static_cast<double>(value), -1.0, 1.0);
    switch (type) {
        case ASIOSTInt16LSB: static_cast<int16_t *>(buffer)[i] = static_cast<int16_t>(std::lrint(v * 32767.0)); break;
        case ASIOSTInt24LSB: {
            const auto s = static_cast<int32_t>(std::lrint(v * 8388607.0));
            auto *b = static_cast<uint8_t *>(buffer) + i * 3;
            b[0] = static_cast<uint8_t>(s);
            b[1] = static_cast<uint8_t>(s >> 8);
            b[2] = static_cast<uint8_t>(s >> 16);
            break;
        }
        case ASIOSTInt32LSB: static_cast<int32_t *>(buffer)[i] = static_cast<int32_t>(std::llrint(v * 2147483647.0)); break;
        case ASIOSTInt32LSB16: static_cast<int32_t *>(buffer)[i] = static_cast<int32_t>(std::lrint(v * 32767.0)); break;
        case ASIOSTInt32LSB18: static_cast<int32_t *>(buffer)[i] = static_cast<int32_t>(std::lrint(v * 131071.0)); break;
        case ASIOSTInt32LSB20: static_cast<int32_t *>(buffer)[i] = static_cast<int32_t>(std::lrint(v * 524287.0)); break;
        case ASIOSTInt32LSB24: static_cast<int32_t *>(buffer)[i] = static_cast<int32_t>(std::lrint(v * 8388607.0)); break;
        case ASIOSTFloat32LSB: static_cast<float *>(buffer)[i] = static_cast<float>(v); break;
        case ASIOSTFloat64LSB: static_cast<double *>(buffer)[i] = v; break;
        default: break;
    }
}

bool typeKnown(ASIOSampleType type) {
    switch (type) {
        case ASIOSTInt16LSB: case ASIOSTInt24LSB: case ASIOSTInt32LSB: case ASIOSTInt32LSB16: case ASIOSTInt32LSB18:
        case ASIOSTInt32LSB20: case ASIOSTInt32LSB24: case ASIOSTFloat32LSB: case ASIOSTFloat64LSB:
            return true;
        default:
            return false;
    }
}

// On the driver's audio thread: reads the chosen input pair, writes the engine's block.
void process(long index) {
    if (!live.on) return;
    const int32_t n = live.frames;
    const int32_t pair = inputPair.load(std::memory_order_relaxed);
    const float *in = nullptr;
    if (pair >= 0 && pair * 2 < live.inputs) {
        const int32_t left = pair * 2;
        const int32_t right = left + 1 < live.inputs ? left + 1 : left;
        for (int32_t i = 0; i < n; ++i) {
            live.in[static_cast<size_t>(i) * 2] = sampleIn(live.buffers[left].buffers[index], live.types[left], i);
            live.in[static_cast<size_t>(i) * 2 + 1] = sampleIn(live.buffers[right].buffers[index], live.types[right], i);
        }
        in = live.in.data();
    }
    live.process(in, live.out.data(), n);
    for (int32_t c = 0; c < 2; ++c) {
        const int32_t slot = live.inputs + c;
        void *dst = live.buffers[slot].buffers[index];
        for (int32_t i = 0; i < n; ++i) sampleOut(dst, live.types[slot], i, live.out[static_cast<size_t>(i) * 2 + c]);
    }
    if (live.postOutput) ASIOOutputReady();
}

void onBufferSwitch(long index, ASIOBool) { process(index); }
ASIOTime *onBufferSwitchTimeInfo(ASIOTime *, long index, ASIOBool) {
    process(index);
    return nullptr;
}
void onRateChange(ASIOSampleRate rate) { LOGI("the driver's sample rate changed to %.0f", rate); }

void resetSoon() {
    auto reset = live.onReset;
    if (reset) std::thread(reset).detach(); // own thread, since the reset closes the driver
}

long onMessage(long selector, long value, void *, double *) {
    switch (selector) {
        case kAsioSelectorSupported:
            return value == kAsioResetRequest || value == kAsioEngineVersion || value == kAsioResyncRequest ||
                   value == kAsioLatenciesChanged;
        case kAsioEngineVersion: return 2;
        // The driver's panel changed its buffer or rate, so reopen it.
        case kAsioResetRequest: resetSoon(); return 1;
        case kAsioResyncRequest: return 1;
        case kAsioLatenciesChanged: return 1;
        default: return 0;
    }
}

int32_t chooseFrames(long minSize, long maxSize, long preferred, long granularity, int32_t bursts) {
    long want = preferred;
    if (bursts <= 1) want = std::max(minSize, preferred / 2);
    else if (bursts >= 3) want = std::min(maxSize, preferred * 2);
    if (granularity == -1) { // powers of two
        long p = minSize;
        while (p < want && p < maxSize) p *= 2;
        want = p;
    } else if (granularity > 0) {
        want = minSize + ((want - minSize) + granularity / 2) / granularity * granularity;
    } else {
        want = preferred;
    }
    return static_cast<int32_t>(std::clamp(want, minSize, maxSize));
}

void closeHere() {
    if (live.on) {
        ASIOStop();
        live.on = false;
    }
    ASIODisposeBuffers();
    ASIOExit();
    if (asioDrivers != nullptr) asioDrivers->removeCurrentDriver();
    live.buffers.clear();
    live.types.clear();
    live.process = nullptr;
    live.onReset = nullptr;
}

bool openHere(const std::string &name, int32_t sampleRate, int32_t bursts, Process process, std::function<void()> onReset, Stream &stream) {
    std::vector<char> driver(name.begin(), name.end());
    driver.push_back(0);
    if (!loadAsioDriver(driver.data())) {
        LOGE("driver \"%s\" would not load", name.c_str());
        return false;
    }
    ASIODriverInfo info{};
    info.asioVersion = 2;
    info.sysRef = GetDesktopWindow();
    if (ASIOInit(&info) != ASE_OK) {
        LOGE("driver \"%s\" would not start: %s", name.c_str(), info.errorMessage);
        if (asioDrivers != nullptr) asioDrivers->removeCurrentDriver();
        return false;
    }
    // The driver has to run at the engine's rate. If it can't, it isn't used
    // and the default output (which resamples) plays instead.
    ASIOSampleRate rate = 0;
    if (ASIOCanSampleRate(sampleRate) != ASE_OK || ASIOSetSampleRate(sampleRate) != ASE_OK ||
        ASIOGetSampleRate(&rate) != ASE_OK || std::lround(rate) != sampleRate) {
        LOGE("driver \"%s\" will not run at %d Hz", name.c_str(), sampleRate);
        closeHere();
        return false;
    }
    long inputs = 0, outputs = 0;
    ASIOGetChannels(&inputs, &outputs);
    if (outputs < 2) {
        LOGE("driver \"%s\" has %ld outputs", name.c_str(), outputs);
        closeHere();
        return false;
    }
    long minSize = 0, maxSize = 0, preferred = 0, granularity = 0;
    ASIOGetBufferSize(&minSize, &maxSize, &preferred, &granularity);
    const int32_t frames = chooseFrames(minSize, maxSize, preferred, granularity, bursts);

    live.inputs = static_cast<int32_t>(std::min<long>(inputs, kMaxInputs));
    live.buffers.assign(static_cast<size_t>(live.inputs + 2), ASIOBufferInfo{});
    for (int32_t i = 0; i < live.inputs; ++i) {
        live.buffers[i].isInput = ASIOTrue;
        live.buffers[i].channelNum = i;
    }
    for (int32_t c = 0; c < 2; ++c) {
        live.buffers[live.inputs + c].isInput = ASIOFalse;
        live.buffers[live.inputs + c].channelNum = c;
    }
    callbacks.bufferSwitch = onBufferSwitch;
    callbacks.sampleRateDidChange = onRateChange;
    callbacks.asioMessage = onMessage;
    callbacks.bufferSwitchTimeInfo = onBufferSwitchTimeInfo;
    live.onReset = std::move(onReset);
    if (ASIOCreateBuffers(live.buffers.data(), static_cast<long>(live.buffers.size()), frames, &callbacks) != ASE_OK) {
        LOGE("driver \"%s\" would not make buffers of %d frames", name.c_str(), frames);
        closeHere();
        return false;
    }
    live.types.assign(live.buffers.size(), ASIOSTInt32LSB);
    stream.inputPairs.clear();
    std::vector<std::string> channelNames;
    for (size_t i = 0; i < live.buffers.size(); ++i) {
        ASIOChannelInfo ci{};
        ci.channel = live.buffers[i].channelNum;
        ci.isInput = live.buffers[i].isInput;
        ASIOGetChannelInfo(&ci);
        live.types[i] = ci.type;
        if (!typeKnown(ci.type)) {
            LOGE("driver \"%s\" uses sample type %ld, which is not read here", name.c_str(), static_cast<long>(ci.type));
            closeHere();
            return false;
        }
        if (ci.isInput) channelNames.emplace_back(ci.name);
    }
    for (size_t i = 0; i < channelNames.size(); i += 2) {
        stream.inputPairs.push_back(i + 1 < channelNames.size() ? channelNames[i] + " / " + channelNames[i + 1] : channelNames[i]);
    }
    long inputLatency = 0, outputLatency = 0;
    ASIOGetLatencies(&inputLatency, &outputLatency);
    live.frames = frames;
    live.in.assign(static_cast<size_t>(frames) * 2, 0.0f);
    live.out.assign(static_cast<size_t>(frames) * 2, 0.0f);
    live.process = std::move(process);
    live.postOutput = ASIOOutputReady() == ASE_OK;
    live.on = true;
    if (ASIOStart() != ASE_OK) {
        LOGE("driver \"%s\" would not start playing", name.c_str());
        closeHere();
        return false;
    }
    stream.sampleRate = sampleRate;
    stream.bufferFrames = frames;
    stream.inputLatency = static_cast<int32_t>(inputLatency);
    stream.outputLatency = static_cast<int32_t>(outputLatency);
    LOGI("driver open: %s, %d Hz, %d frames (driver's range %ld to %ld, preferred %ld), latency in %ld out %ld, %ld in, %ld out",
         name.c_str(), sampleRate, frames, minSize, maxSize, preferred, inputLatency, outputLatency, inputs, outputs);
    return true;
}

} // namespace

std::vector<std::string> driverNames() {
    return Home::get().call<std::vector<std::string>>([] {
        AsioDrivers list;
        // The list copies each name whole, up to its own limit (MAXDRVNAMELEN).
        static char storage[32][MAXDRVNAMELEN];
        char *names[32];
        for (int i = 0; i < 32; ++i) names[i] = storage[i];
        const long n = list.getDriverNames(names, 32);
        std::vector<std::string> out;
        for (long i = 0; i < n; ++i) out.emplace_back(names[i]);
        return out;
    });
}

bool open(const std::string &name, int32_t sampleRate, int32_t bursts, Process process, std::function<void()> onReset, Stream &stream) {
    return Home::get().call<bool>([&] {
        if (live.on || !live.buffers.empty()) closeHere();
        return openHere(name, sampleRate, bursts, std::move(process), std::move(onReset), stream);
    });
}

void close() {
    Home::get().call<bool>([] {
        if (live.on || !live.buffers.empty()) closeHere();
        return true;
    });
    LOGI("driver closed");
}

bool isOpen() { return live.on; }

void setInputPair(int32_t pair) { inputPair.store(pair, std::memory_order_relaxed); }

} // namespace acidulous::asio
