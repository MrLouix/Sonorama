package io.github.mrlouix.sonorama

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.Screen
import io.github.mrlouix.sonorama.ui.SonoramaRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

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
        viewModel = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
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
        assertEquals(listOf("nature", "ville", "technologique", "meteo", "transports"), library.pages.map { it.id })
        assertEquals(24, library.cards.size)
        rule.onNodeWithText("Météo").assertExists()
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
        waitFor { viewModel.library.value.pages.size == 6 }
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
}
