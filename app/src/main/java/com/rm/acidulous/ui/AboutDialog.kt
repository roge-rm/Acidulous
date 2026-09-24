package com.rm.acidulous.ui

import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R

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
        title = stringResource(R.string.about_title),
        selected = tab,
        pages = listOf(
            { AppTab() },
            { LicenceTab { reading = it } },
            { ComponentsTab { reading = it } },
        ),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.done),
        spacing = 16.dp,
        chips = { SectionChips(stringArrayResource(R.array.about_tabs).toList(), tab) { tab = it } },
    )

    reading?.let { LicenceTextDialog(it) { reading = null } }
}

/** The three texts the app ships, and where the build staged each one. */
private enum class Licence(val title: String, val asset: String) {
    Gpl3("GNU General Public License v3", "licences/gpl-3.0.txt"),
    Gpl2("GNU General Public License v2", "licences/gpl-2.0.txt"),
    Lgpl2("GNU Library General Public License v2", "licences/lgpl-2.0.txt"),
    Apache2("Apache License 2.0", "licences/apache-2.0.txt"),
    Bsl1("Boost Software License 1.0", "licences/bsl-1.0.txt"),
}

@Composable
private fun AppTab() {
    val c = Acid.colors
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    // Asked of the package manager rather than of BuildConfig, so it is the
    // version of the APK that is actually installed and not of the module
    // that happened to be compiled.
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "%s (%d)".format(info.versionName, info.longVersionCode)
        }.getOrDefault(resources.getString(R.string.about_version_unknown))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Acidulous", color = c.text, fontSize = 22.sp)
        Readout(stringResource(R.string.about_version, version))
        Body(stringResource(R.string.about_what))
        Body("Copyright © 2026 Dan Hunke")
        // The one place that *names* it, which a gesture has no way to be:
        // holding play does the same and is the fast path. Here rather than
        // in the file menu (Dan, 2026-09-23), where it sat among things you
        // choose rather than things you reach for.
        androidx.compose.material3.OutlinedButton(
            onClick = { panicEverything() },
            border = androidx.compose.foundation.BorderStroke(1.dp, c.red),
        ) { Text(stringResource(R.string.about_panic), color = c.red) }
        // The last crash report, while there is one, for whoever asks for it.
        val report = remember { com.rm.acidulous.CrashReports.latest(context) }
        if (report != null) {
            androidx.compose.material3.TextButton(
                onClick = { com.rm.acidulous.shareCrashReport(context, report) },
            ) { Text(stringResource(R.string.about_share_crash)) }
        }
    }
}

@Composable
private fun LicenceTab(onRead: (Licence) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Body(stringResource(R.string.about_gpl_1))
        Body(stringResource(R.string.about_gpl_2))
        ListSection(stringResource(R.string.about_licence_itself)) {
            LicenceRow(Licence.Gpl3, stringResource(R.string.about_licence_app), onRead)
        }
    }
}

@Composable
private fun ComponentsTab(onRead: (Licence) -> Unit) {
    ListSection(
        stringResource(R.string.about_not_ours),
        stringResource(R.string.about_not_ours_note),
    ) {
        LicenceRow(Licence.Apache2, stringResource(R.string.about_oboe), onRead)
        LicenceRow(Licence.Lgpl2, stringResource(R.string.about_lame), onRead)
        LicenceRow(Licence.Gpl2, stringResource(R.string.about_link), onRead)
        LicenceRow(Licence.Bsl1, stringResource(R.string.about_asio), onRead)
    }
}

@Composable
private fun LicenceRow(licence: Licence, under: String, onRead: (Licence) -> Unit) =
    DialogRow("¶", licence.title, under = under, trailing = stringResource(R.string.about_read)) { onRead(licence) }

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
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val text = remember(licence) {
        runCatching {
            context.assets.open(licence.asset).bufferedReader().use { it.readText() }
        }.getOrElse { resources.getString(R.string.about_licence_missing, licence.asset) }
    }
    PlainDialog(licence.title, onDismiss = onDismiss, dismissLabel = stringResource(R.string.close), spacing = 0.dp) {
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
