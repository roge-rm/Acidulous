package com.rm.acidulous.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.acidulous.engine.NativeEngine
import kotlin.math.abs
import kotlin.math.sign

/**
 * A game controller: what its buttons do, and what it last did, for the
 * readout in Settings.
 *
 * The d-pad is arrow keys already, so it moves focus and the editors' cursors
 * as the arrows do. The other buttons stand in for keys (A is Enter, B is Esc,
 * X is Menu) or run an action. Every button is taken, even one with no
 * job: one left alone comes round again as Android's stand-in for it, and on
 * the Retroid Pocket that's Delete for X, which deletes a note in the roll.
 */
object Pad {
    sealed interface Job {
        /** Acts as this key, so everything that takes the key takes the button. */
        data class Key(val code: Int, val alt: Boolean = false) : Job
        data class Action(val action: KeyAction) : Job
        /** Taken, and does nothing. */
        data object Nothing : Job
    }

    /** The buttons whose jobs can be changed, in the order the keys window lists them, with the names printed on them. */
    val BUTTONS: List<Pair<Int, String>> = listOf(
        KeyCodes.KEYCODE_BUTTON_A to "A", KeyCodes.KEYCODE_BUTTON_B to "B",
        KeyCodes.KEYCODE_BUTTON_X to "X", KeyCodes.KEYCODE_BUTTON_Y to "Y",
        KeyCodes.KEYCODE_BUTTON_L1 to "L1", KeyCodes.KEYCODE_BUTTON_R1 to "R1",
        KeyCodes.KEYCODE_BUTTON_L2 to "L2", KeyCodes.KEYCODE_BUTTON_R2 to "R2",
        KeyCodes.KEYCODE_BUTTON_START to "Start", KeyCodes.KEYCODE_BUTTON_SELECT to "Select",
        KeyCodes.KEYCODE_BUTTON_THUMBL to "L3", KeyCodes.KEYCODE_BUTTON_THUMBR to "R3",
    )

    /** Every job a button can be given: pressing, going back, a long press, nothing, or any shortcut's action. */
    val CHOICES: List<Job> = listOf(
        Job.Key(KeyCodes.KEYCODE_ENTER), Job.Key(KeyCodes.KEYCODE_ESCAPE), Job.Key(KeyCodes.KEYCODE_MENU), Job.Nothing,
    ) + KeyAction.entries.map { Job.Action(it) }

    /** A job as it's stored: "key:66", "action:PlayStop", "none". */
    fun encode(job: Job): String = when (job) {
        is Job.Key -> "key:${job.code}"
        is Job.Action -> "action:${job.action.name}"
        Job.Nothing -> "none"
    }

    fun decode(s: String): Job? = when {
        s == "none" -> Job.Nothing
        s.startsWith("key:") -> s.removePrefix("key:").toIntOrNull()?.let { Job.Key(it) }
        s.startsWith("action:") -> KeyAction.entries.firstOrNull { it.name == s.removePrefix("action:") }?.let { Job.Action(it) }
        else -> null
    }

    val DEFAULT_JOBS: Map<Int, Job> = mapOf(
        KeyCodes.KEYCODE_BUTTON_A to Job.Key(KeyCodes.KEYCODE_ENTER),
        KeyCodes.KEYCODE_BUTTON_B to Job.Key(KeyCodes.KEYCODE_ESCAPE),
        // A long press: the focused control's action menu. As the Menu key,
        // not Alt+Enter, which a plain button takes as a press.
        KeyCodes.KEYCODE_BUTTON_X to Job.Key(KeyCodes.KEYCODE_MENU),
        KeyCodes.KEYCODE_BUTTON_Y to Job.Action(KeyAction.PlayStop),
        KeyCodes.KEYCODE_BUTTON_START to Job.Action(KeyAction.PlayMode),
        KeyCodes.KEYCODE_BUTTON_SELECT to Job.Action(KeyAction.FileMenu),
        KeyCodes.KEYCODE_BUTTON_L1 to Job.Action(KeyAction.PagePrev),
        KeyCodes.KEYCODE_BUTTON_R1 to Job.Action(KeyAction.PageNext),
        KeyCodes.KEYCODE_BUTTON_L2 to Job.Action(KeyAction.TrackPrev),
        KeyCodes.KEYCODE_BUTTON_R2 to Job.Action(KeyAction.TrackNext),
    )

