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
 * A foreground service that tells Android this process is playing music.
 *
 * It isn't a player. The engine stays in the activity's process and this
 * holds no audio, state or threads. Being a foreground service keeps the
 * process from being trimmed, frozen or killed while the app is in the
 * background, and lets it keep playing with the screen off.
 *
 * It runs only while the transport is playing, so there's no notification
 * while the app sits idle.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, build())
        // Not sticky: if the system kills this, the engine went with it, and
        // restarting the service would show a notification with nothing
        // playing.
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.playing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.playing_channel_note)
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
            .setContentText(getString(R.string.playing))
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
         * Nothing here may crash. Starting a foreground service can be refused
         * (no notification permission on Android 13+, background limits, OEM
         * policies), and then the app just keeps playing without it.
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
