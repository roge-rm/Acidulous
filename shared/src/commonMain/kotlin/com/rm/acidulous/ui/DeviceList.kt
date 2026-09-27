package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.a11y_named
import com.rm.acidulous.ui.theme.Acid
import org.jetbrains.compose.resources.stringResource

/**
 * A choice of audio device, one to a line: a computer's outputs or inputs.
 *
 * [SwitchGrid] is a knob's height at most, which suits three or four choices
 * and not a desktop's list: Dan's Windows machine has a dozen outputs, and in
 * a switch they were a dozen slivers with no names left in them. Here each
 * device gets a line its name fits on (shortened with an ellipsis if not), and
 * past [DeviceRowsShown] the list scrolls, with its bar, the chosen one in view.
 */
@Composable
internal fun DeviceList(
    label: String,
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    val rowPx = with(LocalDensity.current) { (DeviceRowH + DeviceRowGap).roundToPx() }
    LaunchedEffect(Unit) { if (selected > 0) scroll.scrollTo(((selected - 1) * rowPx).coerceAtLeast(0)) }
    Column(modifier.widthIn(min = 160.dp, max = 340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.silent())
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = DeviceRowH * DeviceRowsShown + DeviceRowGap * (DeviceRowsShown - 1))
                .clip(RoundedCornerShape(4.dp)).background(Acid.colors.card)
                .verticalScrollWithBar(scroll),
            verticalArrangement = Arrangement.spacedBy(DeviceRowGap),
        ) {
            labels.forEachIndexed { i, l ->
                val on = i == selected
                val name = stringResource(Res.string.a11y_named, label, l)
                Box(
                    Modifier.fillMaxWidth().height(DeviceRowH)
                        .background(if (on) Acid.colors.green else Acid.colors.control)
                        .clickable(enabled = enabled) { onPick(i) }
                        .choice(name, on),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        l, color = if (on) Acid.colors.onAccent else if (enabled) Acid.colors.textMid else Acid.colors.textFaint,
                        fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

private val DeviceRowH = 26.dp
private val DeviceRowGap = 1.dp
private const val DeviceRowsShown = 6
