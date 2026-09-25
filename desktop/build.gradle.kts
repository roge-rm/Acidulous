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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.common)
    runtimeOnly(compose.desktop.currentOs)
    debAmd64Runtime(compose.desktop.linux_x64)
    debArm64Runtime(compose.desktop.linux_arm64)
}

for (runtime in listOf(debAmd64Runtime, debArm64Runtime)) {
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
val nativeBuild = layout.projectDirectory.dir("native/build")

/** The engine for this machine, as libacidulous.so (and LAME's libmp3lame.so beside it). */
val buildEngine = tasks.register<Exec>("buildEngine") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.file(nativeDir.file("CMakeLists.txt"))
    outputs.dir(nativeBuild)
    workingDir = nativeDir.asFile
    commandLine("sh", "-c", "cmake -S . -B build -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build build -j8")
}

/**
 * The engine for a Raspberry Pi, cross-compiled in a Debian Trixie container
 * (native/Dockerfile.arm64) so it is linked against the Pi's own glibc and
 * libstdc++. Needs Docker; the container only compiles, it runs no ARM code.
 */
val nativeArm64 = layout.projectDirectory.dir("native/build-arm64")
val buildEngineArm64 = tasks.register<Exec>("buildEngineArm64") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.files(nativeDir.file("CMakeLists.txt"), nativeDir.file("aarch64-linux-gnu.cmake"), nativeDir.file("Dockerfile.arm64"))
    outputs.dir(nativeArm64)
    val root = rootProject.projectDir.absolutePath
    workingDir = nativeDir.asFile
    commandLine(
        "sh", "-c",
        "docker build -q -t acidulous-arm64-cross -f Dockerfile.arm64 . >/dev/null && " +
            "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src -w /src/desktop/native acidulous-arm64-cross sh -c '" +
            "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
            "cmake -S . -B build-arm64 -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=aarch64-linux-gnu.cmake \"-DJNI_INCLUDE_DIRS=\$J;\$J/linux\" >/dev/null && " +
            "cmake --build build-arm64 -j8'",
    )
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
        from(listOf(engineDir.file("libacidulous.so"), engineDir.file("lame/libmp3lame.so"))) { into("usr/lib/acidulous/native") }
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

tasks.matching { it.name == "run" }.configureEach { dependsOn(buildEngine) }
