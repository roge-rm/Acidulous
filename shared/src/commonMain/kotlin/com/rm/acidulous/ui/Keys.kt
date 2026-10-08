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
 * The hardware keyboard, for playing and for shortcuts.
 *
 * In play mode the letters are a piano like Ableton's computer keyboard: the
 * home row is the white keys and the row above it the black ones. Notes go
 * where hardware MIDI goes, so recording and the chord, scale and arp chips
 * work the same. Outside play mode the letters are shortcuts. Shortcuts with a
 * modifier, and Space for play, work in both.
 *
 * Every shortcut is a [KeyAction] with up to two [KeyChord]s: one for a full
 * keyboard (Ctrl, brackets, Esc) and one for a phone's built-in keyboard, which
 * only has letters, Alt, Sym and a touchpad that swipes as a d-pad. The user's
 * bindings live in [UiPrefs].
 *
 * What an action does depends on the screen. Each screen and window registers a
 * [KeyScope], and the innermost one that handles the action runs it, so undo is
 * the song's on the grid and the clip's in the editor.
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
    TrackPrev(Res.string.keys_track_prev, KeyGroup.Editor),
    TrackNext(Res.string.keys_track_next, KeyGroup.Editor),
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
 * Actions that still work with a window open: play and stop, panic and the
 * other things you need mid-take. Everything else is blocked, so a letter typed
 * in Settings doesn't arm recording behind it.
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
    /** No modifier held. These only do anything outside play mode. */
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
    if (shift) append(AppStrings.getString(Res.string.keys_name_shift) + "+")
    if (meta) append("Meta+")
    append(keyName(key))
}

