plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Acidulous in a browser: Compose for Kotlin/Wasm, calling the engine built as
// threaded WebAssembly in ../engine. For now the spike that proves the two
// meet: a page, a note, a meter.
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
