package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Measurements the piano roll, the drum grid and the automation strip share.
 *
 * They show the same clip stacked on top of each other, so the gutter has one
 * width (a tick is at the same x in all three and the playheads line up) and
 * names have one size.
 *
 * The roll draws text with a TextMeasurer in a Canvas, so these sizes can't
 * come from the app's typography. Change them here, not where they're used.
 */
internal val GutterWidth = 34.dp

/** Tall enough for a bar number at [RulerTextSize]. */
internal val RulerHeight = 18.dp

/** A pitch in the roll's gutter, a voice in the drum grid. */
internal val NameTextSize = 10.sp

/** Bar numbers along the top. */
internal val RulerTextSize = 12.sp

/** The beats between bar numbers, and other minor marks. */
internal val TickTextSize = 9.sp

/**
 * The drum grid's rows: the gap between them, and the limits of the automatic
 * height.
 *
 * Rows share whatever height the grid's slot has between the machine's voices.
 * Below the floor boxes get too small to tap, so the grid scrolls instead. The
 * ceiling only applies to the automatic fit, a pinch can go past it.
 */
internal const val RowGap = 2f
internal const val MinRow = 14f
internal const val MaxRow = 44f
/**
 * The fit's ceiling on a big screen, so tall windows fill their grid. Phones
 * keep [MaxRow].
 */
internal const val MaxRowLarge = 88f

