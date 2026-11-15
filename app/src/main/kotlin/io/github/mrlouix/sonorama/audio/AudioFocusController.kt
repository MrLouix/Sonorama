package io.github.mrlouix.sonorama.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Focus audio pour tout ce que l'appli fait entendre, mix et pré-écoute : pause sur perte de focus
 * (appel entrant), reprise du mix après une perte passagère, pause au débranchement du casque.
 * La baisse temporaire sur notification est laissée au système, qui l'applique aux lecteurs.
 */
class AudioFocusController(
    context: Context,
    private val engine: MixEngine,
    private val preview: PreviewMixer,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** Vrai tant que le système nous accorde le focus. */
    private var hasFocus = false

    /** Le mix jouait quand le focus a été perdu passagèrement : il reprendra à son retour. */
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                silence()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                resumeOnGain = engine.state.value.playing
                silence()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
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
                silence()
            }
        }
    }

    init {
        scope.launch {
            combine(engine.state, preview.owner) { mix, owner -> mix.playing || owner != null }
                .distinctUntilChanged()
                .collect { audible -> if (audible) onAudible() else onSilent() }
        }
    }

    private fun silence() {
        engine.pause()
        preview.stop()
    }

    private fun onAudible() {
        // Sans focus (jamais obtenu, ou perdu pendant un appel), il faut le redemander :
        // s'il est refusé, rien ne doit jouer par-dessus l'appel en cours.
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                silence()
                return
            }
            resumeOnGain = false
        }
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    private fun onSilent() {
        if (noisyRegistered) {
            appContext.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        // Pendant une perte passagère, la demande est gardée pour être prévenu du retour du focus.
        if (!resumeOnGain) {
            audioManager.abandonAudioFocusRequest(request)
            hasFocus = false
        }
    }
}
