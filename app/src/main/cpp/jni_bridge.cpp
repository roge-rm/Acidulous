#include "EngineHost.h"
#include <android/log.h>
#include <cstdio>
#include <engine/effect/EffectRegistry.h>
#include <engine/eventor/EventorRegistry.h>
#include <engine/machine/MachineRegistry.h>
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
Java_com_rm_acidulous_engine_NativeEngine_nativeUnmountMachine(JNIEnv *, jobject, jint rackId) {
    host().unmountMachine(rackId);
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMountEffect(JNIEnv *env, jobject, jint rackId, jint slot, jstring typeName) {
    return host().mountEffect(rackId, slot, toStdString(env, typeName)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeControlChange(JNIEnv *, jobject, jint rackId, jint cc, jint value) {
    host().controlChange(rackId, static_cast<uint8_t>(cc), static_cast<uint8_t>(value));
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeChannelPressure(JNIEnv *, jobject, jint rackId, jint value) {
    host().channelPressure(rackId, static_cast<uint8_t>(value));
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativePrewarm(JNIEnv *, jobject) { acidulous::EngineHost::prewarm(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMountEventor(JNIEnv *env, jobject, jint rackId, jint slot, jstring typeName) {
    return host().mountEventor(rackId, slot, toStdString(env, typeName)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeEventorTypes(JNIEnv *env, jobject) {
    const int32_t n = acidulous::EventorRegistry::count();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    for (int32_t i = 0; i < n; ++i) {
        jstring s = env->NewStringUTF(acidulous::EventorRegistry::name(i));
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeEffectTypes(JNIEnv *env, jobject) {
    const int32_t n = acidulous::EffectRegistry::count();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    for (int32_t i = 0; i < n; ++i) {
        jstring s = env->NewStringUTF(acidulous::EffectRegistry::name(i));
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMachineTypes(JNIEnv *env, jobject) {
    const int32_t n = acidulous::MachineRegistry::count();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    for (int32_t i = 0; i < n; ++i) {
        jstring s = env->NewStringUTF(acidulous::MachineRegistry::name(i));
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSoundFontPresets(JNIEnv *env, jobject, jstring path) {
    std::string error;
    const std::string list = acidulous::EngineHost::soundFontPresets(toStdString(env, path), error);
    return env->NewStringUTF(error.empty() ? list.c_str() : ("!" + error).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadSoundFont(JNIEnv *env, jobject, jint rackId, jstring path,
                                                              jint presetIndex) {
    std::string error;
    host().loadSoundFont(rackId, toStdString(env, path), presetIndex, error);
    return env->NewStringUTF(error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadZoneMap(JNIEnv *env, jobject, jint rackId, jstring spec,
                                                            jstring name) {
    std::string error;
    host().loadZoneMap(rackId, toStdString(env, spec), toStdString(env, name), error);
    return env->NewStringUTF(error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSampleMapInfo(JNIEnv *env, jobject, jint rackId) {
    return env->NewStringUTF(host().sampleMapInfo(rackId).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadSample(JNIEnv *env, jobject, jint rackId, jint slot, jstring path) {
    std::string error;
    const bool ok = host().loadSample(rackId, slot, toStdString(env, path), error);
    return env->NewStringUTF(ok ? "" : error.c_str()); // empty = success
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSampleInfo(JNIEnv *env, jobject, jint rackId, jint slot) {
    return env->NewStringUTF(host().sampleInfo(rackId, slot).c_str());
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

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativePanic(JNIEnv *, jobject) { host().panic(); }

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadNexusPatch(JNIEnv *env, jobject, jint rack, jstring spec) {
    const char *chars = env->GetStringUTFChars(spec, nullptr);
    const std::string result = host().loadNexusPatch(rack, chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(spec, chars);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeNexusPalette(JNIEnv *env, jobject) {
    return env->NewStringUTF(host().nexusPalette().c_str());
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeNexusScope(JNIEnv *env, jobject, jint rack, jfloatArray out) {
    const jsize max = env->GetArrayLength(out);
    if (max <= 0) return 0;
    jfloat *data = env->GetFloatArrayElements(out, nullptr);
    const int32_t n = host().nexusScope(rack, data, static_cast<int32_t>(max));
    env->ReleaseFloatArrayElements(out, data, 0);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStartInput(JNIEnv *, jobject) {
    return host().startInput() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStopInput(JNIEnv *, jobject) { host().stopInput(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeInputRunning(JNIEnv *, jobject) {
    return host().inputRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeInputPeak(JNIEnv *, jobject) { return host().inputPeak(); }

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetInputGain(JNIEnv *, jobject, jfloat g) {
    host().setInputGain(g);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetMonitorLevel(JNIEnv *, jobject, jfloat level) {
    host().setMonitorLevel(level);
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStartCapture(JNIEnv *env, jobject, jstring path, jint source) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    const std::string result = host().startCapture(chars ? chars : "", source);
    if (chars) env->ReleaseStringUTFChars(path, chars);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeStopCapture(JNIEnv *, jobject) { host().stopCapture(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeCapturing(JNIEnv *, jobject) {
    return host().capturing() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeCapturedSeconds(JNIEnv *, jobject) {
    return host().capturedSeconds();
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeCapturedPeak(JNIEnv *, jobject) { return host().capturedPeak(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeCaptureOverflowed(JNIEnv *, jobject) {
    return host().captureOverflowed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMidiEvent(JNIEnv *, jobject, jint rackId, jint status,
                                                   jint d1, jint d2) {
    host().midiEvent(rackId, static_cast<uint8_t>(status), static_cast<uint8_t>(d1 & 0x7f),
                     static_cast<uint8_t>(d2 & 0x7f));
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetParam(JNIEnv *env, jobject, jint rackId,
                                                  jstring unit, jstring name, jfloat value, jboolean record) {
    return host().setParam(rackId, toStdString(env, unit), toStdString(env, name), value, record == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetSampleRate(JNIEnv *, jobject) {
    return host().sampleRate();
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadFormula(JNIEnv *env, jobject, jint rack, jstring formula,
                                                            jstring arp, jstring duty, jstring vol) {
    auto str = [&](jstring s) {
        if (s == nullptr) return std::string();
        const char *p = env->GetStringUTFChars(s, nullptr);
        std::string out(p);
        env->ReleaseStringUTFChars(s, p);
        return out;
    };
    return env->NewStringUTF(host().loadFormula(rack, str(formula), str(arp), str(duty), str(vol)).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeBuildCloud(JNIEnv *env, jobject, jint rack, jfloatArray spectrum) {
    std::vector<jfloat> v;
    if (spectrum != nullptr) {
        const jsize n = env->GetArrayLength(spectrum);
        v.resize(static_cast<size_t>(n));
        if (n > 0) env->GetFloatArrayRegion(spectrum, 0, n, v.data());
    }
    return env->NewStringUTF(host().buildCloud(rack, v.empty() ? nullptr : v.data(),
                                               static_cast<int32_t>(v.size())).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeFreezeClip(JNIEnv *env, jobject, jint rack, jlong sceneId,
                                                           jstring path, jfloat tailSeconds) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    int32_t frames = 0, ticks = 0;
    float bpm = 0.0f, peak = 0.0f;
    const std::string err = host().freezeClip(rack, sceneId, p, tailSeconds, frames, ticks, bpm, peak);
    env->ReleaseStringUTFChars(path, p);
    // One string, because the alternative is five calls that can disagree:
    // "ok|frames|ticks|bpm|peak", or the reason it did not happen.
    char out[160];
    if (err.empty()) {
        std::snprintf(out, sizeof(out), "ok|%d|%d|%.6f|%.6f", frames, ticks, bpm, peak);
    } else {
        std::snprintf(out, sizeof(out), "%s", err.c_str());
    }
    return env->NewStringUTF(out);
}

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeLoadFrozen(JNIEnv *env, jobject, jint rack, jlongArray sceneIds,
                                                           jobjectArray paths, jfloatArray bpms, jintArray ticks) {
    const jsize n = env->GetArrayLength(sceneIds);
    std::vector<std::pair<int64_t, std::string>> clips;
    std::vector<float> bpmList;
    std::vector<int32_t> tickList;
    std::vector<jlong> ids(static_cast<size_t>(n));
    if (n > 0) env->GetLongArrayRegion(sceneIds, 0, n, ids.data());
    std::vector<jfloat> bs(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(bpms, 0, n, bs.data());
    std::vector<jint> ts(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(ticks, 0, n, ts.data());
    for (jsize i = 0; i < n; ++i) {
        auto str = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char *p = env->GetStringUTFChars(str, nullptr);
        clips.emplace_back(static_cast<int64_t>(ids[static_cast<size_t>(i)]), std::string(p));
        env->ReleaseStringUTFChars(str, p);
        env->DeleteLocalRef(str);
        bpmList.push_back(bs[static_cast<size_t>(i)]);
        tickList.push_back(ts[static_cast<size_t>(i)]);
    }
    return env->NewStringUTF(host().loadFrozenSet(rack, clips, bpmList, tickList).c_str());
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetBufferBursts(JNIEnv *, jobject, jint bursts) {
    host().setBufferBursts(bursts);
}

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeBufferFrames(JNIEnv *, jobject) {
    return host().bufferFrames();
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetVoiceLimit(JNIEnv *, jobject, jint notes) {
    host().setVoiceLimit(notes);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetQuality(JNIEnv *, jobject, jint level) {
    host().setQuality(level);
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetRecordBits(JNIEnv *, jobject, jint bits) {
    host().setRecordBits(bits);
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

JNIEXPORT jstring JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeRenderSong(JNIEnv *env, jobject, jstring path, jfloat tailSeconds) {
    std::string error;
    const bool ok = host().renderSong(toStdString(env, path), tailSeconds, error);
    return env->NewStringUTF(ok ? "" : error.c_str());
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeCancelRender(JNIEnv *, jobject) { host().cancelRender(); }

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsRendering(JNIEnv *, jobject) { return host().isRendering() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeRenderedSeconds(JNIEnv *, jobject) { return host().renderedSeconds(); }

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeRenderedPeak(JNIEnv *, jobject) { return host().renderedPeak(); }

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeSetStopAtEnd(JNIEnv *, jobject, jboolean on) {
    host().setStopAtEnd(on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeIsStopAtEndArmed(JNIEnv *, jobject) {
    return host().isStopAtEndArmed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeQueueScene(JNIEnv *, jobject, jint idx) { host().queueScene(idx); }

JNIEXPORT jint JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeQueuedScene(JNIEnv *, jobject) { return host().queuedScene(); }

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
    const int maxEvents = static_cast<int>(len / 5);
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
Java_com_rm_acidulous_engine_NativeEngine_nativeSnapshotSetLane(JNIEnv *env, jobject, jlong handle, jint rack,
                                                                jint scene, jstring machineType, jstring unit,
                                                                jstring name, jboolean linear, jfloatArray points) {
    const jsize len = points != nullptr ? env->GetArrayLength(points) : 0;
    const int count = static_cast<int>(len / 2);
    jfloat *data = count > 0 ? env->GetFloatArrayElements(points, nullptr) : nullptr;
    const bool ok = host().snapshotSetLane(handle, rack, scene, toStdString(env, machineType), toStdString(env, unit),
                                           toStdString(env, name), linear == JNI_TRUE, data, count);
    if (data != nullptr) env->ReleaseFloatArrayElements(points, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMachineParamNames(JNIEnv *env, jobject, jstring type) {
    int32_t n = 0;
    const acidulous::ParamDef *defs = acidulous::MachineRegistry::paramDefs(toStdString(env, type).c_str(), n);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    for (int32_t i = 0; i < n; ++i) {
        jstring s = env->NewStringUTF(defs[i].name);
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

// "name|min|max|def|curve|steps|unit" per parameter; curve 0 linear, 1 exponential, 2 stepped.
static jobjectArray paramInfoArray(JNIEnv *env, const acidulous::ParamDef *defs, int32_t n) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    char buf[160];
    for (int32_t i = 0; i < n; ++i) {
        const auto &d = defs[i];
        snprintf(buf, sizeof(buf), "%s|%g|%g|%g|%d|%d|%s", d.name, d.min, d.max, d.def, static_cast<int>(d.curve), d.steps, d.unit);
        jstring s = env->NewStringUTF(buf);
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeMachineParamInfo(JNIEnv *env, jobject, jstring type) {
    int32_t n = 0;
    const acidulous::ParamDef *defs = acidulous::MachineRegistry::paramDefs(toStdString(env, type).c_str(), n);
    return paramInfoArray(env, defs, n);
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeEventorParamInfo(JNIEnv *env, jobject, jstring type) {
    int32_t n = 0;
    const acidulous::ParamDef *defs = acidulous::EventorRegistry::paramDefs(toStdString(env, type).c_str(), n);
    return paramInfoArray(env, defs, n);
}

JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeEffectParamInfo(JNIEnv *env, jobject, jstring type) {
    int32_t n = 0;
    const acidulous::ParamDef *defs = acidulous::EffectRegistry::paramDefs(toStdString(env, type).c_str(), n);
    return paramInfoArray(env, defs, n);
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
Java_com_rm_acidulous_engine_NativeEngine_nativeReadRackPeak(JNIEnv *, jobject, jint rackId) {
    return host().rackPeak(rackId);
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeGetMasterFade(JNIEnv *, jobject) {
    return host().masterFade();
}

JNIEXPORT jfloat JNICALL
Java_com_rm_acidulous_engine_NativeEngine_nativeParamNormalized(JNIEnv *env, jobject, jint rackId, jstring unit, jstring name) {
    return host().paramNormalized(rackId, toStdString(env, unit), toStdString(env, name));
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
