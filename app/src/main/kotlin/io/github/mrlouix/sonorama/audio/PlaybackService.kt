package io.github.mrlouix.sonorama.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.mrlouix.sonorama.MainActivity
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.SonoramaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Expose le play/pause du mix à la notification média, à l'écran verrouillé et aux casques
 * Bluetooth, et maintient l'appli au premier plan pendant la lecture. Le son lui-même est
 * produit par [MixEngine] ; ce service n'en est que la télécommande.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as SonoramaApp).container
        val engine = container.engine
        val player = MixSessionPlayer(mainLooper, engine)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        scope.launch {
            combine(engine.state, container.repository.library) { mix, library ->
                val french = resources.configuration.locales[0].language == "fr"
                val names = mix.active.mapNotNull { library.cards[it]?.name?.get(french) }
                player.update(mix.playing, names, getString(R.string.app_name))
            }.collect {}
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val engine = (application as SonoramaApp).container.engine
        if (!engine.state.value.playing) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Lecteur factice présenté à la session média : il reflète le mix et relaie play/pause. */
@OptIn(UnstableApi::class)
private class MixSessionPlayer(looper: Looper, private val engine: MixEngine) : SimpleBasePlayer(looper) {
    private var playing = false
    private var cardNames: List<String> = emptyList()
    private var title = ""

    fun update(playing: Boolean, cardNames: List<String>, title: String) {
        this.playing = playing
        this.cardNames = cardNames
        this.title = title
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (cardNames.isEmpty()) {
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(cardNames.joinToString(" · "))
            .build()
        val item = MediaItem.Builder().setMediaId(MIX_ID).setMediaMetadata(metadata).build()
        return builder
            .setPlaybackState(Player.STATE_READY)
            .setPlaylist(listOf(MediaItemData.Builder(MIX_ID).setMediaItem(item).setMediaMetadata(metadata).build()))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.resume() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val MIX_ID = "mix"
    }
}
