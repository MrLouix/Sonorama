package io.github.mrlouix.sonorama.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Focus audio pour tout le mix : pause sur perte de focus (appel entrant), reprise après une
 * perte passagère, pause au débranchement du casque. La baisse temporaire sur notification
 * est laissée au système, qui l'applique automatiquement aux lecteurs.
 */
class AudioFocusController(context: Context, private val engine: MixEngine, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false
    private var hasFocus = false
    private var noisyRegistered = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = engine.state.value.playing
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain) {
                    resumeOnGain = false
                    engine.resume()
                }
            }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener(listener)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnGain = false
                engine.pause()
            }
        }
    }

    init {
        scope.launch {
            engine.state.map { it.playing }.distinctUntilChanged().collect { playing ->
                if (playing) onPlay() else onPause()
            }
        }
    }

    private fun onPlay() {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                engine.pause()
                return
            }
        }
        if (!noisyRegistered) {
            appContext.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
    }

    private fun onPause() {
        if (noisyRegistered) {
            appContext.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        // Pendant une perte passagère, on garde la demande pour être prévenu du retour du focus.
        if (hasFocus && !resumeOnGain) {
            audioManager.abandonAudioFocusRequest(request)
            hasFocus = false
        }
    }
}
