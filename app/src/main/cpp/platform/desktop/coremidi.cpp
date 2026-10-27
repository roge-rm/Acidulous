// MIDI on macOS through CoreMIDI, for CoreMidi.kt. It sees USB devices,
// Bluetooth MIDI paired in Audio MIDI Setup, network sessions, the IAC bus
// and other programs' virtual ports.
//
// One client, made on a thread of its own that runs a CFRunLoop for as long
// as the app runs, since CoreMIDI only keeps a client's list of devices up
// to date while the thread that made it runs its loop. Incoming packets
// arrive on CoreMIDI's own thread and wait in a queue for nativeRead.
// Everything else takes the lock.

#include <CoreFoundation/CoreFoundation.h>
#include <CoreMIDI/CoreMIDI.h>
#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <algorithm>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#define LOG_TAG "Acidulous.MIDI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// The packet list calls are deprecated from macOS 11 in favour of event
// lists, but still work there and keep the bytes as MIDI 1.0 bytes.
#pragma clang diagnostic ignored "-Wdeprecated-declarations"

namespace {

std::mutex lock;
MIDIClientRef client = 0;
MIDIPortRef inPort = 0;
MIDIPortRef outPort = 0;

/** Messages waiting for nativeRead: the source's unique id and the bytes. */
std::mutex queueLock;
std::condition_variable queued;
std::deque<std::pair<int32_t, std::vector<uint8_t>>> queue;
/** Past this many waiting, new ones are dropped rather than growing without end. */
constexpr size_t kMaxQueued = 4096;

/** The test loopback's ends, if made. */
MIDIEndpointRef loopSource = 0;
MIDIEndpointRef loopDestination = 0;

std::string toUtf8(CFStringRef s) {
    if (s == nullptr) return {};
    const CFIndex length = CFStringGetLength(s);
    const CFIndex size = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1;
    std::string out(static_cast<size_t>(size), '\0');
    if (!CFStringGetCString(s, out.data(), size, kCFStringEncodingUTF8)) return {};
    out.resize(std::char_traits<char>::length(out.c_str()));
    return out;
}

std::string stringProperty(MIDIObjectRef object, CFStringRef property) {
    CFStringRef value = nullptr;
    if (MIDIObjectGetStringProperty(object, property, &value) != noErr || value == nullptr) return {};
    std::string out = toUtf8(value);
    CFRelease(value);
    return out;
}

SInt32 intProperty(MIDIObjectRef object, CFStringRef property, SInt32 fallback) {
    SInt32 value = 0;
    return MIDIObjectGetIntegerProperty(object, property, &value) == noErr ? value : fallback;
}

void received(const MIDIPacketList *list, void *refCon) {
    const auto source = static_cast<int32_t>(reinterpret_cast<intptr_t>(refCon));
    std::lock_guard<std::mutex> guard(queueLock);
    const MIDIPacket *packet = &list->packet[0];
    for (UInt32 i = 0; i < list->numPackets; i++) {
        if (packet->length > 0 && queue.size() < kMaxQueued) {
            queue.emplace_back(source, std::vector<uint8_t>(packet->data, packet->data + packet->length));
        }
        packet = MIDIPacketNext(packet);
    }
    queued.notify_one();
}

/** Sends [count] bytes from [data] to [destination], split into packets CoreMIDI can carry. */
bool sendBytes(MIDIEndpointRef destination, const uint8_t *data, size_t count) {
    constexpr size_t kChunk = 4096;
    std::vector<uint8_t> buffer(kChunk + 256);
    for (size_t at = 0; at < count; at += kChunk) {
        const size_t n = std::min(kChunk, count - at);
        auto *list = reinterpret_cast<MIDIPacketList *>(buffer.data());
        MIDIPacket *packet = MIDIPacketListInit(list);
        // Timestamp 0 is now: timed sends wait on the MIDI thread (MidiThread.kt).
        packet = MIDIPacketListAdd(list, buffer.size(), packet, 0, n, data + at);
        if (packet == nullptr) return false;
        if (MIDISend(outPort, destination, list) != noErr) return false;
    }
    return true;
}

MIDIEndpointRef endpointById(jint id) {
    MIDIObjectRef object = 0;
    MIDIObjectType type;
    if (MIDIObjectFindByUniqueID(id, &object, &type) != noErr) return 0;
    return static_cast<MIDIEndpointRef>(object);
}

/** Adds one endpoint's six strings to [out]. See nativeEndpoints. */
void describe(MIDIEndpointRef endpoint, int kind, std::vector<std::string> &out) {
    if (intProperty(endpoint, kMIDIPropertyOffline, 0) != 0) return;
    if (intProperty(endpoint, kMIDIPropertyPrivate, 0) != 0) return;
    const SInt32 id = intProperty(endpoint, kMIDIPropertyUniqueID, 0);
    const std::string name = stringProperty(endpoint, kMIDIPropertyName);
    MIDIEntityRef entity = 0;
    MIDIDeviceRef device = 0;
    if (MIDIEndpointGetEntity(endpoint, &entity) == noErr && entity != 0) MIDIEntityGetDevice(entity, &device);
    std::string key, deviceName, maker;
    int flags = 0;
    if (device != 0) {
        key = "d" + std::to_string(intProperty(device, kMIDIPropertyUniqueID, 0));
        deviceName = stringProperty(device, kMIDIPropertyName);
        maker = stringProperty(device, kMIDIPropertyManufacturer);
        const std::string driver = stringProperty(device, kMIDIPropertyDriverOwner);
        if (driver.find("USB") != std::string::npos) flags |= 1;
        if (driver.find("Bluetooth") != std::string::npos || driver.find("BLE") != std::string::npos) flags |= 2;
    } else {
        // A program's own port, with no device behind it. Its source and
        // destination usually share a name, which pairs them.
        key = "v" + name;
        deviceName = name;
        flags |= 4;
    }
    if (deviceName.empty()) deviceName = name;
    for (std::string s : {std::to_string(id), std::to_string(kind), key, deviceName, maker, std::to_string(flags)}) {
        out.push_back(std::move(s));
    }
}

} // namespace

