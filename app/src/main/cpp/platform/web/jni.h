#pragma once
// JNI, as much of it as platform/android/jni_bridge.cpp uses, for the browser
// build: so the phone's bridge compiles to WebAssembly unchanged, and the page
// calls the very functions Android's NativeEngine does.
//
// A jstring is a std::string, a jfloatArray a std::vector<float> and so on,
// on the WebAssembly heap. Everything the bridge makes during a call - the
// strings it returns, the arrays it fills - goes into an arena, and so does
// everything the page makes to pass in; the page reads what it wants and
// then frees the arena (acid_jni_release, web_bridge.cpp). So DeleteLocalRef
// frees nothing, which is what the bridge assumes of it anyway: a local
// reference outlives nothing it still needs.
//
// **An arena a thread.** A call the page hands to one of the engine's threads
// (web_async.cpp) takes its arena with it - its arguments were made in it, and
// its results are made there - while the page goes on making and freeing its
// own. Each thread has one of its own until it is given another.

#include <cstdint>
#include <cstring>
#include <functional>
#include <memory>
#include <string>
#include <vector>

typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

#define JNI_FALSE 0
#define JNI_TRUE 1
#define JNI_ABORT 2
#define JNIEXPORT __attribute__((used, visibility("default")))
#define JNICALL

struct _jobject {
    virtual ~_jobject() = default;
};
struct _jclass : _jobject {};
struct _jstring : _jobject {
    std::string text;
};
struct _jarray : _jobject {
    virtual jsize size() const = 0;
};
template <class T> struct _jtypedArray : _jarray {
    std::vector<T> items;
    jsize size() const override { return static_cast<jsize>(items.size()); }
};
struct _jfloatArray : _jtypedArray<jfloat> {};
struct _jintArray : _jtypedArray<jint> {};
struct _jlongArray : _jtypedArray<jlong> {};
struct _jbyteArray : _jtypedArray<jbyte> {};
struct _jobjectArray : _jarray {
    std::vector<_jobject *> items;
    jsize size() const override { return static_cast<jsize>(items.size()); }
};

typedef _jobject *jobject;
typedef _jclass *jclass;
typedef _jstring *jstring;
typedef _jarray *jarray;
typedef _jfloatArray *jfloatArray;
typedef _jintArray *jintArray;
typedef _jlongArray *jlongArray;
typedef _jbyteArray *jbyteArray;
typedef _jobjectArray *jobjectArray;

namespace jniweb {
/** What a call has made, freed together when the page has read it. */
using Arena = std::vector<std::unique_ptr<_jobject>>;

/** This thread's arena: its own, or the one a call it is running brought. */
inline Arena *&current() {
    static thread_local Arena own;
    static thread_local Arena *in = nullptr;
    if (in == nullptr) in = &own;
    return in;
}
inline Arena &arena() { return *current(); }
/** Back to this thread's own. */
inline void useOwn() { current() = nullptr; }
template <class T> T *keep(T *made) {
    arena().emplace_back(made);
    return made;
}

/** A call handed to an engine thread: its arena, and its result once it has one. */
struct Ticket {
    Arena *arena;
    int32_t i = 0; // an int, a boolean, or a JNI object
    float f = 0.0f;
    int64_t j = 0;
    double d = 0.0;
};
/**
 * [work] on a thread of its own, in [arena] - which the page made the call's
 * arguments in - and the page told when it is done (web_bridge.cpp). The
 * page's own arena is its own again from here. The ticket is the handle.
 */
int runAsync(Arena *arena, std::function<void(Ticket &)> work);
} // namespace jniweb

struct JNIEnv_ {
    const char *GetStringUTFChars(jstring s, jboolean *copy) {
        if (copy != nullptr) *copy = JNI_FALSE;
        return s != nullptr ? s->text.c_str() : nullptr;
    }
    void ReleaseStringUTFChars(jstring, const char *) {}
    jstring NewStringUTF(const char *chars) {
        auto *s = jniweb::keep(new _jstring());
        s->text = chars != nullptr ? chars : "";
        return s;
    }

    jsize GetArrayLength(jarray a) { return a != nullptr ? a->size() : 0; }

#define ACID_JNI_ARRAY(Name, T, A)                                                                    \
    T *Get##Name##ArrayElements(A *a, jboolean *copy) {                                  \
        if (copy != nullptr) *copy = JNI_FALSE;                                                     \
        return a != nullptr ? a->items.data() : nullptr;                                             \
    }                                                                                               \
    void Release##Name##ArrayElements(A *, T *, jint) {}                                 \
    void Get##Name##ArrayRegion(A *a, jsize start, jsize len, T *buf) {                   \
        std::memcpy(buf, a->items.data() + start, static_cast<size_t>(len) * sizeof(T));             \
    }                                                                                               \
    void Set##Name##ArrayRegion(A *a, jsize start, jsize len, const T *buf) {             \
        std::memcpy(a->items.data() + start, buf, static_cast<size_t>(len) * sizeof(T));             \
    }                                                                                               \
    A *New##Name##Array(jsize len) {                                                      \
        auto *a = jniweb::keep(new A());                                                  \
        a->items.assign(static_cast<size_t>(len), T{});                                             \
        return a;                                                                                   \
    }
    // The element type and the array type apart: on 32-bit WebAssembly a C
    // `long` is 32 bits, and a jlong is not.
    ACID_JNI_ARRAY(Float, jfloat, _jfloatArray)
    ACID_JNI_ARRAY(Int, jint, _jintArray)
    ACID_JNI_ARRAY(Long, jlong, _jlongArray)
#undef ACID_JNI_ARRAY

    jclass FindClass(const char *) {
        static _jclass any;
        return &any;
    }
    jobjectArray NewObjectArray(jsize len, jclass, jobject initial) {
        auto *a = jniweb::keep(new _jobjectArray());
        a->items.assign(static_cast<size_t>(len), initial);
        return a;
    }
    jobject GetObjectArrayElement(jobjectArray a, jsize i) { return a->items[static_cast<size_t>(i)]; }
    void SetObjectArrayElement(jobjectArray a, jsize i, jobject value) { a->items[static_cast<size_t>(i)] = value; }
    void DeleteLocalRef(jobject) {}
};
typedef JNIEnv_ JNIEnv;
