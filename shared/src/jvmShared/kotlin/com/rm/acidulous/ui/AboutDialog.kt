package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.AppHost
import com.rm.acidulous.res.*

/**
 * Who wrote this, what it is under, and whose work came with it.
 *
 * The licences are not summarised here and left at that: the GPL requires
 * that whoever has the program can read the licence itself, and the LGPL
 * requires the same of LAME's. So each one is a **row that opens the whole
 * text**, staged into the app's assets from the very files in the tree - see
 * the `stageLicences` task. A window that paraphrased a licence would be the
 * one thing here that must not drift.
 *
 * The full text gets its own window rather than a fourth tab, because
 * [TabbedDialog] measures every page and takes the tallest: thirty-five
 * thousand characters of GPL would make the *other* tabs five hundred dp of
 * mostly nothing.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var reading by remember { mutableStateOf<Licence?>(null) }

    TabbedDialog(
        title = stringResource(Res.string.about_title),
        selected = tab,
        pages = listOf(
            { AppTab() },
            { LicenceTab { reading = it } },
            { ComponentsTab { reading = it } },
        ),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.done),
        spacing = 16.dp,
        chips = { SectionChips(stringArrayResource(Res.array.about_tabs).toList(), tab) { tab = it } },
    )

    reading?.let { LicenceTextDialog(it) { reading = null } }
}

/** The three texts the app ships, and where the build staged each one. */
private enum class Licence(val title: String, val asset: String) {
    Gpl3("GNU General Public License v3", "licences/gpl-3.0.txt"),
    Gpl2("GNU General Public License v2", "licences/gpl-2.0.txt"),
    Lgpl2("GNU Library General Public License v2", "licences/lgpl-2.0.txt"),
    Lgpl21("GNU Lesser General Public License v2.1", "licences/lgpl-2.1.txt"),
    Apache2("Apache License 2.0", "licences/apache-2.0.txt"),
    Bsl1("Boost Software License 1.0", "licences/bsl-1.0.txt"),
    PublicDomain("Unlicense or MIT-0", "licences/miniaudio.txt"),
}

@Composable
private fun AppTab() {
    val c = Acid.colors
    val resources = AppStrings
    // Asked of the platform rather than of BuildConfig: see AppHost.versionName.
    val version = remember { AppHost.current.versionLong ?: resources.getString(Res.string.about_version_unknown) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Acidulous", color = c.text, fontSize = 22.sp)
        Readout(stringResource(Res.string.about_version, version))
        // Android is the app's home, so the line stays as it is; another
        // platform's build says which it is, straight after "for Android"
        // (Dan, 2026-09-25).
        val platform = com.rm.acidulous.AppHost.current.platformName
        val forPlatform = if (platform == null) "" else " " + stringResource(Res.string.about_for_platform, platform)
        Body(stringResource(Res.string.about_what, forPlatform))
        Body("Copyright © 2026 Dan Hunke")
        // The one place that *names* it, which a gesture has no way to be:
        // holding play does the same and is the fast path. Here rather than
        // in the file menu (Dan, 2026-09-23), where it sat among things you
        // choose rather than things you reach for.
        androidx.compose.material3.OutlinedButton(
            onClick = { panicEverything() },
            border = androidx.compose.foundation.BorderStroke(1.dp, c.red),
        ) { Text(stringResource(Res.string.about_panic), color = c.red) }
        // The last crash report, while there is one, for whoever asks for it.
        val report = remember { AppHost.current.latestCrashReport() }
        if (report != null) {
            androidx.compose.material3.TextButton(
                onClick = { AppHost.current.shareCrashReport(report) },
            ) { Text(stringResource(Res.string.about_share_crash)) }
        }
    }
}

@Composable
private fun LicenceTab(onRead: (Licence) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Body(stringResource(Res.string.about_gpl_1))
        Body(stringResource(Res.string.about_gpl_2))
        ListSection(stringResource(Res.string.about_licence_itself)) {
            LicenceRow(Licence.Gpl3, stringResource(Res.string.about_licence_app), onRead)
        }
    }
}

@Composable
private fun ComponentsTab(onRead: (Licence) -> Unit) {
    ListSection(
        stringResource(Res.string.about_not_ours),
        stringResource(Res.string.about_not_ours_note),
    ) {
        // The audio stream is Oboe on the phone and miniaudio on the desktop,
        // whose MIDI is ALSA's.
        if (com.rm.acidulous.AppHost.current.usesOboe) {
            LicenceRow(Licence.Apache2, stringResource(Res.string.about_oboe), onRead)
        } else {
            LicenceRow(Licence.PublicDomain, stringResource(Res.string.about_miniaudio), onRead)
            LicenceRow(Licence.Lgpl21, stringResource(Res.string.about_alsa), onRead)
        }
        LicenceRow(Licence.Lgpl2, stringResource(Res.string.about_lame), onRead)
        LicenceRow(Licence.Gpl2, stringResource(Res.string.about_link), onRead)
        LicenceRow(Licence.Bsl1, stringResource(Res.string.about_asio), onRead)
    }
}

@Composable
private fun LicenceRow(licence: Licence, under: String, onRead: (Licence) -> Unit) =
    DialogRow("¶", licence.title, under = under, trailing = stringResource(Res.string.about_read)) { onRead(licence) }

/**
 * One licence, whole. Monospace, because these texts are written to a fixed
 * width and their indentation means something - but **wrapped rather than
 * scrolled sideways**. A sideways scroll would put its position bar at the
 * bottom of thirty-five thousand characters of text, which is to say
 * nowhere; a 72-column line wrapping once at a phone's width is ragged and
 * perfectly readable.
 */
@Composable
private fun LicenceTextDialog(licence: Licence, onDismiss: () -> Unit) {
    val resources = AppStrings
    val text = remember(licence) {
        AppHost.current.licenceText(licence.asset) ?: resources.getString(Res.string.about_licence_missing, licence.asset)
    }
    PlainDialog(licence.title, onDismiss = onDismiss, dismissLabel = stringResource(Res.string.close), spacing = 0.dp) {
        Text(
            text,
            color = Acid.colors.textMid,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** A paragraph, as this window has more of them than the rest of the app. */
@Composable
private fun Body(text: String) {
    Text(text, color = Acid.colors.textDim, fontSize = 12.sp, lineHeight = 17.sp)
}
