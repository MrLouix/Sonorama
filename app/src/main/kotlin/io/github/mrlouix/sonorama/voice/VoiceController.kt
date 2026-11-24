package io.github.mrlouix.sonorama.voice

import io.github.mrlouix.sonorama.audio.MixEngine
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.RecognitionMode
import io.github.mrlouix.sonorama.data.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class VoiceState(
    /** Le mode vocal est allumé (bouton micro). */
    val enabled: Boolean = false,
    /** Le micro écoute vraiment : allumé, hors pause, modèle chargé. */
    val listening: Boolean = false,
    /** Allumé mais en pause : le micro est coupé jusqu'à la reprise. */
    val paused: Boolean = false,
    /** Dernier mot-clé reconnu. */
    val lastKeyword: String? = null,
    /** Mots-clés de la page affichée, dans la langue de l'histoire. */
    val keywordCount: Int = 0,
    val language: String = "fr",
)

/** Messages ponctuels pour l'interface. */
enum class VoiceNotice { NO_MODEL, NO_KEYWORDS, AUTO_STOPPED, FAILED }

/** Lance et arrête le service au premier plan qui porte le micro. */
interface VoiceServiceLauncher {
    fun start()

    fun stop()
}

/** Destination de la transcription ; n'existe que dans l'APK de débogage. */
interface TranscriptSink {
    val enabled: Boolean

    fun record(text: String, cardIds: List<String>)
}

