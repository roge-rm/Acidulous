import java.security.MessageDigest
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Acidulous in a browser: Compose for Kotlin/Wasm with the app's shared code,
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

/** The engine's WebAssembly, built by web/engine/build.sh and served next to the page. */
val engineOut = rootProject.file("web/engine/out")
val buildEngine = tasks.register<Exec>("buildEngine") {
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.files(rootProject.file("web/engine/CMakeLists.txt"), rootProject.file("web/engine/worklet-clock.js"))
    outputs.dir(engineOut)
    // Put the CMake build under acidulous.buildRoot if it's set (see the root
    // build.gradle.kts).
    providers.gradleProperty("acidulous.buildRoot").orNull?.let {
        environment("ACIDULOUS_ENGINE_BUILD", File(it, "${rootDir.name}/web-engine").path)
    }
    commandLine(rootProject.file("web/engine/build.sh").absolutePath)
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(files(engineOut).builtBy(buildEngine)) }

/** The version is read from app/build.gradle.kts, like the desktop's. */
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
 * The licence texts the About window shows, served next to the page under the
 * names the app uses. Same as :app's and the desktop's, minus the audio
 * libraries and Link, which the browser build leaves out.
 */
val stageLicences = tasks.register<Sync>("stageLicences") {
    val app = rootProject.file("app/src/main/cpp/third_party")
    from(file("$app/lame/COPYING")) { rename { "lgpl-2.0.txt" } }
    from(rootProject.file("LICENSE")) { rename { "gpl-3.0.txt" } }
    into(layout.buildDirectory.dir("generated/licences/licences"))
}
kotlin.sourceSets.named("wasmJsMain") { resources.srcDir(stageLicences.map { it.destinationDir.parentFile }) }

/**
 * Writes load-sizes.json into the distribution: each file the page downloads
 * before the app starts, and its size, for the progress bar in index.html.
 * It's written after bundling because the WebAssembly file names are the
 * bundler's hashes.
 *
 * Also fills in the service worker's two lines (sw.js): the list of files in
 * the build, and a fingerprint of them all that tells a browser there's a new
 * build. Source maps are left out, only a debugger asks for them.
 */
listOf(
    "wasmJsBrowserDistribution" to "productionExecutable",
    "wasmJsBrowserDevelopmentExecutableDistribution" to "developmentExecutable",
).forEach { (task, dir) ->
    val dist = layout.buildDirectory.dir("dist/wasmJs/$dir").get().asFile
    tasks.matching { it.name == task }.configureEach {
        doLast {
            val files = dist.listFiles().orEmpty()
                .filter { it.isFile && (it.name.endsWith(".wasm") || it.name.endsWith(".js")) && it.name != "sw.js" }
                .sortedBy { it.name }
            dist.resolve("load-sizes.json").writeText(
                files.joinToString(",\n", "{\n", "\n}\n") { "  \"${it.name}\": ${it.length()}" },
            )
            val worker = dist.resolve("sw.js")
            val kept = dist.walkTopDown()
                .filter { it.isFile && it != worker && !it.name.endsWith(".map") }
                .map { it.relativeTo(dist).invariantSeparatorsPath }
                .sorted().toList()
            val digest = MessageDigest.getInstance("SHA-256")
            for (path in kept) {
                digest.update(path.toByteArray())
                digest.update(dist.resolve(path).readBytes())
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            worker.writeText(
                worker.readText()
                    .replace("'__VERSION__'", "'$fingerprint'")
                    .replace("__FILES__", kept.joinToString(", ", "[", "]") { "'$it'" }),
            )
        }
    }
}
