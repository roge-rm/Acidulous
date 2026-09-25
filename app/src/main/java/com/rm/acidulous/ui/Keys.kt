package com.rm.acidulous.ui

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.R
import com.rm.acidulous.engine.NativeEngine
import androidx.compose.ui.res.stringResource

/**
 * The keyboard: a hardware one, played and driven.
 *
 * Two jobs share the keys. **Play mode** turns the letters into a piano - the
 * home row the white keys, the row above it the black - the way Ableton's
 * computer keyboard does, and sends the notes where hardware MIDI goes, so
 * recording and the chord, scale and arp chips apply unchanged. Out of it the
 * same letters are shortcuts. A shortcut with a modifier, and Space for play,
 * work in both.
 *
 * Every shortcut is a [KeyAction] with up to two [KeyChord]s: one for a full
 * keyboard (Ctrl, brackets, Esc) and one for a phone's built-in keyboard,
 * which has letters, Alt and Sym and a touchpad that swipes as a d-pad and
 * nothing else. The chords are the person's and live in [UiPrefs].
 *
 * What an action *does* depends on the screen. Each screen, and each window,
 * says so with [KeyScope]; the innermost one that knows the action runs it, so
 * undo is the song's on the grid and the clip's in the editor, exactly as the
 * undo pill on each is.
 */
enum class KeyAction(@StringRes val label: Int, val group: KeyGroup) {
    PlayStop(R.string.keys_play_stop, KeyGroup.Transport),
    Record(R.string.keys_record, KeyGroup.Transport),
    Loop(R.string.keys_loop, KeyGroup.Transport),
    Undo(R.string.keys_undo, KeyGroup.Transport),
    Redo(R.string.keys_redo, KeyGroup.Transport),
    Panic(R.string.keys_panic, KeyGroup.Transport),
    PlayMode(R.string.keys_play_mode, KeyGroup.Play),
    Save(R.string.keys_save, KeyGroup.App),
    FileMenu(R.string.keys_file_menu, KeyGroup.App),
    Panel(R.string.keys_panel, KeyGroup.App),
    Help(R.string.keys_help, KeyGroup.App),
    KeysHelp(R.string.keys_keys_help, KeyGroup.App),
    Back(R.string.keys_back, KeyGroup.App),
    PagePrev(R.string.keys_page_prev, KeyGroup.Editor),
    PageNext(R.string.keys_page_next, KeyGroup.Editor),
    EditMode(R.string.keys_edit_mode, KeyGroup.Editor),
    StepView(R.string.keys_step_view, KeyGroup.Editor),
    LockSteps(R.string.keys_lock_steps, KeyGroup.Editor),
    Generate(R.string.keys_generate, KeyGroup.Editor),
    FoldPanel(R.string.keys_fold_panel, KeyGroup.Editor),
    FoldKeys(R.string.keys_fold_keys, KeyGroup.Editor),
}

enum class KeyGroup(@StringRes val label: Int) {
    Transport(R.string.keys_group_transport),
    Play(R.string.keys_group_play),
    App(R.string.keys_group_app),
    Editor(R.string.keys_group_editor),
}

/**
 * Actions that go through a window: play and stop, panic and the rest of the
 * things you reach for mid-take. A window stops everything else, so a letter
 * pressed in Settings does not arm the record behind it.
 */
private val THROUGH_WINDOWS = setOf(
    KeyAction.PlayStop, KeyAction.Panic, KeyAction.PlayMode, KeyAction.KeysHelp, KeyAction.Back,
)

