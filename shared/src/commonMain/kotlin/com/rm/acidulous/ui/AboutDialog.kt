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
 * Who wrote this, its licence, and the third-party code it includes.
 *
 * The GPL and LGPL require that users can read the full licence text, so
 * each licence is a row that opens the whole text. The texts are copied into
 * the app's assets from the files in the tree by the `stageLicences` task.
 *
 * The full text opens in its own window rather than a fourth tab, because
 * [TabbedDialog] sizes every page to the tallest one and the GPL would make
 * the other tabs huge.
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
        pageNames = stringArrayResource(Res.array.about_tabs).toList(),
        onSelectPage = { tab = it },
    )

    reading?.let { LicenceTextDialog(it) { reading = null } }
}

/** The licence texts the app ships, and where the build put each one. */
private enum class Licence(val title: String, val asset: String) {
    Gpl3("GNU General Public License v3", "licences/gpl-3.0.txt"),
    Gpl2("GNU General Public License v2", "licences/gpl-2.0.txt"),
    Lgpl2("GNU Library General Public License v2", "licences/lgpl-2.0.txt"),
    Lgpl21("GNU Lesser General Public License v2.1", "licences/lgpl-2.1.txt"),
    Apache2("Apache License 2.0", "licences/apache-2.0.txt"),
    Bsl1("Boost Software License 1.0", "licences/bsl-1.0.txt"),
    PublicDomain("Unlicense or MIT-0", "licences/miniaudio.txt"),
    /** Travels with the dictionary as one of the app's own files, the same on every platform. */
    Cmu("CMU Pronouncing Dictionary licence", "files/dictionary-licence.txt"),
}

@Composable
private fun AppTab() {
    val c = Acid.colors
    val resources = AppStrings
    // From the platform rather than BuildConfig; see AppHost.versionName.
    val version = remember { AppHost.current.versionLong ?: resources.getString(Res.string.about_version_unknown) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Acidulous", color = c.text, fontSize = 22.sp)
        Readout(stringResource(Res.string.about_version, version))
        // The Android build keeps the line as is. Other platforms add which
        // one they are after "for Android".
        val platform = com.rm.acidulous.AppHost.current.platformName
        val forPlatform = if (platform == null) "" else " " + stringResource(Res.string.about_for_platform, platform)
        Body(stringResource(Res.string.about_what, forPlatform))
        Body("Copyright © 2026 Dan Hunke")
        // A named button for panic. Holding play does the same and is
        // quicker, but this is where you can find it by name.
        androidx.compose.material3.OutlinedButton(
            onClick = { panicEverything() },
            border = androidx.compose.foundation.BorderStroke(1.dp, c.red),
        ) { Text(stringResource(Res.string.about_panic), color = c.red) }
        // The last crash report, if there is one, to share.
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
        // which uses ALSA for MIDI. Browsers use their own.
        when (com.rm.acidulous.AppHost.current.audioStream) {
            com.rm.acidulous.AudioStream.Oboe -> LicenceRow(Licence.Apache2, stringResource(Res.string.about_oboe), onRead)
            com.rm.acidulous.AudioStream.Miniaudio -> {
                LicenceRow(Licence.PublicDomain, stringResource(Res.string.about_miniaudio), onRead)
                if (com.rm.acidulous.AppHost.current.hasAlsa) LicenceRow(Licence.Lgpl21, stringResource(Res.string.about_alsa), onRead)
                // Used under the GPL v3, the app's own licence (NOTICE has the rest).
                if (com.rm.acidulous.AppHost.current.hasDriverSdk) LicenceRow(Licence.Gpl3, stringResource(Res.string.about_driver_sdk), onRead)
            }
            com.rm.acidulous.AudioStream.Browser -> {}
        }
        LicenceRow(Licence.Lgpl2, stringResource(Res.string.about_lame), onRead)
        LicenceRow(Licence.Cmu, stringResource(Res.string.about_cmudict), onRead)
        // Link, and the networking library it uses, when it's built in.
        if (com.rm.acidulous.AppHost.current.hasLink) {
            LicenceRow(Licence.Gpl2, stringResource(Res.string.about_link), onRead)
            LicenceRow(Licence.Bsl1, stringResource(Res.string.about_asio), onRead)
        }
    }
}

@Composable
private fun LicenceRow(licence: Licence, under: String, onRead: (Licence) -> Unit) =
    DialogRow("¶", licence.title, under = under, trailing = stringResource(Res.string.about_read)) { onRead(licence) }

/**
 * One licence in full. Monospace, because these texts are laid out for a
 * fixed width, but wrapped rather than scrolled sideways, since a sideways
 * scroll bar at the bottom of that much text would be unreachable.
 */
@Composable
private fun LicenceTextDialog(licence: Licence, onDismiss: () -> Unit) {
    val resources = AppStrings
    val missing = resources.getString(Res.string.about_licence_missing, licence.asset)
    val text by androidx.compose.runtime.produceState<String?>(null, licence) {
        value = if (licence.asset.startsWith("files/")) {
            runCatching { Res.readBytes(licence.asset).decodeToString() }.getOrNull() ?: missing
        } else {
            AppHost.current.licenceText(licence.asset) ?: missing
        }
    }
    PlainDialog(licence.title, onDismiss = onDismiss, dismissLabel = stringResource(Res.string.close), spacing = 0.dp) {
        Text(
            text ?: "",
            color = Acid.colors.textMid,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** A paragraph. This window has more of them than the rest of the app. */
@Composable
private fun Body(text: String) {
    Text(text, color = Acid.colors.textDim, fontSize = 12.sp, lineHeight = 17.sp)
}
