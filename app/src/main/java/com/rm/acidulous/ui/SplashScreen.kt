package com.rm.acidulous.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.R
import com.rm.acidulous.ui.theme.Acid

/**
 * The icon, whole, with the version under it.
 *
 * **Ours rather than the system's, for two reasons Dan named.** Android's own
 * splash takes the launcher icon and *masks* it - a circle on most phones -
 * so the artwork arrives cropped at the moment it is biggest; and it has no
 * way to show text, so the version was only ever readable two menus deep in
 * About. Drawing it here solves both: the foreground vector is painted
 * unmasked at a size nothing is cut off at, and the version sits under it.
 *
 * The system splash is still there - it is the window before the first frame
 * and cannot be turned off - but `values-v31/themes.xml` gives it a
 * transparent icon on the same ink, so what it shows is a flat ground that
 * this then draws onto. No jump between the two.
 *
 * The version is asked of the package manager rather than of `BuildConfig`,
 * for the reason the About window already gives: it is then the version of
 * the APK that is installed, not of the module that happened to be compiled.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // Fades in rather than appearing, because the frame before this one is
    // the system's flat ink and a hard cut between them reads as a flicker.
    val shown by animateFloatAsState(1f, tween(durationMillis = 180), label = "splash")
    Box(
        modifier.fillMaxSize().background(Acid.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.alpha(shown),
        ) {
            // The foreground alone, not the adaptive icon: an
            // `AdaptiveIconDrawable` applies the launcher's mask wherever it
            // is drawn, which is the cropping this exists to avoid. The
            // background layer is a flat square of the same ink the screen
            // already is, so leaving it out costs nothing.
            //
            // Two hundred and fifty-six dp because the artwork occupies the
            // middle sixty-eight of the vector's hundred and eight units -
            // the part an adaptive icon guarantees survives a mask - so what
            // you see is about a hundred and sixty dp of it, which is the
            // size the system's own splash draws its cropped version at.
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(256.dp),
            )
            Text(
                version,
                color = Acid.colors.textMid,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** How long the splash is held before the app arrives. Dan asked for 0.75s. */
const val SplashMillis = 750L
