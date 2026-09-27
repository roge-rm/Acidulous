/*
 * Acidulous.exe: the app's jars on the Java runtime installed beside it, with
 * the engine's DLLs in app\ - what the Debian package's launcher script does
 * (deb/acidulous), as a Windows program, so there is no console window and
 * the Start menu and the taskbar have the app's own icon (launcher.rc).
 *
 *   Acidulous.exe
 *   runtime\bin\javaw.exe      Eclipse Temurin's Java runtime
 *   app\acidulous.dll          the engine, and LAME's mp3lame.dll
 *   app\lib\*.jar              the app
 *
 * If the JVM itself crashes - the engine's native code, say - its report goes
 * where the app looks for crash reports on the next start (Main.kt).
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlobj.h>
#include <wchar.h>

static void fail(const wchar_t *what) {
    MessageBoxW(NULL, what, L"Acidulous", MB_OK | MB_ICONERROR);
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
    (void)instance; (void)previous; (void)show;
    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) { fail(L"Could not find where Acidulous is installed."); return 1; }
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash != NULL) *slash = 0;

    wchar_t crashes[MAX_PATH] = L"";
    wchar_t appdata[MAX_PATH];
    if (GetEnvironmentVariableW(L"APPDATA", appdata, MAX_PATH) > 0) {
        _snwprintf(crashes, MAX_PATH, L"%ls\\Acidulous\\data\\crashes", appdata);
        crashes[MAX_PATH - 1] = 0;
        SHCreateDirectoryExW(NULL, crashes, NULL);
    }

    static wchar_t command[32768];
    int length = _snwprintf(command, 32768,
        L"\"%ls\\runtime\\bin\\javaw.exe\" %ls%ls%ls "
        L"\"-Djava.library.path=%ls\\app\" --enable-native-access=ALL-UNNAMED "
        L"-cp \"%ls\\app\\lib\\*\" com.rm.acidulous.desktop.MainKt %ls",
        dir,
        crashes[0] ? L"\"-XX:ErrorFile=" : L"", crashes, crashes[0] ? L"\\hs_err_%p.log\"" : L"",
        dir, dir, args ? args : L"");
    if (length < 0) { fail(L"The command line is too long."); return 1; }

    STARTUPINFOW startup = {0};
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process = {0};
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, 0, NULL, dir, &startup, &process)) {
        fail(L"Could not start Java. The runtime folder beside Acidulous.exe may be missing: try installing again.");
        return 1;
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}
