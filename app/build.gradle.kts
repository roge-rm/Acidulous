plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rm.acidulous"
    compileSdk {
        version = release(37)
    }

    // Pinned so the native ABI does not shift under us between machines.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.rm.acidulous"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // The engine throws in a few places (LUT size mismatch,
                    // unknown setting keys), so exceptions must stay on.
                    "-DANDROID_CPP_FEATURES=exceptions rtti",
                )
            }
        }

        ndk {
            // 64-bit only. armeabi-v7a would need auditing for the engine's
            // alignas(32) buffers and is not worth it for a synth app.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        prefab = true // Oboe ships its headers and .so through Prefab
    }
}

/**
 * The licence texts the About window shows, staged into the assets from
 * wherever they actually live: the GPL the app is under is the repository's
 * own LICENSE, and the LGPL is the one that came in LAME's tarball. Copies
 * checked in beside the code would drift from them; this cannot.
 *
 * A task of its own rather than a `Copy`, because the assets are wired
 * through the variant API - which needs an output `DirectoryProperty`, and a
 * `Copy` has a plain `File`. See licences/README.md.
 */
abstract class StageLicences : DefaultTask() {
    @get:InputFiles abstract val texts: ConfigurableFileCollection

    /**
     * What each input is called once it is in the app, keyed by the name it
     * has on disk. Keyed rather than paired by position, because the first
     * cut of this paired two lists by path order and quietly shipped the
     * GPL under the LGPL's name.
     */
    @get:Input abstract val names: MapProperty<String, String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val into = outputDir.get().asFile.resolve("licences")
        into.deleteRecursively()
        into.mkdirs()
        val named = names.get()
        for (from in texts.files) {
            val name = named[from.name] ?: error("no name given for ${from.name}")
            from.copyTo(into.resolve(name), overwrite = true)
        }
    }
}

val stageLicences = tasks.register<StageLicences>("stageLicences") {
    texts.from(
        file("src/main/cpp/third_party/lame/COPYING"),
        rootProject.file("LICENSE"),
        rootProject.file("licences/Apache-2.0.txt"),
    )
    names.set(
        mapOf(
            "COPYING" to "lgpl-2.0.txt",
            "LICENSE" to "gpl-3.0.txt",
            "Apache-2.0.txt" to "apache-2.0.txt",
        ),
    )
    outputDir.set(layout.buildDirectory.dir("generated/licences"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageLicences, StageLicences::outputDir)
    }
}

dependencies {
    implementation(libs.oboe)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}