import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** The 32-bit build, for tablets like the Fire HD 8: see `release` below. */
val arm32 = project.hasProperty("arm32")

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
        // Bumped by hand, and the name is the tag. The minor number goes up
        // when saved songs mean something different, e.g. a song that uses
        // a new feature won't play the same in an older version.
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
        //
        // 0.9.5: loops fit the song - Dice and a loop on an audio track play
        // at the song's tempo, stretched without changing pitch - and tablets
        // use the room. The crash opening Trinity's envelopes, there since
        // 0.9.3, is fixed, and so is the error on deleting an audio track.
        //
        // 0.9.6: keyboards - letters play notes, every control can be reached
        // and worked from keys, shortcuts on every screen, and they can be
        // changed in Settings. Nexus's fit button lays the patch out to fit
        // the screen.
        // 0.9.7: a 32-bit build beside the 64-bit one; square phones' windows
        // fit without scrolling; MPE follows the controller and expresses by
        // default; an Exquis shows the key and a Launchpad Pro is driven
        // whole; quantise, groove and humanise; a take is one undo; velocity
        // runs from a whisper to full on every machine, with a MIDI curve.
        // 0.9.8: the same app on Linux (Debian packages and AppImages),
        // Windows (an installer, with interfaces' own low-latency drivers and
        // Link) and in a browser (installable, and it works offline), from the
        // same code; panels and windows show one tab at a time; a scene's
        // header in clip mode starts its clips and stops the rest together; a
        // take plays with a playhead; a crash auditioning a sound is fixed.
        // 0.9.9: the Sound window's edits show and play before apply; the mic
        // raw or clean; a scene's header follows the loop pill; Bias's
        // automation lines up with the tape; crash fixes from a bug hunt:
        // the input opening, notes lost between threads, reads of freed
        // memory, bad audio files and songs, the organ's top wheels, and
        // Harmonizer and Shifter feedback running away.
        // 0.9.10: Molt no longer leaves the end of a note repeating as a tone;
        // the whole app from a game controller, sticks and play mode too.
        // 0.9.11: Diction sings words, in the built-in voice or one recorded
        // in the app; lyrics on the roll and from MIDI files; clean, whisper,
        // effort, rasp, growl, choir, harmony, the built-in voice's folds or
        // throat, and a morph to a second voice; octave below zero fixed.
        // 0.9.12: tempo-locked LFOs step through triplets and dotted notes
        // (songs and patches move onto the new list as they load); wobble
        // patches for the Filter and Trinity.
        // 0.9.13: Hammer, modelled pianos: grand, upright, honky-tonk,
        // fortepiano, electric grand, tine and reed electric pianos, a tangent
        // keyboard, celesta, toy piano, dulcimer and cimbalom, with half
        // pedalling and preparations; Diction talks with another track's
        // sound; drum grid hits are heard and can be taken out again.
        // 0.10.0: tracks play on more than one core (a cores setting), on a
        // phone's fast ones; the running time in the readout; recording
        // takes that replace, start on the first note or stop after one pass;
        // shared Diction voices import; Hammer's top keys even and its chords
        // cheaper; the audio buffer settles on the smallest size the device
        // holds; slow tablets like the Fire HD 8 play without breaking up.
        // 0.10.1: Tongue, modelled jaw harps: ten kinds, up to five reeds as
        // a chord, the keys playing the drone's harmonics, plucking patterns,
        // words said by the mouth and another track's melody followed; Diction
        // and Molt knobs work while they play; diagnostics in debug builds only.
        //
        // Two APKs per release: 64-bit, and with -Parm32 a 32-bit one for
        // tablets like the Fire HD 8. A store installs the highest versionCode
        // a device can run, and most 64-bit phones can also run 32-bit code,
        // so the 64-bit APK must be higher: the release number times ten,
        // plus 2 for 64-bit and 1 for 32-bit. Bump [release], not the code.
        val release = 30
        versionCode = release * 10 + if (arm32) 1 else 2
        versionName = "0.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // The engine throws in a few places (LUT size mismatch,
                    // unknown setting keys), so exceptions must stay on.
                    "-DANDROID_CPP_FEATURES=exceptions rtti",
                )
                // Use ccache if this machine has it (see desktop/).
                if (File("/usr/bin/ccache").canExecute()) {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
            }
        }

        ndk {
            // See [release] for why there are two builds. The 32-bit one
            // includes x86 so it runs on the Android 8.1 x86 emulator.
            abiFilters += if (arm32) listOf("armeabi-v7a", "x86") else listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
            // Put the native staging (.cxx) with the rest of the build output
            // if acidulous.buildRoot is set. See the root build.gradle.kts.
            providers.gradleProperty("acidulous.buildRoot").orNull?.let {
                buildStagingDirectory = File(it, "${rootDir.name}/app-cxx")
            }
        }
    }

    /**
     * Release signing. The keystore and its properties live in a sibling
     * `Keys/` directory outside the repository, so they can never be
     * committed.
     *
     * Without that file (a fresh clone, CI) no signing config is created and
     * the release build comes out unsigned instead of failing.
     *
     * Losing the keystore means installed copies can never be updated, so
     * keep it backed up.
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
                // v1 as well as v2, for older phones and sideloading.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Shrunk and optimised, since most of the code is unused. What
            // must be kept is in proguard-rules.pro.
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // A phone set to English (XA) shows string resources
            // [ŵîţĥ åççéñţš], so any plain text on screen is still hardcoded.
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
 * Copies the licence texts shown in the About window into the assets from
 * their real locations (the repository's LICENSE, LAME's COPYING and so on),
 * so they can't drift.
 *
 * Its own task instead of a `Copy` because the variant API needs an output
 * `DirectoryProperty`. See licences/README.md.
 */
abstract class StageLicences : DefaultTask() {
    @get:InputFiles abstract val texts: ConfigurableFileCollection

    /**
     * The name each input gets in the app, keyed by its file name on disk.
     * A map, not two lists, so the names can't get paired with the wrong file.
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
    implementation(project(":shared"))
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