package com.rm.acidulous.ui

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
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource

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
enum class KeyAction(val label: StringResource, val group: KeyGroup) {
    PlayStop(Res.string.keys_play_stop, KeyGroup.Transport),
    Record(Res.string.keys_record, KeyGroup.Transport),
    Loop(Res.string.keys_loop, KeyGroup.Transport),
    Undo(Res.string.keys_undo, KeyGroup.Transport),
    Redo(Res.string.keys_redo, KeyGroup.Transport),
    Panic(Res.string.keys_panic, KeyGroup.Transport),
    PlayMode(Res.string.keys_play_mode, KeyGroup.Play),
    Save(Res.string.keys_save, KeyGroup.App),
    FileMenu(Res.string.keys_file_menu, KeyGroup.App),
    Panel(Res.string.keys_panel, KeyGroup.App),
    Help(Res.string.keys_help, KeyGroup.App),
    KeysHelp(Res.string.keys_keys_help, KeyGroup.App),
    Back(Res.string.keys_back, KeyGroup.App),
    PagePrev(Res.string.keys_page_prev, KeyGroup.Editor),
    PageNext(Res.string.keys_page_next, KeyGroup.Editor),
    EditMode(Res.string.keys_edit_mode, KeyGroup.Editor),
    StepView(Res.string.keys_step_view, KeyGroup.Editor),
    LockSteps(Res.string.keys_lock_steps, KeyGroup.Editor),
    Generate(Res.string.keys_generate, KeyGroup.Editor),
    Quantise(Res.string.keys_quantise, KeyGroup.Editor),
    FoldPanel(Res.string.keys_fold_panel, KeyGroup.Editor),
    FoldKeys(Res.string.keys_fold_keys, KeyGroup.Editor),
}

enum class KeyGroup(val label: StringResource) {
    Transport(Res.string.keys_group_transport),
    Play(Res.string.keys_group_play),
    App(Res.string.keys_group_app),
    Editor(Res.string.keys_group_editor),
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
        fun of(e: KeyPress) = KeyChord(e.keyCode, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, e.isMetaPressed)

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
    KeyCodes.KEYCODE_SPACE -> "Space"
    KeyCodes.KEYCODE_GRAVE -> "`"
    KeyCodes.KEYCODE_SYM -> "Sym"
    KeyCodes.KEYCODE_ESCAPE -> "Esc"
    KeyCodes.KEYCODE_LEFT_BRACKET -> "["
    KeyCodes.KEYCODE_RIGHT_BRACKET -> "]"
    KeyCodes.KEYCODE_SLASH -> "/"
    KeyCodes.KEYCODE_PERIOD -> "."
    KeyCodes.KEYCODE_COMMA -> ","
    KeyCodes.KEYCODE_MINUS -> "-"
    KeyCodes.KEYCODE_EQUALS -> "="
    KeyCodes.KEYCODE_SEMICOLON -> ";"
    KeyCodes.KEYCODE_APOSTROPHE -> "'"
    KeyCodes.KEYCODE_ENTER -> "Enter"
    KeyCodes.KEYCODE_TAB -> "Tab"
    else -> KeyCodes.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
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
        KeyAction.PlayStop to listOf(k(KeyCodes.KEYCODE_SPACE)),
        KeyAction.Record to listOf(k(KeyCodes.KEYCODE_R), alt(KeyCodes.KEYCODE_R)),
        KeyAction.Loop to listOf(k(KeyCodes.KEYCODE_L), alt(KeyCodes.KEYCODE_L)),
        KeyAction.Undo to listOf(k(KeyCodes.KEYCODE_Z, ctrl = true), alt(KeyCodes.KEYCODE_Z)),
        KeyAction.Redo to listOf(k(KeyCodes.KEYCODE_Z, ctrl = true, shift = true), alt(KeyCodes.KEYCODE_Y)),
        KeyAction.Panic to listOf(k(KeyCodes.KEYCODE_PERIOD, ctrl = true), alt(KeyCodes.KEYCODE_P)),
        KeyAction.PlayMode to listOf(k(KeyCodes.KEYCODE_GRAVE), k(KeyCodes.KEYCODE_SYM)),
        KeyAction.Save to listOf(k(KeyCodes.KEYCODE_S, ctrl = true), alt(KeyCodes.KEYCODE_S)),
        KeyAction.FileMenu to listOf(k(KeyCodes.KEYCODE_F), alt(KeyCodes.KEYCODE_F)),
        KeyAction.Panel to listOf(k(KeyCodes.KEYCODE_M), alt(KeyCodes.KEYCODE_M)),
        KeyAction.Help to listOf(k(KeyCodes.KEYCODE_F1), alt(KeyCodes.KEYCODE_H)),
        KeyAction.KeysHelp to listOf(k(KeyCodes.KEYCODE_SLASH, shift = true), alt(KeyCodes.KEYCODE_Q)),
        KeyAction.Back to listOf(k(KeyCodes.KEYCODE_ESCAPE)),
        KeyAction.PagePrev to listOf(k(KeyCodes.KEYCODE_LEFT_BRACKET), alt(KeyCodes.KEYCODE_B)),
        KeyAction.PageNext to listOf(k(KeyCodes.KEYCODE_RIGHT_BRACKET), alt(KeyCodes.KEYCODE_N)),
        KeyAction.EditMode to listOf(k(KeyCodes.KEYCODE_D), alt(KeyCodes.KEYCODE_D)),
        KeyAction.StepView to listOf(k(KeyCodes.KEYCODE_T), alt(KeyCodes.KEYCODE_T)),
        KeyAction.LockSteps to listOf(k(KeyCodes.KEYCODE_K), alt(KeyCodes.KEYCODE_K)),
        KeyAction.Generate to listOf(k(KeyCodes.KEYCODE_G), alt(KeyCodes.KEYCODE_G)),
        KeyAction.Quantise to listOf(k(KeyCodes.KEYCODE_Q), alt(KeyCodes.KEYCODE_U)),
        KeyAction.FoldPanel to listOf(k(KeyCodes.KEYCODE_P), alt(KeyCodes.KEYCODE_V)),
        KeyAction.FoldKeys to listOf(k(KeyCodes.KEYCODE_B), alt(KeyCodes.KEYCODE_J)),
    )
}