/** One key and the modifiers held with it. */
data class KeyChord(
    val key: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    /** Letters and digits with nothing held, which only mean anything out of play mode. */
    val plain: Boolean get() = !ctrl && !alt && !meta

    fun encode(): String = buildString {
        if (ctrl) append("c")
        if (alt) append("a")
        if (shift) append("s")
        if (meta) append("m")
        append(':').append(key)
    }

    companion object {
        fun of(e: KeyEvent) = KeyChord(e.keyCode, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, e.isMetaPressed)

        fun decode(s: String): KeyChord? {
            val (mods, code) = s.split(':').takeIf { it.size == 2 } ?: return null
            val key = code.toIntOrNull() ?: return null
            return KeyChord(key, 'c' in mods, 'a' in mods, 's' in mods, 'm' in mods)
        }
    }
}

/** How a chord is written on screen: "Ctrl+Z", "Alt+Z", "Space", "`". */
fun KeyChord.words(): String = buildString {
    if (ctrl) append("Ctrl+")
    if (alt) append("Alt+")
    if (shift) append("Shift+")
    if (meta) append("Meta+")
    append(keyName(key))
}

internal fun keyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_GRAVE -> "`"
    KeyEvent.KEYCODE_SYM -> "Sym"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    KeyEvent.KEYCODE_SLASH -> "/"
    KeyEvent.KEYCODE_PERIOD -> "."
    KeyEvent.KEYCODE_COMMA -> ","
    KeyEvent.KEYCODE_MINUS -> "-"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_SEMICOLON -> ";"
    KeyEvent.KEYCODE_APOSTROPHE -> "'"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_TAB -> "Tab"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * The shipped keys. The first of each pair is a full keyboard's; the second
 * is for one with letters, Alt and Sym and nothing else, which is what the
 * square phones have.
 */
val DEFAULT_KEYS: Map<KeyAction, List<KeyChord>> = run {
    fun k(code: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) = KeyChord(code, ctrl, alt, shift)
    val alt = { code: Int -> k(code, alt = true) }
    mapOf(
        KeyAction.PlayStop to listOf(k(KeyEvent.KEYCODE_SPACE)),
        KeyAction.Record to listOf(k(KeyEvent.KEYCODE_R), alt(KeyEvent.KEYCODE_R)),
        KeyAction.Loop to listOf(k(KeyEvent.KEYCODE_L), alt(KeyEvent.KEYCODE_L)),
        KeyAction.Undo to listOf(k(KeyEvent.KEYCODE_Z, ctrl = true), alt(KeyEvent.KEYCODE_Z)),
        KeyAction.Redo to listOf(k(KeyEvent.KEYCODE_Z, ctrl = true, shift = true), alt(KeyEvent.KEYCODE_Y)),
        KeyAction.Panic to listOf(k(KeyEvent.KEYCODE_PERIOD, ctrl = true), alt(KeyEvent.KEYCODE_P)),
        KeyAction.PlayMode to listOf(k(KeyEvent.KEYCODE_GRAVE), k(KeyEvent.KEYCODE_SYM)),
        KeyAction.Save to listOf(k(KeyEvent.KEYCODE_S, ctrl = true), alt(KeyEvent.KEYCODE_S)),
        KeyAction.FileMenu to listOf(k(KeyEvent.KEYCODE_F), alt(KeyEvent.KEYCODE_F)),
        KeyAction.Panel to listOf(k(KeyEvent.KEYCODE_M), alt(KeyEvent.KEYCODE_M)),
        KeyAction.Help to listOf(k(KeyEvent.KEYCODE_F1), alt(KeyEvent.KEYCODE_H)),
        KeyAction.KeysHelp to listOf(k(KeyEvent.KEYCODE_SLASH, shift = true), alt(KeyEvent.KEYCODE_Q)),
        KeyAction.Back to listOf(k(KeyEvent.KEYCODE_ESCAPE)),
        KeyAction.PagePrev to listOf(k(KeyEvent.KEYCODE_LEFT_BRACKET), alt(KeyEvent.KEYCODE_B)),
        KeyAction.PageNext to listOf(k(KeyEvent.KEYCODE_RIGHT_BRACKET), alt(KeyEvent.KEYCODE_N)),
        KeyAction.EditMode to listOf(k(KeyEvent.KEYCODE_D), alt(KeyEvent.KEYCODE_D)),
        KeyAction.StepView to listOf(k(KeyEvent.KEYCODE_T), alt(KeyEvent.KEYCODE_T)),
        KeyAction.LockSteps to listOf(k(KeyEvent.KEYCODE_K), alt(KeyEvent.KEYCODE_K)),
        KeyAction.Generate to listOf(k(KeyEvent.KEYCODE_G), alt(KeyEvent.KEYCODE_G)),
        KeyAction.FoldPanel to listOf(k(KeyEvent.KEYCODE_P), alt(KeyEvent.KEYCODE_V)),
        KeyAction.FoldKeys to listOf(k(KeyEvent.KEYCODE_B), alt(KeyEvent.KEYCODE_J)),
    )
}

