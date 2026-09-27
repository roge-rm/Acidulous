// MIDI on Linux through the ALSA sequencer, for AlsaSeq.kt. It sees every
// sequencer port: hardware MIDI devices, other programs, and BlueZ Bluetooth
// MIDI, which Java Sound's raw MIDI can't see.
//
// libasound is loaded at runtime rather than linked, like miniaudio does for
// audio, so the engine builds with just the headers (third_party/alsa). On a
// machine without it we fall back to Java Sound's raw MIDI.
//
// One sequencer handle, open for as long as the app runs. The reading thread
// only reads. Everything else (sending, listing ports, connecting) takes the
// lock.

#include <alsa/asoundlib.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <poll.h>
#include <string>
#include <vector>

#define LOG_TAG "Acidulous.MIDI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

/** The libasound functions we use, typed from its own declarations. */
struct Alsa {
#define ALSA_FN(name) decltype(&::name) name = nullptr
    ALSA_FN(snd_seq_open);
    ALSA_FN(snd_seq_close);
    ALSA_FN(snd_seq_set_client_name);
    ALSA_FN(snd_seq_client_id);
    ALSA_FN(snd_seq_create_simple_port);
    ALSA_FN(snd_seq_connect_from);
    ALSA_FN(snd_seq_disconnect_from);
    ALSA_FN(snd_seq_connect_to);
    ALSA_FN(snd_seq_disconnect_to);
    ALSA_FN(snd_seq_event_output_direct);
    ALSA_FN(snd_seq_event_input);
    ALSA_FN(snd_seq_event_input_pending);
    ALSA_FN(snd_seq_poll_descriptors_count);
    ALSA_FN(snd_seq_poll_descriptors);
    ALSA_FN(snd_seq_client_info_malloc);
    ALSA_FN(snd_seq_client_info_free);
    ALSA_FN(snd_seq_client_info_set_client);
    ALSA_FN(snd_seq_client_info_get_client);
    ALSA_FN(snd_seq_client_info_get_name);
    ALSA_FN(snd_seq_client_info_get_type);
    ALSA_FN(snd_seq_client_info_get_card);
    ALSA_FN(snd_seq_query_next_client);
    ALSA_FN(snd_seq_port_info_malloc);
    ALSA_FN(snd_seq_port_info_free);
    ALSA_FN(snd_seq_port_info_set_client);
    ALSA_FN(snd_seq_port_info_set_port);
    ALSA_FN(snd_seq_port_info_get_port);
    ALSA_FN(snd_seq_port_info_get_name);
    ALSA_FN(snd_seq_port_info_get_capability);
    ALSA_FN(snd_seq_query_next_port);
    ALSA_FN(snd_midi_event_new);
    ALSA_FN(snd_midi_event_free);
    ALSA_FN(snd_midi_event_no_status);
    ALSA_FN(snd_midi_event_reset_encode);
    ALSA_FN(snd_midi_event_reset_decode);
    ALSA_FN(snd_midi_event_encode);
    ALSA_FN(snd_midi_event_decode);
    ALSA_FN(snd_strerror);
#undef ALSA_FN
};

/** The largest message in or out, big enough for a whole SysEx dump. */
constexpr size_t kMaxMessage = 65536;

Alsa alsa;
std::mutex lock;
snd_seq_t *seq = nullptr;
int ownClient = -1;
int inPort = -1;  // where incoming messages arrive
int outPort = -1; // where we send from
snd_midi_event_t *encoder = nullptr;
snd_midi_event_t *decoder = nullptr;

