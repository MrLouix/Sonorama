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
import io.github.mrlouix.sonorama.data.CardSound
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.data.PageExchange
import io.github.mrlouix.sonorama.data.Settings
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.data.pack.ImportState
import io.github.mrlouix.sonorama.data.pack.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class MessageAction { OPEN_PACKS }

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

    val library: StateFlow<Library> = repository.library
    val mix: StateFlow<MixState> = engine.state
    val settings: StateFlow<Settings> = container.settings
    val importState: StateFlow<ImportState> = container.importer.state

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

    init {
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    MixEvent.LIMIT_REACHED -> say(R.string.message_limit, MixEngine.MAX_SOUNDS)
                    MixEvent.CARD_UNAVAILABLE -> say(R.string.message_card_unavailable)
                    MixEvent.CHANGED_WHILE_PAUSED -> say(R.string.message_paused)
                }
            }
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
            _messages.tryEmit(
                UiMessage(R.string.message_pack_missing, actionLabel = R.string.action_reimport, action = MessageAction.OPEN_PACKS),
            )
            return
        }
        engine.tap(card.id)
    }

    fun setVolume(cardId: String, volume: Int, commit: Boolean) = engine.setVolume(cardId, volume, commit)

    fun togglePlayback() {
        if (mix.value.active.isEmpty()) say(R.string.message_empty_mix) else engine.togglePlayback()
    }

    /** Renvoie faux si le plan n'est pas vide : l'écran demande alors quoi en faire. */
    fun setMode(mode: MixMode): Boolean = engine.setMode(mode)

    fun applyPlan() = engine.applyPlan()

    fun cancelPlan() = engine.cancelPlan()

    fun stopAll() = engine.stopAll()

    fun stopCard(cardId: String) {
        if (mix.value.isActive(cardId)) engine.tap(cardId)
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
            if (preview.owner == EDITOR_PREVIEW) preview.stop()
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
        _previewCard.value = card.id
    }

    fun stopPreview() {
        preview.stop()
        _previewCard.value = null
        _compositeDraft.update { it?.copy(listening = false) }
    }

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
