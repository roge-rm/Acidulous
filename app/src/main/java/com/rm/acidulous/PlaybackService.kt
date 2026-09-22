package com.rm.acidulous

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * The service that says this process is playing music.
 *
 * **Not a player.** The engine is in the activity's process and stays there;
 * this holds no audio, no state and no thread. What it does is tell Android
 * that the process is doing something the user can see, which changes two
 * things that no amount of DSP can:
 *
 *  - **Importance.** Without it, a process whose activity is not in front is a
 *    cached process: it may be trimmed, frozen or killed, and it is the first
 *    thing the scheduler gives up on. A groovebox that stops playing because
 *    you looked at a message is not a groovebox.
 *  - **Playing with the screen off** becomes a supported thing rather than an
 *    accident of how long the device took to doze.
 *
 * It runs while the transport does and stops when the transport does, so an
 * app sitting idle on the grid is not holding a notification open for nothing.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, build())
        // Not sticky: if the system kills this, the engine went with it, and
        // bringing back a service for a transport that is not running would
        // put a notification on screen with nothing behind it.
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the transport is running."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Playing")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Acidulous.Service"
        private const val CHANNEL = "transport"
        private const val NOTE_ID = 1

        /**
         * Follow the transport.
         *
         * **Every failure here is survivable and none of them may crash.** A
         * foreground service can be refused - no notification permission on
         * Android 13 and later, a restricted background state, an OEM policy -
         * and when it is, the app keeps playing exactly as it did before this
         * existed. It is an improvement to ask for, not a thing to depend on.
         */
        fun follow(context: Context, playing: Boolean) {
            val intent = Intent(context, PlaybackService::class.java)
            runCatching {
                if (playing) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            }.onFailure { Log.w(TAG, "could not ${if (playing) "start" else "stop"} the service: $it") }
        }

        /** What the type in the manifest is called, for the settings readout. */
        @Suppress("unused")
        val mediaPlaybackType: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
    }
}
