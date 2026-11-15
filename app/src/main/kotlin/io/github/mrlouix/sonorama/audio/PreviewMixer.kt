package io.github.mrlouix.sonorama.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pré-écoute indépendante du mix : sélecteur de cartes et éditeur de carte composée.
 * Les sons démarrent et s'arrêtent sans fondu long ; ce n'est pas le mix principal.
 */
class PreviewMixer(private val players: LoopPlayerFactory) {
    private val active = LinkedHashMap<String, LoopPlayer>()

    private val _owner = MutableStateFlow<String?>(null)

    /** Identifiant libre de ce qui est pré-écouté (une carte, l'éditeur…) ; null quand rien ne joue. */
    val owner: StateFlow<String?> = _owner.asStateFlow()

    /** Joue exactement [sounds] (URI → volume de 0 à 100), en réutilisant les lecteurs déjà lancés. */
    fun play(owner: String, sounds: Map<String, Int>) {
        (active.keys - sounds.keys).forEach { active.remove(it)?.release() }
        for ((uri, volume) in sounds) {
            val player = active.getOrPut(uri) { players.create(uri) { drop(uri) }.also { it.play() } }
            player.setGain(VolumeCurve.gainForPercent(volume))
        }
        _owner.value = if (active.isEmpty()) null else owner
    }

    fun stop() {
        active.values.forEach { it.release() }
        active.clear()
        _owner.value = null
    }

    private fun drop(uri: String) {
        active.remove(uri)?.release()
        if (active.isEmpty()) _owner.value = null
    }
}
