package io.github.mrlouix.sonorama.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.SonoramaApp
import io.github.mrlouix.sonorama.audio.FadeSettings
import io.github.mrlouix.sonorama.audio.MixEngine
import io.github.mrlouix.sonorama.audio.MixEvent
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.audio.MixState
import io.github.mrlouix.sonorama.audio.VolumeCurve
import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.CardSize
import io.github.mrlouix.sonorama.data.CardSound
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.data.PageExchange
import io.github.mrlouix.sonorama.data.Settings
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.data.pack.ImportState
import io.github.mrlouix.sonorama.data.pack.readAtMost
import io.github.mrlouix.sonorama.ui.tour.TOUR_PAGE
import io.github.mrlouix.sonorama.ui.tour.TourState
import io.github.mrlouix.sonorama.ui.tour.TourStep
import io.github.mrlouix.sonorama.voice.ModelImportError
import io.github.mrlouix.sonorama.voice.ModelImportException
import io.github.mrlouix.sonorama.voice.VoiceNotice
import io.github.mrlouix.sonorama.voice.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Main : Screen
    data object Packs : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data object PageEditor : Screen
    data object CardPicker : Screen
    data object CompositeEditor : Screen
    data object Keywords : Screen
}

/** Page en cours d'édition ; [pageId] nul pour une nouvelle page. */
data class PageDraft(val pageId: String?, val name: String, val symbol: String, val cardIds: List<String>)

/** Carte composée en cours d'édition ; [cardId] nul pour une nouvelle carte. */
data class CompositeDraft(
    val cardId: String?,
    val name: String,
    val symbol: String,
    val sounds: List<CardSound>,
    val fromMix: Boolean = false,
    val listening: Boolean = false,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && sounds.size in Limits.COMPOSITE_SOUNDS_MIN..Limits.COMPOSITE_SOUNDS_MAX
}

enum class MessageAction { OPEN_PACKS, OPEN_APP_SETTINGS, OPEN_SETTINGS }

