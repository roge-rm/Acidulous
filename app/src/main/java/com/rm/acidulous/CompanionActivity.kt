package com.rm.acidulous

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rm.acidulous.ui.KeyHub
import com.rm.acidulous.ui.PadEvents
import com.rm.acidulous.ui.SecondScreenRoot
import com.rm.acidulous.ui.toPress
import com.rm.acidulous.ui.UiPrefs

private const val TAG = "Acidulous.Second"

/** How long after a hand-back to check that focus went. */
private const val HAND_BACK_CHECK_MS = 400L

/** How many more times to try when it didn't. */
private const val HAND_BACK_TRIES = 3

/** The display this activity is on. */
internal val Activity.displayIdNow: Int
    @Suppress("DEPRECATION")
    get() = if (android.os.Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY
    else windowManager.defaultDisplay.displayId

/**
 * Which display the second screen goes on, and opening and closing it there.
 * Main thread only. MainActivity calls [update] whenever a display comes,
 * goes or changes, and when the setting changes.
 */
internal object SecondScreens {
    /** Set while a MainActivity exists; the companion closes itself without one. */
    var mainAlive = false

    /** The main activity, for keys the second screen passes on to it. */
    var main: MainActivity? = null

    /** The main activity's task, which focus is handed back to. */
    var mainTaskId = -1

    /**
     * Whether the main activity is resumed. Paused, something else covers
     * it (another app, a system window it opened), and focus stays put.
     */
    var mainResumed = false

    /** The open companion, if it got as far as onCreate. */
    var companion: CompanionActivity? = null

    /** The display it was started on, or [Display.INVALID_DISPLAY]. */
    private var startedOn = Display.INVALID_DISPLAY

    /** Display.getType()'s built-in panel, a hidden constant. */
    private const val TYPE_INTERNAL = 1

    /** What [choose] last logged, so a display that changes often isn't logged each time. */
    private var lastSeen = ""

    /**
     * The display to put the second screen on, or null for none: a built-in
     * second panel that isn't the main activity's, isn't private and is on,
     * one marked for presentations first.
     * An external screen (HDMI, a cast or a mirror) doesn't get it.
     *
     * Built in is the display's type, read by reflection. If that can't be
     * read, the devices known to have a second panel are taken by name.
     * A debug build on the emulator takes any type, since the emulator's
     * extra displays aren't built in.
     */
    fun choose(displays: DisplayManager, mainDisplay: Int): Display? {
        val any = emulatorDebug()
        val candidates = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val seen = candidates.joinToString { "${it.displayId}: type ${typeOf(it)} flags 0x${it.flags.toString(16)}" }
        if (seen != lastSeen) {
            lastSeen = seen
            Log.i(TAG, "presentation displays [$seen]${if (any) ", emulator debug: any type" else ""}")
        }
        fun usable(it: Display) =
            it.displayId != mainDisplay &&
                it.displayId != Display.DEFAULT_DISPLAY &&
                it.isValid &&
                (it.flags and Display.FLAG_PRIVATE) == 0 &&
                it.state != Display.STATE_OFF
        // A display marked for presentations comes first. Failing that, any other built-in panel.
        val pick = candidates.firstOrNull {
            usable(it) && (any || (it.flags and Display.FLAG_PRESENTATION) != 0 && builtIn(it))
        } ?: displays.displays.firstOrNull { usable(it) && (any || builtIn(it)) }
        if (pick != null && pick.displayId != lastChosen) {
            Log.i(TAG, "chose display ${pick.displayId}: type ${typeOf(pick)} flags 0x${pick.flags.toString(16)} name ${pick.name}")
        }
        lastChosen = pick?.displayId ?: Display.INVALID_DISPLAY
        return pick
    }

    /** The display [choose] last logged. */
    private var lastChosen = Display.INVALID_DISPLAY

    /** The display's type (1 built in, 2 external, 3 Wi-Fi, 4 overlay, 5 virtual), or null if it can't be read. */
    private fun typeOf(d: Display): Int? =
        runCatching { Display::class.java.getMethod("getType").invoke(d) as Int }.getOrNull()

    private fun builtIn(d: Display): Boolean {
        typeOf(d)?.let { return it == TYPE_INTERNAL }
        val model = android.os.Build.MODEL.lowercase()
        return model.startsWith("retroid pocket duo") || model.startsWith("ayn thor")
    }

    /** A debug build on the emulator, whose added displays read as external or virtual. */
    private fun emulatorDebug(): Boolean =
        AppHost.current.debugBuild &&
            (android.os.Build.HARDWARE == "ranchu" || android.os.Build.HARDWARE == "goldfish")

    /**
     * Gives the main activity focus, and so the keys and the controller.
     * Moving a task that's already on top of its display is no change to
     * the system, so focus wouldn't move: the first [attempt] moves the task
     * through its own call, which takes it off the top and back. Later ones
     * start the activity again, which reaches it as a new intent.
     * False if there was nothing to move.
     */
    fun focusMain(context: android.content.Context, attempt: Int = 0): Boolean {
        val activity = main ?: return false
        if (attempt == 0) {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val task = runCatching {
                am?.appTasks?.firstOrNull { t ->
                    val info = t.taskInfo ?: return@firstOrNull false
                    @Suppress("DEPRECATION")
                    (if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId) == mainTaskId
                }
            }.getOrNull()
            if (task != null && runCatching { task.moveToFront() }.isSuccess) return true
        }
        return startOn(context, MainActivity::class.java, activity.displayIdNow)
    }

    /**
     * Gives the second screen focus. Its task's own call would bring the home
     * screen up over the main screen, so it's started again on its display.
     */
    fun focusCompanion(context: android.content.Context): Boolean {
        val bottom = companion ?: return false
        return startOn(context, CompanionActivity::class.java, bottom.displayIdNow)
    }

    /** Starts the running [activity] again on [display], which brings it to the front with focus. */
    private fun startOn(context: android.content.Context, activity: Class<*>, display: Int): Boolean =
        runCatching {
            val intent = Intent(context, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
            true
        }.getOrElse {
            Log.w(TAG, "could not bring ${activity.simpleName} to the front", it)
            false
        }

    /** The keys go to the other screen, or back to the main one. Set as KeyHub.otherScreen while a second screen is up. */
    fun switchKeys() {
        val bottom = companion ?: return
        val main = main ?: return
        if (KeyHub.onSecond) {
            KeyHub.onSecond = false
            focusMain(main)
            Log.i(TAG, "keys to the main screen")
        } else if (mainResumed) {
            KeyHub.releaseAll()
            KeyHub.onSecond = true
            focusCompanion(main)
            bottom.keysArrived()
            Log.i(TAG, "keys to the second screen")
        }
    }

    /** Opens, moves or closes the second screen to match the displays and the setting. */
    fun update(main: Activity) {
        val displays = main.getSystemService(DisplayManager::class.java) ?: return
        val mainDisplay = main.displayIdNow
        val chosen = choose(displays, mainDisplay)
        AppState.secondScreenPresent.value = chosen != null
        val target = chosen?.displayId?.takeIf { UiPrefs.secondScreen && !main.isFinishing }
            ?: Display.INVALID_DISPLAY
        if (startedOn != Display.INVALID_DISPLAY && startedOn != target) close()
        if (target != Display.INVALID_DISPLAY && startedOn == Display.INVALID_DISPLAY) {
            val intent = Intent(main, CompanionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
            runCatching { main.startActivity(intent, options.toBundle()) }
                .onSuccess {
                    startedOn = target
                    Log.i(TAG, "opened on display $target")
                }
                .onFailure {
                    AppState.secondScreenShown.value = false
                    Log.w(TAG, "could not open on display $target", it)
                }
        }
    }

    /** Closes the second screen if it's open. */
    fun close() {
        AppState.secondScreenShown.value = false
        KeyHub.onSecond = false
        if (startedOn != Display.INVALID_DISPLAY) Log.i(TAG, "closed on display $startedOn")
        startedOn = Display.INVALID_DISPLAY
        companion?.finish()
    }

    /** A companion is going. If it was the open one, it can be opened again. */
    fun gone(activity: CompanionActivity) {
        if (companion !== activity) {
            // One that finished in onCreate never became the open one.
            if (companion == null && activity.isFinishing) startedOn = Display.INVALID_DISPLAY
            return
        }
        companion = null
        AppState.secondScreenShown.value = false
        KeyHub.onSecond = false
        KeyHub.otherScreen = null
        KeyHub.onSecondTyping = null
        if (activity.isFinishing) startedOn = Display.INVALID_DISPLAY
    }
}

/**
 * The second screen: keys or pads for the track the main screen plays,
 * drawn by [SecondScreenRoot] from the same song and engine.
 */
class CompanionActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == shownOn) finish()
        }
    }
    private var shownOn = Display.INVALID_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownOn = displayIdNow
        // Restored by the system without the app around it, or put on the
        // main screen: there's nothing for it to do.
        if (!SecondScreens.mainAlive || shownOn == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        SecondScreens.companion?.takeIf { it !== this }?.finish()
        SecondScreens.companion = this
        KeyHub.otherScreen = { SecondScreens.switchKeys() }
        KeyHub.onSecondTyping = { on -> if (on) SecondScreens.focusCompanion(this) else handBack("typing done") }
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, null)
        // Back would leave an empty screen behind, so it does nothing here.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideBars()
        setContent { SecondScreenRoot() }
        Log.i(TAG, "showing on display $shownOn")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A display that goes away hands its activities to the main one.
        if (displayIdNow == Display.DEFAULT_DISPLAY) finish()
    }

    override fun onStart() {
        super.onStart()
        if (SecondScreens.companion === this) AppState.secondScreenShown.value = true
    }

    override fun onPause() {
        pointerDown = false
        super.onPause()
    }

    override fun onStop() {
        if (SecondScreens.companion === this) {
            AppState.secondScreenShown.value = false
            KeyHub.onSecond = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        SecondScreens.gone(this)
        super.onDestroy()
    }

    /*
     * Focus. A touch on this screen gives it focus, and the keys and the
     * controller would follow it here and go dead on the main screen. So
     * whenever this screen takes focus by itself it hands it straight back,
     * and checks a moment later that it went. It keeps focus while one of its
     * text fields is typed in, while the keys were moved here on purpose, and
     * while the main activity is paused under something else.
     */

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Whether this is the top resumed activity, the one the keys go to. */
    private var topResumed = false

    /** Hand-backs that didn't land in a row, see [check]. */
    private var misses = 0

    /** A finger is down on this screen. Focus stays until it lifts, or a hand-back mid-drag moves the controls. */
    private var pointerDown = false

    private fun mayHandBack(): Boolean =
        SecondScreens.companion === this && !isFinishing && SecondScreens.mainResumed &&
            !KeyHub.onSecond && KeyHub.typingSecond == 0 && !pointerDown &&
            // Top resumed without the window's focus: a dialog of ours has it, and its touches don't pass here.
            !(topResumed && !hasWindowFocus())

    private fun holdsFocus(): Boolean = topResumed || hasWindowFocus()

    /** Gives focus back to the main screen if this one has it. [why] is for the log. */
    internal fun handBack(why: String) {
        if (!mayHandBack() || !holdsFocus()) return
        Log.d(TAG, "hand-back: $why")
        if (!SecondScreens.focusMain(this)) Log.w(TAG, "hand-back ($why): no main task to give focus to")
        handler.removeCallbacks(check)
        handler.postDelayed(check, HAND_BACK_CHECK_MS)
    }

    /**
     * Still focused after a hand-back: try starting the main activity, a few
     * times, and say so in the log rather than leave the keys dead quietly.
     */
    private val check: Runnable = Runnable { checkFocus() }

    private fun checkFocus() {
        if (!mayHandBack() || !holdsFocus()) {
            misses = 0
            return
        }
        misses++
        if (misses > HAND_BACK_TRIES) {
            Log.e(TAG, "focus would not go back to the main screen after $HAND_BACK_TRIES tries")
            misses = 0
            return
        }
        Log.w(TAG, "focus still here after a hand-back, try $misses")
        SecondScreens.focusMain(this, attempt = misses)
        handler.postDelayed(check, HAND_BACK_CHECK_MS)
    }

    private val focusFirst = Runnable { if (KeyHub.onSecond && !KeyHub.secondAnyFocused) KeyHub.secondFocusFirst() }

    // The move didn't land: say so and leave the keys where they are.
    private val moveCheck = Runnable {
        if (KeyHub.onSecond && !holdsFocus()) {
            Log.w(TAG, "keys could not move to the second screen")
            KeyHub.onSecond = false
        }
    }

    /** Keys were moved here: start them from this screen's first control. */
    internal fun keysArrived() {
        handler.removeCallbacks(check)
        handler.postDelayed(focusFirst, 150)
        handler.postDelayed(moveCheck, HAND_BACK_CHECK_MS)
    }

    override fun onResume() {
        super.onResume()
        handBack("resumed")
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
        if (isTopResumedActivity) handBack("top resumed")
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        KeyHub.usingKeys = false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> pointerDown = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> pointerDown = false
        }
        val used = super.dispatchTouchEvent(ev)
        // Once the last finger is up, so nothing is lost mid-drag.
        if (!pointerDown && (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL)) handler.post { handBack("touch up") }
        return used
    }

    /**
     * A key that reaches this screen before focus is back goes to the main
     * screen's handling, the same way it would have gone there. With the
     * keys moved here on purpose, this screen handles them as the main one
     * does its own.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!KeyHub.onSecond && KeyHub.typingSecond == 0) {
            if (!SecondScreens.mainResumed) return super.dispatchKeyEvent(event)
            val main = SecondScreens.main
            if (event.action == android.view.KeyEvent.ACTION_DOWN) handler.post { handBack("key") }
            if (main != null) {
                val used = main.dispatchKeyEvent(event)
                Log.d(TAG, "key ${event.keyCode} ${event.action} passed to the main screen, used $used")
                return used
            }
        }
        PadEvents.key(event)
        PadEvents.route(event) { dispatchKeyEvent(it) }?.let { return it }
        if (KeyHub.preview(event.toPress())) return true
        if (super.dispatchKeyEvent(event)) return true
        return KeyHub.fallback(event.toPress())
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        // Controller sticks go to the main screen with the keys; a wheel, hover or touchpad stays here.
        val pad = ev.source and (android.view.InputDevice.SOURCE_JOYSTICK or android.view.InputDevice.SOURCE_GAMEPAD or
            android.view.InputDevice.SOURCE_DPAD) != 0
        if (pad && !KeyHub.onSecond && SecondScreens.mainResumed) SecondScreens.main?.let { return it.dispatchGenericMotionEvent(ev) }
        if (PadEvents.motion(ev) { dispatchKeyEvent(it) }) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideBars()
            handBack("window focus")
        }
    }

    private fun hideBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