bool loadAlsa() {
    static bool loaded = [] {
        void *lib = dlopen("libasound.so.2", RTLD_NOW | RTLD_LOCAL);
        if (lib == nullptr) {
            LOGW("no libasound: %s", dlerror());
            return false;
        }
        bool all = true;
#define ALSA_LOAD(name)                                                     \
    alsa.name = reinterpret_cast<decltype(alsa.name)>(dlsym(lib, #name)); \
    if (alsa.name == nullptr) {                                             \
        LOGW("libasound has no %s", #name);                               \
        all = false;                                                        \
    }
        ALSA_LOAD(snd_seq_open)
        ALSA_LOAD(snd_seq_close)
        ALSA_LOAD(snd_seq_set_client_name)
        ALSA_LOAD(snd_seq_client_id)
        ALSA_LOAD(snd_seq_create_simple_port)
        ALSA_LOAD(snd_seq_connect_from)
        ALSA_LOAD(snd_seq_disconnect_from)
        ALSA_LOAD(snd_seq_connect_to)
        ALSA_LOAD(snd_seq_disconnect_to)
        ALSA_LOAD(snd_seq_event_output_direct)
        ALSA_LOAD(snd_seq_event_input)
        ALSA_LOAD(snd_seq_event_input_pending)
        ALSA_LOAD(snd_seq_poll_descriptors_count)
        ALSA_LOAD(snd_seq_poll_descriptors)
        ALSA_LOAD(snd_seq_client_info_malloc)
        ALSA_LOAD(snd_seq_client_info_free)
        ALSA_LOAD(snd_seq_client_info_set_client)
        ALSA_LOAD(snd_seq_client_info_get_client)
        ALSA_LOAD(snd_seq_client_info_get_name)
        ALSA_LOAD(snd_seq_client_info_get_type)
        ALSA_LOAD(snd_seq_client_info_get_card)
        ALSA_LOAD(snd_seq_query_next_client)
        ALSA_LOAD(snd_seq_port_info_malloc)
        ALSA_LOAD(snd_seq_port_info_free)
        ALSA_LOAD(snd_seq_port_info_set_client)
        ALSA_LOAD(snd_seq_port_info_set_port)
        ALSA_LOAD(snd_seq_port_info_get_port)
        ALSA_LOAD(snd_seq_port_info_get_name)
        ALSA_LOAD(snd_seq_port_info_get_capability)
        ALSA_LOAD(snd_seq_query_next_port)
        ALSA_LOAD(snd_midi_event_new)
        ALSA_LOAD(snd_midi_event_free)
        ALSA_LOAD(snd_midi_event_no_status)
        ALSA_LOAD(snd_midi_event_reset_encode)
        ALSA_LOAD(snd_midi_event_reset_decode)
        ALSA_LOAD(snd_midi_event_encode)
        ALSA_LOAD(snd_midi_event_decode)
        ALSA_LOAD(snd_strerror)
#undef ALSA_LOAD
        return all;
    }();
    return loaded;
}

void closeLocked() {
    if (encoder != nullptr) alsa.snd_midi_event_free(encoder);
    if (decoder != nullptr) alsa.snd_midi_event_free(decoder);
    encoder = decoder = nullptr;
    if (seq != nullptr) alsa.snd_seq_close(seq);
    seq = nullptr;
    ownClient = inPort = outPort = -1;
}

} // namespace

extern "C" {

/** Open the sequencer as a client called "Acidulous". Returns its client number, or negative on failure. */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeOpen(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    if (seq != nullptr) return ownClient;
    if (!loadAlsa()) return -1;
    int err = alsa.snd_seq_open(&seq, "default", SND_SEQ_OPEN_DUPLEX, 0);
    if (err < 0) {
        LOGW("no sequencer: %s", alsa.snd_strerror(err));
        seq = nullptr;
        return -1;
    }
    alsa.snd_seq_set_client_name(seq, "Acidulous");
    ownClient = alsa.snd_seq_client_id(seq);
    // Not open to other programs. Incoming messages are sorted by the port
    // they came from, so one we didn't connect to would go nowhere.
    const unsigned type = SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION;
    inPort = alsa.snd_seq_create_simple_port(seq, "in",
        SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE | SND_SEQ_PORT_CAP_NO_EXPORT, type);
    outPort = alsa.snd_seq_create_simple_port(seq, "out",
        SND_SEQ_PORT_CAP_READ | SND_SEQ_PORT_CAP_SUBS_READ | SND_SEQ_PORT_CAP_NO_EXPORT, type);
    if (inPort < 0 || outPort < 0 ||
        alsa.snd_midi_event_new(kMaxMessage, &encoder) < 0 || alsa.snd_midi_event_new(kMaxMessage, &decoder) < 0) {
        LOGW("could not set up the sequencer's ports");
        closeLocked();
        return -1;
    }
    // Always include the status byte. The hub doesn't handle running status.
    alsa.snd_midi_event_no_status(decoder, 1);
    LOGI("sequencer open as client %d", ownClient);
    return ownClient;
}

/**
 * Every port as seven strings in a row: client, port, capabilities, client
 * type (1 user, 2 kernel), card (-1 for none), client name, port name.
 * AlsaSeq.kt decides which ones are instruments.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativePorts(JNIEnv *env, jclass) {
    std::vector<std::string> out;
    {
        std::lock_guard<std::mutex> guard(lock);
        if (seq != nullptr) {
            snd_seq_client_info_t *client = nullptr;
            snd_seq_port_info_t *port = nullptr;
            alsa.snd_seq_client_info_malloc(&client);
            alsa.snd_seq_port_info_malloc(&port);
            alsa.snd_seq_client_info_set_client(client, -1);
            while (alsa.snd_seq_query_next_client(seq, client) >= 0) {
                const int c = alsa.snd_seq_client_info_get_client(client);
                alsa.snd_seq_port_info_set_client(port, c);
                alsa.snd_seq_port_info_set_port(port, -1);
                while (alsa.snd_seq_query_next_port(seq, port) >= 0) {
                    out.push_back(std::to_string(c));
                    out.push_back(std::to_string(alsa.snd_seq_port_info_get_port(port)));
                    out.push_back(std::to_string(alsa.snd_seq_port_info_get_capability(port)));
                    out.push_back(std::to_string(static_cast<int>(alsa.snd_seq_client_info_get_type(client))));
                    out.push_back(std::to_string(alsa.snd_seq_client_info_get_card(client)));
                    out.push_back(alsa.snd_seq_client_info_get_name(client));
                    out.push_back(alsa.snd_seq_port_info_get_name(port));
                }
            }
            alsa.snd_seq_port_info_free(port);
            alsa.snd_seq_client_info_free(client);
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

/** Start or stop receiving from client:port. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeListen(JNIEnv *, jclass, jint client, jint port, jboolean on) {
    std::lock_guard<std::mutex> guard(lock);
    if (seq == nullptr) return JNI_FALSE;
    const int err = on ? alsa.snd_seq_connect_from(seq, inPort, client, port)
                       : alsa.snd_seq_disconnect_from(seq, inPort, client, port);
    if (err < 0 && on) LOGW("could not listen to %d:%d: %s", client, port, alsa.snd_strerror(err));
    else LOGI("%s %d:%d", on ? "listening to" : "no longer listening to", client, port);
    return err >= 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Subscribe to or unsubscribe from client:port for sending. Needed even
 * though every message names its destination, because a hardware port only
 * opens its device's output while something is subscribed. Otherwise sends
 * fail with "No such device" (the Launchpad Pro never gets switched to
 * programmer mode, for example).
 */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeSpeak(JNIEnv *, jclass, jint client, jint port, jboolean on) {
    std::lock_guard<std::mutex> guard(lock);
    if (seq == nullptr) return JNI_FALSE;
    const int err = on ? alsa.snd_seq_connect_to(seq, outPort, client, port)
                       : alsa.snd_seq_disconnect_to(seq, outPort, client, port);
    if (err < 0 && on) LOGW("could not connect to %d:%d: %s", client, port, alsa.snd_strerror(err));
    else LOGI("%s %d:%d", on ? "sending to" : "no longer sending to", client, port);
    return err >= 0 ? JNI_TRUE : JNI_FALSE;
}

/** Send complete messages to client:port right away. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeSend(JNIEnv *env, jclass, jint client, jint port,
                                                  jbyteArray bytes, jint offset, jint count) {
    if (count <= 0) return JNI_TRUE;
    std::vector<unsigned char> data(static_cast<size_t>(count));
    env->GetByteArrayRegion(bytes, offset, count, reinterpret_cast<jbyte *>(data.data()));
    std::lock_guard<std::mutex> guard(lock);
    if (seq == nullptr) return JNI_FALSE;
    alsa.snd_midi_event_reset_encode(encoder);
    const unsigned char *at = data.data();
    long left = count;
    bool ok = true;
    while (left > 0) {
        snd_seq_event_t ev;
        snd_seq_ev_clear(&ev);
        const long used = alsa.snd_midi_event_encode(encoder, at, left, &ev);
        if (used <= 0) break;
        at += used;
        left -= used;
        if (ev.type == SND_SEQ_EVENT_NONE) continue; // more bytes to come
        snd_seq_ev_set_source(&ev, outPort);
        snd_seq_ev_set_dest(&ev, client, port);
        snd_seq_ev_set_direct(&ev);
        const int err = alsa.snd_seq_event_output_direct(seq, &ev);
        if (err < 0) {
            // Log once per destination and error, not on every clock tick.
            static int lastClient = -1, lastPort = -1, lastErr = 0;
            if (client != lastClient || port != lastPort || err != lastErr) {
                LOGW("could not send to %d:%d: %s", client, port, alsa.snd_strerror(err));
                lastClient = client; lastPort = port; lastErr = err;
            }
            ok = false;
        }
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Read the next incoming message into [out] and return its length, with the
 * source client and port in from[0] and from[1]. Returns 0 if nothing came
 * within [timeoutMs] and negative once the sequencer has closed.
 */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeRead(JNIEnv *env, jclass, jbyteArray out, jintArray from, jint timeoutMs) {
    snd_seq_t *handle = seq;
    if (handle == nullptr) return -1;
    // Check what alsa-lib has already fetched first, since poll doesn't see it.
    if (alsa.snd_seq_event_input_pending(handle, 0) <= 0) {
        const int n = alsa.snd_seq_poll_descriptors_count(handle, POLLIN);
        std::vector<pollfd> fds(static_cast<size_t>(n));
        alsa.snd_seq_poll_descriptors(handle, fds.data(), static_cast<unsigned>(n), POLLIN);
        if (poll(fds.data(), static_cast<nfds_t>(n), timeoutMs) <= 0) return 0;
    }
    snd_seq_event_t *ev = nullptr;
    if (alsa.snd_seq_event_input(handle, &ev) < 0 || ev == nullptr) return 0;
    static unsigned char buffer[kMaxMessage]; // only the reading thread uses it
    alsa.snd_midi_event_reset_decode(decoder);
    const long length = alsa.snd_midi_event_decode(decoder, buffer, sizeof buffer, ev);
    if (length <= 0) return 0; // not MIDI, e.g. an announcement or timer tick
    const jint source[2] = {ev->source.client, ev->source.port};
    env->SetIntArrayRegion(from, 0, 2, source);
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(buffer));
    return static_cast<jint>(length);
}

} // extern "C"
