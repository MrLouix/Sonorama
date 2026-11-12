package io.github.mrlouix.sonorama.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Un son d'une carte : où le lire et à quel volume relatif (0 à 100). */
data class SoundSpec(val uri: String, val relativeVolume: Int)

/** Ce que le moteur doit savoir d'une carte pour la jouer. */
data class CardSpec(val id: String, val volume: Int, val sounds: List<SoundSpec>)

enum class MixMode { DIRECT, PLAN }

/** Changement préparé en mode plan pour une carte. */
data class PlanChange(val toggle: Boolean = false, val volume: Int? = null) {
    val isEmpty: Boolean get() = !toggle && volume == null
}

data class MixState(
    /** Cartes actives, dans l'ordre d'activation. */
    val active: List<String> = emptyList(),
    val playing: Boolean = false,
    val mode: MixMode = MixMode.DIRECT,
    val plan: Map<String, PlanChange> = emptyMap(),
    /** Volumes réglés pendant cette session, plus récents que ceux de la base. */
    val volumes: Map<String, Int> = emptyMap(),
    val soundCount: Int = 0,
) {
    fun isActive(cardId: String): Boolean = cardId in active
}

enum class MixEvent { LIMIT_REACHED, CARD_UNAVAILABLE, CHANGED_WHILE_PAUSED }

data class FadeSettings(
    val fadeInMs: Int = 500,
    val fadeOutMs: Int = 500,
    val crossfadeMs: Int = 2000,
)

interface MixPersistence {
    fun saveVolume(cardId: String, volume: Int)

    fun saveMix(activeCardIds: List<String>)
}

/**
 * Possède l'état du mix et pilote les lecteurs. L'interface n'envoie que des commandes
 * et n'observe que [state]. Toutes les méthodes s'appellent depuis le fil de [scope].
 */
