#include "EngineHost.h"
#include <android/log.h>
#include <jni.h>
#include <string>

#define LOG_TAG "Acidulous.JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

acidulous::EngineHost &host() {
    return acidulous::EngineHost::instance();
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStart(JNIEnv *, jobject) {
    return host().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStop(JNIEnv *, jobject) {
    host().stop();
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsRunning(JNIEnv *, jobject) {
    return host().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMountMachine(JNIEnv *env, jobject,
                                                      jint rackId, jstring typeName) {
    return host().mountMachine(rackId, toStdString(env, typeName)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeNoteOn(JNIEnv *, jobject,
                                                jint rackId, jint note, jint velocity) {
    host().noteOn(rackId, static_cast<uint8_t>(note & 0x7f),
                  static_cast<uint8_t>(velocity & 0x7f));
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeNoteOff(JNIEnv *, jobject, jint rackId, jint note) {
    host().noteOff(rackId, static_cast<uint8_t>(note & 0x7f));
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetParam(JNIEnv *env, jobject, jint rackId,
                                                  jstring unit, jstring name, jfloat value) {
    return host().setParam(rackId, toStdString(env, unit), toStdString(env, name), value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetSampleRate(JNIEnv *, jobject) {
    return host().sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetFramesPerBurst(JNIEnv *, jobject) {
    return host().framesPerBurst();
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsLowLatency(JNIEnv *, jobject) {
    return host().lowLatency() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetXRunCount(JNIEnv *, jobject) {
    return static_cast<jlong>(host().xRunCount());
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetLoadAvg(JNIEnv *, jobject) {
    return host().loadPercent();
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeReadPeakLevel(JNIEnv *, jobject) {
    return host().peakLevel();
}

// --- Transport ---------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeTransportPlay(JNIEnv *, jobject, jint sceneIdx) {
    host().transportPlay(sceneIdx);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeTransportStop(JNIEnv *, jobject) { host().transportStop(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsPlaying(JNIEnv *, jobject) {
    return host().isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetLoopScene(JNIEnv *, jobject, jboolean on) {
    host().setLoopScene(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetLoopSong(JNIEnv *, jobject, jboolean on) {
    host().setLoopSong(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetRecordArmed(JNIEnv *, jobject, jboolean on) {
    host().setRecordArmed(on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsRecordArmed(JNIEnv *, jobject) {
    return host().isRecordArmed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeDrainRecorded(JNIEnv *env, jobject, jlongArray out) {
    if (out == nullptr) {
        return 0;
    }
    const jsize len = env->GetArrayLength(out);
    const int maxEvents = static_cast<int>(len / 4);
    if (maxEvents <= 0) {
        return 0;
    }
    jlong *data = env->GetLongArrayElements(out, nullptr);
    if (data == nullptr) {
        return 0;
    }
    const int n = host().drainRecorded(reinterpret_cast<int64_t *>(data), maxEvents);
    env->ReleaseLongArrayElements(out, data, 0); // copy back
    return n;
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetRecordedDropped(JNIEnv *, jobject) {
    return static_cast<jint>(host().recordedDropped());
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetTempo(JNIEnv *, jobject, jfloat bpm) { host().setTempo(bpm); }

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetTempo(JNIEnv *, jobject) { return host().tempo(); }

JNIEXPORT jlong JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetPositionPacked(JNIEnv *, jobject) {
    return static_cast<jlong>(host().positionPacked());
}

// --- Song snapshot builder -----------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotBegin(JNIEnv *, jobject) {
    return static_cast<jlong>(host().snapshotBegin());
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotAddScene(JNIEnv *, jobject, jlong handle, jlong sceneId,
                                                                 jint ticksPerBar, jint repeat, jfloat bpmOverride,
                                                                 jboolean smooth, jboolean fadeIn, jboolean fadeOut) {
    return host().snapshotAddScene(handle, sceneId, ticksPerBar, repeat, bpmOverride, smooth == JNI_TRUE,
                                   fadeIn == JNI_TRUE, fadeOut == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotSetClipCached(JNIEnv *, jobject, jlong handle, jint rack,
                                                                      jint scene, jlong rev) {
    return host().snapshotSetClipCached(handle, rack, scene, rev) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotSetClip(JNIEnv *env, jobject, jlong handle, jint rack,
                                                                jint scene, jlong rev, jint bars, jint playMode,
                                                                jboolean mute, jintArray notes) {
    const jsize len = notes != nullptr ? env->GetArrayLength(notes) : 0;
    const int noteCount = static_cast<int>(len / 4);
    bool ok;
    if (noteCount > 0) {
        jint *data = env->GetIntArrayElements(notes, nullptr);
        if (data == nullptr) {
            return JNI_FALSE;
        }
        ok = host().snapshotSetClip(handle, rack, scene, rev, bars, playMode, mute == JNI_TRUE,
                                    reinterpret_cast<const int32_t *>(data), noteCount);
        env->ReleaseIntArrayElements(notes, data, JNI_ABORT); // read only; don't copy back
    } else {
        ok = host().snapshotSetClip(handle, rack, scene, rev, bars, playMode, mute == JNI_TRUE, nullptr, 0);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotCommit(JNIEnv *, jobject, jlong handle) {
    return host().snapshotCommit(handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotAbandon(JNIEnv *, jobject, jlong handle) {
    host().snapshotAbandon(handle);
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetNotesOn(JNIEnv *, jobject, jint rackId) {
    return static_cast<jint>(host().notesOn(rackId));
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeDebugParam(JNIEnv *env, jobject, jint rackId, jstring name) {
    return host().debugParam(rackId, toStdString(env, name));
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetNotesOff(JNIEnv *, jobject, jint rackId) {
    return static_cast<jint>(host().notesOff(rackId));
}

} // extern "C"
