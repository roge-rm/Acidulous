import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Acidulous on a Linux desktop: the shared app in a window, and the engine
// built for the host by CMake from the same sources as the phone's (see
// native/CMakeLists.txt). `./gradlew :desktop:run` builds both and starts it.

kotlin { jvmToolchain(21) }

/** The version, from where it is set: app/build.gradle.kts, the one place it is written. */
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
// The 64-bit build's code: see how :app works it out from `release`.
val versionCode = (Regex("val release = (\\d+)").find(appGradle)!!.groupValues[1].toInt() * 10 + 2).toString()
val buildInfo by tasks.registering {
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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

val nativeDir = layout.projectDirectory.dir("native")
val nativeBuild = layout.projectDirectory.dir("native/build")

/** The engine, as libacidulous.so (and LAME's libmp3lame.so beside it). */
val buildEngine by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.file(nativeDir.file("CMakeLists.txt"))
    outputs.dir(nativeBuild)
    workingDir = nativeDir.asFile
    commandLine("sh", "-c", "cmake -S . -B build -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build build -j8")
}

/**
 * The licence texts the About window shows, from where they live and under
 * the names the app gives them: the same as :app's stageLicences.
 */
val stageLicences by tasks.registering(Sync::class) {
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
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "acidulous"
        }
    }
}

tasks.matching { it.name == "run" }.configureEach { dependsOn(buildEngine) }