/**
 * The note keys, Ableton's layout: A to ; are the white keys from C, the
 * row above them the black ones, as a piano's black keys sit above and
 * between its white ones. Semitones from the octave's C.
 */
internal val NOTE_KEYS: Map<Int, Int> = mapOf(
    KeyCodes.KEYCODE_A to 0, KeyCodes.KEYCODE_W to 1, KeyCodes.KEYCODE_S to 2, KeyCodes.KEYCODE_E to 3,
    KeyCodes.KEYCODE_D to 4, KeyCodes.KEYCODE_F to 5, KeyCodes.KEYCODE_T to 6, KeyCodes.KEYCODE_G to 7,
    KeyCodes.KEYCODE_Y to 8, KeyCodes.KEYCODE_H to 9, KeyCodes.KEYCODE_U to 10, KeyCodes.KEYCODE_J to 11,
    KeyCodes.KEYCODE_K to 12, KeyCodes.KEYCODE_O to 13, KeyCodes.KEYCODE_L to 14, KeyCodes.KEYCODE_P to 15,
    KeyCodes.KEYCODE_SEMICOLON to 16, KeyCodes.KEYCODE_APOSTROPHE to 17,
)

/**
 * A tracker's layout, for a full keyboard: two octaves on two rows, Z to /
 * and Q to P, with the rows above each as the black keys - the number row
 * for the upper octave. Z, X, C and V are notes here, so the octave is on -
 * and = and the velocity on [ and ].
 */
internal val TRACKER_KEYS: Map<Int, Int> = mapOf(
    KeyCodes.KEYCODE_Z to 0, KeyCodes.KEYCODE_S to 1, KeyCodes.KEYCODE_X to 2, KeyCodes.KEYCODE_D to 3,
    KeyCodes.KEYCODE_C to 4, KeyCodes.KEYCODE_V to 5, KeyCodes.KEYCODE_G to 6, KeyCodes.KEYCODE_B to 7,
    KeyCodes.KEYCODE_H to 8, KeyCodes.KEYCODE_N to 9, KeyCodes.KEYCODE_J to 10, KeyCodes.KEYCODE_M to 11,
    KeyCodes.KEYCODE_COMMA to 12, KeyCodes.KEYCODE_L to 13, KeyCodes.KEYCODE_PERIOD to 14,
    KeyCodes.KEYCODE_SEMICOLON to 15, KeyCodes.KEYCODE_SLASH to 16,
    KeyCodes.KEYCODE_Q to 12, KeyCodes.KEYCODE_2 to 13, KeyCodes.KEYCODE_W to 14, KeyCodes.KEYCODE_3 to 15,
    KeyCodes.KEYCODE_E to 16, KeyCodes.KEYCODE_R to 17, KeyCodes.KEYCODE_5 to 18, KeyCodes.KEYCODE_T to 19,
    KeyCodes.KEYCODE_6 to 20, KeyCodes.KEYCODE_Y to 21, KeyCodes.KEYCODE_7 to 22, KeyCodes.KEYCODE_U to 23,
    KeyCodes.KEYCODE_I to 24, KeyCodes.KEYCODE_9 to 25, KeyCodes.KEYCODE_O to 26, KeyCodes.KEYCODE_0 to 27,
    KeyCodes.KEYCODE_P to 28,
)

