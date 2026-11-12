package io.github.mrlouix.sonorama

import android.app.Application
import android.content.Context
import io.github.mrlouix.sonorama.audio.AudioFocusController
import io.github.mrlouix.sonorama.audio.ExoLoopPlayer
import io.github.mrlouix.sonorama.audio.MixEngine
import io.github.mrlouix.sonorama.audio.MixPersistence
import io.github.mrlouix.sonorama.audio.PreviewMixer
import io.github.mrlouix.sonorama.data.LibraryRepository
import io.github.mrlouix.sonorama.data.Settings
import io.github.mrlouix.sonorama.data.SettingsStore
import io.github.mrlouix.sonorama.data.db.AppDatabase
import io.github.mrlouix.sonorama.data.pack.BasePack
import io.github.mrlouix.sonorama.data.pack.PackImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SonoramaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Câblage manuel des dépendances, pour toute la durée de vie du processus. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsStore = SettingsStore(context)
    val settings: StateFlow<Settings> =
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, settingsStore.current())

    val repository = LibraryRepository(AppDatabase.create(context), scope)

    private val playerFactory = ExoLoopPlayer.Factory(context)

    val engine = MixEngine(
        scope = scope,
        players = playerFactory,
        cards = repository::cardSpec,
        persistence = object : MixPersistence {
            override fun saveVolume(cardId: String, volume: Int) {
                scope.launch { repository.setCardVolume(cardId, volume) }
            }

            override fun saveMix(activeCardIds: List<String>) {
                scope.launch { settingsStore.setLastMix(activeCardIds) }
            }
        },
        fades = { settings.value.fades },
    )

    val preview = PreviewMixer(playerFactory)

    val importer = PackImporter(context, repository, scope)

    @Suppress("unused")
    private val focus = AudioFocusController(context, engine, scope)

    init {
        scope.launch {
            val lastMix = settings.value.lastMix
            runCatching { BasePack.ensureInstalled(context.applicationContext, repository) }
            repository.library.first { it.loaded && it.packs.isNotEmpty() }
            // Le dernier mix est restauré en pause.
            engine.restore(lastMix)
            repository.library.collect { engine.syncWithLibrary() }
        }
    }
}
