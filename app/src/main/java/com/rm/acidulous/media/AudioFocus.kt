package com.rm.acidulous.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Audio focus. While the transport runs the app holds focus, and it stops
 * when something else takes it (a call, an alarm, another app's music) or
 * when headphones are unplugged, so it doesn't carry on out of the speaker.
 *
 * Like the playback service, [follow] is called whenever playing changes and
 * [stop] stops the app. Short losses that allow ducking (a navigation prompt,
 * a notification) are ignored so the song keeps playing under them.
 */
object AudioFocus {
    private const val TAG = "Acidulous.Focus"

    private var request: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    fun follow(context: Context, playing: Boolean, stop: () -> Unit) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        if (playing) {
            if (request == null) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                Log.i(TAG, "focus lost ($change): stopping")
                                stop()
                            }
                        }
                    }
                    .build()
                request = req
                // Being refused (during a call) is the same as losing it.
                if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    Log.i(TAG, "focus refused: stopping")
                    stop()
                }
            }
            if (noisy == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                            Log.i(TAG, "output about to become the speaker: stopping")
                            stop()
                        }
                    }
                }
                noisy = receiver
                // Not exported: the system broadcast reaches it either way,
                // and no other app should be able to stop us.
                androidx.core.content.ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        } else {
            request?.let { audio.abandonAudioFocusRequest(it) }
            request = null
            noisy?.let { runCatching { app.unregisterReceiver(it) } }
            noisy = null
        }
    }
}
