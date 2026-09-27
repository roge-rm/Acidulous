/*
 * The platform half of the JNI headers, for building the engine for Windows
 * on Linux: jni.h itself is the same everywhere, and the cross compiler has
 * only Linux's JDK to take it from. What Windows' own jni_md.h says, in the
 * types this code already uses - jint is 32 bits and jlong 64 either way.
 */
#ifndef ACIDULOUS_WIN32_JNI_MD_H
#define ACIDULOUS_WIN32_JNI_MD_H

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
