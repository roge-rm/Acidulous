import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Acidulous on a Linux desktop: the shared app in a window, and the engine
// built by CMake from the same sources as the phone's (see native/).
//
//   ./gradlew :desktop:run       build the engine for this machine and start it
//   ./gradlew :desktop:debAmd64  a .deb for Debian Trixie on x86-64
//   ./gradlew :desktop:debArm64  a .deb for Raspberry Pi OS (Trixie, 64-bit)
//
// The packages run on the system's own Java 21 rather than carrying one, so
// all that differs between them is the engine's two libraries and Skia's.

kotlin { jvmToolchain(21) }

/** The version, from where it is set: app/build.gradle.kts, the one place it is written. */
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
// The 64-bit build's code: see how :app works it out from `release`.
val versionCode = (Regex("val release = (\\d+)").find(appGradle)!!.groupValues[1].toInt() * 10 + 2).toString()
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    val name = versionName
    val code = versionCode
    inputs.property("version", "$name ($code)")
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/rm/acidulous/desktop/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.rm.acidulous.desktop\n\ninternal const val VERSION_NAME = \"$name\"\ninternal const val VERSION_CODE = $code\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(buildInfo) }

/** The app's jars for one architecture: everything but Skia's native renderer is the same. */
val debAmd64Runtime: Configuration = configurations.create("debAmd64Runtime")
val debArm64Runtime: Configuration = configurations.create("debArm64Runtime")
val windowsX64Runtime: Configuration = configurations.create("windowsX64Runtime")

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.common)
    // Dispatchers.Main on the desktop is Swing's event thread; the app hops to
    // it (an export pushes the song from there), and without this it has none.
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(compose.desktop.currentOs)
    debAmd64Runtime(compose.desktop.linux_x64)
    debArm64Runtime(compose.desktop.linux_arm64)
    windowsX64Runtime(compose.desktop.windows_x64)
    testImplementation(libs.junit)
}

for (runtime in listOf(debAmd64Runtime, debArm64Runtime, windowsX64Runtime)) {
    runtime.extendsFrom(configurations.implementation.get())
    runtime.isCanBeConsumed = false
    // Resolved as the run classpath is, so :shared hands over its desktop jar.
    val from = configurations.runtimeClasspath.get().attributes
    runtime.attributes {
        for (key in from.keySet()) {
            @Suppress("UNCHECKED_CAST")
            attribute(key as Attribute<Any>, from.getAttribute(key)!!)
        }
    }
}

val nativeDir = layout.projectDirectory.dir("native")
/**
 * Where the engine's CMake builds go: with the rest of the build output under
 * this machine's build root when it has one (acidulous.buildRoot - see the
 * root build.gradle.kts), in native/ otherwise, as always.
 */
val nativeOut: File = providers.gradleProperty("acidulous.buildRoot").orNull
    ?.let { File(it, "${rootDir.name}/desktop-native") } ?: file("native")
fun nativeOutDir(name: String): Directory = layout.projectDirectory.dir(File(nativeOut, name).absolutePath)

/**
 * The compiler cache, where this machine has ccache: the engine is the same
 * C++ built for ten targets, and an unchanged file is a cache hit rather than
 * a compile. The build containers have their own ccache (their Dockerfiles),
 * each with a cache folder of its own here, mounted in: their versions differ
 * from this machine's.
 */
val ccache: Boolean = File("/usr/bin/ccache").canExecute()
val launcher = if (ccache) "-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache" else ""
/** The mounts and settings a build container needs: the build folder at its own path, and its compiler cache. */
fun containerArgs(image: String): String {
    val cache = File(System.getProperty("user.home"), ".cache/ccache-containers/$image")
    nativeOut.mkdirs()
    if (ccache) cache.mkdirs()
    return "-v '$nativeOut':'$nativeOut'" + if (ccache) " -v '$cache':/ccache -e CCACHE_DIR=/ccache" else ""
}

val nativeBuild = nativeOutDir("build")

/** The engine for this machine, as libacidulous.so (and LAME's libmp3lame.so beside it). */
val buildEngine = tasks.register<Exec>("buildEngine") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.file(nativeDir.file("CMakeLists.txt"))
    outputs.dir(nativeBuild)
    workingDir = nativeDir.asFile
    val out = nativeBuild.asFile.path
    commandLine("sh", "-c", "cmake -S . -B '$out' -DCMAKE_BUILD_TYPE=Release $launcher >/dev/null && cmake --build '$out' -j8")
}

