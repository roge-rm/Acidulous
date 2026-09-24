import java.util.Properties

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
        // Bumped by hand, and the name is the tag. 0.1.0 is the first
        // release put out for anybody else to install - see docs and the
        // repository's tags.
        //
        // 0.1.1 because the 0.1.0 tag fell behind: twenty-eight commits
        // landed past it, among them a behaviour change anybody who installed
        // it would notice - panic became a long press of the stop pill rather
        // than a button of its own - and an interface that can be made larger.
        // A tag that no longer describes what people have is worse than no
        // tag, so it is a new one rather than a moved one.
        //
        // 0.2.0 is the manual: the first release you can be handed without
        // also being told how any of it works.
        //
        // 0.4.0 because two things changed what a song already saved *means*,
        // which is the case a minor number exists for. Modifiers replaced
        // eventors outright and deliberately without migration, so a song from
        // 0.3.0 opens without the chain it was written with; and a freeze now
        // stores its ring-out as its own region, so every freeze rendered
        // before this says so and asks to be made again. Swing and a transport
        // that ends rather than only looping came with them, and the
        // performance work that made the meter worth reading.
        //
        // 0.5.0 is a freeze that follows a tempo ramp. A scene that changes
        // tempo smoothly spends its first bar between two tempos, matching no
        // clip's rendered tempo, so every frozen clip in it used to hand that
        // bar back to its machine - in the busiest scene, which is usually why
        // anything was frozen. The audio is time-stretched to the ramp
        // instead, through the stretcher the audio tracks already used, now
        // float and stereo. No saved song means anything different for it.
        //
        // 0.5.1 is the first of the performance releases, and nothing a song
        // holds changes in any of them. The dearest machines were doing work
        // at audio rate that nothing at audio rate asked for: envelopes
        // nobody read, a pitch that only moves when a note glides, a `pow`
        // and a `tanh` of numbers that hold still for a block. Trinity's
        // worst block on a mid-range phone halved.
        //
        // 0.5.2 measures the patches people play rather than the defaults
        // nobody does, and found that the pad machine had never been measured
        // at all - it takes its wavetables through a mount, like the samplers,
        // so with nothing mounted it made no sound and cost nothing, while the
        // same track was the second dearest on a phone.
        //
        // 0.5.3 makes the per-track figure a measurement rather than an
        // anecdote. It was a peak over a whole song, which one unlucky block
        // sets for good; three runs of one build on one phone put it up to a
        // quarter apart, so most of what is left to find could not be seen.
        // It is the worst block in a hundred now, out of a histogram the
        // engine keeps per rack.
        //
        // 0.6.0 because saved songs mean something different. Eleven matrix
        // destinations that had been offered and read by nothing now move
        // what they name, so a routing saved to one of them starts doing
        // something; a frozen vocoder spectrum holds as long as its decay knob
        // says rather than a sixty-fourth of it; and the organ's leakage, hum
        // and blower fade out when it is not playing rather than hissing
        // through every scene it sits out. Also: lean reaches the patch that
        // costs its release, machines with nothing to play go to sleep, and
        // the demo is a dub.
        //
        // 0.7.0: the mix can be finished on the phone. A sidechain from any
        // track, group tracks, two inserts on the master, and a loudness meter
        // with a normalised export. Swing, which had never reached the engine
        // from the app, now does.
        //
        // 0.7.1: groups are strips in the mixer, not tracks. A song saved with
        // 0.7.0 has its bus tracks turned into groups when it opens. Groups
        // have pan, and the demo uses everything 0.7 added.
        //
        // 0.7.2: the perform pages (hold, pad, live), five demo songs, exports
        // that repeat again, and play putting automated knobs back where the
        // song has them. The rest of 0.8 - the looper, pattern generators and
        // step locks - comes before 0.8.0.
        //
        // 0.8.0: play it. Empty launcher cells are loopers, MIDI follow has an
        // auto setting, pattern generators write notes, a step can lock any
        // knob, and every window with settings in it is cards of knobs and
        // switches. A song with step locks means something 0.7 cannot play.
        //
        // 0.9.0: in and out. MIDI files and song bundles import, exports go
        // to the share sheet and files open with the app. Scenes ramp their
        // tempo, songs and tracks take tunings, and each track has its own
        // settings. One switch for the diagnostics, panic in About, and
        // layouts for turned and square phones. A song with a tempo ramp or
        // a tuning means something 0.8 cannot play.
        //
        // 0.9.1: sustain, sostenuto and soft pedals, recorded as lanes; MIDI
        // files bring their pedals, controllers, bends and tempo changes;
        // the organ and Nexus take tunings. A point release on the way to
        // 1.0, though a song with pedal lanes plays without them in 0.9.0
        // (Dan's call: it was 0.10.0 for a minute).
        //
        // 0.9.2: the first release build that has been played. Songs are
        // saved so a crash cannot leave half of one; the app stops for a
        // call, another app's music or headphones pulled out; crashes and
        // freezes leave a report on the phone that can be shared; backup
        // keeps the songs and not the samples; and the release is shrunk.
        //
        // 0.9.3: every word the app shows comes from string resources, the
        // machine panels' too, so it can be translated; counts say "1 note"
        // rather than "1 notes". One demo song, an acid track that opens on
        // the first run, in place of the five and their menu. The first
        // release on GitHub and in the F-Droid repo.
        //
        // 0.9.4: TalkBack can play it - every control says what it is and
        // what it is set to, knobs are sliders, holds are named actions, and
        // the song grid comes a page of scenes at a time so no clip is out
        // of reach. A high contrast theme. The version reads on Android 8.1.
        versionCode = 19
        versionName = "0.9.4"

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

    /**
     * Release signing, from a file that is not in the repository *or beside
     * it*.
     *
     * Both the keystore and the properties that name it live in a sibling
     * `Keys/` directory - outside the tree entirely, so there is no version
     * of this repository, public or private, in which a slip could commit
     * them. Dan: "the old method is not safe enough for me". Gitignoring a
     * password file that sits in the working tree relies on the ignore rule
     * holding for the life of the project; a file one directory up does not.
     *
     * The path is relative, so it is a true statement about a layout rather
     * than about one machine, and it resolves to nothing for anybody who
     * clones this. Absent - a fresh clone, CI, somebody else's machine - no
     * config is created and a release build comes out unsigned rather than
     * failing. Anybody can build this; only one person can sign it.
     *
     * **Losing the keystore means never being able to update an installed
     * copy**, so it is Dan's to keep and back up, not the build's.
     */
    val keystoreProps = rootProject.file("../Keys/acidulous-keystore.properties")
    val signing: Properties? = if (keystoreProps.exists()) {
        Properties().also { p -> keystoreProps.inputStream().use { p.load(it) } }
    } else {
        null
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                // v1 as well, because minSdk is 27 and v2-only APKs are not
                // installable below 24 - and a phone that verifies v1 is one
                // fewer thing to explain to somebody sideloading a release.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Shrunk and optimised: the debug APK is 22 MB, most of it code
            // nothing calls. What must survive is in proguard-rules.pro.
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // A phone set to English (XA) shows every string resource
            // [ŵîţĥ åççéñţš], so a word on screen without them is one that
            // is still written in the code.
            isPseudoLocalesEnabled = true
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
        file("src/main/cpp/third_party/asio/LICENSE_1_0.txt"),
        rootProject.file("LICENSE"),
        rootProject.file("licences/Apache-2.0.txt"),
        rootProject.file("licences/GPL-2.0.txt"),
    )
    names.set(
        mapOf(
            "COPYING" to "lgpl-2.0.txt",
            "LICENSE_1_0.txt" to "bsl-1.0.txt",
            "LICENSE" to "gpl-3.0.txt",
            "Apache-2.0.txt" to "apache-2.0.txt",
            "GPL-2.0.txt" to "gpl-2.0.txt",
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