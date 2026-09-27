package com.rm.acidulous.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid

/**
 * The icon, whole, with the version under it.
 *
 * Drawn by the app because Android's own splash masks the launcher icon
 * (usually to a circle) and can't show text. The system splash still shows
 * before the first frame, but `values-v31/themes.xml` gives it a transparent
 * icon on the same ink, so there's no jump between the two.
 *
 * The version comes from the platform, not `BuildConfig`, so it's the
 * installed version (see AppHost.versionName).
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val version = remember { com.rm.acidulous.AppHost.current.versionName.orEmpty() }
    // Fades in, since a hard cut from the system's flat ink looks like a
    // flicker.
    val shown by animateFloatAsState(1f, tween(durationMillis = 180), label = "splash")
    BoxWithConstraints(
        modifier.fillMaxSize().background(Acid.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        // 256 dp, or 60% of the short edge if that's smaller, so the icon isn't
        // cropped at the largest UI scale on a turned phone and the version line
        // still fits under it.
        val icon = minOf(256.dp, minOf(maxWidth, maxHeight) * 0.6f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.alpha(shown),
        ) {
            // The foreground alone, not the adaptive icon, since an
            // `AdaptiveIconDrawable` applies the launcher's mask wherever it's
            // drawn. The background layer is the same ink as the screen, so
            // leaving it out changes nothing.
            //
            // The artwork is the middle 68 of the vector's 108 units (the part an
            // adaptive icon keeps), so at 256 dp you see about 160 dp of it, the
            // size the system splash draws its cropped version at.
            Image(
                painter = painterResource(Res.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(icon),
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

/** How long the splash is held before the app appears. */
const val SplashMillis = 750L