/**
 * The engine for a Raspberry Pi, cross-compiled in a Debian Trixie container
 * (native/Dockerfile.arm64) so it is linked against the Pi's own glibc and
 * libstdc++. Needs Docker; the container only compiles, it runs no ARM code.
 */
val nativeArm64 = nativeOutDir("build-arm64")
val buildEngineArm64 = tasks.register<Exec>("buildEngineArm64") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.files(nativeDir.file("CMakeLists.txt"), nativeDir.file("aarch64-linux-gnu.cmake"), nativeDir.file("Dockerfile.arm64"))
    outputs.dir(nativeArm64)
    val root = rootProject.projectDir.absolutePath
    workingDir = nativeDir.asFile
    commandLine(
        "sh", "-c",
        "docker build -q -t acidulous-arm64-cross -f Dockerfile.arm64 . >/dev/null && " +
            "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("arm64-cross")} -w /src/desktop/native acidulous-arm64-cross sh -c '" +
            "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
            "cmake -S . -B ${nativeArm64.asFile.path} -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=aarch64-linux-gnu.cmake $launcher \"-DJNI_INCLUDE_DIRS=\$J;\$J/linux\" >/dev/null && " +
            "cmake --build ${nativeArm64.asFile.path} -j8'",
    )
}

/** miniaudio's licence, which is the foot of its header: public domain or MIT-0. */
val miniaudioLicence = tasks.register("miniaudioLicence") {
    val header = rootProject.file("app/src/main/cpp/third_party/miniaudio/miniaudio.h")
    val out = layout.buildDirectory.file("generated/miniaudio/miniaudio.txt")
    inputs.file(header)
    outputs.file(out)
    doLast {
        val text = header.readText()
        val start = text.indexOf("This software is available as a choice of the following licenses.")
        val end = text.lastIndexOf("*/")
        check(start >= 0 && end > start) { "no licence at the foot of miniaudio.h" }
        out.get().asFile.writeText(text.substring(start, end).trimEnd() + "\n")
    }
}

/**
 * The licence texts the About window shows, from where they live and under
 * the names the app gives them: the same as :app's stageLicences.
 */
val stageLicences = tasks.register<Sync>("stageLicences") {
    val app = rootProject.file("app/src/main/cpp/third_party")
    from(file("$app/lame/COPYING")) { rename { "lgpl-2.0.txt" } }
    from(file("$app/asio/LICENSE_1_0.txt")) { rename { "bsl-1.0.txt" } }
    from(rootProject.file("LICENSE")) { rename { "gpl-3.0.txt" } }
    from(rootProject.file("licences/Apache-2.0.txt")) { rename { "apache-2.0.txt" } }
    from(rootProject.file("licences/GPL-2.0.txt")) { rename { "gpl-2.0.txt" } }
    from(rootProject.file("licences/LGPL-2.1.txt")) { rename { "lgpl-2.1.txt" } }
    from(miniaudioLicence)
    into(layout.buildDirectory.dir("generated/licences/licences"))
}
sourceSets.main { resources.srcDir(stageLicences.map { it.destinationDir.parentFile }) }

compose.desktop {
    application {
        mainClass = "com.rm.acidulous.desktop.MainKt"
        jvmArgs += listOf("-Djava.library.path=${nativeBuild.asFile.absolutePath}", "--enable-native-access=ALL-UNNAMED")
    }
}

// --- The Debian packages ------------------------------------------------------
//
// Laid out as Debian lays out a Java program: the jars in /usr/lib/acidulous/lib,
// the engine in /usr/lib/acidulous/native, a launcher in /usr/bin, a menu
// entry and an icon. Built with dpkg-deb, owned by root, from a staged tree.

