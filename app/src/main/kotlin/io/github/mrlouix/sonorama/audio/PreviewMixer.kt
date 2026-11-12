package io.github.mrlouix.sonorama.audio

/**
 * Pré-écoute indépendante du mix : sélecteur de cartes et éditeur de carte composée.
 * Les sons démarrent et s'arrêtent sans fondu long ; ce n'est pas le mix principal.
 */
class PreviewMixer(private val players: LoopPlayerFactory) {
    private val active = LinkedHashMap<String, LoopPlayer>()

    /** Identifiant libre de ce qui est pré-écouté (une carte, l'éditeur…), pour l'interface. */
    var owner: String? = null
        private set

    /** Joue exactement [sounds] (URI → volume de 0 à 100), en réutilisant les lecteurs déjà lancés. */
    fun play(owner: String, sounds: Map<String, Int>) {
        this.owner = owner
        (active.keys - sounds.keys).forEach { active.remove(it)?.release() }
        for ((uri, volume) in sounds) {
            val player = active.getOrPut(uri) { players.create(uri).also { it.play() } }
            player.setGain(VolumeCurve.gainForPercent(volume))
        }
    }

    fun stop() {
        owner = null
        active.values.forEach { it.release() }
        active.clear()
    }
}
