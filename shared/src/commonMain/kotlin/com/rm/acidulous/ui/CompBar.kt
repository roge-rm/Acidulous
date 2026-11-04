package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.TakeScores
import com.rm.acidulous.model.scoreSpec
import com.rm.acidulous.model.stack
import com.rm.acidulous.model.takeCount
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Each stack's take scores by phrase, worked out by the engine off the main
 * thread and kept by stack and phrases, like `Phrasing`'s cuts.
 */
object Scoring {
    private val cache = com.rm.acidulous.util.LruMap<String, TakeScores>(16)

    /** Everything the scores depend on: the takes, the phrases and the grid. */
    private fun keyOf(t: TakeRef, cuts: List<Int>): String = buildString {
        append(t.frames).append('|').append(t.bpm).append('|').append(t.startTick)
        for (s in t.stack()) append('|').append(s.file).append('@').append(s.offset).append(':').append(s.start).append('-').append(s.end)
        append('#')
        cuts.joinTo(this, ",")
    }

    /** [take]'s scores over [cuts], from the cache or the engine. Null if it can't score it. */
    suspend fun scores(root: File?, take: TakeRef, cuts: List<Int>): TakeScores? {
        if (root == null || take.takeCount < 2 || cuts.size < 2) return null
        val key = keyOf(take, cuts)
        com.rm.acidulous.util.locked(this) { cache[key] }?.let { return it }
        return withContext(Dispatchers.Default) {
            val spec = take.scoreSpec(cuts, root)
            if (spec.isEmpty()) return@withContext null
            val made = TakeScores.of(runCatching { NativeEngine.scoreTakes(spec) }.getOrElse { Log.w("Scoring", it.message.orEmpty()); null })
            if (made != null) com.rm.acidulous.util.locked(this@Scoring) { cache[key] = made }
            made
        }
    }

    /** [take]'s scores, or null until they're ready. */
    @Composable
    fun rememberScores(root: File?, take: TakeRef?, cuts: List<Int>?): TakeScores? {
        val key = if (take != null && cuts != null) keyOf(take, cuts) else ""
        var got by remember(key) { mutableStateOf(com.rm.acidulous.util.locked(this) { cache[key] }) }
        LaunchedEffect(key) {
            if (got != null || take == null || cuts == null) return@LaunchedEffect
            got = scores(root, take, cuts)
        }
        return got
    }
}

/**
 * The comp tools over a lane's take rows: suggest fills every phrase with
 * its best take, line up moves this lane's phrases onto another lane's
 * timing, and blend plays every take at once.
 *
 * [leads] are the lanes it can line up on, [lined] whether it's lined up
 * now: tapping it then puts it back.
 */
@Composable
internal fun CompBar(
    take: TakeRef,
    leads: List<Int>,
    lined: Boolean,
    onSuggest: () -> Unit,
    onLineUp: (Int) -> Unit,
    onUnline: () -> Unit,
    onBlend: (Boolean) -> Unit,
) {
    val c = Acid.colors
    val stacked = take.takeCount > 1
    Row(Modifier.fillMaxWidth().height(18.dp).padding(bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(GutterW))
        if (stacked) {
            Chip(stringResource(Res.string.tape_comp_suggest), lit = false, onClick = onSuggest)
        }
        if (leads.isNotEmpty() || lined) {
            var menu by remember { mutableStateOf(false) }
            Box {
                Chip(
                    stringResource(if (lined) Res.string.tape_comp_lined_up else Res.string.tape_comp_line_up),
                    lit = lined,
                    onClick = { if (lined) onUnline() else if (leads.size == 1) onLineUp(leads[0]) else menu = true },
                )
                val menuScroll = rememberScrollState()
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ScaledMenu(menuScroll) {
                        for (l in leads) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.tape_comp_line_up_to, l + 1), fontSize = 12.sp, color = c.text) },
                                onClick = { menu = false; onLineUp(l) },
                            )
                        }
                    }
                }
            }
        }
        if (stacked) {
            Chip(stringResource(Res.string.tape_comp_blend), lit = take.blend, onClick = { onBlend(!take.blend) })
        }
    }
}

@Composable
private fun Chip(label: String, lit: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.padding(start = 2.dp).fillMaxHeight()
            .clip(RoundedCornerShape(3.dp))
            .background(if (lit) c.accent.copy(alpha = 0.35f) else c.control)
            .clickable(onClick = onClick)
            .button(label, if (lit) stringResource(Res.string.a11y_on) else null)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (lit) c.accent else c.textMid, fontSize = 10.sp, lineHeight = 10.sp)
    }
}
