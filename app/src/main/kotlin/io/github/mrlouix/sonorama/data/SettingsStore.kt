package io.github.mrlouix.sonorama.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mrlouix.sonorama.audio.FadeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Mode vocal : reconnaissance de toute la phrase, ou limitée aux mots-clés de la page. */
enum class RecognitionMode { FULL, KEYWORDS_ONLY }

data class Settings(
    val fades: FadeSettings = FadeSettings(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Code de langue (`fr`, `en`) ou chaîne vide pour suivre le système. */
    val language: String = "",
    val lastMix: List<String> = emptyList(),
    val notificationsAsked: Boolean = false,
    /** Durée de jeu d'une carte déclenchée à la voix. */
    val voiceDurationSec: Int = DEFAULT_VOICE_SECONDS,
    /** Langue de l'histoire (`fr`, `en`) ; chaîne vide pour suivre la langue de l'appli. */
    val storyLanguage: String = "",
    val recognitionMode: RecognitionMode = RecognitionMode.FULL,
) {
    companion object {
        const val MIN_VOICE_SECONDS = 3
        const val MAX_VOICE_SECONDS = 30
        const val DEFAULT_VOICE_SECONDS = 5
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map(::read)

    /** Lecture bloquante, réservée au démarrage (langue de l'activité, état initial). */
    fun current(): Settings = runBlocking { settings.first() }

    private fun read(p: Preferences) = Settings(
        fades = FadeSettings(
            fadeInMs = (p[FADE_IN] ?: 500).coerceIn(0, MAX_FADE_MS),
            fadeOutMs = (p[FADE_OUT] ?: 500).coerceIn(0, MAX_FADE_MS),
            crossfadeMs = (p[CROSSFADE] ?: 2000).coerceIn(0, MAX_FADE_MS),
        ),
        theme = ThemeMode.entries.firstOrNull { it.name == p[THEME] } ?: ThemeMode.SYSTEM,
        language = p[LANGUAGE].orEmpty(),
        lastMix = p[LAST_MIX].orEmpty().split('\n').filter { it.isNotEmpty() },
        notificationsAsked = p[NOTIFICATIONS_ASKED] ?: false,
        voiceDurationSec = (p[VOICE_SECONDS] ?: Settings.DEFAULT_VOICE_SECONDS)
            .coerceIn(Settings.MIN_VOICE_SECONDS, Settings.MAX_VOICE_SECONDS),
        storyLanguage = p[STORY_LANGUAGE].orEmpty(),
        recognitionMode = RecognitionMode.entries.firstOrNull { it.name == p[RECOGNITION_MODE] } ?: RecognitionMode.FULL,
    )

    suspend fun setFades(fades: FadeSettings) = store.edit {
        it[FADE_IN] = fades.fadeInMs
        it[FADE_OUT] = fades.fadeOutMs
        it[CROSSFADE] = fades.crossfadeMs
    }

    suspend fun setTheme(theme: ThemeMode) = store.edit { it[THEME] = theme.name }

    suspend fun setLanguage(language: String) = store.edit { it[LANGUAGE] = language }

    suspend fun setLastMix(cardIds: List<String>) = store.edit { it[LAST_MIX] = cardIds.joinToString("\n") }

    suspend fun setNotificationsAsked() = store.edit { it[NOTIFICATIONS_ASKED] = true }

    suspend fun setVoiceDuration(seconds: Int) = store.edit {
        it[VOICE_SECONDS] = seconds.coerceIn(Settings.MIN_VOICE_SECONDS, Settings.MAX_VOICE_SECONDS)
    }

    suspend fun setStoryLanguage(language: String) = store.edit { it[STORY_LANGUAGE] = language }

    suspend fun setRecognitionMode(mode: RecognitionMode) = store.edit { it[RECOGNITION_MODE] = mode.name }

    companion object {
        const val MAX_FADE_MS = 5000
        private val FADE_IN = intPreferencesKey("fade_in_ms")
        private val FADE_OUT = intPreferencesKey("fade_out_ms")
        private val CROSSFADE = intPreferencesKey("crossfade_ms")
        private val THEME = stringPreferencesKey("theme")
        private val LANGUAGE = stringPreferencesKey("language")
        private val LAST_MIX = stringPreferencesKey("last_mix")
        private val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
        private val VOICE_SECONDS = intPreferencesKey("voice_seconds")
        private val STORY_LANGUAGE = stringPreferencesKey("story_language")
        private val RECOGNITION_MODE = stringPreferencesKey("recognition_mode")
    }
}
