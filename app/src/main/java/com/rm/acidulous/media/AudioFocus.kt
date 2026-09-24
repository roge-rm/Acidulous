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
 * Being a good neighbour on the phone's audio: while the transport runs, the
 * app holds audio focus, and it stops when something else takes it - a call,
 * an alarm, another app starting music - or when headphones are pulled out,
 * which would otherwise carry on at full volume out of the speaker.
 *
 * Follows the transport the way the playback service does: [follow] is called
 * on each change of playing, and [stop] is how the app stops. A short loss
 * that says it can duck - a navigation prompt, a notification - is let pass:
 * the song carrying on under it is what a musician wants.
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
                // Refused - a call in progress - is the same as losing it.
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
                // Not exported: it is the system's broadcast, which reaches a
                // receiver either way, and nothing else should be able to stop us.
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