extern "C" {

/** Start CoreMIDI as a client called "Acidulous". False if it won't start. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeOpen(JNIEnv *, jclass) {
    static std::mutex opening;
    std::lock_guard<std::mutex> once(opening);
    {
        std::lock_guard<std::mutex> guard(lock);
        if (client != 0) return JNI_TRUE;
    }
    // Shared with the thread, which outlives this call if CoreMIDI is slow.
    struct Start {
        std::mutex m;
        std::condition_variable ready;
        bool done = false;
        OSStatus status = 1;
    };
    auto start = std::make_shared<Start>();
    std::thread([start] {
        MIDIClientRef made = 0;
        OSStatus s = MIDIClientCreateWithBlock(CFSTR("Acidulous"), &made, ^(const MIDINotification *) {});
        MIDIPortRef in = 0, out = 0;
        if (s == noErr) s = MIDIInputPortCreateWithBlock(made, CFSTR("in"), &in, ^(const MIDIPacketList *list, void *refCon) {
            received(list, refCon);
        });
        if (s == noErr) s = MIDIOutputPortCreate(made, CFSTR("out"), &out);
        if (s == noErr) {
            std::lock_guard<std::mutex> guard(lock);
            client = made;
            inPort = in;
            outPort = out;
        }
        {
            std::lock_guard<std::mutex> guard(start->m);
            start->status = s;
            start->done = true;
        }
        start->ready.notify_one();
        if (s == noErr) CFRunLoopRun();
    }).detach();
    // MIDIClientCreate can wait on the MIDI server for a few seconds the first time.
    std::unique_lock<std::mutex> wait(start->m);
    if (!start->ready.wait_for(wait, std::chrono::seconds(10), [&] { return start->done; })) {
        LOGW("CoreMIDI did not answer");
        return JNI_FALSE;
    }
    if (start->status != noErr) {
        LOGW("CoreMIDI would not start: %d", static_cast<int>(start->status));
        return JNI_FALSE;
    }
    LOGI("CoreMIDI open");
    return JNI_TRUE;
}

/**
 * Every source and destination as six strings in a row: its unique id, 1 for
 * a source or 2 for a destination, the key of the device it belongs to, the
 * device's name, its maker, and flags (1 USB, 2 Bluetooth, 4 a program's own
 * port). CoreMidi.kt groups them into devices.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeEndpoints(JNIEnv *env, jclass) {
    std::vector<std::string> out;
    {
        std::lock_guard<std::mutex> guard(lock);
        if (client != 0) {
            const ItemCount sources = MIDIGetNumberOfSources();
            for (ItemCount i = 0; i < sources; i++) describe(MIDIGetSource(i), 1, out);
            const ItemCount destinations = MIDIGetNumberOfDestinations();
            for (ItemCount i = 0; i < destinations; i++) describe(MIDIGetDestination(i), 2, out);
        }
    }
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(out.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < out.size(); i++) {
        jstring s = env->NewStringUTF(out[i].c_str());
        env->SetObjectArrayElement(array, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return array;
}

/** Start or stop hearing a source. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeListen(JNIEnv *, jclass, jint id, jboolean on) {
    std::lock_guard<std::mutex> guard(lock);
    if (client == 0) return JNI_FALSE;
    const MIDIEndpointRef source = endpointById(id);
    if (source == 0) return JNI_FALSE;
    const OSStatus s = on
        ? MIDIPortConnectSource(inPort, source, reinterpret_cast<void *>(static_cast<intptr_t>(id)))
        : MIDIPortDisconnectSource(inPort, source);
    if (s != noErr) LOGW("could not %s source %d: %d", on ? "connect" : "disconnect", static_cast<int>(id), static_cast<int>(s));
    return s == noErr ? JNI_TRUE : JNI_FALSE;
}

/** Send bytes to a destination now. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeSend(JNIEnv *env, jclass, jint id, jbyteArray bytes, jint offset, jint count) {
    if (count <= 0) return JNI_TRUE;
    std::vector<uint8_t> data(static_cast<size_t>(count));
    env->GetByteArrayRegion(bytes, offset, count, reinterpret_cast<jbyte *>(data.data()));
    std::lock_guard<std::mutex> guard(lock);
    if (client == 0) return JNI_FALSE;
    const MIDIEndpointRef destination = endpointById(id);
    if (destination == 0) return JNI_FALSE;
    return sendBytes(destination, data.data(), data.size()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Read the next incoming packet into [out] and return its length, with the
 * source's unique id in from[0]. Returns 0 if nothing came within
 * [timeoutMs] and negative if CoreMIDI never started.
 */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeRead(JNIEnv *env, jclass, jbyteArray out, jintArray from, jint timeoutMs) {
    {
        std::lock_guard<std::mutex> guard(lock);
        if (client == 0) return -1;
    }
    std::unique_lock<std::mutex> guard(queueLock);
    if (!queued.wait_for(guard, std::chrono::milliseconds(timeoutMs), [] { return !queue.empty(); })) return 0;
    auto message = std::move(queue.front());
    queue.pop_front();
    guard.unlock();
    const jsize room = env->GetArrayLength(out);
    const auto length = static_cast<jsize>(std::min<size_t>(message.second.size(), static_cast<size_t>(room)));
    const jint source = message.first;
    env->SetIntArrayRegion(from, 0, 1, &source);
    env->SetByteArrayRegion(out, 0, length, reinterpret_cast<const jbyte *>(message.second.data()));
    return length;
}

/**
 * For the tests: a virtual source and destination both called [name], where
 * whatever reaches the destination comes straight back out of the source.
 */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_CoreMidiNative_nativeLoopback(JNIEnv *env, jclass, jstring name) {
    std::lock_guard<std::mutex> guard(lock);
    if (client == 0) return JNI_FALSE;
    if (loopSource != 0) return JNI_TRUE;
    const char *chars = env->GetStringUTFChars(name, nullptr);
    CFStringRef cfName = CFStringCreateWithCString(nullptr, chars, kCFStringEncodingUTF8);
    env->ReleaseStringUTFChars(name, chars);
    OSStatus s = MIDISourceCreate(client, cfName, &loopSource);
    if (s == noErr) {
        s = MIDIDestinationCreateWithBlock(client, cfName, &loopDestination, ^(const MIDIPacketList *list, void *) {
            MIDIReceived(loopSource, list);
        });
    }
    CFRelease(cfName);
    if (s != noErr) LOGW("could not make the loopback: %d", static_cast<int>(s));
    return s == noErr ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