    /**
     * In play mode the d-pad and the face buttons are eight notes, round each
     * cluster clockwise from the bottom: the scale's first four on the d-pad,
     * the next four on the buttons.
     */
    private val DEGREES: Map<Int, Int> = mapOf(
        KeyCodes.KEYCODE_DPAD_DOWN to 0, KeyCodes.KEYCODE_DPAD_LEFT to 1,
        KeyCodes.KEYCODE_DPAD_UP to 2, KeyCodes.KEYCODE_DPAD_RIGHT to 3,
        KeyCodes.KEYCODE_BUTTON_B to 4, KeyCodes.KEYCODE_BUTTON_Y to 5,
        KeyCodes.KEYCODE_BUTTON_X to 6, KeyCodes.KEYCODE_BUTTON_A to 7,
    )

    /** How far the triggers are pulled, 0 to 1: the harder of the two. */
    var trigger = 0f
        private set

    /**
     * A controller's key in play mode. The notes, the octave on L1 and R1,
     * play and stop on Select (Y is a note now). Start still leaves play
     * mode, as its job. True when it was taken here.
     */
    fun play(keyCode: Int, down: Boolean, repeat: Int): Boolean {
        DEGREES[keyCode]?.let { degree ->
            if (repeat == 0) {
                // Pulled partway, a trigger says how hard; let go, the keys' velocity.
                val velocity = if (trigger > 0.05f) (20f + trigger * 107f).toInt().coerceIn(1, 127) else KeyHub.velocity
                KeyHub.padNote(keyCode, degree, down, velocity)
            }
            return true
        }
        val first = down && repeat == 0
        return when (keyCode) {
            KeyCodes.KEYCODE_BUTTON_L1 -> { if (first) KeyHub.shiftOctave(-1); true }
            KeyCodes.KEYCODE_BUTTON_R1 -> { if (first) KeyHub.shiftOctave(1); true }
            KeyCodes.KEYCODE_BUTTON_SELECT -> { if (first) KeyHub.run(KeyAction.PlayStop); true }
            KeyCodes.KEYCODE_BUTTON_START -> false
            else -> isButton(keyCode)
        }
    }

    /** A controller button's job, or null for a key that isn't a controller button (the d-pad is arrows). */
    fun jobOf(keyCode: Int): Job? =
        UiPrefs.padJobs[keyCode] ?: DEFAULT_JOBS[keyCode] ?: if (isButton(keyCode)) Job.Nothing else null

    /** A gamepad button: A to Mode, and the numbered ones. Android's KeyEvent.isGamepadButton, on any platform. */
    fun isButton(keyCode: Int): Boolean =
        keyCode in KeyCodes.KEYCODE_BUTTON_A..KeyCodes.KEYCODE_BUTTON_MODE ||
            keyCode in KeyCodes.KEYCODE_BUTTON_1..KeyCodes.KEYCODE_BUTTON_16

    /** One axis the controller has: its name, where it is now, and how far from centre it counts as resting. */
    data class Axis(val name: String, val value: Float, val flat: Float)

    /** The controller's name, as it calls itself. Empty until one has been used. */
    var device by mutableStateOf("")
        private set
    /** The last button, as its key name and code, and whether it went down or up. */
    var lastButton by mutableStateOf("")
        private set
    var axes by mutableStateOf(emptyList<Axis>())
        private set
    /** Every button pressed since the app started, in the order first pressed, so one look shows them all. */
    var seen by mutableStateOf(emptyList<String>())
        private set