class MixEngine(
    private val scope: CoroutineScope,
    private val players: LoopPlayerFactory,
    private val cards: (String) -> CardSpec?,
    private val persistence: MixPersistence,
    private val fades: () -> FadeSettings,
) {
    private val _state = MutableStateFlow(MixState())
    val state: StateFlow<MixState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MixEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MixEvent> = _events.asSharedFlow()

    private val voices = LinkedHashMap<String, Voice>()
    private val releasing = ArrayList<Voice>()
    private val master = Ramp(1f)

    private var playing = false
    private var pausePending = false
    private var mode = MixMode.DIRECT
    private var plan: Map<String, PlanChange> = emptyMap()
    private var volumes: Map<String, Int> = emptyMap()
    private var savedMix: List<String> = emptyList()
    private var ticker: Job? = null

    // ---------------------------------------------------------------- commandes

    /** Appui court sur une carte : effet immédiat en mode direct, mise au plan sinon. */
    fun tap(cardId: String) {
        when {
            mode == MixMode.PLAN -> togglePlan(cardId)
            cardId in voices -> deactivate(cardId)
            else -> activate(cardId)
        }
    }

    fun activate(cardId: String): Boolean {
        if (cardId in voices) return true
        val spec = cards(cardId)
        if (spec == null || spec.sounds.isEmpty()) {
            _events.tryEmit(MixEvent.CARD_UNAVAILABLE)
            return false
        }
        if (soundCount() + spec.sounds.size > MAX_SOUNDS) {
            _events.tryEmit(MixEvent.LIMIT_REACHED)
            return false
        }
        val voice = Voice(spec, volumeOf(cardId))
        if (voices.isEmpty() && !playing) startPlayingFromEmpty()
        voices[cardId] = voice
        if (playing) {
            start(voice)
            voice.fade.go(1f, fades().fadeInMs)
        } else {
            voice.fade.snap(1f)
            _events.tryEmit(MixEvent.CHANGED_WHILE_PAUSED)
        }
        publish()
        kick()
        return true
    }

    fun deactivate(cardId: String) {
        val voice = voices.remove(cardId) ?: return
        if (voice.players != null && (playing || pausePending)) {
            releasing += voice
            voice.fade.go(0f, fades().fadeOutMs)
        } else {
            voice.release()
        }
        if (voices.isEmpty()) becomeEmpty()
        publish()
        kick()
    }

    /**
     * Règle le volume d'une carte. En mode direct l'effet est immédiat et [commit] le mémorise ;
     * en mode plan seul un appel avec [commit] compte, et il rejoint le plan.
     */
    fun setVolume(cardId: String, volume: Int, commit: Boolean) {
        val target = volume.coerceIn(VolumeCurve.MIN_VOLUME, VolumeCurve.MAX_VOLUME)
        if (mode == MixMode.PLAN) {
            if (!commit) return
            val change = (plan[cardId] ?: PlanChange())
                .copy(volume = target.takeIf { it != volumeOf(cardId) })
            plan = if (change.isEmpty) plan - cardId else plan + (cardId to change)
            publish()
            return
        }
        voices[cardId]?.let {
            it.volume.snap(target / 100f)
            applyGain(it)
        }
        volumes = volumes + (cardId to target)
        if (commit) persistence.saveVolume(cardId, target)
        publish()
    }

    /** Renvoie faux si le retour au mode direct est bloqué par un plan non vide. */
    fun setMode(newMode: MixMode): Boolean {
        if (newMode == MixMode.DIRECT && plan.isNotEmpty()) return false
        mode = newMode
        publish()
        return true
    }

    fun cancelPlan() {
        plan = emptyMap()
        publish()
    }

    /** Applique tous les changements du plan au même instant, en fondu croisé. */
    fun applyPlan() {
        val changes = plan
        if (changes.isEmpty()) return
        plan = emptyMap()
        val wasEmpty = voices.isEmpty()
        val starts = ArrayList<Voice>()
        val stops = ArrayList<Voice>()
        val moves = ArrayList<Pair<Voice, Float>>()
        for ((cardId, change) in changes) {
            change.volume?.let {
                volumes = volumes + (cardId to it)
                persistence.saveVolume(cardId, it)
            }
            val existing = voices[cardId]
            if (change.toggle) {
                if (existing != null) {
                    voices.remove(cardId)
                    stops += existing
                } else {
                    val spec = cards(cardId)?.takeIf { it.sounds.isNotEmpty() } ?: continue
                    starts += Voice(spec, volumeOf(cardId))
                }
            } else if (existing != null && change.volume != null) {
                moves += existing to change.volume / 100f
            }
        }
        val audible = playing || (wasEmpty && starts.isNotEmpty())
        if (!audible) {
            starts.forEach {
                it.fade.snap(1f)
                voices[it.cardId] = it
            }
            stops.forEach { it.release() }
            moves.forEach { (voice, level) -> voice.volume.snap(level) }
            if (voices.isEmpty()) becomeEmpty()
            publish()
            kick()
            return
        }
        if (!playing) startPlayingFromEmpty()
        // D'abord préparer tous les lecteurs, à gain nul…
        starts.forEach {
            voices[it.cardId] = it
            it.fade.snap(0f)
            start(it)
        }
        stops.forEach { if (it.players == null) it.release() else releasing += it }
        if (voices.isEmpty()) becomeEmpty()
        publish()
        // … puis lancer tous les fondus au même tick.
        scope.launch {
            var waited = 0L
            while (waited < READY_TIMEOUT_MS && starts.any { !it.isReady }) {
                delay(TICK_MS)
                waited += TICK_MS
            }
            val crossfade = fades().crossfadeMs
            starts.forEach { if (voices[it.cardId] === it) it.fade.go(1f, crossfade) }
            stops.forEach { it.fade.go(0f, crossfade) }
            moves.forEach { (voice, level) -> if (voices[voice.cardId] === voice) voice.volume.go(level, crossfade) }
            kick()
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        if (voices.isNotEmpty()) {
            pausePending = true
            master.go(0f, fades().fadeOutMs)
        }
        publish()
        kick()
    }

    fun resume() {
        if (playing || voices.isEmpty()) return
        playing = true
        pausePending = false
        voices.values.forEach { start(it) }
        master.go(1f, fades().fadeInMs)
        publish()
        kick()
    }

    fun togglePlayback() {
        if (playing) pause() else resume()
    }

    fun stopAll() {
        plan = emptyMap()
        voices.keys.toList().forEach { deactivate(it) }
        publish()
    }

    /** Restaure un mix enregistré, en pause. Sans effet si un mix est déjà en place. */
    fun restore(cardIds: List<String>) {
        if (voices.isNotEmpty()) return
        for (cardId in cardIds) {
            val spec = cards(cardId)?.takeIf { it.sounds.isNotEmpty() } ?: continue
            if (soundCount() + spec.sounds.size > MAX_SOUNDS) break
            voices[cardId] = Voice(spec, volumeOf(cardId)).also { it.fade.snap(1f) }
        }
        if (voices.isNotEmpty()) {
            playing = false
            master.snap(0f)
        }
        savedMix = voices.keys.toList()
        publish()
    }

    /** À appeler quand la bibliothèque change : retire les cartes devenues injouables. */
    fun syncWithLibrary() {
        voices.keys.filter { cards(it)?.sounds.isNullOrEmpty() }.forEach { deactivate(it) }
        val stale = plan.keys.filter { cards(it) == null }
        if (stale.isNotEmpty()) {
            plan = plan - stale.toSet()
            publish()
        }
    }

    // ------------------------------------------------------------------ interne

    private fun togglePlan(cardId: String) {
        val existing = plan[cardId] ?: PlanChange()
        val change = existing.copy(toggle = !existing.toggle)
        val candidate = if (change.isEmpty) plan - cardId else plan + (cardId to change)
        val before = projectedSoundCount(plan)
        val after = projectedSoundCount(candidate)
        if (after == null) {
            _events.tryEmit(MixEvent.CARD_UNAVAILABLE)
            return
        }
        if (after > MAX_SOUNDS && after > (before ?: 0)) {
            _events.tryEmit(MixEvent.LIMIT_REACHED)
            return
        }
        plan = candidate
        publish()
    }

    /** Nombre de sons du mix une fois [candidate] appliqué ; null si une carte à lancer est injouable. */
    private fun projectedSoundCount(candidate: Map<String, PlanChange>): Int? {
        var count = 0
        for ((cardId, voice) in voices) {
            if (candidate[cardId]?.toggle != true) count += voice.spec.sounds.size
        }
        for ((cardId, change) in candidate) {
            if (change.toggle && cardId !in voices) {
                val spec = cards(cardId)?.takeIf { it.sounds.isNotEmpty() } ?: return null
                count += spec.sounds.size
            }
        }
        return count
    }

    private fun volumeOf(cardId: String): Int =
        volumes[cardId] ?: cards(cardId)?.volume ?: VolumeCurve.DEFAULT_VOLUME

    private fun soundCount(): Int = voices.values.sumOf { it.spec.sounds.size }

    private fun startPlayingFromEmpty() {
        playing = true
        pausePending = false
        // Des voix peuvent encore s'éteindre : ne pas les faire remonter d'un coup.
        if (releasing.isEmpty()) master.snap(1f) else master.go(1f, fades().fadeInMs)
    }

    private fun becomeEmpty() {
        playing = false
        pausePending = false
        if (releasing.isEmpty()) master.snap(1f)
    }

    private fun start(voice: Voice) {
        if (voice.players == null) {
            voice.players = voice.spec.sounds.map {
                players.create(it.uri) to VolumeCurve.gainForPercent(it.relativeVolume)
            }
        }
        applyGain(voice)
        voice.players?.forEach { it.first.play() }
    }

    private fun applyGain(voice: Voice) {
        val base = VolumeCurve.gain(voice.volume.value) *
            VolumeCurve.fade(voice.fade.value) *
            VolumeCurve.fade(master.value)
        voice.players?.forEach { (player, relative) -> player.setGain(base * relative) }
    }

    private fun publish() {
        val active = voices.keys.toList()
        _state.value = MixState(
            active = active,
            playing = playing,
            mode = mode,
            plan = plan,
            volumes = volumes,
            soundCount = soundCount(),
        )
        if (active != savedMix) {
            savedMix = active
            persistence.saveMix(active)
        }
    }

    /** Applique l'état courant aux lecteurs et lance le ticker si une rampe est en cours. */
    private fun kick() {
        settle()
        val moving = master.moving ||
            voices.values.any { it.moving } ||
            releasing.any { it.moving }
        if (moving && ticker == null) {
            ticker = scope.launch {
                try {
                    while (true) {
                        delay(TICK_MS)
                        if (!step()) break
                    }
                } finally {
                    ticker = null
                }
            }
        }
    }

    private fun step(): Boolean {
        var moving = master.tick()
        voices.values.forEach { if (it.tick()) moving = true }
        releasing.forEach { if (it.tick()) moving = true }
        settle()
        return moving
    }

    private fun settle() {
        voices.values.forEach(::applyGain)
        val done = releasing.filter { it.fade.value <= 0f && !it.fade.moving }
        done.forEach { it.release() }
        releasing.removeAll(done.toSet())
        releasing.forEach(::applyGain)
        if (pausePending && master.value <= 0f) {
            pausePending = false
            voices.values.forEach { voice -> voice.players?.forEach { it.first.pause() } }
        }
    }

    private class Voice(val spec: CardSpec, volume: Int) {
        val cardId: String get() = spec.id
        var players: List<Pair<LoopPlayer, Float>>? = null
        val fade = Ramp(0f)
        val volume = Ramp(volume / 100f)

        val moving: Boolean get() = fade.moving || volume.moving

        val isReady: Boolean get() = players?.all { it.first.isReady } ?: true

        fun tick(): Boolean {
            val a = fade.tick()
            val b = volume.tick()
            return a || b
        }

        fun release() {
            players?.forEach { it.first.release() }
            players = null
        }
    }

    /** Valeur qui rejoint sa cible par pas de [TICK_MS]. */
    private class Ramp(var value: Float) {
        private var target = value
        private var step = 0f

        val moving: Boolean get() = value != target

        fun snap(to: Float) {
            value = to
            target = to
        }

        fun go(to: Float, durationMs: Int) {
            target = to
            if (durationMs <= 0 || value == to) {
                value = to
            } else {
                step = abs(to - value) / max(1f, durationMs / TICK_MS.toFloat())
            }
        }

        fun tick(): Boolean {
            if (value == target) return false
            value = if (value < target) min(target, value + step) else max(target, value - step)
            return value != target
        }
    }

    companion object {
        const val MAX_SOUNDS = 12
        const val TICK_MS = 20L
        const val READY_TIMEOUT_MS = 1000L
    }
}
