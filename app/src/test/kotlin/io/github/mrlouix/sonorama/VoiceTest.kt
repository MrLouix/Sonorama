package io.github.mrlouix.sonorama

import io.github.mrlouix.sonorama.audio.CardSpec
import io.github.mrlouix.sonorama.audio.FadeSettings
import io.github.mrlouix.sonorama.audio.LoopPlayer
import io.github.mrlouix.sonorama.audio.LoopPlayerFactory
import io.github.mrlouix.sonorama.audio.MixEngine
import io.github.mrlouix.sonorama.audio.MixPersistence
import io.github.mrlouix.sonorama.audio.SoundSpec
import io.github.mrlouix.sonorama.data.Card
import io.github.mrlouix.sonorama.data.CardSound
import io.github.mrlouix.sonorama.data.Keywords
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.LocalizedName
import io.github.mrlouix.sonorama.data.Page
import io.github.mrlouix.sonorama.data.RecognitionMode
import io.github.mrlouix.sonorama.data.Settings
import io.github.mrlouix.sonorama.voice.Heard
import io.github.mrlouix.sonorama.voice.HeardWord
import io.github.mrlouix.sonorama.voice.KeywordMatcher
import io.github.mrlouix.sonorama.voice.SpeechEngine
import io.github.mrlouix.sonorama.voice.SpeechListener
import io.github.mrlouix.sonorama.voice.SpeechModelSource
import io.github.mrlouix.sonorama.voice.SpeechOptions
import io.github.mrlouix.sonorama.voice.SpeechSession
import io.github.mrlouix.sonorama.voice.SpeechSource
import io.github.mrlouix.sonorama.voice.Stretcher
import io.github.mrlouix.sonorama.voice.TranscriptEntry
import io.github.mrlouix.sonorama.voice.TranscriptSink
import io.github.mrlouix.sonorama.voice.VoiceController
import io.github.mrlouix.sonorama.voice.VoiceNotice
import io.github.mrlouix.sonorama.voice.VoiceServiceLauncher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTest {

    // ------------------------------------------------------------- mots-clés

    @Test
    fun `normalisation ignores case, accents and punctuation`() {
        assertEquals("foret d ete", Keywords.normalize("  Forêt  d'Été !"))
        assertEquals(listOf("Loup", "pluie"), Keywords.clean(listOf(" Loup ", "", "loup", "pluie")))
    }

    @Test
    fun `a keyword fires once per sentence even when partial results repeat`() {
        val matcher = KeywordMatcher(mapOf("loup" to listOf("wolf")))
        assertEquals(listOf("wolf"), matcher.onPartial(Heard.of("il était un loup")).single().cardIds)
        assertTrue(matcher.onPartial(Heard.of("il était un loup qui")).isEmpty())
        // Redit dans la même phrase : le nombre d'occurrences augmente, le son repart.
        assertEquals(1, matcher.onPartial(Heard.of("il était un loup qui voyait un loup")).size)
        assertTrue(matcher.onFinal(Heard.of("il était un loup qui voyait un loup")).isEmpty())
        // Nouvelle phrase : les compteurs repartent de zéro.
        assertEquals(1, matcher.onPartial(Heard.of("le loup")).size)
    }

    @Test
    fun `an unsure keyword waits until it holds, a fleeting one never fires`() {
        val matcher = KeywordMatcher(mapOf("loup" to listOf("wolf")))
        val unsure = Heard.of("un loup", 0.4f)
        repeat(KeywordMatcher.STABLE_RESULTS - 1) { assertTrue(matcher.onPartial(unsure).isEmpty()) }
        assertEquals(0.4f, matcher.onPartial(unsure).single().confidence)
        assertTrue(matcher.onFinal(unsure).isEmpty())
        // L'hypothèse change avant d'avoir tenu : rien, et le compte repart de zéro.
        repeat(KeywordMatcher.STABLE_RESULTS - 1) { matcher.onPartial(unsure) }
        assertTrue(matcher.onPartial(Heard.of("un loue", 0.4f)).isEmpty())
        assertTrue(matcher.onPartial(unsure).isEmpty())
        assertTrue(matcher.onFinal(unsure).isEmpty())
        // Sûr d'emblée : déclenche aussitôt.
        assertEquals(1, matcher.onPartial(Heard.of("un loup", KeywordMatcher.MIN_CONFIDENCE)).size)
    }

    @Test
    fun `an abandoned sentence does not hide the keyword of the next one`() {
        val matcher = KeywordMatcher(mapOf("loup" to listOf("wolf")))
        assertEquals(1, matcher.onPartial(Heard.of("un loup")).size)
        assertTrue(matcher.onFinal(Heard.of("")).isEmpty())
        assertEquals(1, matcher.onPartial(Heard.of("le loup")).size)
    }

    @Test
    fun `a phrase is as sure as its least sure word`() {
        val matcher = KeywordMatcher(mapOf("bord de mer" to listOf("waves")))
        val heard = Heard(listOf(HeardWord("bord", 0.9f), HeardWord("de", 1f), HeardWord("mer", 0.3f)))
        assertTrue(matcher.onPartial(heard).isEmpty())
    }

    @Test
    fun `the stretched signal is lower and continuous across buffers`() {
        val input = ShortArray(16_000) { (10_000 * kotlin.math.sin(2 * Math.PI * 300 * it / 16_000)).toInt().toShort() }
        val stretcher = Stretcher()
        val out = input.toList().chunked(1600).flatMap { stretcher.process(it.toShortArray()).toList() }
        assertEquals(19_000.0, out.size.toDouble(), 2.0)
        val crossings = out.zipWithNext().count { (a, b) -> a < 0 && b >= 0 }
        assertEquals(300.0, crossings.toDouble(), 2.0) // 300 périodes sur 19 000 échantillons : 253 Hz lus à 16 kHz
        assertTrue(out.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(a - b) } < 1300)
    }

    @Test
    fun `phrases, elisions and accents match, unknown markers do not`() {
        val matcher = KeywordMatcher(
            mapOf("feu de camp" to listOf("fire"), "nuit d'été" to listOf("crickets"), "forêt" to listOf("birds")),
        )
        assertTrue(matcher.onPartial(Heard.of("un feu [unk]")).isEmpty())
        assertEquals(listOf("fire"), matcher.onPartial(Heard.of("un feu de camp")).flatMap { it.cardIds })
        assertEquals(listOf("crickets"), matcher.onPartial(Heard.of("un feu de camp par une nuit d'été")).flatMap { it.cardIds })
        assertEquals(listOf("birds"), matcher.onFinal(Heard.of("un feu de camp par une nuit d'été en FORET")).flatMap { it.cardIds })
    }

    @Test
    fun `only playable cards of the list are matched, a shared keyword starts them all`() {
        val cards = listOf(
            card("rain", fr = listOf("orage")),
            card("thunder", fr = listOf("orage", "tonnerre")),
            card("gone", fr = listOf("orage"), available = false),
        )
        val matcher = KeywordMatcher.forCards(cards, "fr")
        assertEquals(listOf("rain", "thunder"), matcher.onPartial(Heard.of("l'orage")).single().cardIds)
        assertTrue(KeywordMatcher.forCards(cards, "en").isEmpty)
    }

    // ------------------------------------------------------------ contrôleur

    private class FakeSession : SpeechSession {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private class FakeSpeech : SpeechEngine {
        val starts = ArrayList<SpeechOptions>()
        val sessions = ArrayList<FakeSession>()
        lateinit var listener: SpeechListener

        val open: Int get() = sessions.count { !it.closed }

        override fun start(modelDir: File, options: SpeechOptions, listener: SpeechListener): SpeechSession {
            starts += options
            this.listener = listener
            return FakeSession().also { sessions += it }
        }

        override fun unknownWords(modelDir: File, words: Collection<String>): Set<String> = words.filterTo(HashSet()) { it == "grignotin" }
    }

    private class Player : LoopPlayer {
        override val isReady = true

        override fun setGain(gain: Float) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun release() = Unit
    }

    private class Harness(scope: TestScope, models: Set<String> = setOf("fr")) {
        val speech = FakeSpeech()
        val settings = MutableStateFlow(Settings(storyLanguage = "fr", voiceDurationSec = 5))
        val library = MutableStateFlow(
            Library(
                loaded = true,
                cards = listOf(
                    card("wolf", fr = listOf("loup")),
                    card("rain", fr = listOf("pluie")),
                    card("train", fr = listOf("train")),
                ).associateBy { it.id },
                pages = listOf(
                    Page("forest", LocalizedName("Forêt", "Forest"), "forest", false, listOf("wolf", "rain")),
                    Page("city", LocalizedName("Ville", "City"), "city", false, listOf("train")),
                ),
            ),
        )
        var serviceRunning = false
        val transcripts = ArrayList<TranscriptEntry>()
        var transcribing = false
        val notices = ArrayList<VoiceNotice>()

        val engine = MixEngine(
            scope = scope.backgroundScope,
            players = LoopPlayerFactory { _, _ -> Player() },
            cards = { id -> library.value.cards[id]?.let { CardSpec(id, 100, listOf(SoundSpec(id, 100))) } },
            persistence = object : MixPersistence {
                override fun saveVolume(cardId: String, volume: Int) = Unit

                override fun saveMix(activeCardIds: List<String>) = Unit
            },
            fades = { FadeSettings(500, 500, 2000) },
            clock = { scope.testScheduler.currentTime },
        )

        val voice = VoiceController(
            scope = scope.backgroundScope,
            engine = engine,
            library = library,
            settings = settings,
            models = object : SpeechModelSource {
                override val installed = MutableStateFlow(models)

                override suspend fun modelDir(language: String): File? = if (language in installed.value) File("model-$language") else null
            },
            speech = { speech },
            service = object : VoiceServiceLauncher {
                override fun start() {
                    serviceRunning = true
                }

                override fun stop() {
                    serviceRunning = false
                }
            },
            transcripts = object : TranscriptSink {
                override val enabled: Boolean get() = transcribing

                override fun record(entry: TranscriptEntry) {
                    transcripts += entry
                }
            },
            io = StandardTestDispatcher(scope.testScheduler),
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { voice.notices.collect { notices += it } }
        }
    }

    @Test
    fun `turning voice mode on opens the microphone and a keyword starts its card for the set duration`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        assertTrue(h.serviceRunning)
        assertEquals(1, h.speech.open)
        assertNull(h.speech.starts.single().grammar)
        assertTrue(h.voice.state.value.listening)

        h.speech.listener.onPartial(Heard.of("il était une fois un loup"))
        runCurrent()
        assertEquals(listOf("wolf"), h.engine.state.value.active)
        assertEquals(5000L, h.engine.state.value.timed["wolf"]?.durationMs)
        assertEquals("loup", h.voice.state.value.lastKeyword)

        h.voice.disable()
        runCurrent()
        assertFalse(h.serviceRunning)
        assertEquals(0, h.speech.open)
    }

    @Test
    fun `only the cards of the visible page are triggered`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        h.speech.listener.onPartial(Heard.of("le train"))
        runCurrent()
        assertTrue(h.engine.state.value.active.isEmpty())
        h.voice.setPage("city")
        h.speech.listener.onFinal(Heard.of("le train siffle"))
        runCurrent()
        assertEquals(listOf("train"), h.engine.state.value.active)
    }

    @Test
    fun `pause closes the microphone and resuming opens it again`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.engine.tap("rain")
        h.voice.enable()
        runCurrent()
        assertEquals(1, h.speech.open)
        h.engine.pause()
        runCurrent()
        assertEquals(0, h.speech.open)
        assertTrue(h.voice.state.value.paused)
        assertTrue(h.voice.state.value.enabled)
        h.engine.resume()
        runCurrent()
        assertEquals(1, h.speech.open)
        assertEquals(2, h.speech.starts.size)
        assertFalse(h.voice.state.value.paused)
    }

    @Test
    fun `listening stops by itself after five minutes without a keyword`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        advanceTimeBy(4 * 60_000L)
        h.speech.listener.onPartial(Heard.of("la pluie"))
        runCurrent()
        // Un mot-clé relance le délai.
        advanceTimeBy(4 * 60_000L)
        assertTrue(h.voice.state.value.enabled)
        advanceTimeBy(61_000L)
        assertFalse(h.voice.state.value.enabled)
        assertEquals(0, h.speech.open)
        assertTrue(VoiceNotice.AUTO_STOPPED in h.notices)
    }

    @Test
    fun `without a model for the story language voice mode stays off`() = runTest {
        val h = Harness(this, models = emptySet())
        h.voice.enable()
        runCurrent()
        assertFalse(h.voice.state.value.enabled)
        assertFalse(h.serviceRunning)
        assertEquals(listOf(VoiceNotice.NO_MODEL), h.notices)
    }

    @Test
    fun `keywords-only mode gives the page keywords to the recognizer and follows page changes`() = runTest {
        val h = Harness(this)
        h.settings.value = h.settings.value.copy(recognitionMode = RecognitionMode.KEYWORDS_ONLY)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        assertEquals(listOf("loup", "pluie"), h.speech.starts.last().grammar)
        h.voice.setPage("city")
        runCurrent()
        assertEquals(listOf("train"), h.speech.starts.last().grammar)
        assertEquals(1, h.speech.open)
    }

    @Test
    fun `the transcript records what was heard and which cards started`() = runTest {
        val h = Harness(this)
        h.transcribing = true
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        h.speech.listener.onPartial(Heard.of("le loup"))
        h.speech.listener.onFinal(Heard.of("le loup hurle"))
        h.speech.listener.onTranscript(Heard.of("le loup hurle"), -30f)
        runCurrent()
        val entry = h.transcripts.single()
        assertEquals("le loup hurle", entry.heard.text)
        assertEquals("Forêt", entry.page)
        assertEquals(-30f, entry.levelDb)
        val hit = entry.hits.single()
        assertEquals("loup", hit.hit.keyword)
        assertEquals(listOf("wolf"), hit.hit.cardIds)
        assertEquals("le loup", hit.text)
    }

    @Test
    fun `the child voice recognizer follows its own sentence and adds its hits`() = runTest {
        val h = Harness(this)
        h.settings.value = h.settings.value.copy(childVoice = true)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        assertTrue(h.speech.starts.single().childVoice)
        h.speech.listener.onPartial(Heard.of("le loup"))
        h.speech.listener.onPartial(Heard.of("la pluie"), SpeechSource.LOWERED)
        runCurrent()
        assertEquals(setOf("wolf", "rain"), h.engine.state.value.active.toSet())
    }

    @Test
    fun `changing the child voice setting reopens the microphone`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        h.settings.value = h.settings.value.copy(childVoice = true)
        runCurrent()
        assertEquals(listOf(false, true), h.speech.starts.map { it.childVoice })
        assertEquals(1, h.speech.open)
    }

    @Test
    fun `edited keywords apply while listening`() = runTest {
        val h = Harness(this)
        h.voice.setPage("forest")
        h.voice.enable()
        runCurrent()
        val lib = h.library.value
        h.library.value = lib.copy(cards = lib.cards + ("wolf" to card("wolf", fr = listOf("hurlement"))))
        runCurrent()
        h.speech.listener.onPartial(Heard.of("un hurlement"))
        runCurrent()
        assertEquals(listOf("wolf"), h.engine.state.value.active)
    }

    @Test
    fun `words unknown to the model are reported`() = runTest {
        val h = Harness(this)
        assertEquals(setOf("grignotin"), h.voice.unknownWords("fr", listOf("loup", "grignotin")))
    }

    private companion object {
        fun card(id: String, fr: List<String>, available: Boolean = true) = Card(
            id = id, composite = false, name = LocalizedName(id, id), symbol = "music_note", volume = 100,
            sounds = listOf(CardSound(id, 100)), available = available, themePageId = null, packId = null,
            keywords = mapOf("fr" to fr),
        )
    }
}