fun registerDeb(arch: String, runtime: Configuration, engine: TaskProvider<Exec>, engineDir: Directory) {
    val stage = layout.buildDirectory.dir("deb/$arch")
    val stageTask = tasks.register<Sync>("stageDeb${arch.replaceFirstChar { it.uppercase() }}") {
        dependsOn(engine)
        into(stage)
        // A jar reached by two paths through the dependencies is still one jar.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Debian's modes whatever the source had: the Gradle cache keeps jars
        // private to their owner, which installed would be root's alone.
        filePermissions { unix("rw-r--r--") }
        dirPermissions { unix("rwxr-xr-x") }
        from(tasks.named("jar")) { into("usr/lib/acidulous/lib") }
        from(listOf(engineDir.file("libacidulous.so"), engineDir.file("libmp3lame.so"))) { into("usr/lib/acidulous/native") }
        from(file("deb/acidulous")) {
            into("usr/bin")
            filePermissions { unix("rwxr-xr-x") }
        }
        from(file("deb/acidulous.desktop")) { into("usr/share/applications") }
        from(rootProject.file("branding/acidulous-icon-1024.svg")) {
            into("usr/share/icons/hicolor/scalable/apps")
            rename { "acidulous.svg" }
        }
        from(rootProject.file("NOTICE")) {
            into("usr/share/doc/acidulous")
            rename { "copyright" }
        }
    }
    val deb = layout.buildDirectory.file("deb/acidulous_${versionName}_$arch.deb")
    tasks.register("deb${arch.replaceFirstChar { it.uppercase() }}") {
        group = "distribution"
        description = "Builds acidulous_${versionName}_$arch.deb"
        dependsOn(stageTask)
        val root = stage.get().asFile
        val out = deb.get().asFile
        val template = file("deb/control")
        val version = versionName
        // The jars, each named with its group: Compose's own
        // "runtime-saveable-desktop" is a stub pointing at AndroidX's jar of
        // the same name, and by file name alone one of the two is lost - the
        // one with the classes, as it happened.
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        inputs.files(runtime)
        inputs.dir(stage)
        inputs.file(template)
        outputs.file(out)
        doLast {
            val lib = File(root, "usr/lib/acidulous/lib")
            for (a in artifacts.get()) {
                val id = a.id.componentIdentifier
                val name = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) "${id.group}-${a.file.name}" else a.file.name
                val to = File(lib, name)
                a.file.copyTo(to, overwrite = true)
                Files.setPosixFilePermissions(to.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            }
            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            val sizeKb = root.walkTopDown().filter { it.isFile && !it.path.contains("/DEBIAN/") }.sumOf { it.length() } / 1024
            val control = File(root, "DEBIAN/control")
            control.parentFile.mkdirs()
            control.writeText(
                template.readText().replace("@VERSION@", version).replace("@ARCH@", arch).replace("@SIZE@", sizeKb.toString()),
            )
            val result = ProcessBuilder("dpkg-deb", "--root-owner-group", "--build", root.path, out.path)
                .redirectErrorStream(true).start()
            val said = result.inputStream.bufferedReader().readText()
            check(result.waitFor() == 0) { "dpkg-deb failed: $said" }
            // Staged again next time, so the control file does not land among the jars.
            control.parentFile.deleteRecursively()
        }
    }
}

registerDeb("amd64", debAmd64Runtime, buildEngine, nativeBuild)
registerDeb("arm64", debArm64Runtime, buildEngineArm64, nativeArm64)

// --- The AppImages --------------------------------------------------------------
//
// One file that runs on most Linux desktops, not only Debian's: Ubuntu 22.04
// and newer, Fedora, Arch, the Steam Deck. So it carries what the Debian
// package takes from the system - a Java runtime (Eclipse Temurin, built for
// old glibc) and an engine built on Ubuntu 22.04 (native/Dockerfile.appimage)
// with the C++ runtime linked in - and asks only for glibc 2.35. Audio and
// MIDI open the system's own libraries at run time, as the package does.
//
//   ./gradlew :desktop:appImageAmd64   build/appimage/Acidulous-<version>-x86_64.AppImage
//   ./gradlew :desktop:appImageArm64   build/appimage/Acidulous-<version>-aarch64.AppImage
//
// The downloads are pinned and checked against their SHA-256.

/** What an AppImage is made from, fetched once into build/appimage/downloads. */
class Download(val url: String, val sha256: String) {
    val name: String get() = url.substringAfterLast('/')
}
val appImageTool = Download(
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)
val downloads = layout.buildDirectory.dir("appimage/downloads")

