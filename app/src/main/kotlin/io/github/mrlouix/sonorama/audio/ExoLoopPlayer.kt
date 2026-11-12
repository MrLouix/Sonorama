package io.github.mrlouix.sonorama.audio

import android.content.Context
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ogg.OggExtractor

/** Lecteur en boucle reposant sur un ExoPlayer réduit au strict nécessaire : Ogg, audio seul. */
@OptIn(UnstableApi::class)
class ExoLoopPlayer(private val player: ExoPlayer) : LoopPlayer {

    override val isReady: Boolean
        get() = player.playbackState == Player.STATE_READY

    override fun setGain(gain: Float) {
        player.volume = gain.coerceIn(0f, 1f)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun release() {
        player.release()
    }

    /** Fabrique partageant un seul fil de lecture entre tous les lecteurs. */
    class Factory(context: Context) : LoopPlayerFactory {
        private val appContext = context.applicationContext

        private val playbackThread = HandlerThread("sonorama-playback", Process.THREAD_PRIORITY_AUDIO)
            .apply { start() }

        private val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, handler, audioListener))
        }

        private val mediaSources = DefaultMediaSourceFactory(
            appContext,
            ExtractorsFactory { arrayOf(OggExtractor()) },
        )

        private val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        override fun create(uri: String): LoopPlayer {
            val player = ExoPlayer.Builder(appContext, renderers, mediaSources)
                .setPlaybackLooper(playbackThread.looper)
                // Le focus audio est géré une fois pour tout le mix, pas lecteur par lecteur.
                .setAudioAttributes(attributes, false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            return ExoLoopPlayer(player)
        }
    }
}