internal fun keyName(code: Int): String = when (code) {
    KeyCodes.KEYCODE_SPACE -> AppStrings.getString(Res.string.keys_name_space)
    KeyCodes.KEYCODE_GRAVE -> "`"
    KeyCodes.KEYCODE_SYM -> "Sym"
    KeyCodes.KEYCODE_ESCAPE -> AppStrings.getString(Res.string.keys_name_esc)
    KeyCodes.KEYCODE_LEFT_BRACKET -> "["
    KeyCodes.KEYCODE_RIGHT_BRACKET -> "]"
    KeyCodes.KEYCODE_SLASH -> "/"
    KeyCodes.KEYCODE_PERIOD -> "."
    KeyCodes.KEYCODE_COMMA -> ","
    KeyCodes.KEYCODE_MINUS -> "-"
    KeyCodes.KEYCODE_EQUALS -> "="
    KeyCodes.KEYCODE_SEMICOLON -> ";"
    KeyCodes.KEYCODE_APOSTROPHE -> "'"
    KeyCodes.KEYCODE_ENTER -> AppStrings.getString(Res.string.keys_name_enter)
    KeyCodes.KEYCODE_TAB -> "Tab"
    else -> KeyCodes.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * The default keys. The first of each pair is for a full keyboard, the second
 * for a keyboard with only letters, Alt and Sym, like the square phones have.
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
        // Shift and the page keys, so the pair sits beside pages.
        KeyAction.TrackPrev to listOf(k(KeyCodes.KEYCODE_LEFT_BRACKET, shift = true)),
        KeyAction.TrackNext to listOf(k(KeyCodes.KEYCODE_RIGHT_BRACKET, shift = true)),
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
 * The note keys in Ableton's layout: A to ; are the white keys from C, the row
 * above them the black keys. Values are semitones from the octave's C.
 */
internal val NOTE_KEYS: Map<Int, Int> = mapOf(
    KeyCodes.KEYCODE_A to 0, KeyCodes.KEYCODE_W to 1, KeyCodes.KEYCODE_S to 2, KeyCodes.KEYCODE_E to 3,
    KeyCodes.KEYCODE_D to 4, KeyCodes.KEYCODE_F to 5, KeyCodes.KEYCODE_T to 6, KeyCodes.KEYCODE_G to 7,
    KeyCodes.KEYCODE_Y to 8, KeyCodes.KEYCODE_H to 9, KeyCodes.KEYCODE_U to 10, KeyCodes.KEYCODE_J to 11,
    KeyCodes.KEYCODE_K to 12, KeyCodes.KEYCODE_O to 13, KeyCodes.KEYCODE_L to 14, KeyCodes.KEYCODE_P to 15,
    KeyCodes.KEYCODE_SEMICOLON to 16, KeyCodes.KEYCODE_APOSTROPHE to 17,
)

/**
 * A tracker layout for a full keyboard: two octaves on two rows, Z to / and Q
 * to P, with the row above each as the black keys (the number row for the upper
 * octave). Z, X, C and V are notes here, so the octave is on - and = and
 * velocity on [ and ].
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

/** The action [bindings] has for this chord, if any. */
internal fun actionFor(chord: KeyChord, bindings: Map<KeyAction, List<KeyChord>>): KeyAction? =
    bindings.entries.firstOrNull { (_, chords) -> chord in chords }?.key

/**
 * The note a play-mode key sounds: [semitone] above the octave's C, or on a
 * drum machine the [semitone]th of its [voices], wrapping round.
 */
internal fun noteFor(semitone: Int, octave: Int, voices: List<Int>?): Int =
    if (!voices.isNullOrEmpty()) voices[semitone % voices.size]
    else ((octave + 1) * 12 + semitone).coerceIn(0, 127)

private val MAJOR = setOf(0, 2, 4, 5, 7, 9, 11)

/**
 * The [degree]th note of a scale up from its [root] (a pitch class) in
 * [octave], MIDI numbering: degree 0 is the root, and past the scale's last
 * note it carries on in the next octave.
 */
internal fun degreeNote(degree: Int, octave: Int, root: Int, classes: Set<Int>): Int {
    val steps = classes.map { (it - root + 12) % 12 }.distinct().sorted().ifEmpty { listOf(0) }
    return noteFor(root + steps[degree % steps.size] + 12 * (degree / steps.size), octave, null)
}

/** One registered set of handlers, a screen's or a window's. */
class KeyScopeHandle internal constructor(
    val window: Boolean,
    internal var handlers: Map<KeyAction, () -> Unit>,
)

object KeyHub {
    /** Letters are notes. */
    var playMode by mutableStateOf(false)
        private set
    /**
     * The octave the A key plays C of, MIDI numbering (4 is middle C). In the
     * editor it follows the on-screen keyboard, so Z and X move both. See
     * [follow].
     */
    var octave by mutableIntStateOf(4)
        private set
    private var octaveSink: ((Int) -> Unit)? = null

    /** The editor's keyboard octave, and where Z and X send a new one. Null disconnects. */
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
    /**
     * A focused control's long-press actions open as a menu (Alt+Enter), or
     * null.
     */
    var actionMenu by mutableStateOf<List<androidx.compose.ui.semantics.CustomAccessibilityAction>?>(null)

    /**
     * Set while the keys window waits for a key to assign. The next key goes
     * here.
     */
    var learning by mutableStateOf<((KeyChord) -> Unit)?>(null)

    private fun isModifier(code: Int) = code in setOf(
        KeyCodes.KEYCODE_SHIFT_LEFT, KeyCodes.KEYCODE_SHIFT_RIGHT, KeyCodes.KEYCODE_CTRL_LEFT, KeyCodes.KEYCODE_CTRL_RIGHT,
        KeyCodes.KEYCODE_ALT_LEFT, KeyCodes.KEYCODE_ALT_RIGHT, KeyCodes.KEYCODE_META_LEFT, KeyCodes.KEYCODE_META_RIGHT,
        KeyCodes.KEYCODE_FUNCTION,
    )

    /** A text field has focus, so it gets every key. */
    internal var typing = false

    /**
     * True when the last input was a key press, not a touch. A window opened
     * then focuses its first control so the keys keep working. Opened by touch
     * it doesn't, so windows don't show a focus ring for no reason.
     */
    var usingKeys = false

    /**
     * Where typed notes go. The app sets it to the track hardware MIDI plays.
     */
    var target: () -> Int = { 0 }
    /**
     * A drum machine's voice notes in pad order for the [target] track, null for melodic machines.
     */
    var drumVoices: (Int) -> List<Int>? = { null }

    /**
     * Moves focus to the screen's first control, for when nothing has focus.
     * Set by the app, where the focus manager is. True if it moved.
     */
    var focusFirst: () -> Boolean = { false }
    /** Whether anything in the main window has focus. Set by the app's root. */
    var anyFocused = false

    private val ARROWS = setOf(
        KeyCodes.KEYCODE_DPAD_UP, KeyCodes.KEYCODE_DPAD_DOWN, KeyCodes.KEYCODE_DPAD_LEFT, KeyCodes.KEYCODE_DPAD_RIGHT,
    )

    /**
     * A track's scale for a controller's notes: its root and pitch classes,
     * from the track's Scale chip or else the song's key. Null plays major
     * from C. Set by the app.
     */
    var scaleOf: (Int) -> Pair<Int, Set<Int>>? = { null }

    /**
     * A controller's note in play mode, for the button [code]: the [degree]th
     * note of the track's scale up from its root in the current octave, or
     * the [degree]th voice on a drum machine. Released by the same button.
     */
    fun padNote(code: Int, degree: Int, down: Boolean, velocityNow: Int = velocity) {
        if (!down) {
            sounding.remove(-code)?.let { (rack, note) -> NativeEngine.noteOff(rack, note) }
            showLit()
            return
        }
        if (-code in sounding) return
        val rack = target()
        val voices = drumVoices(rack)
        val note = if (!voices.isNullOrEmpty()) {
            voices[degree % voices.size]
        } else {
            val (root, classes) = scaleOf(rack) ?: (0 to MAJOR)
            degreeNote(degree, octave, root, classes)
        }
        NativeEngine.noteOn(rack, note, velocityNow)
        // Negative, so a controller's button never meets a keyboard key's code.
        sounding[-code] = rack to note
        showLit()
    }

    /** One octave up or down, for a controller's shoulder buttons. */
    fun shiftOctave(by: Int) = moveOctave(octave + by)

    /** Keys sounding now and what each sent, so key up releases what key down played. */
    private val sounding = HashMap<Int, Pair<Int, Int>>()

    /** The rack and note of everything [sounding], for the on-screen keys to light. */
    var lit by mutableStateOf(emptySet<Pair<Int, Int>>())
        private set

    private fun showLit() { lit = sounding.values.toSet() }
    /** Key downs this consumed, so their key ups are consumed too and don't reach a control. */
    private val taken = HashSet<Int>()

    private val scopes = mutableStateListOf<KeyScopeHandle>()

    internal fun push(handle: KeyScopeHandle) { scopes += handle }
    internal fun remove(handle: KeyScopeHandle) { scopes -= handle }

    /**
     * The actions something on screen handles right now, for the overlay and Android's shortcut
     * list.
     */
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

    /** Release every note a key is holding, on a mode change, lost window or screen change. */
    fun releaseAll() {
        for ((_, sent) in sounding) NativeEngine.noteOff(sent.first, sent.second)
        sounding.clear()
        showLit()
    }

    /**
     * Runs before anything on screen sees the key: play mode's notes, and every
     * chord with a modifier or on Space. Returns true when it was consumed.
     */
    fun preview(e: KeyPress): Boolean {
        usingKeys = true
        // Learning a key for the keys window: the next real key is the answer.
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
            sounding.remove(code)?.let { (rack, note) -> NativeEngine.noteOff(rack, note); showLit(); return true }
            return taken.remove(code)
        }
        if (e.action != KeyCodes.ACTION_DOWN) return false
        // An arrow or Enter with nothing focused, as after a window closes or
        // the screen changes, has nothing to act on. Focus the screen's first
        // control instead, and the next press acts from there. Not in play
        // mode, where arrows are left alone.
        if ((code in ARROWS || code == KeyCodes.KEYCODE_ENTER) && !anyFocused && !playMode && focusFirst()) {
            taken += code
            return true
        }
        val chord = KeyChord.of(e)
        val layout = UiPrefs.noteLayout
        // Shift plays an octave up, unless the chord is a shortcut: Shift+/ is
        // the key list and the tracker layout has / as a note.
        val shortcut = chord.shift && actionFor(chord, UiPrefs.keyBindings) != null
        if (playMode && chord.plain && !shortcut) {
            layout.notes[code]?.let { semitone ->
                if (e.repeatCount == 0 && code !in sounding) {
                    val rack = target()
                    val note = noteFor(semitone + (if (chord.shift) 12 else 0), octave, drumVoices(rack))
                    NativeEngine.noteOn(rack, note, velocity)
                    sounding[code] = rack to note
                    showLit()
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
        // Chords with a modifier, and keys no control uses. Not Esc: a grabbed
        // knob or the roll's cursor handles it first, and only an unused Esc
        // comes back as back (see [fallback]).
        val always = !chord.plain || shortcut || code == KeyCodes.KEYCODE_SPACE || code == KeyCodes.KEYCODE_SYM ||
            code == KeyCodes.KEYCODE_GRAVE || code in KeyCodes.KEYCODE_F1..KeyCodes.KEYCODE_F12
        if (!always) return false
        return dispatch(e, chord)
    }

    /**
     * After the focused control has passed on it: the plain letters, outside play mode.
     */
    fun fallback(e: KeyPress): Boolean {
        if (typing || e.action != KeyCodes.ACTION_DOWN) return false
        val chord = KeyChord.of(e)
        if (playMode && chord.plain && e.keyCode != KeyCodes.KEYCODE_ESCAPE) return false
        return dispatch(e, chord)
    }

    private fun dispatch(e: KeyPress, chord: KeyChord): Boolean {
        val action = actionFor(chord, UiPrefs.keyBindings) ?: return false
        if (e.repeatCount > 0) return handlerFor(action) != null // held: consumed, not repeated
        if (!run(action)) return false
        taken += e.keyCode
        return true
    }
}

/**
 * What the keys do while this is on screen. [window] is for a window over a
 * screen, it stops the screen's keys except for the ones in [THROUGH_WINDOWS].
 */
@Composable
fun KeyScope(vararg handlers: Pair<KeyAction, () -> Unit>, window: Boolean = false) {
    val handle = androidx.compose.runtime.remember { KeyScopeHandle(window, emptyMap()) }
    // Update the handlers every composition since the screen's state changes,
    // while the scope keeps its place in the stack.
    androidx.compose.runtime.SideEffect { handle.handlers = handlers.toMap() }
    // A screen kept behind another (see KeepBuilt) handles nothing.
    val hidden = LocalHidden.current
    DisposableEffect(handle, hidden) {
        if (!hidden) KeyHub.push(handle)
        onDispose { KeyHub.remove(handle) }
    }
}

/**
 * A text field gets every key while it has focus. A field that leaves the
 * screen while focused (a window closed on Enter) releases it too, otherwise
 * every shortcut would stay dead.
 */
fun Modifier.typing(): Modifier = composed {
    DisposableEffect(Unit) { onDispose { KeyHub.typing = false } }
    onFocusChanged { KeyHub.typing = it.isFocused }
}

/**
 * "PlayStop=:62;Undo=c:54,a:54": stored by name, so reordering the enum can't move anybody's
 * keys.
 */
internal fun encodeKeys(bindings: Map<KeyAction, List<KeyChord>>): String =
    bindings.entries.joinToString(";") { (a, chords) -> a.name + "=" + chords.joinToString(",") { it.encode() } }

internal fun decodeKeys(s: String): Map<KeyAction, List<KeyChord>> =
    s.split(';').filter { '=' in it }.mapNotNull { entry ->
        val (name, chords) = entry.split('=', limit = 2)
        val action = runCatching { KeyAction.valueOf(name) }.getOrNull() ?: return@mapNotNull null
        action to chords.split(',').filter { it.isNotBlank() }.mapNotNull { KeyChord.decode(it) }
    }.toMap()

/**
 * The keys the screen handles now, grouped, with the note layout and the
 * navigation keys above them. ? opens it.
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
        // Desktop extras that aren't bindings: the window's own key, and what
        // the mouse does instead of a finger.
        if (com.rm.acidulous.AppHost.current.onDesktop) {
            androidx.compose.material3.Text(
                stringResource(Res.string.keys_group_desktop), color = c.teal, fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
            for ((what, keys) in listOf(
                Res.string.keys_full_screen to "F11",
                Res.string.keys_right_click to stringResource(Res.string.keys_right_click_keys),
                Res.string.keys_wheel_sideways to stringResource(Res.string.keys_name_shift) + "+" + stringResource(Res.string.keys_name_wheel),
                Res.string.keys_wheel_zoom to "Ctrl+" + stringResource(Res.string.keys_name_wheel),
            )) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(stringResource(what), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Text(
                        keys, color = c.accent, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
