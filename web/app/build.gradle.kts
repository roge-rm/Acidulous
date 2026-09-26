plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Acidulous in a browser: Compose for Kotlin/Wasm, the app's shared code,
// calling the engine built as threaded WebAssembly in ../engine.
//
//   ./gradlew :webApp:wasmJsBrowserDistribution    the page, in build/dist/wasmJs/productionExecutable
//   ./gradlew :webApp:serve                        that, served with the isolation headers on :8765

kotlin {
    wasmJs {
        outputModuleName.set("acidulous-ui")
        browser {
            commonWebpackConfig { outputFileName = "acidulous-ui.js" }
        }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
        }
    }
}

/** The engine's WebAssembly, built by its own script, served beside the page. */
val engineOut = rootProject.file("web/engine/out")
val buildEngine = tasks.register<Exec>("buildEngine") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.files(rootProject.file("web/engine/CMakeLists.txt"), rootProject.file("web/engine/worklet-clock.js"))
    outputs.dir(engineOut)
    commandLine(rootProject.file("web/engine/build.sh").absolutePath)
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(files(engineOut).builtBy(buildEngine)) }

/** The version, from where it is set: app/build.gradle.kts, as the desktop's is. */
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]
val versionCode = Regex("val release = (\\d+)").find(appGradle)!!.groupValues[1]
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildInfo")
    val name = versionName
    val code = versionCode
    inputs.property("version", "$name ($code)")
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/rm/acidulous/web/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.rm.acidulous.web\n\ninternal const val VERSION_NAME = \"$name\"\ninternal const val VERSION_CODE = $code\n")
    }
}
kotlin.sourceSets.named("wasmJsMain") { kotlin.srcDir(buildInfo) }

/**
 * The licence texts the About window shows, served beside the page under the
 * names the app gives them: :app's and the desktop's, less the audio
 * libraries a browser does not use.
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
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(stageLicences.map { it.destinationDir.parentFile }) }
