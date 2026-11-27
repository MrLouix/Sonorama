package io.github.mrlouix.sonorama

import io.github.mrlouix.sonorama.audio.CardSpec
import io.github.mrlouix.sonorama.audio.FadeSettings
import io.github.mrlouix.sonorama.audio.LoopPlayer
import io.github.mrlouix.sonorama.audio.LoopPlayerFactory
import io.github.mrlouix.sonorama.audio.MixEngine
import io.github.mrlouix.sonorama.audio.MixEvent
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.audio.MixPersistence
import io.github.mrlouix.sonorama.audio.SoundSpec
import io.github.mrlouix.sonorama.audio.VolumeCurve
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixEngineTest {

    private class FakePlayer(val uri: String, val fail: () -> Unit) : LoopPlayer {
        var gain = 0f
            private set
        var playing = false
        var released = false
        val gains = ArrayList<Float>()
        override var isReady = true

        override fun setGain(gain: Float) {
            this.gain = gain
            gains += gain
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun release() {
            released = true
            playing = false
        }
    }

    private class Harness(scope: TestScope, fades: FadeSettings = FadeSettings(500, 500, 2000)) {
        val players = ArrayList<FakePlayer>()
        val cards = HashMap<String, CardSpec>()
        val savedVolumes = HashMap<String, Int>()
        var savedMix: List<String> = emptyList()
        val events = ArrayList<MixEvent>()
        var readyOnCreate = true

        val engine = MixEngine(
            scope = scope.backgroundScope,
            players = LoopPlayerFactory { uri, onError -> FakePlayer(uri, onError).also { it.isReady = readyOnCreate; players += it } },
            cards = { cards[it] },
            persistence = object : MixPersistence {
                override fun saveVolume(cardId: String, volume: Int) {
                    savedVolumes[cardId] = volume
                }

                override fun saveMix(activeCardIds: List<String>) {
                    savedMix = activeCardIds
                }
            },
            fades = { fades },
            clock = { scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { engine.events.collect { events += it } }
        }

        fun card(id: String, sounds: Int = 1, volume: Int = 100) {
            cards[id] = CardSpec(id, volume, (1..sounds).map { SoundSpec("$id-$it", 100) })
        }

        fun live() = players.filter { !it.released }

        fun player(uri: String) = players.last { it.uri == uri }
    }

    @Test
    fun `direct tap fades the card in over the configured duration`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.tap("rain")
        val player = h.player("rain-1")
        assertTrue(player.playing)
        assertEquals(0f, player.gain, 0f)
        assertTrue(h.engine.state.value.playing)
        assertEquals(listOf("rain"), h.engine.state.value.active)

        advanceTimeBy(260)
        assertTrue(player.gain > 0f && player.gain < 1f)
        advanceTimeBy(300)
        assertEquals(1f, player.gain, 1e-4f)
        // La rampe ne fait jamais de saut : le gain est monotone.
        assertEquals(player.gains.sorted(), player.gains)
        assertEquals(listOf("rain"), h.savedMix)
    }

    @Test
    fun `direct tap on an active card fades it out then releases its player`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.tap("rain")
        val player = h.player("rain-1")
        assertFalse(player.released)
        assertTrue(h.engine.state.value.active.isEmpty())
        advanceTimeBy(600)
        assertTrue(player.released)
        assertFalse(h.engine.state.value.playing)
    }

    @Test
    fun `zero fade durations switch immediately`() = runTest {
        val h = Harness(this, FadeSettings(0, 0, 0))
        h.card("rain")
        h.engine.tap("rain")
        assertEquals(1f, h.player("rain-1").gain, 1e-4f)
        h.engine.tap("rain")
        assertTrue(h.player("rain-1").released)
    }

    @Test
    fun `pause fades everything out and keeps cards active, resume restores the mix`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        h.engine.tap("wind")
        advanceTimeBy(600)

        h.engine.pause()
        assertFalse(h.engine.state.value.playing)
        assertEquals(listOf("rain", "wind"), h.engine.state.value.active)
        assertTrue(h.live().all { it.playing })
        advanceTimeBy(600)
        // En pause, les lecteurs sont rendus au système après le fondu, à gain nul.
        assertTrue(h.live().isEmpty())
        assertTrue(h.players.all { it.released && it.gain == 0f })

        h.engine.resume()
        assertTrue(h.live().all { it.playing })
        advanceTimeBy(600)
        assertTrue(h.live().all { it.gain > 0.99f })
        assertEquals(2, h.live().size)
    }

    @Test
    fun `changes made while paused are silent until the next play`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.pause()
        advanceTimeBy(600)

        h.engine.tap("wind")
        assertEquals(listOf("rain", "wind"), h.engine.state.value.active)
        assertTrue(h.players.none { it.uri == "wind-1" })
        assertTrue(MixEvent.CHANGED_WHILE_PAUSED in h.events)

        h.engine.resume()
        advanceTimeBy(600)
        assertTrue(h.player("wind-1").playing)
        assertEquals(1f, h.player("wind-1").gain, 1e-4f)
    }

    @Test
    fun `activation is refused beyond twelve simultaneous sounds`() = runTest {
        val h = Harness(this)
        h.card("scene", sounds = 10)
        h.card("pair", sounds = 2)
        h.card("one")
        h.engine.tap("scene")
        h.engine.tap("pair")
        assertEquals(12, h.engine.state.value.soundCount)
        h.engine.tap("one")
        assertEquals(listOf("scene", "pair"), h.engine.state.value.active)
        assertTrue(MixEvent.LIMIT_REACHED in h.events)
    }

    @Test
    fun `plan mode changes nothing audible until applied`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.setMode(MixMode.PLAN)

        h.engine.tap("rain")
        h.engine.tap("wind")
        h.engine.setVolume("rain", 40, commit = true)
        val state = h.engine.state.value
        assertEquals(listOf("rain"), state.active)
        assertEquals(2, state.plan.size)
        assertTrue(state.plan.getValue("rain").toggle)
        assertEquals(40, state.plan.getValue("rain").volume)
        assertEquals(1, h.players.size)
        assertEquals(1f, h.player("rain-1").gain, 1e-4f)
        assertNull(h.savedVolumes["rain"])
    }

    @Test
    fun `a second tap removes the change from the plan`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain")
        assertEquals(1, h.engine.state.value.plan.size)
        h.engine.tap("rain")
        assertTrue(h.engine.state.value.plan.isEmpty())
    }

    @Test
    fun `applying a plan crossfades starts, stops and volume changes together`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.card("fire")
        h.engine.tap("rain")
        h.engine.tap("fire")
        advanceTimeBy(600)
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain") // arrêt
        h.engine.tap("wind") // démarrage
        h.engine.setVolume("fire", 50, commit = true)

        h.engine.applyPlan()
        runCurrent()
        assertEquals(listOf("fire", "wind"), h.engine.state.value.active)
        assertTrue(h.engine.state.value.plan.isEmpty())
        assertEquals(50, h.savedVolumes["fire"])

        advanceTimeBy(1000)
        val rain = h.player("rain-1")
        val wind = h.player("wind-1")
        val fire = h.player("fire-1")
        assertTrue(rain.gain > 0f && rain.gain < 1f)
        assertTrue(wind.gain > 0f && wind.gain < 1f)
        assertTrue(fire.gain < 1f && fire.gain > VolumeCurve.gainForPercent(50))

        advanceTimeBy(1100)
        assertTrue(rain.released)
        assertEquals(1f, wind.gain, 1e-4f)
        assertEquals(VolumeCurve.gainForPercent(50), fire.gain, 1e-3f)
    }

    @Test
    fun `plan fades wait until every new player is ready`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.readyOnCreate = false
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain")
        h.engine.tap("wind")
        h.engine.applyPlan()

        advanceTimeBy(300)
        assertEquals(1f, h.player("rain-1").gain, 1e-4f)
        assertEquals(0f, h.player("wind-1").gain, 0f)

        h.player("wind-1").isReady = true
        advanceTimeBy(1000)
        assertTrue(h.player("rain-1").gain < 1f)
        assertTrue(h.player("wind-1").gain > 0f)
    }

    @Test
    fun `cancelling a plan leaves the mix untouched`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("wind")
        h.engine.cancelPlan()
        assertTrue(h.engine.state.value.plan.isEmpty())
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertEquals(1, h.players.size)
    }

    @Test
    fun `leaving plan mode is blocked while the plan is not empty`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain")
        assertFalse(h.engine.setMode(MixMode.DIRECT))
        assertEquals(MixMode.PLAN, h.engine.state.value.mode)
        h.engine.cancelPlan()
        assertTrue(h.engine.setMode(MixMode.DIRECT))
    }

    @Test
    fun `the plan cannot exceed twelve sounds`() = runTest {
        val h = Harness(this)
        h.card("scene", sounds = 10)
        h.card("trio", sounds = 3)
        h.engine.tap("scene")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("trio")
        assertTrue(h.engine.state.value.plan.isEmpty())
        assertTrue(MixEvent.LIMIT_REACHED in h.events)
        // En retirant la scène du mix projeté, le trio tient.
        h.engine.tap("scene")
        h.engine.tap("trio")
        assertEquals(2, h.engine.state.value.plan.size)
    }

    @Test
    fun `volume changes are heard live in direct mode and saved on commit`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.setVolume("rain", 30, commit = false)
        assertEquals(VolumeCurve.gainForPercent(30), h.player("rain-1").gain, 1e-4f)
        assertNull(h.savedVolumes["rain"])
        h.engine.setVolume("rain", 35, commit = true)
        assertEquals(35, h.savedVolumes["rain"])
        assertEquals(35, h.engine.state.value.volumes["rain"])
    }

    @Test
    fun `volume can be prepared on an inactive card`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.setVolume("rain", 20, commit = true)
        assertEquals(20, h.savedVolumes["rain"])
        h.engine.tap("rain")
        advanceTimeBy(600)
        assertEquals(VolumeCurve.gainForPercent(20), h.player("rain-1").gain, 1e-4f)
    }

    @Test
    fun `a composite card applies relative volumes to each sound`() = runTest {
        val h = Harness(this, FadeSettings(0, 0, 0))
        h.cards["scene"] = CardSpec("scene", 80, listOf(SoundSpec("a", 100), SoundSpec("b", 50)))
        h.engine.tap("scene")
        val overall = VolumeCurve.gainForPercent(80)
        assertEquals(overall, h.player("a").gain, 1e-4f)
        assertEquals(overall * VolumeCurve.gainForPercent(50), h.player("b").gain, 1e-4f)
        assertEquals(2, h.engine.state.value.soundCount)
    }

    @Test
    fun `the last mix is restored paused`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.restore(listOf("rain", "gone", "wind"))
        val state = h.engine.state.value
        assertEquals(listOf("rain", "wind"), state.active)
        assertFalse(state.playing)
        assertTrue(h.players.isEmpty())
        h.engine.resume()
        advanceTimeBy(600)
        assertEquals(2, h.live().count { it.playing && it.gain > 0.99f })
    }

    @Test
    fun `cards that disappear from the library leave the mix`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        h.engine.tap("wind")
        h.cards.remove("wind")
        h.engine.syncWithLibrary()
        assertEquals(listOf("rain"), h.engine.state.value.active)
    }

    @Test
    fun `stop all empties the mix and the plan`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.engine.tap("rain")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("wind")
        h.engine.stopAll()
        advanceTimeBy(600)
        val state = h.engine.state.value
        assertTrue(state.active.isEmpty() && state.plan.isEmpty())
        assertTrue(h.players.all { it.released })
        assertEquals(emptyList<String>(), h.savedMix)
    }

    @Test
    fun `a sound that fails to play stops its card instead of leaving it silently active`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("broken")
        h.engine.tap("rain")
        h.engine.tap("broken")
        advanceTimeBy(600)

        h.player("broken-1").fail()
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertTrue(h.player("broken-1").released)
        assertTrue(MixEvent.PLAYBACK_FAILED in h.events)
        assertTrue(h.engine.state.value.playing)
        assertEquals(listOf("rain"), h.savedMix)

        // Le dernier son qui échoue vide le mix, qui repasse à l'arrêt.
        h.player("rain-1").fail()
        assertFalse(h.engine.state.value.playing)
    }

    @Test
    fun `the limit is checked again when the plan is applied`() = runTest {
        val h = Harness(this)
        h.card("scene", sounds = 10)
        h.card("x", sounds = 2)
        h.card("y")
        h.engine.tap("scene")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("x")
        // La carte grossit entre sa mise au plan et la validation (carte composée modifiée).
        h.card("x", sounds = 12)
        h.engine.setVolume("scene", 40, commit = true)
        h.engine.applyPlan()
        advanceTimeBy(3000)

        assertEquals(listOf("scene"), h.engine.state.value.active)
        assertEquals(10, h.engine.state.value.soundCount)
        assertEquals(10, h.live().size)
        assertTrue(MixEvent.LIMIT_REACHED in h.events)
        // Le reste du plan est appliqué.
        assertEquals(40, h.savedVolumes["scene"])
    }

    // ------------------------------------------------------------- mode vocal

    @Test
    fun `a voice trigger plays the card for the set duration with short fades`() = runTest {
        val h = Harness(this)
        h.card("wolf")
        h.engine.trigger(listOf("wolf"), 5000)
        val player = h.player("wolf-1")
        assertTrue(h.engine.state.value.playing)
        assertEquals(5000L, h.engine.state.value.timed["wolf"]?.durationMs)
        assertEquals(5000L, h.engine.state.value.timed["wolf"]?.endsAtMs)
        advanceTimeBy(560)
        assertEquals(1f, player.gain, 1e-4f)
        advanceTimeBy(4500)
        assertTrue(player.gain < 1f)
        assertTrue(h.engine.state.value.timed.isEmpty())
        advanceTimeBy(1100)
        assertTrue(player.released)
        assertFalse(h.engine.state.value.playing)
        // Un son vocal ne fait pas partie du mix restauré au lancement.
        assertEquals(emptyList<String>(), h.savedMix)
    }

    @Test
    fun `hearing the keyword again restarts the countdown`() = runTest {
        val h = Harness(this)
        h.card("wolf")
        h.engine.trigger(listOf("wolf"), 5000)
        advanceTimeBy(4000)
        h.engine.trigger(listOf("wolf"), 5000)
        assertEquals(1, h.players.size)
        advanceTimeBy(4000)
        assertTrue("wolf" in h.engine.state.value.active)
        assertEquals(1f, h.player("wolf-1").gain, 1e-4f)
        advanceTimeBy(2200)
        assertFalse("wolf" in h.engine.state.value.active)
    }

    @Test
    fun `a card already in the mix is left alone by the voice`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.trigger(listOf("rain"), 5000)
        assertTrue(h.engine.state.value.timed.isEmpty())
        advanceTimeBy(6000)
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertEquals(1, h.players.size)
    }

    @Test
    fun `nothing is triggered while paused`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wolf")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.pause()
        h.engine.trigger(listOf("wolf"), 5000)
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertTrue(h.players.none { it.uri == "wolf-1" })
    }

    @Test
    fun `pausing stops voice sounds and keeps the mix`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wolf")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.trigger(listOf("wolf"), 10_000)
        advanceTimeBy(600)
        h.engine.pause()
        advanceTimeBy(600)
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertTrue(h.player("wolf-1").released)
        h.engine.resume()
        advanceTimeBy(600)
        assertEquals(listOf("rain"), h.engine.state.value.active)
    }

    @Test
    fun `a pause with only voice sounds stops playback`() = runTest {
        val h = Harness(this)
        h.card("wolf")
        h.engine.trigger(listOf("wolf"), 10_000)
        advanceTimeBy(600)
        h.engine.pause()
        assertFalse(h.engine.state.value.playing)
        assertTrue(h.engine.state.value.active.isEmpty())
        // La pause tient même mix vide : rien ne se déclenche avant la reprise.
        assertTrue(h.engine.state.value.paused)
        h.engine.trigger(listOf("wolf"), 5000)
        assertTrue(h.engine.state.value.active.isEmpty())
        h.engine.resume()
        assertFalse(h.engine.state.value.paused)
        h.engine.trigger(listOf("wolf"), 5000)
        assertEquals(listOf("wolf"), h.engine.state.value.active)
    }

    @Test
    fun `at the limit the oldest voice sound makes room, mix sounds never do`() = runTest {
        val h = Harness(this)
        h.card("mix", sounds = 10)
        h.card("a")
        h.card("b")
        h.card("c")
        h.engine.tap("mix")
        h.engine.trigger(listOf("a"), 5000)
        advanceTimeBy(100)
        h.engine.trigger(listOf("b"), 5000)
        h.engine.trigger(listOf("c"), 5000)
        assertEquals(listOf("mix", "b", "c"), h.engine.state.value.active)
        assertEquals(12, h.engine.state.value.soundCount)

        h.card("big", sounds = 3)
        h.engine.trigger(listOf("big"), 5000)
        assertTrue(MixEvent.LIMIT_REACHED in h.events)
        assertTrue("mix" in h.engine.state.value.active)
    }

    @Test
    fun `one keyword can start several cards together`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("thunder")
        h.engine.trigger(listOf("rain", "thunder"), 5000)
        assertEquals(listOf("rain", "thunder"), h.engine.state.value.active)
        assertEquals(setOf("rain", "thunder"), h.engine.state.value.timed.keys)
    }

    @Test
    fun `voice triggers are immediate in plan mode and leave the plan alone`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wolf")
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain")
        h.engine.trigger(listOf("wolf"), 5000)
        assertEquals(listOf("wolf"), h.engine.state.value.active)
        assertEquals(setOf("rain"), h.engine.state.value.plan.keys)
    }

    @Test
    fun `a first tap keeps a voice card playing, a second tap stops it`() = runTest {
        val h = Harness(this)
        h.card("wolf")
        h.card("rain")
        h.engine.trigger(listOf("wolf"), 5000)
        advanceTimeBy(600)
        h.engine.tap("wolf")
        assertTrue(h.engine.state.value.timed.isEmpty())
        advanceTimeBy(6000)
        assertEquals(listOf("wolf"), h.engine.state.value.active)
        h.engine.tap("wolf")
        advanceTimeBy(600)
        assertTrue(h.engine.state.value.active.isEmpty())

        // En mode plan aussi, le premier appui garde la carte au lieu de préparer son arrêt.
        h.engine.trigger(listOf("rain"), 5000)
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("rain")
        assertTrue(h.engine.state.value.plan.isEmpty())
        assertTrue(h.engine.state.value.timed.isEmpty())
        h.engine.tap("rain")
        assertEquals(setOf("rain"), h.engine.state.value.plan.keys)
    }

    @Test
    fun `a voice card can be kept playing like a card started by hand`() = runTest {
        val h = Harness(this)
        h.card("mix", sounds = 10)
        h.card("wolf")
        h.engine.trigger(listOf("wolf"), 5000)
        advanceTimeBy(1000)
        h.engine.setMode(MixMode.PLAN)
        h.engine.keep("wolf")
        assertTrue(h.engine.state.value.timed.isEmpty())
        assertTrue(h.engine.state.value.plan.isEmpty())
        assertEquals(listOf("wolf"), h.savedMix)
        advanceTimeBy(10_000)
        assertEquals(listOf("wolf"), h.engine.state.value.active)
        // Gardée, elle ne cède plus sa place aux sons vocaux.
        h.engine.setMode(MixMode.DIRECT)
        h.engine.tap("mix")
        h.card("big", sounds = 2)
        h.engine.trigger(listOf("big"), 5000)
        assertEquals(listOf("wolf", "mix"), h.engine.state.value.active)
        assertTrue(MixEvent.LIMIT_REACHED in h.events)
        // Une carte qui n'est pas lancée à la voix n'est pas concernée.
        h.engine.keep("mix")
        assertTrue("mix" in h.engine.state.value.active)
    }

    @Test
    fun `the guided tour sets the mix aside and brings it back as it was`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("wind")
        h.card("birds")
        h.engine.tap("rain")
        h.engine.tap("wind")
        advanceTimeBy(600)
        h.engine.setMode(MixMode.PLAN)
        h.engine.tap("birds")

        h.engine.setAside()
        val during = h.engine.state.value
        assertTrue(during.active.isEmpty())
        assertTrue(during.plan.isEmpty())
        assertEquals(MixMode.DIRECT, during.mode)
        advanceTimeBy(600)
        assertTrue(h.live().isEmpty())
        // La visite lance une carte : le mix enregistré reste celui d'avant.
        h.engine.tap("birds")
        assertEquals(listOf("rain", "wind"), h.savedMix)

        h.engine.bringBack()
        val after = h.engine.state.value
        assertEquals(listOf("rain", "wind"), after.active)
        assertTrue(after.playing)
        assertEquals(MixMode.PLAN, after.mode)
        assertEquals(setOf("birds"), after.plan.keys)
        advanceTimeBy(600)
        assertEquals(setOf("rain-1", "wind-1"), h.live().map { it.uri }.toSet())
        assertEquals(1f, h.player("rain-1").gain, 1e-4f)
    }

    @Test
    fun `a paused mix comes back paused after the tour`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.engine.tap("rain")
        advanceTimeBy(600)
        h.engine.pause()
        advanceTimeBy(600)
        h.engine.setAside()
        h.engine.bringBack()
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertTrue(h.engine.state.value.paused)
        assertFalse(h.engine.state.value.playing)
    }

    @Test
    fun `volumes changed during the tour are put back and saved`() = runTest {
        val h = Harness(this)
        h.card("birds", volume = 60)
        h.engine.setVolume("birds", 80, commit = true)
        h.engine.setAside()
        h.engine.tap("birds")
        h.engine.setVolume("birds", 40, commit = false)
        h.engine.setVolume("birds", 30, commit = true)
        assertEquals(30, h.savedVolumes["birds"])

        h.engine.bringBack()
        assertEquals(80, h.engine.state.value.volumes["birds"])
        assertEquals(80, h.savedVolumes["birds"])
        advanceTimeBy(600)
        // La visite n'avait rien à rétablir : sa carte s'éteint en fondu.
        assertTrue(h.engine.state.value.active.isEmpty())
        assertTrue(h.live().isEmpty())
    }

    @Test
    fun `the mix restored at launch waits for the end of the tour`() = runTest {
        val h = Harness(this)
        h.card("rain")
        h.card("birds")
        h.engine.setAside()
        h.engine.restore(listOf("rain"))
        assertTrue(h.engine.state.value.active.isEmpty())
        h.engine.tap("birds")
        h.engine.bringBack()
        assertEquals(listOf("rain"), h.engine.state.value.active)
        assertTrue(h.engine.state.value.paused)
    }
}
