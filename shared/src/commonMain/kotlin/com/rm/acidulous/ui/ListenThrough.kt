package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.ListenPlan
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.listenSpec
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The one listen-through playing, if any (see `model/Listen.kt`). The
 * engine plays it on the lane's Bias instead of the song, so the transport
 * and the launcher are never touched, and stopping puts the song back.
 */
object Listening {
    /** A phrase being listened through: [take] is the lane as it was when it started. */
    data class Session(val track: Int, val sceneId: String, val phrase: Int, val plan: ListenPlan, val take: TakeRef) {
        fun on(t: Int, s: String) = track == t && sceneId == s
    }

    var session by mutableStateOf<Session?>(null)
        private set

    /** Engine calls in the order they were made, one at a time. */
    private val calls = Channel<Pair<Int, String>>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for ((rack, spec) in calls) {
                val error = runCatching { NativeEngine.listenReel(rack, spec) }.getOrElse { it.message.orEmpty() }
                if (error.isNotEmpty()) {
                    Log.w("Listening", "rack $rack: $error")
                    if (spec.isNotEmpty() && session?.track == rack) session = null
                }
            }
        }
    }

    fun start(track: Int, sceneId: String, phrase: Int, take: TakeRef, plan: ListenPlan) {
        val root = EngineSync.sampleRoot ?: return
        val spec = take.listenSpec(plan, root)
        if (spec.isEmpty()) return
        val was = session
        if (was != null && was.track != track) calls.trySend(was.track to "")
        session = Session(track, sceneId, phrase, plan, take)
        calls.trySend(track to spec)
    }

    fun stop() {
        val s = session ?: return
        session = null
        calls.trySend(s.track to "")
    }

    /** Frames played so far on [track], -1 before the engine has started it. */
    fun played(track: Int): Long = runCatching { NativeEngine.listenPlayed(track) }.getOrDefault(-1L)
}

/**
 * The listen-through button: plays when a phrase is picked, stops while
 * listening. Sits in the gutter left of the ruler.
 */
@Composable
internal fun ListenButton(listening: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.width(GutterW).fillMaxHeight()
            .clip(RoundedCornerShape(2.dp))
            .background(if (listening) c.accent.copy(alpha = 0.35f) else c.control)
            .clickable(onClick = onClick)
            .button(stringResource(if (listening) Res.string.a11y_listen_stop else Res.string.a11y_listen_through)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (listening) "■" else "▶", color = if (listening) c.accent else c.textHi, fontSize = 9.sp, lineHeight = 9.sp)
    }
}
