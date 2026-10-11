// Game controllers on Linux, read straight from /dev/input/event*, for
// desktop/src/.../Evdev.kt. Each call is one system call or close to it; the
// Kotlin side keeps the devices and decides what the events mean.
//
// A controller's event nodes are readable by the person at the screen on
// SteamOS and most desktops (udev's uaccess). Nothing here grabs a device, so
// Steam or the desktop still see it too.

#include <jni.h>

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

bool hasBit(const unsigned long *bits, int bit) {
    constexpr int kPer = static_cast<int>(sizeof(unsigned long) * 8);
    return (bits[bit / kPer] >> (bit % kPer)) & 1UL;
}

} // namespace

extern "C" {

/** Opens an event node for reading, or -1. */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeOpen(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    const int fd = open(p, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, p);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeClose(JNIEnv *, jclass, jint fd) {
    if (fd >= 0) close(fd);
}

/** The device's own name, as the kernel has it. */
JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeName(JNIEnv *env, jclass, jint fd) {
    char name[256] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) name[0] = 0;
    return env->NewStringUTF(name);
}

/**
 * Whether it's a game controller: it has the gamepad's south button (A), or a
 * joystick's trigger button, and at least one axis.
 */
JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeIsController(JNIEnv *, jclass, jint fd) {
    unsigned long keys[KEY_MAX / (sizeof(unsigned long) * 8) + 1] = {0};
    unsigned long abs[ABS_MAX / (sizeof(unsigned long) * 8) + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys) < 0) return JNI_FALSE;
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs)), abs) < 0) return JNI_FALSE;
    bool anyAxis = false;
    for (int a = 0; a <= ABS_MAX && !anyAxis; ++a) anyAxis = hasBit(abs, a);
    return (hasBit(keys, BTN_SOUTH) || hasBit(keys, BTN_TRIGGER)) && anyAxis ? JNI_TRUE : JNI_FALSE;
}

/**
 * Every axis the device has, four ints each: code, minimum, maximum and flat
 * (how far from centre counts as resting). Returns how many axes.
 */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeAxes(JNIEnv *env, jclass, jint fd, jintArray out) {
    unsigned long abs[ABS_MAX / (sizeof(unsigned long) * 8) + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs)), abs) < 0) return 0;
    const jsize room = env->GetArrayLength(out) / 4;
    jint found[4 * (ABS_MAX + 1)];
    int n = 0;
    for (int a = 0; a <= ABS_MAX && n < room; ++a) {
        if (!hasBit(abs, a)) continue;
        input_absinfo info{};
        if (ioctl(fd, EVIOCGABS(a), &info) < 0) continue;
        found[n * 4] = a;
        found[n * 4 + 1] = info.minimum;
        found[n * 4 + 2] = info.maximum;
        found[n * 4 + 3] = info.flat;
        ++n;
    }
    env->SetIntArrayRegion(out, 0, n * 4, found);
    return n;
}

/**
 * Waits up to timeoutMs for events and reads what's there as three ints each:
 * type, code and value. Returns how many events, 0 on a timeout, or -1 when
 * the device has gone (unplugged).
 */
JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeRead(JNIEnv *env, jclass, jint fd, jintArray out, jint timeoutMs) {
    pollfd p{fd, POLLIN, 0};
    const int ready = poll(&p, 1, timeoutMs);
    if (ready < 0) return errno == EINTR ? 0 : -1;
    if (ready == 0) return 0;
    if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    const jsize room = env->GetArrayLength(out) / 3;
    input_event events[64];
    const int want = room < 64 ? room : 64;
    const ssize_t got = read(fd, events, sizeof(input_event) * static_cast<size_t>(want));
    if (got < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    const int n = static_cast<int>(got / static_cast<ssize_t>(sizeof(input_event)));
    jint flat[64 * 3];
    for (int i = 0; i < n; ++i) {
        flat[i * 3] = events[i].type;
        flat[i * 3 + 1] = events[i].code;
        flat[i * 3 + 2] = events[i].value;
    }
    env->SetIntArrayRegion(out, 0, n * 3, flat);
    return n;
}

} // extern "C"

// Sends a HID feature report to a hidraw node: [data] is the report without
// its id, which is 0. Returns 0, or -1 if the node couldn't be opened or
// written. Opened and closed each time; it's sent every few seconds at most.
extern "C" JNIEXPORT jint JNICALL
Java_com_rm_acidulous_desktop_Evdev_nativeHidFeature(JNIEnv *env, jclass, jstring path, jbyteArray data) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDWR | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return -1;
    unsigned char buf[65] = {0};
    jsize n = env->GetArrayLength(data);
    if (n > 64) n = 64;
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte *>(buf + 1));
    int r = ioctl(fd, HIDIOCSFEATURE(sizeof buf), buf);
    close(fd);
    return r < 0 ? -1 : 0;
}