data class UiMessage(
    @StringRes val text: Int,
    val args: List<Any> = emptyList(),
    @StringRes val actionLabel: Int? = null,
    val action: MessageAction? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as SonoramaApp).container
    private val engine: MixEngine = container.engine
    private val repository = container.repository
    private val preview = container.preview
    private val voice = container.voice

    val library: StateFlow<Library> = repository.library
    val mix: StateFlow<MixState> = engine.state
    val settings: StateFlow<Settings> = container.settings
    val importState: StateFlow<ImportState> = container.importer.state
    val voiceState: StateFlow<VoiceState> = voice.state
    /** Langues dont le modèle de reconnaissance est prêt. */
    val speechModels: StateFlow<Set<String>> = container.speechModels.installed

    /** Page dont on modifie les mots-clés. */
    private val _keywordsPage = MutableStateFlow<String?>(null)
    val keywordsPage: StateFlow<String?> = _keywordsPage.asStateFlow()

    /** Carte dont on modifie les mots-clés, seule à l'écran ; nul pour toute la page. */
    private val _keywordsCard = MutableStateFlow<String?>(null)
    val keywordsCard: StateFlow<String?> = _keywordsCard.asStateFlow()

    /** Mode paramétrage : toucher une carte ouvre ses mots-clés au lieu de la lancer. */
    private val _setupMode = MutableStateFlow(false)
    val setupMode: StateFlow<Boolean> = _setupMode.asStateFlow()

    /** Dernière page affichée sur l'écran principal. */
    var visiblePage: String? = null
        private set

    private val _backStack = MutableStateFlow<List<Screen>>(listOf(Screen.Main))
    val backStack: StateFlow<List<Screen>> = _backStack.asStateFlow()

    private val _pageDraft = MutableStateFlow<PageDraft?>(null)
    val pageDraft: StateFlow<PageDraft?> = _pageDraft.asStateFlow()

    private val _compositeDraft = MutableStateFlow<CompositeDraft?>(null)
    val compositeDraft: StateFlow<CompositeDraft?> = _compositeDraft.asStateFlow()

    /** Carte en pré-écoute dans le sélecteur. */
    private val _previewCard = MutableStateFlow<String?>(null)
    val previewCard: StateFlow<String?> = _previewCard.asStateFlow()

    /** Page à afficher dès qu'elle existe (après création, duplication ou import). */
    private val _pageToShow = MutableStateFlow<String?>(null)
    val pageToShow: StateFlow<String?> = _pageToShow.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** Visite guidée en cours ; nulle hors visite. */
    private val _tour = MutableStateFlow<TourState?>(null)
    val tour: StateFlow<TourState?> = _tour.asStateFlow()

    /** Mode vocal allumé quand la visite a commencé : il est rallumé à la fin. */
    private var voiceBeforeTour = false

    init {
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    MixEvent.LIMIT_REACHED -> say(R.string.message_limit, MixEngine.MAX_SOUNDS)
                    MixEvent.CARD_UNAVAILABLE -> say(R.string.message_card_unavailable)
                    MixEvent.CHANGED_WHILE_PAUSED -> say(R.string.message_paused)
                    MixEvent.PLAYBACK_FAILED -> say(R.string.message_playback_failed)
                }
            }
        }
        viewModelScope.launch {
            voice.notices.collect { notice ->
                when (notice) {
                    VoiceNotice.NO_MODEL -> _messages.tryEmit(
                        UiMessage(R.string.message_voice_no_model, actionLabel = R.string.settings_title, action = MessageAction.OPEN_SETTINGS),
                    )
                    VoiceNotice.NO_KEYWORDS -> say(R.string.message_voice_no_keywords)
                    VoiceNotice.AUTO_STOPPED -> say(R.string.message_voice_auto_stopped)
                    VoiceNotice.FAILED -> say(R.string.message_voice_failed)
                }
            }
        }
        // La pré-écoute peut s'arrêter d'elle-même (appel entrant, casque débranché, son illisible).
        viewModelScope.launch {
            preview.owner.collect { owner ->
                _previewCard.value = owner.takeIf { it != EDITOR_PREVIEW }
                if (owner != EDITOR_PREVIEW) _compositeDraft.update { it?.copy(listening = false) }
            }
        }
        // Premier lancement : la visite guidée commence une fois le pack de base installé.
        viewModelScope.launch {
            library.first { tourCard(it) != null }
            if (!container.settingsStore.settings.first().tourSeen) startTour()
        }
    }

    private fun say(@StringRes text: Int, vararg args: Any) {
        _messages.tryEmit(UiMessage(text, args.toList()))
    }

    // ------------------------------------------------------------- navigation

    fun open(screen: Screen) {
        _backStack.update { it + screen }
    }

    /** Renvoie faux quand il n'y a plus d'écran à fermer. */
    fun back(): Boolean {
        val stack = _backStack.value
        if (stack.size <= 1) return false
        when (stack.last()) {
            Screen.CardPicker -> stopPreview()
            Screen.CompositeEditor -> {
                stopPreview()
                _compositeDraft.value = null
            }
            Screen.PageEditor -> _pageDraft.value = null
            Screen.Keywords -> {
                _keywordsPage.value = null
                _keywordsCard.value = null
            }
            else -> Unit
        }
        _backStack.value = stack.dropLast(1)
        return true
    }

    fun pageShown() {
        _pageToShow.value = null
    }

    // -------------------------------------------------------------------- mix

    fun tapCard(card: Card) {
        if (!card.available) {
            val pack = library.value.packs.firstOrNull { it.id == card.packId }
            val text = when {
                card.composite -> R.string.message_composite_missing
                pack?.installed == true -> R.string.message_sound_removed
                else -> R.string.message_pack_missing
            }
            _messages.tryEmit(UiMessage(text, actionLabel = R.string.action_reimport, action = MessageAction.OPEN_PACKS))
            return
        }
        engine.tap(card.id)
        val tour = _tour.value
        if (tour?.step == TourStep.CARD && mix.value.isActive(tour.cardId)) nextTourStep()
    }

    fun setVolume(cardId: String, volume: Int, commit: Boolean) {
        engine.setVolume(cardId, volume, commit)
        val tour = _tour.value
        if (commit && tour?.step == TourStep.VOLUME && cardId == tour.cardId) nextTourStep()
    }

    fun togglePlayback() {
        val state = mix.value
        if (state.active.isEmpty() && !state.paused) say(R.string.message_empty_mix) else engine.togglePlayback()
    }

    /** Renvoie faux si le plan n'est pas vide : l'écran demande alors quoi en faire. */
    fun setMode(mode: MixMode): Boolean = engine.setMode(mode)

    fun applyPlan() = engine.applyPlan()

    fun cancelPlan() = engine.cancelPlan()

    fun stopAll() = engine.stopAll()

    /** Bouton d'arrêt du panneau « En cours » : une carte lancée à la voix s'arrête au lieu d'être gardée. */
    fun stopCard(cardId: String) {
        val state = mix.value
        when {
            cardId in state.timed -> engine.deactivate(cardId)
            state.isActive(cardId) -> engine.tap(cardId)
        }
    }

    // -------------------------------------------------------------- mode vocal

    /** Page affichée : le mode vocal n'écoute que ses mots-clés. */
    fun setVisiblePage(pageId: String?) {
        visiblePage = pageId
        voice.setPage(pageId)
    }

    /** À appeler une fois la permission du micro accordée, appli visible. */
    fun toggleVoice() = voice.toggle()

    fun micDenied(permanently: Boolean) {
        if (permanently) {
            _messages.tryEmit(
                UiMessage(R.string.message_mic_denied_forever, actionLabel = R.string.action_open_settings, action = MessageAction.OPEN_APP_SETTINGS),
            )
        } else {
            say(R.string.message_mic_denied)
        }
    }

    fun openKeywords(pageId: String) {
        _keywordsPage.value = pageId
        open(Screen.Keywords)
    }

    fun openCardKeywords(cardId: String) {
        // Un double appui ne doit pas empiler l'écran deux fois.
        if (_backStack.value.last() == Screen.Keywords) return
        _keywordsCard.value = cardId
        open(Screen.Keywords)
    }

    fun setSetupMode(enabled: Boolean) {
        _setupMode.value = enabled
    }

    fun storyLanguage(): String = voice.language()

    fun setCardKeywords(cardId: String, language: String, words: List<String>) {
        viewModelScope.launch { repository.setCardKeywords(cardId, language, words) }
    }

    fun resetCardKeywords(cardId: String) {
        viewModelScope.launch { repository.resetCardKeywords(cardId) }
    }

    /** Mots que le modèle ne reconnaîtra jamais. */
    suspend fun unknownWords(language: String, words: Collection<String>): Set<String> = voice.unknownWords(language, words)

    fun setVoiceDuration(seconds: Int) {
        viewModelScope.launch { container.settingsStore.setVoiceDuration(seconds) }
    }

    fun setStoryLanguage(language: String) {
        viewModelScope.launch { container.settingsStore.setStoryLanguage(language) }
    }

    fun importSpeechModel(uri: Uri) {
        viewModelScope.launch {
            try {
                container.speechModels.import(uri)
                say(R.string.message_model_installed)
            } catch (e: ModelImportException) {
                say(
                    when (e.error) {
                        ModelImportError.UNKNOWN_MODEL -> R.string.message_model_unknown
                        ModelImportError.TOO_LARGE -> R.string.message_model_too_large
                        ModelImportError.UNREADABLE -> R.string.message_model_unreadable
                    },
                )
            }
        }
    }

    fun deleteSpeechModel(language: String) {
        if (voiceState.value.enabled && voiceState.value.language == language) voice.disable()
        viewModelScope.launch { container.speechModels.delete(language) }
    }

    // ---------------------------------------------------------- visite guidée

    /** Carte d'exemple de la visite : la première carte jouable de la page Nature et météo. */
    private fun tourCard(lib: Library): String? =
        lib.page(TOUR_PAGE)?.cardIds?.firstOrNull { lib.cards[it]?.available == true }

    /**
     * Lance la visite depuis un écran neutre : page Nature et météo, mode direct, rien qui joue.
     * Le mix et le mode vocal sont mis de côté, puis rendus à la fin ([endTour]).
     */
    fun startTour() {
        if (_tour.value != null) return
        val cardId = tourCard(library.value) ?: return
        _setupMode.value = false
        voiceBeforeTour = voiceState.value.enabled
        if (voiceBeforeTour) voice.disable()
        engine.setAside()
        _pageToShow.value = TOUR_PAGE
        _tour.value = TourState(TourStep.entries.first(), cardId)
        // Vue dès qu'elle commence : passée ou finie, elle ne revient qu'à la demande.
        viewModelScope.launch { container.settingsStore.setTourSeen() }
    }

    fun nextTourStep() {
        val tour = _tour.value ?: return
        val next = TourStep.entries.getOrNull(tour.step.ordinal + 1)
        if (next == null) endTour() else _tour.value = tour.copy(step = next)
    }

    /** Fin ou abandon de la visite : arrête ce qu'elle a lancé et rend le mix d'avant. */
    fun endTour() {
        if (_tour.value == null) return
        _tour.value = null
        engine.bringBack()
        if (voiceBeforeTour) voice.enable()
        voiceBeforeTour = false
    }

    // ------------------------------------------------------------------ pages

    fun newPage() {
        _pageDraft.value = PageDraft(null, "", "bookmark", emptyList())
        open(Screen.PageEditor)
    }

    fun editPage(pageId: String) {
        val page = library.value.page(pageId)?.takeIf { !it.builtIn } ?: return
        _pageDraft.value = PageDraft(page.id, page.name.fr, page.symbol, page.cardIds)
        open(Screen.PageEditor)
    }

    fun updatePageDraft(transform: (PageDraft) -> PageDraft) {
        _pageDraft.update { it?.let(transform) }
    }

    fun togglePageCard(cardId: String) {
        val draft = _pageDraft.value ?: return
        if (cardId in draft.cardIds) {
            _pageDraft.value = draft.copy(cardIds = draft.cardIds - cardId)
        } else if (draft.cardIds.size >= Limits.PAGE_CARDS_MAX) {
            say(R.string.message_page_full, Limits.PAGE_CARDS_MAX)
        } else {
            _pageDraft.value = draft.copy(cardIds = draft.cardIds + cardId)
        }
    }

    fun movePageCard(cardId: String, delta: Int) {
        updatePageDraft { draft ->
            val from = draft.cardIds.indexOf(cardId)
            val to = from + delta
            if (from < 0 || to !in draft.cardIds.indices) return@updatePageDraft draft
            draft.copy(cardIds = draft.cardIds.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun savePage() {
        val draft = _pageDraft.value?.takeIf { it.name.isNotBlank() } ?: return
        viewModelScope.launch {
            val id = repository.savePage(draft.pageId, draft.name, draft.symbol, draft.cardIds)
            _pageToShow.value = id
            _pageDraft.value = null
            _backStack.update { stack -> stack.filter { it == Screen.Main } }
        }
    }

    fun duplicatePage(pageId: String, copyName: String) {
        val page = library.value.page(pageId) ?: return
        viewModelScope.launch {
            _pageToShow.value = repository.savePage(null, copyName, page.symbol, page.cardIds)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch { repository.deletePage(pageId) }
    }

    fun movePage(pageId: String, delta: Int) {
        viewModelScope.launch {
            repository.movePage(pageId, delta)
            _pageToShow.value = pageId
        }
    }

    fun exportPage(pageId: String, target: Uri) {
        val lib = library.value
        val page = lib.page(pageId) ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(target, "wt")!!.use {
                        it.write(PageExchange.export(page, lib).toByteArray())
                    }
                }.isSuccess
            }
            say(if (ok) R.string.message_page_exported else R.string.message_export_failed)
        }
    }

    fun importPage(source: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(source)!!.use {
                        it.readAtMost(1024 * 1024).decodeToString()
                    }
                    PageExchange.import(PageExchange.parse(text), repository)
                }
            }
            val report = result.getOrNull()
            when {
                report == null -> say(R.string.message_import_page_failed)
                report.missingCards > 0 -> {
                    _pageToShow.value = report.pageId
                    say(R.string.message_page_imported_missing, report.missingCards)
                }
                else -> {
                    _pageToShow.value = report.pageId
                    say(R.string.message_page_imported)
                }
            }
        }
    }

    // --------------------------------------------------------- cartes composées

    fun newComposite() {
        stopPreview()
        _compositeDraft.value = CompositeDraft(null, "", "layers", emptyList())
        open(Screen.CompositeEditor)
    }

    fun editComposite(cardId: String) {
        val card = library.value.cards[cardId]?.takeIf { it.composite } ?: return
        stopPreview()
        _compositeDraft.value = CompositeDraft(card.id, card.name.fr, card.symbol, card.sounds)
        open(Screen.CompositeEditor)
    }

    /** Scène : met à plat le mix en cours en une liste de sons à leurs volumes du moment. */
    fun newSceneFromMix() {
        val lib = library.value
        val state = mix.value
        val flat = LinkedHashMap<String, Int>()
        for (cardId in state.active) {
            val card = lib.cards[cardId] ?: continue
            val cardVolume = state.volumes[cardId] ?: card.volume
            for (sound in card.sounds) {
                val volume = VolumeCurve.combine(cardVolume, sound.relativeVolume)
                flat[sound.soundId] = maxOf(flat[sound.soundId] ?: 0, volume)
            }
        }
        if (flat.size < Limits.COMPOSITE_SOUNDS_MIN) {
            say(R.string.message_scene_needs_two)
            return
        }
        val sounds = flat.entries.take(Limits.COMPOSITE_SOUNDS_MAX).map { CardSound(it.key, it.value) }
        _compositeDraft.value = CompositeDraft(null, "", "theaters", sounds, fromMix = true)
        open(Screen.CompositeEditor)
    }

    fun updateCompositeDraft(transform: (CompositeDraft) -> CompositeDraft) {
        val updated = _compositeDraft.value?.let(transform) ?: return
        _compositeDraft.value = updated
        refreshCompositePreview(updated)
    }

    fun toggleCompositeSound(soundId: String) {
        updateCompositeDraft { draft ->
            when {
                draft.sounds.any { it.soundId == soundId } ->
                    draft.copy(sounds = draft.sounds.filter { it.soundId != soundId })
                draft.sounds.size >= Limits.COMPOSITE_SOUNDS_MAX -> {
                    say(R.string.message_composite_full, Limits.COMPOSITE_SOUNDS_MAX)
                    draft
                }
                else -> draft.copy(sounds = draft.sounds + CardSound(soundId, library.value.sounds[soundId]?.defaultVolume ?: 70))
            }
        }
    }

    private fun refreshCompositePreview(draft: CompositeDraft) {
        if (!draft.listening || draft.sounds.isEmpty()) {
            if (preview.owner.value == EDITOR_PREVIEW) preview.stop()
            return
        }
        val lib = library.value
        val sounds = draft.sounds.mapNotNull { part ->
            lib.sounds[part.soundId]?.takeIf { it.available }?.let { it.uri to part.relativeVolume }
        }.toMap()
        preview.play(EDITOR_PREVIEW, sounds)
    }

    fun saveComposite() {
        val draft = _compositeDraft.value?.takeIf { it.isValid } ?: return
        stopPreview()
        viewModelScope.launch {
            // Une carte active est arrêtée avant d'être modifiée : ses lecteurs ne correspondraient plus.
            draft.cardId?.let { if (mix.value.isActive(it)) engine.deactivate(it) }
            val id = repository.saveComposite(draft.cardId, draft.name, draft.symbol, draft.sounds)
            _compositeDraft.value = null
            _backStack.update { it.dropLast(1) }
            val page = _pageDraft.value
            if (draft.cardId == null && page != null && page.cardIds.size < Limits.PAGE_CARDS_MAX) {
                _pageDraft.value = page.copy(cardIds = page.cardIds + id)
            }
            if (draft.cardId == null) say(R.string.message_composite_saved)
        }
    }

    fun deleteComposite(cardId: String) {
        stopPreview()
        viewModelScope.launch {
            engine.deactivate(cardId)
            repository.deleteCard(cardId)
            updatePageDraft { it.copy(cardIds = it.cardIds - cardId) }
            if (_compositeDraft.value?.cardId == cardId) {
                _compositeDraft.value = null
                _backStack.update { it.dropLast(1) }
            }
        }
    }

    // --------------------------------------------------------------- pré-écoute

    fun togglePreview(card: Card) {
        if (_previewCard.value == card.id) {
            stopPreview()
            return
        }
        val lib = library.value
        val sounds = card.sounds.mapNotNull { part ->
            lib.sounds[part.soundId]?.let { it.uri to VolumeCurve.combine(card.volume, part.relativeVolume) }
        }.toMap()
        preview.play(card.id, sounds)
    }

    fun stopPreview() = preview.stop()

    // ------------------------------------------------------------------- packs

    fun importPacks(uris: List<Uri>) = container.importer.import(uris)

    fun cancelImport() = container.importer.cancel()

    fun answerImport(replace: Boolean) = container.importer.answer(replace)

    fun dismissImportResults() = container.importer.dismissResults()

    fun uninstallPack(packId: String) {
        viewModelScope.launch { container.importer.uninstall(packId) }
    }

    // ---------------------------------------------------------------- réglages

    fun setFades(fades: FadeSettings) {
        viewModelScope.launch { container.settingsStore.setFades(fades) }
    }

    fun setTheme(theme: ThemeMode) {
        viewModelScope.launch { container.settingsStore.setTheme(theme) }
    }

    fun setCardSize(size: CardSize) {
        viewModelScope.launch { container.settingsStore.setCardSize(size) }
    }

    fun setLanguage(language: String, then: () -> Unit) {
        viewModelScope.launch {
            container.settingsStore.setLanguage(language)
            then()
        }
    }

    fun notificationsAsked() {
        viewModelScope.launch { container.settingsStore.setNotificationsAsked() }
    }

    override fun onCleared() {
        preview.stop()
    }

    private companion object {
        const val EDITOR_PREVIEW = "editor"
    }
}