/**
 * Mode vocal : écoute la personne qui lit et déclenche, pour un temps limité, les cartes de la page
 * affichée dont elle prononce un mot-clé. Toutes les méthodes s'appellent depuis le fil de [scope].
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val engine: MixEngine,
    private val library: StateFlow<Library>,
    private val settings: StateFlow<Settings>,
    private val models: SpeechModelSource,
    private val speech: () -> SpeechEngine,
    private val service: VoiceServiceLauncher,
    private val transcripts: TranscriptSink?,
    private val autoStopMs: Long = AUTO_STOP_MS,
    /** Fil des appels bloquants (chargement du modèle, micro) ; remplacé dans les tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<VoiceNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<VoiceNotice> = _notices.asSharedFlow()

    private var pageId: String? = null
    private var matcher = KeywordMatcher(emptyMap())
    private var session: SpeechSession? = null
    private var opening: Job? = null
    /** Ouverture en cours ; l'appel natif ne s'interrompt pas, une ouverture périmée referme ce qu'elle obtient. */
    private var openToken: Any? = null
    private var autoStop: Job? = null
    /** Ce qui exige de rouvrir le micro quand ça change : langue, mots-clés en mode limité, réglages. */
    private var sessionKey: Any? = null
    private val pendingCards = LinkedHashSet<String>()

    init {
        scope.launch {
            // La bibliothèque compte aussi : des mots-clés modifiés s'appliquent tout de suite.
            combine(engine.state, library, settings) { mix, lib, s -> Triple(mix.paused, lib, s) }
                .distinctUntilChanged()
                .collect { reconcile() }
        }
    }

    fun language(): String = storyLanguage(settings.value)

    /** Page affichée : seules ses cartes peuvent être déclenchées. */
    fun setPage(id: String?) {
        if (id == pageId) return
        pageId = id
        reconcile()
    }

    /** Allume le mode vocal. L'appelant a obtenu la permission du micro et l'appli est visible. */
    fun enable() {
        if (_state.value.enabled) return
        if (language() !in models.installed.value) {
            _notices.tryEmit(VoiceNotice.NO_MODEL)
            return
        }
        _state.value = _state.value.copy(enabled = true, lastKeyword = null)
        service.start()
        reconcile()
        if (matcher.isEmpty) _notices.tryEmit(VoiceNotice.NO_KEYWORDS)
    }

    fun disable() {
        if (!_state.value.enabled) return
        _state.value = _state.value.copy(enabled = false)
        closeSession()
        autoStop?.cancel()
        service.stop()
        reconcile()
    }

    fun toggle() = if (_state.value.enabled) disable() else enable()

    /** Mots de [words] que le modèle de la langue de l'histoire ne connaît pas ; vide si aucun modèle. */
    suspend fun unknownWords(language: String, words: Collection<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        return withContext(io) {
            val dir = models.modelDir(language) ?: return@withContext emptySet()
            runCatching { speech().unknownWords(dir, words) }.getOrDefault(emptySet())
        }
    }

    // ------------------------------------------------------------------ interne

    /** Réapplique les réglages qui ne passent pas par [settings] (transcription). */
    fun refresh() = reconcile()

    private fun reconcile() {
        val s = settings.value
        val language = storyLanguage(s)
        val page = library.value.page(pageId ?: "")
        val cards = page?.cardIds.orEmpty().mapNotNull { library.value.cards[it] }
        matcher = KeywordMatcher.forCards(cards, language)
        val paused = engine.state.value.paused
        val enabled = _state.value.enabled
        val grammar = if (s.recognitionMode == RecognitionMode.KEYWORDS_ONLY) matcher.keywords.sorted() else null
        val key = listOf(language, grammar, transcripts?.enabled == true)
        val wanted = enabled && !paused && (grammar == null || grammar.isNotEmpty())
        if (session != null || opening != null) {
            if (!wanted || key != sessionKey) closeSession()
        }
        if (wanted && session == null && opening == null) open(language, grammar, key)
        if (enabled && !paused) {
            if (autoStop == null || _state.value.paused) armAutoStop()
        } else {
            autoStop?.cancel()
            autoStop = null
        }
        _state.value = _state.value.copy(
            paused = enabled && paused,
            listening = session != null,
            keywordCount = matcher.keywords.size,
            language = language,
        )
    }

    private fun open(language: String, grammar: List<String>?, key: Any) {
        val token = Any()
        openToken = token
        sessionKey = key
        val transcribe = transcripts?.enabled == true
        opening = scope.launch {
            val started = runCatching {
                withContext(io) {
                    val dir: File = models.modelDir(language) ?: error("modèle absent")
                    speech().start(dir, grammar, transcribe, Listener())
                }
            }
            if (openToken !== token) {
                started.onSuccess { withContext(io) { it.close() } }
                return@launch
            }
            openToken = null
            opening = null
            started.onSuccess {
                session = it
                _state.value = _state.value.copy(listening = true)
            }.onFailure {
                _notices.tryEmit(VoiceNotice.FAILED)
                disable()
            }
        }
    }

    private fun closeSession() {
        openToken = null
        opening = null
        sessionKey = null
        val closing = session ?: return
        session = null
        _state.value = _state.value.copy(listening = false)
        scope.launch(io) { closing.close() }
    }

    private fun armAutoStop() {
        autoStop?.cancel()
        autoStop = scope.launch {
            delay(autoStopMs)
            autoStop = null
            disable()
            _notices.tryEmit(VoiceNotice.AUTO_STOPPED)
        }
    }

    private fun onHits(hits: List<KeywordHit>) {
        if (hits.isEmpty() || !_state.value.enabled) return
        val cards = hits.flatMap { it.cardIds }.distinct()
        engine.trigger(cards, settings.value.voiceDurationSec * 1000L)
        pendingCards += cards
        _state.value = _state.value.copy(lastKeyword = hits.last().keyword)
        armAutoStop()
    }

    private inner class Listener : SpeechListener {
        override fun onPartial(text: String) {
            scope.launch { onHits(matcher.onPartial(text)) }
        }

        override fun onFinal(text: String) {
            scope.launch { onHits(matcher.onFinal(text)) }
        }

        override fun onTranscript(text: String) {
            scope.launch {
                val sink = transcripts?.takeIf { it.enabled } ?: return@launch
                sink.record(text, pendingCards.toList())
                pendingCards.clear()
            }
        }

        override fun onError(error: Throwable) {
            scope.launch {
                _notices.tryEmit(VoiceNotice.FAILED)
                disable()
            }
        }
    }

    companion object {
        const val AUTO_STOP_MS = 5 * 60_000L

        /** Langue de l'histoire : réglage dédié, sinon langue de l'appli, sinon celle du système. */
        fun storyLanguage(settings: Settings): String {
            val code = settings.storyLanguage.ifEmpty { settings.language.ifEmpty { Locale.getDefault().language } }
            return if (code == "fr") "fr" else "en"
        }
    }
}
