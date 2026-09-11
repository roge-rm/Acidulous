package com.rm.acidulous.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The measurements the two step editors and the automation strip have to
 * agree on.
 *
 * The piano roll, the drum grid and the automation strip are three different
 * views of one clip, and a player reads them as one thing: names down the
 * left, time across the top, stacked. So the gutter is one width - a tick is
 * then at the same x in all three and the playheads line up - and a name is
 * one size, whether it names a pitch or a drum voice.
 *
 * The roll draws its text with a TextMeasurer inside a Canvas rather than
 * with Text, so these sizes cannot come from the app's typography; this is
 * where they live instead. Change them here, not at the use site.
 */
internal val GutterWidth = 34.dp

/** Tall enough for a bar number at [RulerTextSize], and no taller. */
internal val RulerHeight = 18.dp

/** A pitch in the roll's gutter, a voice in the drum grid. */
internal val NameTextSize = 10.sp

/** Bar numbers along the top. */
internal val RulerTextSize = 12.sp

/** The beats between them, and other second-rank marks. */
internal val TickTextSize = 9.sp
