/*
 * Acidulous.app/Contents/MacOS/Acidulous: starts the app's jars on the Java
 * runtime inside the bundle, with the engine's libraries in Contents/app.
 * It does what the Debian package's launcher script does (deb/acidulous),
 * but starts Java inside this process, through the runtime's libjli like
 * the java command does. That keeps the app's Info.plist in charge of the
 * process, which macOS reads for the Dock icon and the microphone prompt.
 *
 *   Contents/MacOS/Acidulous                  this
 *   Contents/runtime/Contents/Home            Eclipse Temurin's Java runtime
 *   Contents/app/libacidulous.dylib           the engine, and LAME's libmp3lame.dylib
 *   Contents/app/lib/*.jar                    the app
 *
 * If the JVM crashes (in the engine's native code, for example) its report
 * goes where the app looks for crash reports on the next start (Main.kt).
 */
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

/* JLI_Launch from the runtime's libjli, as declared in the JDK's java.h. */
typedef int (*JLI_Launch_t)(int argc, char **argv, int jargc, const char **jargv,
                            int appclassc, const char **appclassv,
                            const char *fullversion, const char *dotversion,
                            const char *pname, const char *lname,
                            unsigned char javaargs, unsigned char cpwildcard,
                            unsigned char javaw, int ergo);

/* Cuts [path] at its last slash, leaving the folder. */
static void parent(char *path) {
    char *slash = strrchr(path, '/');
    if (slash != NULL) *slash = 0;
}

/* mkdir -p */
static void makeDirs(const char *path) {
    char at[PATH_MAX];
    snprintf(at, sizeof at, "%s", path);
    for (char *p = at + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(at, 0755);
            *p = '/';
        }
    }
    mkdir(at, 0755);
}

static char *format(const char *fmt, const char *a) {
    size_t n = strlen(fmt) + strlen(a) + 1;
    char *out = malloc(n);
    snprintf(out, n, fmt, a);
    return out;
}

int main(int argc, char **argv) {
    char exe[PATH_MAX], contents[PATH_MAX];
    uint32_t size = sizeof exe;
    if (_NSGetExecutablePath(exe, &size) != 0 || realpath(exe, contents) == NULL) {
        fprintf(stderr, "Acidulous: could not find where it is installed\n");
        return 1;
    }
    parent(contents); /* Contents/MacOS */
    parent(contents); /* Contents */

    char jli[PATH_MAX];
    snprintf(jli, sizeof jli, "%s/runtime/Contents/Home/lib/libjli.dylib", contents);
    void *lib = dlopen(jli, RTLD_NOW | RTLD_GLOBAL);
    JLI_Launch_t launch = lib != NULL ? (JLI_Launch_t)dlsym(lib, "JLI_Launch") : NULL;
    if (launch == NULL) {
        fprintf(stderr, "Acidulous: could not load the Java runtime in %s: %s\n", jli, dlerror());
        return 1;
    }

    const char *home = getenv("HOME");
    char crashes[PATH_MAX] = "";
    if (home != NULL) {
        snprintf(crashes, sizeof crashes, "%s/Library/Application Support/Acidulous/data/crashes", home);
        makeDirs(crashes);
    }

    char **args = calloc((size_t)argc + 16, sizeof(char *));
    int n = 0;
    args[n++] = argv[0];
    if (crashes[0]) args[n++] = format("-XX:ErrorFile=%s/hs_err_%%p.log", crashes);
    args[n++] = format("-Djava.library.path=%s/app", contents);
    args[n++] = "--enable-native-access=ALL-UNNAMED";
    /* Light or dark title bar, following the system. */
    args[n++] = "-Dapple.awt.application.appearance=system";
    args[n++] = "-cp";
    args[n++] = format("%s/app/lib/*", contents);
    args[n++] = "com.rm.acidulous.desktop.MainKt";
    for (int i = 1; i < argc; i++) {
        /* Old Finders add a process serial number. It isn't the app's. */
        if (strncmp(argv[i], "-psn_", 5) == 0) continue;
        args[n++] = argv[i];
    }
    args[n] = NULL;
    /* Wildcards in the class path are expanded, like the java command does. */
    return launch(n, args, 0, NULL, 0, NULL, "", "", "java", "java", 0, 1, 0, 0);
}
