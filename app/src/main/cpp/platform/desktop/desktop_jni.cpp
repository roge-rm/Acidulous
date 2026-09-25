// What only the desktop build asks of the native side: the phone's questions
// go through jni_bridge.cpp, unchanged, and Android answers these ones itself
// (AudioManager lists its inputs).

#include <drivers/AudioDriver.h>
#include <jni.h>
#include <string>

extern "C" {

/** Every input as three strings in a row - its id, its name and the server's own name for it - for DesktopAudio. */
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

} // extern "C"
