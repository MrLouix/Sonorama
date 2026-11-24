package io.github.mrlouix.sonorama.voice

import android.content.Context
import androidx.compose.runtime.Composable

/** Version de publication : pas de transcription ni de réglages de test. */
object VariantFeatures {
    fun transcripts(context: Context): TranscriptSink? = null

    @Composable
    fun VoiceTestSettings() = Unit
}