/**
 * The note keys, Ableton's layout: A to ; are the white keys from C, the
 * row above them the black ones, as a piano's black keys sit above and
 * between its white ones. Semitones from the octave's C.
 */
internal val NOTE_KEYS: Map<Int, Int> = mapOf(
    KeyEvent.KEYCODE_A to 0, KeyEvent.KEYCODE_W to 1, KeyEvent.KEYCODE_S to 2, KeyEvent.KEYCODE_E to 3,
    KeyEvent.KEYCODE_D to 4, KeyEvent.KEYCODE_F to 5, KeyEvent.KEYCODE_T to 6, KeyEvent.KEYCODE_G to 7,
    KeyEvent.KEYCODE_Y to 8, KeyEvent.KEYCODE_H to 9, KeyEvent.KEYCODE_U to 10, KeyEvent.KEYCODE_J to 11,
    KeyEvent.KEYCODE_K to 12, KeyEvent.KEYCODE_O to 13, KeyEvent.KEYCODE_L to 14, KeyEvent.KEYCODE_P to 15,
    KeyEvent.KEYCODE_SEMICOLON to 16, KeyEvent.KEYCODE_APOSTROPHE to 17,
)
internal const val KEY_OCTAVE_DOWN = KeyEvent.KEYCODE_Z
internal const val KEY_OCTAVE_UP = KeyEvent.KEYCODE_X
internal const val KEY_VELOCITY_DOWN = KeyEvent.KEYCODE_C
internal const val KEY_VELOCITY_UP = KeyEvent.KEYCODE_V

/** The chord an event is, if it is one of [bindings]'s; the action it runs. */
internal fun actionFor(chord: KeyChord, bindings: Map<KeyAction, List<KeyChord>>): KeyAction? =
    bindings.entries.firstOrNull { (_, chords) -> chord in chords }?.key

/**
 * The note a play-mode key sounds: [semitone] above the octave's C, or - on
 * a drum machine, whose pads are not a scale - the [semitone]th of its
 * [voices], wrapping round.
 */
internal fun noteFor(semitone: Int, octave: Int, voices: List<Int>?): Int =
    if (!voices.isNullOrEmpty()) voices[semitone % voices.size]
    else ((octave + 1) * 12 + semitone).coerceIn(0, 127)

/** One registered set of handlers: a screen's or a window's. */
class KeyScopeHandle internal constructor(
    val window: Boolean,
    internal var handlers: Map<KeyAction, () -> Unit>,
)

object KeyHub {
    /** Letters are notes. */
    var playMode by mutableStateOf(false)
        private set
    /**
     * The octave the A key is C of, as MIDI counts it: 4 is middle C. In the
     * editor it is the on-screen keyboard's own, so typing starts where the
     * keys on screen do and Z and X move both - see [follow].
     */
    var octave by mutableIntStateOf(4)
        private set
    private var octaveSink: ((Int) -> Unit)? = null

    /** The editor's keyboard octave, and where Z and X should put a new one. Null lets go. */
    fun follow(octaveNow: Int, sink: ((Int) -> Unit)?) {
        octaveSink = sink
        if (sink != null) octave = octaveNow
    }