fun fetch(d: Download, into: File): File {
    val file = File(into, d.name)
    fun sum(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (file.isFile && sum(file) == d.sha256) return file
    into.mkdirs()
    URI(d.url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    val got = sum(file)
    check(got == d.sha256) { "${d.name}: expected SHA-256 ${d.sha256}, got $got" }
    return file
}

fun registerAppImage(
    arch: String, appImageArch: String, runtime: Configuration,
    jre: Download, appImageRuntime: Download, cmakeArgs: String,
) {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val engineDir = nativeOutDir("build-appimage-$arch")
    val engine = tasks.register<Exec>("buildEngineAppImage$cap") {
        inputs.dir(rootProject.file("app/src/main/cpp"))
        inputs.files(nativeDir.file("CMakeLists.txt"), nativeDir.file("aarch64-linux-gnu.cmake"), nativeDir.file("Dockerfile.appimage"))
        outputs.dir(engineDir)
        val root = rootProject.projectDir.absolutePath
        workingDir = nativeDir.asFile
        commandLine(
            "sh", "-c",
            "docker build -q -t acidulous-appimage -f Dockerfile.appimage . >/dev/null && " +
                "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("appimage")} -w /src/desktop/native acidulous-appimage sh -c '" +
                "cmake -S . -B ${engineDir.asFile.path} -DCMAKE_BUILD_TYPE=Release $launcher " +
                "\"-DCMAKE_SHARED_LINKER_FLAGS=-static-libstdc++ -static-libgcc\" " +
                // FindJNI on 22.04's CMake wants AWT, which a headless JDK has not.
                "\"-DJNI_INCLUDE_DIRS=/usr/lib/jvm/java-17-openjdk-amd64/include;/usr/lib/jvm/java-17-openjdk-amd64/include/linux\" " +
                "$cmakeArgs >/dev/null && " +
                "cmake --build ${engineDir.asFile.path} -j8'",
        )
    }
    val appDir = layout.buildDirectory.dir("appimage/$arch/Acidulous.AppDir")
    val stage = tasks.register<Sync>("stageAppImage$cap") {
        dependsOn(engine)
        into(appDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(tasks.named("jar")) { into("usr/lib/acidulous/lib") }
        from(listOf(engineDir.file("libacidulous.so"), engineDir.file("libmp3lame.so"))) { into("usr/lib/acidulous/native") }
        from(file("appimage/AppRun")) { filePermissions { unix("rwxr-xr-x") } }
        from(file("deb/acidulous.desktop"))
        from(rootProject.file("branding/acidulous-icon-1024.svg")) { rename { "acidulous.svg" } }
        from(rootProject.file("NOTICE")) { into("usr/share/doc/acidulous") }
    }
    val out = layout.buildDirectory.file("appimage/Acidulous-$versionName-$appImageArch.AppImage")
    tasks.register("appImage$cap") {
        group = "distribution"
        description = "Builds Acidulous-$versionName-$appImageArch.AppImage"
        // Its action fetches and runs tools through this script's own helpers.
        notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
        dependsOn(stage)
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        val dir = appDir.get().asFile
        val cache = downloads.get().asFile
        val image = out.get().asFile
        inputs.files(runtime)
        inputs.dir(appDir)
        outputs.file(image)
        doLast {
            // The jars, named with their group, as the Debian package names them.
            val lib = File(dir, "usr/lib/acidulous/lib")
            for (a in artifacts.get()) {
                val id = a.id.componentIdentifier
                val name = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) "${id.group}-${a.file.name}" else a.file.name
                a.file.copyTo(File(lib, name), overwrite = true)
            }
            // The Java runtime, unpacked under the one name AppRun looks for.
            val jreDir = File(dir, "usr/lib/acidulous/jre")
            jreDir.deleteRecursively()
            jreDir.mkdirs()
            runCommand("tar", "-xzf", fetch(jre, cache).path, "-C", jreDir.path, "--strip-components=1")
            val tool = fetch(appImageTool, cache).apply { setExecutable(true) }
            val runtimeFile = fetch(appImageRuntime, cache)
            image.delete()
            // Extracted and run rather than mounted, so building needs no FUSE.
            runCommand(
                tool.path, "--no-appstream", "--runtime-file", runtimeFile.path, dir.path, image.path,
                env = mapOf("ARCH" to appImageArch, "APPIMAGE_EXTRACT_AND_RUN" to "1"),
            )
        }
    }
}

/** Runs [command], failing the build with what it said if it fails; what it printed otherwise. */
fun runCommand(vararg command: String, env: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    val said = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.first().substringAfterLast('/')} failed: $said" }
    return said.trim()
}

registerAppImage(
    "amd64", "x86_64", debAmd64Runtime,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz",
        "2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
        "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
    ),
    "",
)
registerAppImage(
    "arm64", "aarch64", debArm64Runtime,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.12.1_1.tar.gz",
        "14be1f35ebdbd1f6e8d57eb911a3ffb74d6d9aa255abc5daf2b1302002cf2cf2",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-aarch64",
        "00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444",
    ),
    "-DCMAKE_TOOLCHAIN_FILE=aarch64-linux-gnu.cmake",
)

tasks.matching { it.name == "run" }.configureEach { dependsOn(buildEngine) }

