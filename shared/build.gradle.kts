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

// What the Android app and the desktop build share: the song model, the UI and
// its strings. The engine itself is C++ and reached through JNI on both, which
// is why most of this lives in jvmShared (Android and desktop) rather than
// commonMain - JNI, java.io.File and String.format are fine on both JVMs, and
// only a browser build would need them replaced.
kotlin {
    android {
        namespace = "com.rm.acidulous.shared"
        compileSdk = 37
        minSdk = 27
        androidResources { enable = true }
    }
    jvm("desktop")

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
 * The UI's strings, read the way Android reads them.
 *
 * They are written in Android's format - src/commonMain/strings/values, which
 * is where to edit them - and Compose Multiplatform's resources do not follow
 * all of its rules: a string in quotes keeps its quotes, `\'` keeps its
 * backslash and a run of spaces stays a run. So this applies Android's rules
 * first (aapt2's: escapes, quoting, whitespace collapsed and trimmed outside
 * quotes) and hands Compose the plain text, and a string reads the same on
 * every platform as it did on Android.
 */
abstract class NormaliseStrings : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    /** Android vector drawables to hand over as they are, into drawable/. */
    @get:InputFiles abstract val drawables: ConfigurableFileCollection
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
    }

    private fun androidText(raw: String): String {
        // Each character, and whether it is a space that quoting did not protect.
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
    // The logo the splash shows is the launcher icon's foreground, which has
    // to stay in the app's own resources for the launcher; one copy, not two.
    drawables.from(rootProject.file("app/src/main/res/drawable/ic_launcher_foreground.xml"))
    output.set(layout.buildDirectory.dir("generated/strings"))
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.rm.acidulous.res"
    generateResClass = always
    customDirectory("commonMain", normaliseStrings.flatMap { it.output })
}

// The tests of what lives here run on the desktop JVM - none of them needs
// Android - and still answer to the name everybody types.
tasks.register("testDebugUnitTest") { dependsOn("desktopTest") }