    private fun moveOctave(to: Int) {
        octave = to.coerceIn(0, 8)
        octaveSink?.invoke(octave)
    }
    var velocity by mutableIntStateOf(100)
        private set
    /** The shortcuts overlay. */
    var showingKeys by mutableStateOf(false)
    /** A focused control's hold actions, open as a menu (Alt+Enter), or null. */
    var actionMenu by mutableStateOf<List<androidx.compose.ui.semantics.CustomAccessibilityAction>?>(null)

    /** A text field has focus: every key is its. */
    internal var typing = false

    /**
     * The last thing the player did was press a key rather than touch the
     * screen. A window opened then puts the focus on its first control, so
     * the keys go on working; opened by a touch it does not, or every window
     * would open wearing a ring nobody asked for.
     */
    var usingKeys = false

    /** Where typed notes go: set by the app to the track hardware MIDI plays. */
    var target: () -> Int = { 0 }
    /** A drum machine's voice notes in pad order, for the track [target] names; null for anything melodic. */
    var drumVoices: (Int) -> List<Int>? = { null }

    /** Keys sounding now and what each sent, so a key up releases what its key down played. */
    private val sounding = HashMap<Int, Pair<Int, Int>>()
    /** Key downs this took, so their key ups are taken too and do not wander into a control. */
    private val taken = HashSet<Int>()

    private val scopes = mutableStateListOf<KeyScopeHandle>()

    internal fun push(handle: KeyScopeHandle) { scopes += handle }
    internal fun remove(handle: KeyScopeHandle) { scopes -= handle }

    /** The actions something on screen would run now, for the overlay and Android's shortcut list. */
    fun live(): List<KeyAction> = KeyAction.entries.filter { a -> handlerFor(a) != null }

    private fun handlerFor(action: KeyAction): (() -> Unit)? {
        for (scope in scopes.asReversed()) {
            scope.handlers[action]?.let { return it }
            if (scope.window && action !in THROUGH_WINDOWS) return null
        }
        return null
    }

    fun run(action: KeyAction): Boolean {
        val h = handlerFor(action) ?: return false
        h()
        return true
    }

    fun togglePlayMode() {
        if (playMode) releaseAll()
        playMode = !playMode
    }

    /** Let go of every note a key is holding: a mode change, a lost window, a screen left behind. */
    fun releaseAll() {
        for ((_, sent) in sounding) NativeEngine.noteOff(sent.first, sent.second)
        sounding.clear()
    }

    /**
     * Before anything on screen sees the key: play mode's notes, and every
     * chord with a modifier or on Space. True when it was taken.
     */
    fun preview(e: KeyEvent): Boolean {
        usingKeys = true
        if (typing) return false
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_UP) {
            sounding.remove(code)?.let { (rack, note) -> NativeEngine.noteOff(rack, note); return true }
            return taken.remove(code)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val chord = KeyChord.of(e)
        if (playMode && chord.plain) {
            NOTE_KEYS[code]?.let { semitone ->
                if (e.repeatCount == 0 && code !in sounding) {
                    val rack = target()
                    val note = noteFor(semitone + (if (chord.shift) 12 else 0), octave, drumVoices(rack))
                    NativeEngine.noteOn(rack, note, velocity)
                    sounding[code] = rack to note
                }
                return true
            }
            when (code) {
                KEY_OCTAVE_DOWN -> { if (e.repeatCount == 0) moveOctave(octave - 1); taken += code; return true }
                KEY_OCTAVE_UP -> { if (e.repeatCount == 0) moveOctave(octave + 1); taken += code; return true }
                KEY_VELOCITY_DOWN -> { if (e.repeatCount == 0) velocity = (velocity - 20).coerceAtLeast(7); taken += code; return true }
                KEY_VELOCITY_UP -> { if (e.repeatCount == 0) velocity = (velocity + 20).coerceAtMost(127); taken += code; return true }
            }
        }
        // Chords with a modifier, and the keys that are never a control's own.
        val always = !chord.plain || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_SYM ||
            code == KeyEvent.KEYCODE_GRAVE || code == KeyEvent.KEYCODE_ESCAPE ||
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
        if (!always) return false
        return dispatch(e, chord)
    }