    fun button(device: String, keyCode: Int, down: Boolean) {
        this.device = device
        val name = "${keyName(keyCode)} $keyCode"
        lastButton = "$name ${if (down) "down" else "up"}"
        if (name !in seen) seen = seen + name
    }

    /** Every axis the controller has, read from one motion event. */
    fun moved(device: String, axes: List<Axis>) {
        this.device = device
        this.axes = axes
        fun at(vararg names: String) = names.firstNotNullOfOrNull { n -> axes.firstOrNull { it.name == n }?.value } ?: 0f
        leftX = deadZoned(at("X"))
        leftY = deadZoned(at("Y"))
        // The right stick is Z and RZ on most controllers, RX and RY on some.
        rightX = deadZoned(if (axes.any { it.name == "Z" }) at("Z") else at("RX"))
        rightY = deadZoned(if (axes.any { it.name == "RZ" }) at("RZ") else at("RY"))
        trigger = maxOf(at("GAS", "RTRIGGER"), at("BRAKE", "LTRIGGER")).coerceIn(0f, 1f)
        val moving = leftX != 0f || leftY != 0f || rightX != 0f || rightY != 0f
        if (moving != active) active = moving
    }

    // --- the sticks -----------------------------------------------------------------

    /**
     * How far a stick has to move before it counts. The Retroid Pocket's
     * sticks say they rest at exactly nothing, but one read -0.04 at rest
     * and one pushed straight up leaked 0.08 sideways.
     */
    const val DEAD_ZONE = 0.15f

