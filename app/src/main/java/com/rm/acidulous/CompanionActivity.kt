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
import com.rm.acidulous.ui.SecondScreenRoot
import com.rm.acidulous.ui.UiPrefs

private const val TAG = "Acidulous.Second"

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

    /** The open companion, if it got as far as onCreate. */
    var companion: CompanionActivity? = null

    /** The display it was started on, or [Display.INVALID_DISPLAY]. */
    private var startedOn = Display.INVALID_DISPLAY

    /**
     * The display to put the second screen on, or null for none: the first
     * one that isn't the main activity's, isn't private and is on.
     *
     * The one place the choice is made, so it can be refined on a real
     * device (a Duo orders its displays differently from a Thor).
     */
    fun choose(displays: DisplayManager, mainDisplay: Int): Display? =
        displays.displays.firstOrNull {
            it.displayId != mainDisplay &&
                it.displayId != Display.DEFAULT_DISPLAY &&
                it.isValid &&
                (it.flags and Display.FLAG_PRIVATE) == 0 &&
                it.state != Display.STATE_OFF
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

    override fun onStop() {
        if (SecondScreens.companion === this) AppState.secondScreenShown.value = false
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        SecondScreens.gone(this)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.rm.acidulous.ui.KeyHub.usingKeys = false
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