    /** After the focused control has passed on it: the plain letters, out of play mode. */
    fun fallback(e: KeyEvent): Boolean {
        if (typing || e.action != KeyEvent.ACTION_DOWN) return false
        val chord = KeyChord.of(e)
        if (playMode && chord.plain) return false
        return dispatch(e, chord)
    }

    private fun dispatch(e: KeyEvent, chord: KeyChord): Boolean {
        val action = actionFor(chord, UiPrefs.keyBindings) ?: return false
        if (e.repeatCount > 0) return handlerFor(action) != null // held: taken, not repeated
        if (!run(action)) return false
        taken += e.keyCode
        return true
    }
}

/**
 * What the keys do while this is on screen. [window] is for a window over a
 * screen: it stops the screen's letters reaching through it, all but
 * play and stop and the other things in [THROUGH_WINDOWS].
 */
@Composable
fun KeyScope(vararg handlers: Pair<KeyAction, () -> Unit>, window: Boolean = false) {
    val handle = androidx.compose.runtime.remember { KeyScopeHandle(window, emptyMap()) }
    // This composition's lambdas, every time: the screen's state moves on and
    // the handlers with it, while the scope keeps its place in the stack.
    androidx.compose.runtime.SideEffect { handle.handlers = handlers.toMap() }
    DisposableEffect(handle) {
        KeyHub.push(handle)
        onDispose { KeyHub.remove(handle) }
    }
}

/**
 * A text field: while it has focus every key is its own. A field that leaves
 * the screen with the focus still in it - a window closed on Enter - lets go
 * too, or every shortcut would stay dead.
 */
fun Modifier.typing(): Modifier = composed {
    DisposableEffect(Unit) { onDispose { KeyHub.typing = false } }
    onFocusChanged { KeyHub.typing = it.isFocused }
}

/** "PlayStop=:62;Undo=c:54,a:54": by name, so a reordered enum cannot move anybody's keys. */
internal fun encodeKeys(bindings: Map<KeyAction, List<KeyChord>>): String =
    bindings.entries.joinToString(";") { (a, chords) -> a.name + "=" + chords.joinToString(",") { it.encode() } }

internal fun decodeKeys(s: String): Map<KeyAction, List<KeyChord>> =
    s.split(';').filter { '=' in it }.mapNotNull { entry ->
        val (name, chords) = entry.split('=', limit = 2)
        val action = runCatching { KeyAction.valueOf(name) }.getOrNull() ?: return@mapNotNull null
        action to chords.split(',').filter { it.isNotBlank() }.mapNotNull { KeyChord.decode(it) }
    }.toMap()

/**
 * The keys the screen answers to now, grouped, with the note layout and the
 * way round the controls above them. A question mark opens it.
 */
@Composable
fun KeysOverlay(onDismiss: () -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    val live = KeyHub.live()
    PlainDialog(
        title = androidx.compose.ui.res.stringResource(R.string.keys_title),
        onDismiss = onDismiss,
        dismissLabel = androidx.compose.ui.res.stringResource(R.string.done),
        spacing = 8.dp,
    ) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.keys_notes_line),
            color = c.textMid, fontSize = 12.sp,
        )
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.keys_nav_line),
            color = c.textMid, fontSize = 12.sp,
        )
        for (group in KeyGroup.entries) {
            val actions = live.filter { it.group == group }
            if (actions.isEmpty()) continue
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(group.label), color = c.teal, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (action in actions) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(action.label), color = c.text, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Text(
                        UiPrefs.keyBindings[action].orEmpty().joinToString("  ") { it.words() },
                        color = c.accent, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
