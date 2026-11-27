package io.github.mrlouix.sonorama

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.data.pack.PackFormat
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.Screen
import io.github.mrlouix.sonorama.ui.SonoramaRoot
import io.github.mrlouix.sonorama.ui.tour.TourStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import io.github.mrlouix.sonorama.voice.Heard
import io.github.mrlouix.sonorama.voice.SpeechEngine
import io.github.mrlouix.sonorama.voice.SpeechListener
import io.github.mrlouix.sonorama.voice.SpeechOptions
import io.github.mrlouix.sonorama.voice.SpeechSession
import java.io.File

/**
 * Tests d'écran sur JVM : l'appli réelle (base, pack de base, moteur) derrière l'interface Compose.
 * Chaque test écrit aussi une capture dans `app/build/screens/` pour relecture visuelle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-w411dp-h891dp-420dpi")
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        // La visite guidée du premier lancement couvrirait l'écran : elle a son propre test.
        val app = ApplicationProvider.getApplicationContext<Application>() as SonoramaApp
        runBlocking { app.container.settingsStore.setTourSeen(true) }
        viewModel = MainViewModel(app)
        rule.setContent { SonoramaRoot(viewModel, onLanguageChanged = {}) }
        waitFor { rule.onAllNodes(hasContentDescription("Oiseaux", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Attend [condition] en laissant tourner le fil principal (base de données, réglages). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(20_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    /** Une carte de la grille : son nom est porté par sa description d'accessibilité. */
    private fun card(name: String) = rule.onNode(hasContentDescription(name, substring = true))

    private fun capture(name: String) = rule.onRoot().captureRoboImage("build/screens/$name.png")

    @Test
    fun `main screen shows the built-in pages and the base pack`() {
        val library = viewModel.library.value
        assertEquals(listOf("nature", "ville", "feerique"), library.pages.map { it.id })
        assertEquals(36, library.cards.size)
        rule.onNodeWithText("Ville et machines").assertExists()
        capture("main")
    }

    @Test
    fun `plan mode marks pending cards and offers apply and cancel`() {
        rule.onNodeWithText("Plan").performClick()
        card("Oiseaux").performClick()
        card("Ruisseau").performClick()
        rule.waitForIdle()
        assertEquals(MixMode.PLAN, viewModel.mix.value.mode)
        assertEquals(2, viewModel.mix.value.plan.size)
        assertTrue(viewModel.mix.value.active.isEmpty())
        rule.onNodeWithText("Appliquer 2 changements").assertExists()
        capture("plan")
        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertTrue(viewModel.mix.value.plan.isEmpty())
    }

    @Test
    fun `a custom page can be composed and saved`() {
        rule.onNodeWithContentDescription("Ajouter une page").performScrollTo().performClick()
        rule.onNodeWithText("Nouvelle page").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Soir d'orage")
        rule.onNodeWithText("Choisir des cartes").performClick()
        rule.onNodeWithText("Feu de camp").performClick()
        rule.onNodeWithText("Grillons").performClick()
        capture("picker")
        rule.onNodeWithText("Terminé").performClick()
        capture("page-editor")
        rule.onNodeWithText("Enregistrer").performClick()
        waitFor { viewModel.library.value.pages.size == 4 }
        val page = viewModel.library.value.pages.last()
        assertEquals("Soir d'orage", page.name.fr)
        assertEquals(2, page.cardIds.size)
        rule.waitForIdle()
        capture("custom-page")
    }

    @Test
    fun `secondary screens render in the dark theme`() {
        viewModel.setTheme(ThemeMode.DARK)
        waitFor { viewModel.settings.value.theme == ThemeMode.DARK }
        capture("main-dark")
        rule.runOnUiThread { viewModel.open(Screen.Settings) }
        rule.onNodeWithText("Fondus").assertExists()
        capture("settings")
        rule.onNodeWithText("Enregistrer le texte entendu (texte seul)").performScrollTo()
        rule.onNodeWithText("Modèles vocaux").assertExists()
        capture("settings-voice")
        rule.runOnUiThread {
            viewModel.back()
            viewModel.open(Screen.Packs)
        }
        rule.onNodeWithText("Importer un pack").assertExists()
        capture("packs")
        rule.runOnUiThread {
            viewModel.back()
            viewModel.open(Screen.Licenses)
        }
        rule.onNodeWithText("Pack de base").assertExists()
        capture("licenses")
        rule.runOnUiThread { viewModel.setTheme(ThemeMode.SYSTEM) }
    }

    @Test
    fun `live mode activates cards and lists them in the now playing panel`() {
        card("Oiseaux").performClick()
        card("Vagues").performClick()
        waitFor { viewModel.mix.value.active.size == 2 }
        assertTrue(viewModel.mix.value.playing)
        capture("live")
        rule.onNodeWithContentDescription("2 cartes actives").performClick()
        rule.onNodeWithText("Enregistrer ce mix").assertExists()
        capture("now-playing")
        rule.onNodeWithText("Tout arrêter").performClick()
        waitFor { viewModel.mix.value.active.isEmpty() }
    }

    @Test
    fun `the guided tour walks through every step and gives the mix back`() {
        card("Vagues").performClick()
        waitFor { viewModel.mix.value.active.size == 1 }
        val mixBefore = viewModel.mix.value.active
        val birds = viewModel.library.value.page("nature")!!.cardIds.first()
        val volumeBefore = viewModel.mix.value.volumes[birds] ?: viewModel.library.value.cards.getValue(birds).volume

        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Visite guidée").performClick()
        waitFor { viewModel.tour.value != null }
        assertTrue(viewModel.mix.value.active.isEmpty())
        rule.onNodeWithText("1 / 9").assertExists()
        // Hors des étapes interactives, l'écran sous le voile ne réagit pas.
        card("Ruisseau").performClick()
        rule.waitForIdle()
        assertTrue(viewModel.mix.value.active.isEmpty())
        capture("tour-1")

        rule.onNodeWithText("Suivant").performClick()
        capture("tour-2")
        // Étape interactive : la carte montrée se touche à travers le voile, et la visite avance seule.
        card("Oiseaux").performClick()
        waitFor { viewModel.tour.value?.step == TourStep.VOLUME }
        assertEquals(listOf(birds), viewModel.mix.value.active)
        capture("tour-3")
        rule.runOnUiThread { viewModel.setVolume(birds, 25, true) }
        waitFor { viewModel.tour.value?.step == TourStep.PLAY }

        for (step in 4..8) {
            rule.onNodeWithText("$step / 9").assertExists()
            capture("tour-$step")
            rule.onNodeWithText("Suivant").performClick()
        }
        rule.onNodeWithText("9 / 9").assertExists()
        rule.onNodeWithText("Passer").assertDoesNotExist()
        capture("tour-9")
        rule.onNodeWithText("Terminer").performClick()
        waitFor { viewModel.tour.value == null }

        // Le mix d'avant revient, et la carte d'exemple retrouve son volume.
        assertEquals(mixBefore, viewModel.mix.value.active)
        assertTrue(viewModel.mix.value.playing)
        assertEquals(volumeBefore, viewModel.mix.value.volumes[birds])
    }

    @Test
    fun `the tour starts by itself on first launch, once, even when skipped`() {
        val app = ApplicationProvider.getApplicationContext<Application>() as SonoramaApp
        val store = app.container.settingsStore
        runBlocking { store.setTourSeen(false) }
        val fresh = MainViewModel(app)
        waitFor { fresh.tour.value != null }
        assertEquals(TourStep.WELCOME, fresh.tour.value!!.step)
        waitFor { runBlocking { store.settings.first().tourSeen } }
        rule.runOnUiThread { fresh.endTour() }
        assertNull(fresh.tour.value)

        val again = MainViewModel(app)
        rule.waitForIdle()
        ShadowLooper.idleMainLooper()
        assertNull(again.tour.value)
        assertFalse(viewModel.mix.value.playing)
    }

    /** Installe un pack dont deux pages portent le même nom que la page Nature et météo. */
    private fun installPackWithDuplicatePageNames() {
        val app = ApplicationProvider.getApplicationContext<Application>() as SonoramaApp
        val manifest = PackFormat.parse(
            """{"format":1,"id":"jumeaux","version":1,"name":{"fr":"Jumeaux"},
               "pages":[{"id":"p1","name":{"fr":"Nature et météo"}},{"id":"p2","name":{"fr":"Nature et météo"}}],
               "sounds":[{"id":"a","file":"a.ogg","name":{"fr":"Son A"},"page":"p1","license":"CC0"},
                         {"id":"b","file":"b.ogg","name":{"fr":"Son B"},"page":"p2","license":"CC0"}]}""",
        ).also(PackFormat::validate)
        runBlocking { app.container.repository.installPack(manifest, builtIn = false) { "file:///absent/${it.file}" } }
        waitFor { viewModel.library.value.cards.size == 38 }
    }

    @Test
    fun `the card picker survives pages with the same name and reports a full page`() {
        installPackWithDuplicatePageNames()
        rule.runOnUiThread {
            viewModel.newPage()
            viewModel.open(Screen.CardPicker)
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Son B"))
        rule.onNodeWithText("Son A").assertExists()
        rule.onNodeWithText("Son B").assertExists()

        // 38 cartes installées pour 24 places : le refus s'affiche sur l'écran où il se produit.
        rule.runOnUiThread { viewModel.library.value.cards.keys.forEach(viewModel::togglePageCard) }
        assertEquals(24, viewModel.pageDraft.value!!.cardIds.size)
        waitFor { rule.onAllNodes(hasText("Une page contient 24 cartes au plus.")).fetchSemanticsNodes().isNotEmpty() }
        capture("picker-full")
    }

    @Test
    @Config(sdk = [35], qualifiers = "fr-w360dp-h640dp-420dpi")
    fun `the bottom bar keeps its labels whole on a narrow screen`() {
        rule.onNodeWithText("Plan").performClick()
        card("Oiseaux").performClick()
        rule.onNodeWithText("Direct").assertExists()
        rule.onNodeWithText("Appliquer 1 changement").assertExists()
        capture("narrow")
    }

    @Test
    @Config(sdk = [35], qualifiers = "fr-w360dp-h640dp-420dpi", fontScale = 2.0f)
    fun `the bottom bar keeps its labels whole at twice the font size`() {
        rule.onNodeWithText("Plan").performClick()
        card("Oiseaux").performClick()
        rule.onNodeWithText("Appliquer 1 changement").assertExists()
        capture("big-font")
    }

    @Test
    fun `setup mode opens the keywords of a card in one tap and comes back to its page`() {
        rule.onNodeWithText("Ville et machines").performClick()
        waitFor { viewModel.visiblePage == "ville" }
        rule.onNodeWithContentDescription("Mode paramétrage").performClick()
        rule.onNodeWithText("Touchez une carte pour modifier ses mots-clés.").assertExists()
        capture("setup")

        val first = viewModel.library.value.run { cards.getValue(page("ville")!!.cardIds.first()) }
        val volume = first.volume
        // Même un appui prolongé, qui réglerait le volume hors paramétrage, ouvre les mots-clés.
        card(first.name.fr).performTouchInput { longClick(durationMillis = 800) }
        rule.waitForIdle()
        rule.onNodeWithText("Mots-clés · ${first.name.fr}").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        capture("setup-keywords")

        rule.runOnUiThread { viewModel.back() }
        waitFor { rule.onAllNodes(hasContentDescription(first.name.fr, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("ville", viewModel.visiblePage)
        assertTrue(viewModel.setupMode.value)
        card(first.name.fr).performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("Mots-clés · ${first.name.fr}").assertExists()
        rule.runOnUiThread { viewModel.back() }
        rule.waitForIdle()
        assertTrue(viewModel.mix.value.active.isEmpty())
        assertEquals(volume, viewModel.library.value.cards.getValue(first.id).volume)

        rule.onNodeWithText("Terminé").performClick()
        card(first.name.fr).performClick()
        waitFor { first.id in viewModel.mix.value.active }
        rule.runOnUiThread { viewModel.stopAll() }
        waitFor { viewModel.mix.value.active.isEmpty() }
    }

    /** Reconnaisseur factice : pas de micro ni de code natif sur la JVM. */
    private class ScriptedSpeech : SpeechEngine {
        var listener: SpeechListener? = null

        override fun start(modelDir: File, options: SpeechOptions, listener: SpeechListener): SpeechSession {
            this.listener = listener
            return SpeechSession { this.listener = null }
        }

        override fun unknownWords(modelDir: File, words: Collection<String>): Set<String> =
            words.filterTo(HashSet()) { it.equals("grignotin", ignoreCase = true) }
    }

    @Test
    fun `voice mode starts a card for a while and keywords are edited per page`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val speech = ScriptedSpeech()
        (app as SonoramaApp).container.speechEngine = { speech }

        rule.onNodeWithContentDescription("Mode vocal").performClick()
        waitFor { viewModel.voiceState.value.listening }
        speech.listener!!.onPartial(Heard.of("il entendit un oiseau"))
        waitFor { "s:sonorama.base/birds" in viewModel.mix.value.timed }
        rule.onNodeWithText("Dernier mot-clé entendu : oiseau").assertExists()
        capture("voice")

        // Un appui sur une carte lancée à la voix la garde en lecture.
        card("Oiseaux").performClick()
        waitFor { viewModel.mix.value.timed.isEmpty() }
        assertTrue("s:sonorama.base/birds" in viewModel.mix.value.active)
        capture("voice-kept")

        viewModel.openKeywords("nature")
        rule.waitForIdle()
        rule.onNodeWithText("Mots-clés · Nature et météo").assertExists()
        capture("keywords")

        viewModel.setCardKeywords("s:sonorama.base/birds", "fr", listOf("merle", "Grignotin"))
        waitFor { viewModel.library.value.cards.getValue("s:sonorama.base/birds").customKeywords }
        waitFor { rule.onAllNodes(hasText("Les mots grisés", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("keywords-edited")
        viewModel.resetCardKeywords("s:sonorama.base/birds")
        waitFor { !viewModel.library.value.cards.getValue("s:sonorama.base/birds").customKeywords }
        viewModel.back()
        viewModel.toggleVoice()
        waitFor { !viewModel.voiceState.value.enabled }
    }
}
