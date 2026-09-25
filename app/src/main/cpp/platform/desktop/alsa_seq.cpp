// MIDI on Linux through ALSA's sequencer, for AlsaSeq.kt: every port the
// sequencer knows - the kernel's for each USB or other hardware MIDI device,
// and those of other programs and of BlueZ's Bluetooth MIDI, which raw MIDI
// (Java Sound's) never sees.
//
// libasound is opened here rather than linked, as miniaudio opens it for the
// audio: the engine builds with nothing but the headers (third_party/alsa),
// and a machine without the library, or without the sequencer, is told so and
// falls back to Java Sound's raw MIDI.
//
// One sequencer handle, open for as long as the app runs: the reading thread
// only ever reads, and everything else - sends, the port list, connecting -
// takes the lock.

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

/** libasound's functions this uses, typed from its own declarations. */
struct Alsa {
#define ALSA_FN(name) decltype(&::name) name = nullptr
    ALSA_FN(snd_seq_open);
    ALSA_FN(snd_seq_close);
    ALSA_FN(snd_seq_set_client_name);
    ALSA_FN(snd_seq_client_id);
    ALSA_FN(snd_seq_create_simple_port);
    ALSA_FN(snd_seq_connect_from);
    ALSA_FN(snd_seq_disconnect_from);
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

/** The largest message either way: a SysEx dump, whole. */
constexpr size_t kMaxMessage = 65536;

Alsa alsa;
std::mutex lock;
snd_seq_t *seq = nullptr;
int ownClient = -1;
int inPort = -1;  // where what we are connected to arrives
int outPort = -1; // what we send from
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

/** Open the sequencer as a client called "Acidulous": its client number, or below nought when there is none. */
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
    // Ours alone: other programs connect to nothing here, because what comes
    // in is sorted by the port it came from and a stranger's would go nowhere.
    const unsigned type = SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION;
    inPort = alsa.snd_seq_create_simple_port(seq, "in",
        SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE | SND_SEQ_PORT_CAP_NO_EXPORT, type);
    outPort = alsa.snd_seq_create_simple_port(seq, "out",
        SND_SEQ_PORT_CAP_READ | SND_SEQ_PORT_CAP_NO_EXPORT, type);
    if (inPort < 0 || outPort < 0 ||
        alsa.snd_midi_event_new(kMaxMessage, &encoder) < 0 || alsa.snd_midi_event_new(kMaxMessage, &decoder) < 0) {
        LOGW("could not set up the sequencer's ports");
        closeLocked();
        return -1;
    }
    // Every message whole, status byte and all: the hub parses no running status.
    alsa.snd_midi_event_no_status(decoder, 1);
    LOGI("sequencer open as client %d", ownClient);
    return ownClient;
}

/**
 * Every port there is, seven strings in a row: client, port, capabilities,
 * client type (1 user, 2 kernel), card (-1 for none), client name, port name.
 * Which of them are instruments is AlsaSeq.kt's to decide.
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

/** Hear what client:port sends, or stop hearing it. */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeListen(JNIEnv *, jclass, jint client, jint port, jboolean on) {
    std::lock_guard<std::mutex> guard(lock);
    if (seq == nullptr) return JNI_FALSE;
    const int err = on ? alsa.snd_seq_connect_from(seq, inPort, client, port)
                       : alsa.snd_seq_disconnect_from(seq, inPort, client, port);
    if (err < 0 && on) LOGW("could not listen to %d:%d: %s", client, port, alsa.snd_strerror(err));
    return err >= 0 ? JNI_TRUE : JNI_FALSE;
}

/** Send whole messages to client:port, now. */
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
        if (alsa.snd_seq_event_output_direct(seq, &ev) < 0) ok = false;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * The next message that has come in, into [out]: its length, with the client
 * and port it came from in from[0] and from[1]; nought when nothing came
 * within [timeoutMs]; below nought once the sequencer has closed.
 */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_AlsaSeq_nativeRead(JNIEnv *env, jclass, jbyteArray out, jintArray from, jint timeoutMs) {
    snd_seq_t *handle = seq;
    if (handle == nullptr) return -1;
    // What alsa-lib has already fetched first: poll says nothing about that.
    if (alsa.snd_seq_event_input_pending(handle, 0) <= 0) {
        const int n = alsa.snd_seq_poll_descriptors_count(handle, POLLIN);
        std::vector<pollfd> fds(static_cast<size_t>(n));
        alsa.snd_seq_poll_descriptors(handle, fds.data(), static_cast<unsigned>(n), POLLIN);
        if (poll(fds.data(), static_cast<nfds_t>(n), timeoutMs) <= 0) return 0;
    }
    snd_seq_event_t *ev = nullptr;
    if (alsa.snd_seq_event_input(handle, &ev) < 0 || ev == nullptr) return 0;
    static unsigned char buffer[kMaxMessage]; // the one reading thread's
    alsa.snd_midi_event_reset_decode(decoder);
    const long length = alsa.snd_midi_event_decode(decoder, buffer, sizeof buffer, ev);
    if (length <= 0) return 0; // not MIDI: an announcement, a timer tick
    const jint source[2] = {ev->source.client, ev->source.port};
    env->SetIntArrayRegion(from, 0, 2, source);
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(buffer));
    return static_cast<jint>(length);
}

} // extern "C"
