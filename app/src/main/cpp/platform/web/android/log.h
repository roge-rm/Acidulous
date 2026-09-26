#pragma once
// Android's log call, for the browser build: the engine's host code logs
// through __android_log_print, and here that is the browser's console, at the
// console's own level - so an engine starting is not shown as an error.
#include <cstdarg>
#include <cstdio>
#include <emscripten/emscripten.h>

enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };

inline int __android_log_print(int priority, const char *tag, const char *fmt, ...) {
    static const char kLevels[] = "??VDIWEF";
    char text[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(text, sizeof text, fmt, args);
    va_end(args);
    const int level = priority >= ANDROID_LOG_ERROR ? EM_LOG_ERROR
                      : priority == ANDROID_LOG_WARN ? EM_LOG_WARN
                      : priority == ANDROID_LOG_INFO ? EM_LOG_INFO
                                                     : EM_LOG_DEBUG;
    emscripten_log(EM_LOG_CONSOLE | level, "%c/%s: %s", priority >= 0 && priority < 8 ? kLevels[priority] : '?', tag, text);
    return 0;
}
