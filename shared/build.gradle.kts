import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The code shared by the Android app, the desktop build and the browser: the
// song model, the UI and its strings, all in commonMain. The engine is C++ on
// all three, reached through JNI on the two JVMs and through the same bridge
// compiled to WebAssembly in the browser. jvmShared only holds the JVM side
// of the few things the browser does differently (files, String.format, the
// engine calls, threads), each an expect in commonMain.
kotlin {
    android {
        namespace = "com.rm.acidulous.shared"
        compileSdk = 37
        minSdk = 27
        androidResources { enable = true }
    }
    // Java 21, which Debian Trixie and Raspberry Pi OS ship and the desktop
    // packages run on. Otherwise it would be whatever JDK Gradle runs on.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
    // The browser: Compose for Kotlin/Wasm, with the engine as WebAssembly
    // next to it (web/).
    wasmJs { browser() }

    sourceSets {
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)

        commonMain.dependencies {
            api(libs.jb.compose.runtime)
            api(libs.jb.compose.foundation)
            api(libs.jb.compose.ui)
            api(libs.jb.compose.material3)
            api(libs.jb.compose.resources)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

/**
 * Makes the UI strings read the way Android reads them.
 *
 * They're written in Android's format (edit them in
 * src/commonMain/strings/values), but Compose Multiplatform's resources don't
 * follow all of Android's rules: quotes are kept, `\'` keeps its backslash
 * and runs of spaces aren't collapsed. So this applies aapt2's rules first
 * (escapes, quoting, whitespace collapsed and trimmed outside quotes) and
 * gives Compose the plain text, so strings read the same on every platform.
 */
abstract class NormaliseStrings : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    /** Android vector drawables to copy as they are into drawable/. */
    @get:InputFiles abstract val drawables: ConfigurableFileCollection
    /** Data files the app reads, like Diction's dictionary, copied as they are into files/. */
    @get:InputFiles abstract val files: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.deleteRecursively()
        source.get().asFile.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { file ->
            val relative = file.relativeTo(source.get().asFile)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            for (tag in listOf("string", "item")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    element.textContent = androidText(element.textContent)
                }
            }
            val target = File(out, relative.path)
            target.parentFile.mkdirs()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(doc), StreamResult(target))
        }
        drawables.forEach { it.copyTo(File(out, "drawable/${it.name}"), overwrite = true) }
        files.forEach { it.copyTo(File(out, "files/${it.name}"), overwrite = true) }
    }

    private fun androidText(raw: String): String {
        // Each character, and whether it's a space that quoting didn't protect.
        val chars = ArrayList<Pair<Char, Boolean>>()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    i++
                    when (next) {
                        'n' -> chars += '\n' to false
                        't' -> chars += '\t' to false
                        'u' -> if (i + 4 < raw.length) {
                            chars += raw.substring(i + 1, i + 5).toInt(16).toChar() to false
                            i += 4
                        }
                        else -> chars += next to false
                    }
                }
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> if (chars.lastOrNull()?.second != true) chars += ' ' to true
                else -> chars += c to false
            }
            i++
        }
        while (chars.firstOrNull()?.second == true) chars.removeAt(0)
        while (chars.lastOrNull()?.second == true) chars.removeAt(chars.size - 1)
        return chars.joinToString("") { it.first.toString() }
    }
}

val normaliseStrings by tasks.registering(NormaliseStrings::class) {
    source.set(layout.projectDirectory.dir("src/commonMain/strings"))
    // The splash logo is the launcher icon's foreground, which has to stay in
    // the app's own resources for the launcher, so it's copied from there.
    drawables.from(rootProject.file("app/src/main/res/drawable/ic_launcher_foreground.xml"))
    files.from(layout.projectDirectory.dir("src/commonMain/files").asFileTree)
    output.set(layout.buildDirectory.dir("generated/strings"))
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.rm.acidulous.res"
    generateResClass = always
    customDirectory("commonMain", normaliseStrings.flatMap { it.output })
}

// The shared tests run on the desktop JVM (none of them need Android), under
// the task name everyone is used to typing.
tasks.register("testDebugUnitTest") { dependsOn("desktopTest") }