// --- Windows -------------------------------------------------------------------
//
// 64-bit Windows 10 and 11, cross-built here: the engine and the launcher with
// MinGW-w64 (native/Dockerfile.windows), the app's jars as for Linux but with
// Skia's Windows renderer, and Eclipse Temurin's Java runtime for Windows.
// Two ways to hand it over, the same files in each:
//
//   ./gradlew :desktop:windowsX64   build/windows/Acidulous-<version>-setup.exe
//                                   build/windows/Acidulous-<version>-windows-x64.zip
//
// Link is off for now (platform/web/LinkOff.cpp), and MIDI is Java Sound's.

val nativeWindows = nativeOutDir("build-windows")
val buildEngineWindows = tasks.register<Exec>("buildEngineWindows") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.files(nativeDir.file("CMakeLists.txt"), nativeDir.file("x86_64-w64-mingw32.cmake"), nativeDir.file("Dockerfile.windows"))
    inputs.dir(layout.projectDirectory.dir("windows"))
    inputs.property("version", versionName)
    outputs.dir(nativeWindows)
    val root = rootProject.projectDir.absolutePath
    val version = versionName
    workingDir = nativeDir.asFile
    commandLine(
        "sh", "-c",
        "docker build -q -t acidulous-windows -f Dockerfile.windows . >/dev/null && " +
            "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("windows")} -w /src/desktop/native acidulous-windows sh -c '" +
            "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
            "cmake -S . -B ${nativeWindows.asFile.path} -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=x86_64-w64-mingw32.cmake $launcher " +
            "\"-DJNI_INCLUDE_DIRS=\$J;/src/desktop/native/win32\" -DACIDULOUS_VERSION=$version >/dev/null && " +
            "cmake --build ${nativeWindows.asFile.path} -j8'",
    )
}

val windowsStage = layout.buildDirectory.dir("windows/Acidulous")
val stageWindows = tasks.register<Sync>("stageWindowsX64") {
    dependsOn(buildEngineWindows)
    into(windowsStage)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(nativeWindows.file("Acidulous.exe"))
    from(listOf(nativeWindows.file("acidulous.dll"), nativeWindows.file("mp3lame.dll"))) { into("app") }
    from(tasks.named("jar")) { into("app/lib") }
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
}

tasks.register("windowsX64") {
    group = "distribution"
    description = "Builds Acidulous-$versionName-setup.exe and the portable zip for 64-bit Windows"
    notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
    dependsOn(stageWindows)
    val artifacts = windowsX64Runtime.incoming.artifacts.resolvedArtifacts
    val stage = windowsStage.get().asFile
    val out = layout.buildDirectory.dir("windows").get().asFile
    val cache = downloads.get().asFile
    val version = versionName
    val root = rootProject.projectDir.absolutePath
    val jre = Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip",
        "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636",
    )
    inputs.files(windowsX64Runtime)
    inputs.dir(windowsStage)
    outputs.files(File(out, "Acidulous-$version-setup.exe"), File(out, "Acidulous-$version-windows-x64.zip"))
    doLast {
        val lib = File(stage, "app/lib")
        for (a in artifacts.get()) {
            val id = a.id.componentIdentifier
            val name = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) "${id.group}-${a.file.name}" else a.file.name
            a.file.copyTo(File(lib, name), overwrite = true)
        }
        val zip = fetch(jre, cache)
        val runtime = File(stage, "runtime")
        runtime.deleteRecursively()
        val unpacked = File(out, "jre-unpacked").apply { deleteRecursively(); mkdirs() }
        runCommand("unzip", "-q", zip.path, "-d", unpacked.path)
        unpacked.listFiles()!!.single().renameTo(runtime)
        unpacked.delete()
        val setup = "Acidulous-$version-setup.exe"
        val portable = "Acidulous-$version-windows-x64.zip"
        File(out, setup).delete()
        File(out, portable).delete()
        val inContainer = "/src/" + out.relativeTo(File(root)).invariantSeparatorsPath
        runCommand(
            "docker", "run", "--rm", "-u", "${runCommand("id", "-u")}:${runCommand("id", "-g")}", "-v", "$root:/src",
            "-w", "/src/desktop/windows", "acidulous-windows", "sh", "-c",
            "makensis -V2 -DVERSION=$version -DSTAGE=$inContainer/Acidulous -DOUT=$inContainer/$setup installer.nsi && " +
                "cd $inContainer && zip -qr $portable Acidulous",
        )
    }
}

