// JNI calls only the desktop build uses. Everything shared goes through
// jni_bridge.cpp. On Android, AudioManager answers these instead.

#include <drivers/AudioDriver.h>
#include <jni.h>
#include <string>

extern "C" {

/** Every input as three strings in a row (id, name, the server's name for it) for DesktopAudio. */
JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_desktop_DesktopAudio_nativeInputs(JNIEnv *env, jclass) {
    const auto inputs = AudioDriver::listInputs();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(inputs.size() * 3), stringClass, nullptr);
    jsize at = 0;
    for (const auto &input : inputs) {
        for (const std::string &text : {std::to_string(input.id), input.name, input.key}) {
            jstring s = env->NewStringUTF(text.c_str());
            env->SetObjectArrayElement(out, at++, s);
            env->DeleteLocalRef(s);
        }
    }
    return out;
}

/** Every output, the same three strings in a row as nativeInputs. */
JNIEXPORT jobjectArray JNICALL
Java_com_rm_acidulous_desktop_DesktopAudio_nativeOutputs(JNIEnv *env, jclass) {
    const auto outputs = AudioDriver::listOutputs();
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(outputs.size() * 3), env->FindClass("java/lang/String"), nullptr);
    jsize at = 0;
    for (const auto &output : outputs) {
        for (const std::string &text : {std::to_string(output.id), output.name, output.key}) {
            jstring s = env->NewStringUTF(text.c_str());
            env->SetObjectArrayElement(out, at++, s);
            env->DeleteLocalRef(s);
        }
    }
    return out;
}

/** Play through this output from now on. 0 means the system default. */
JNIEXPORT void JNICALL
Java_com_rm_acidulous_desktop_DesktopAudio_nativeChooseOutput(JNIEnv *, jclass, jint id) {
    AudioDriver::chooseOutput(id);
}

} // extern "C"