/** Which letters are which notes, and which keys move the octave and the velocity. */
enum class NoteLayout(
    internal val notes: Map<Int, Int>,
    internal val octaveDown: Int, internal val octaveUp: Int,
    internal val velocityDown: Int, internal val velocityUp: Int,
) {
    Piano(NOTE_KEYS, KeyCodes.KEYCODE_Z, KeyCodes.KEYCODE_X, KeyCodes.KEYCODE_C, KeyCodes.KEYCODE_V),
    Tracker(TRACKER_KEYS, KeyCodes.KEYCODE_MINUS, KeyCodes.KEYCODE_EQUALS, KeyCodes.KEYCODE_LEFT_BRACKET, KeyCodes.KEYCODE_RIGHT_BRACKET),
}

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

    /** Waiting for a key to assign in the keys window; the next key goes here. */
    var learning by mutableStateOf<((KeyChord) -> Unit)?>(null)

    private fun isModifier(code: Int) = code in setOf(
        KeyCodes.KEYCODE_SHIFT_LEFT, KeyCodes.KEYCODE_SHIFT_RIGHT, KeyCodes.KEYCODE_CTRL_LEFT, KeyCodes.KEYCODE_CTRL_RIGHT,
        KeyCodes.KEYCODE_ALT_LEFT, KeyCodes.KEYCODE_ALT_RIGHT, KeyCodes.KEYCODE_META_LEFT, KeyCodes.KEYCODE_META_RIGHT,
        KeyCodes.KEYCODE_FUNCTION,
    )

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
    fun preview(e: KeyPress): Boolean {
        usingKeys = true
        // Learning a key for the keys window: the next real key is the answer,
        // whatever it would otherwise have done.
        learning?.let { learn ->
            if (e.action == KeyCodes.ACTION_DOWN && !isModifier(e.keyCode)) {
                learning = null
                taken += e.keyCode
                learn(KeyChord.of(e))
            }
            return true
        }
        if (typing) return false
        val code = e.keyCode
        if (e.action == KeyCodes.ACTION_UP) {
            sounding.remove(code)?.let { (rack, note) -> NativeEngine.noteOff(rack, note); return true }
            return taken.remove(code)
        }
        if (e.action != KeyCodes.ACTION_DOWN) return false
        val chord = KeyChord.of(e)
        val layout = UiPrefs.noteLayout
        // Shift plays an octave up, unless the chord is a shortcut: Shift+/
        // is the list of keys, and the tracker layout has / as a note.
        val shortcut = chord.shift && actionFor(chord, UiPrefs.keyBindings) != null
        if (playMode && chord.plain && !shortcut) {
            layout.notes[code]?.let { semitone ->
                if (e.repeatCount == 0 && code !in sounding) {
                    val rack = target()
                    val note = noteFor(semitone + (if (chord.shift) 12 else 0), octave, drumVoices(rack))
                    NativeEngine.noteOn(rack, note, velocity)
                    sounding[code] = rack to note
                }
                return true
            }
            when (code) {
                layout.octaveDown -> { if (e.repeatCount == 0) moveOctave(octave - 1); taken += code; return true }
                layout.octaveUp -> { if (e.repeatCount == 0) moveOctave(octave + 1); taken += code; return true }
                layout.velocityDown -> { if (e.repeatCount == 0) velocity = (velocity - 20).coerceAtLeast(7); taken += code; return true }
                layout.velocityUp -> { if (e.repeatCount == 0) velocity = (velocity + 20).coerceAtMost(127); taken += code; return true }
            }
        }
        // Chords with a modifier, and the keys that are never a control's own.
        // Not Esc: a grabbed knob or the roll's cursor lets go on it first,
        // and only an Esc nothing wanted comes back as back - see [fallback].
        val always = !chord.plain || shortcut || code == KeyCodes.KEYCODE_SPACE || code == KeyCodes.KEYCODE_SYM ||
            code == KeyCodes.KEYCODE_GRAVE || code in KeyCodes.KEYCODE_F1..KeyCodes.KEYCODE_F12
        if (!always) return false
        return dispatch(e, chord)
    }

    /** After the focused control has passed on it: the plain letters, out of play mode. */
    fun fallback(e: KeyPress): Boolean {
        if (typing || e.action != KeyCodes.ACTION_DOWN) return false
        val chord = KeyChord.of(e)
        if (playMode && chord.plain && e.keyCode != KeyCodes.KEYCODE_ESCAPE) return false
        return dispatch(e, chord)
    }

    private fun dispatch(e: KeyPress, chord: KeyChord): Boolean {
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
        title = stringResource(Res.string.keys_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.done),
        spacing = 8.dp,
    ) {
        androidx.compose.material3.Text(
            stringResource(
                if (UiPrefs.noteLayout == NoteLayout.Tracker) Res.string.keys_notes_line_tracker else Res.string.keys_notes_line,
            ),
            color = c.textMid, fontSize = 12.sp,
        )
        androidx.compose.material3.Text(
            stringResource(Res.string.keys_nav_line),
            color = c.textMid, fontSize = 12.sp,
        )
        for (group in KeyGroup.entries) {
            val actions = live.filter { it.group == group }
            if (actions.isEmpty()) continue
            androidx.compose.material3.Text(
                stringResource(group.label), color = c.teal, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (action in actions) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(
                        stringResource(action.label), color = c.text, fontSize = 13.sp,
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
