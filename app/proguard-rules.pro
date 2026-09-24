# What R8 must leave alone when it shrinks the release build.
#
# The engine is reached through JNI: the C++ side exports functions named
# Java_com_rm_acidulous_engine_NativeEngine_<method>, which bind by name, so
# the class and its native methods keep theirs. Nothing in the engine calls
# back into our classes by name (it looks up java/lang/String and nothing
# else), and the app uses no reflection. kotlinx.serialization and Compose
# ship their own rules.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.rm.acidulous.engine.NativeEngine { *; }

# Readable crash reports: keep line numbers, and the file names they belong to.
-keepattributes SourceFile,LineNumberTable