    /** From the edge of the dead zone to full push, 0 to 1, keeping the sign. */
    fun deadZoned(v: Float): Float =
        if (abs(v) < DEAD_ZONE) 0f else sign(v) * ((abs(v) - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceAtMost(1f)

    /** The sticks, dead zone taken out: right and down are positive, as Android has them. */
    var leftX = 0f; private set
    var leftY = 0f; private set
    var rightX = 0f; private set
    var rightY = 0f; private set

    /** A stick is off centre, so something has to move each frame; the app ticks [tick] while it is. */
    var active by mutableStateOf(false)
        private set

    /** What the left stick turns: the focused knob or fader, if one is. */
    class Turnable(
        val value: () -> Float,
        /** A stepped control's steps as keyAdjust counts them, or 0 for a smooth one. */
        val steps: () -> Int,
        val begin: () -> Unit,
        val change: (Float) -> Unit,
        val end: () -> Unit,
    )
    var turnable: Turnable? = null

    /** Sends a key to the window the controller is using: the right stick's arrows. Set by the platform. */
    var sendKey: (Int) -> Unit = {}

    /** A full push turns a smooth knob through its whole range in this many seconds. */
    private const val SWEEP_SECONDS = 1.5f
    /** A stepped knob, pushed fully, moves this many steps a second. */
    private const val STEPS_PER_SECOND = 8f
    /** The right stick's arrows: this far apart barely pushed, and this far apart fully pushed. */
    private const val SLOWEST_REPEAT = 0.35f
    private const val FASTEST_REPEAT = 0.06f

    private var turning: Turnable? = null
    private var turnValue = 0f
    private var stepCarry = 0f
    private var repeatWait = 0f
    private var lastArrow = 0

    /** The sticks' work for one frame of [seconds]. */
    fun tick(seconds: Float) {
        if (KeyHub.playMode) {
            express()
            return
        }
        express()
        turn(seconds)
        arrows(seconds)
    }

    // --- play mode's expression ----------------------------------------------------

    /** What was last sent, so only changes go out. At rest: bend centred, no mod, no pressure. */
    private var sentBend = BEND_CENTRE
    private var sentMod = 0
    private var sentPressure = 0
    private var expressRack = -1
    private const val BEND_CENTRE = 8192

    /**
     * In play mode the right stick bends sideways and adds mod upwards, and
     * the left stick up is pressure, sent to the track the notes go to, as a
     * keyboard's wheels would be (and recorded the same way). Out of play
     * mode, or back at centre, they go back to rest.
     */
    private fun express() {
        val playing = KeyHub.playMode
        val bend = if (playing) (BEND_CENTRE + rightX * 8191f).toInt().coerceIn(0, 16383) else BEND_CENTRE
        val mod = if (playing) (maxOf(0f, -rightY) * 127f).toInt() else 0
        val pressure = if (playing) (maxOf(0f, -leftY) * 127f).toInt() else 0
        val rack = KeyHub.target()
        if (rack != expressRack) {
            // Another track now: the last one is put back to rest.
            send(expressRack, BEND_CENTRE, 0, 0)
            expressRack = rack
        }
        send(rack, bend, mod, pressure)
    }

    private fun send(rack: Int, bend: Int, mod: Int, pressure: Int) {
        if (rack >= 0) {
            if (bend != sentBend) NativeEngine.midiEvent(rack, 0xE0, bend and 0x7F, bend shr 7)
            if (mod != sentMod) NativeEngine.controlChange(rack, 1, mod)
            if (pressure != sentPressure) NativeEngine.channelPressure(rack, pressure)
        }
        sentBend = bend
        sentMod = mod
        sentPressure = pressure
    }

    /**
     * The left stick turns the focused control: up or right is more, the
     * further the faster, squared so small pushes are fine. The control
     * hears one gesture from leaving the centre to coming back, so a turn
     * is one step of undo.
     */
    private fun turn(seconds: Float) {
        // Whichever way it's pushed more: up (negative Y) or right.
        val push = if (abs(leftY) >= abs(leftX)) -leftY else leftX
        val target = turnable
        if (push == 0f || target == null || target != turning) {
            turning?.end?.invoke()
            turning = null
            stepCarry = 0f
            if (push == 0f || target == null) return
        }
        if (turning == null) {
            turning = target
            // Kept here through the turn: the control's own value only
            // catches up when the screen next draws.
            turnValue = target.value()
            target.begin()
            // A stepped control moves a step as soon as it's pushed, as a key
            // press would, then repeats.
            if (target.steps() > 0) stepCarry = sign(push) * 0.999f
        }
        val speed = sign(push) * push * push
        val steps = target.steps()
        if (steps > 0) {
            stepCarry += speed * STEPS_PER_SECOND * seconds
            val whole = stepCarry.toInt()
            if (whole != 0) {
                stepCarry -= whole
                // A step as the keys take it (see keyAdjust).
                turnValue = (turnValue + whole.toFloat() / (steps + 1)).coerceIn(0f, 1f)
                target.change(turnValue)
            }
        } else {
            turnValue = (turnValue + speed * seconds / SWEEP_SECONDS).coerceIn(0f, 1f)
            target.change(turnValue)
        }
    }

    /**
     * The right stick is a fast d-pad: arrows, repeating faster the further
     * it's pushed, so it moves between controls and moves the editor's
     * cursor at speed.
     */
    private fun arrows(seconds: Float) {
        val x = rightX
        val y = rightY
        val push = maxOf(abs(x), abs(y))
        if (push == 0f) {
            repeatWait = 0f
            lastArrow = 0
            return
        }
        val arrow = if (abs(x) >= abs(y)) {
            if (x > 0f) KeyCodes.KEYCODE_DPAD_RIGHT else KeyCodes.KEYCODE_DPAD_LEFT
        } else {
            if (y > 0f) KeyCodes.KEYCODE_DPAD_DOWN else KeyCodes.KEYCODE_DPAD_UP
        }
        // A new direction moves at once; a held one repeats.
        if (arrow != lastArrow) {
            lastArrow = arrow
            repeatWait = 0f
        }
        repeatWait -= seconds
        if (repeatWait <= 0f) {
            sendKey(arrow)
            repeatWait = SLOWEST_REPEAT + (FASTEST_REPEAT - SLOWEST_REPEAT) * push
        }
    }
}
