package io.github.mrlouix.sonorama

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.mrlouix.sonorama.audio.PlaybackService
import io.github.mrlouix.sonorama.data.ThemeMode
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.SonoramaRoot
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.notificationsAsked() }

    override fun attachBaseContext(newBase: Context) {
        // Langue choisie dans les réglages ; vide = langue du système.
        val language = (newBase.applicationContext as SonoramaApp).container.settingsStore.current().language
        if (language.isEmpty()) {
            super.attachBaseContext(newBase)
        } else {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Les touches de volume règlent toujours le volume média, même sans son en cours.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val theme = viewModel.settings.collectAsStateWithLifecycle().value.theme
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Les icônes des barres système suivent le thème choisi dans l'appli, pas celui du système.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SonoramaRoot(viewModel, onLanguageChanged = ::recreate)
        }
        askForNotificationsOnce()
    }

    override fun onStart() {
        super.onStart()
        // Se connecter à la session démarre le service qui porte la notification média.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        // La pré-écoute n'a ni notification ni commande : elle ne doit pas survivre à la sortie de l'appli.
        viewModel.stopPreview()
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (viewModel.settings.value.notificationsAsked) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
