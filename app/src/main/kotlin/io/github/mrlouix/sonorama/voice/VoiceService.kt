package io.github.mrlouix.sonorama.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.mrlouix.sonorama.MainActivity
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.SonoramaApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui permet d'écouter écran éteint. Android n'autorise le micro en arrière-plan
 * que dans un service de type « micro » démarré pendant que l'appli est visible, avec une notification.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voice_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val voice = (application as SonoramaApp).container.voice
        scope.launch {
            voice.state.collect { state ->
                if (state.enabled) manager.notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = (application as SonoramaApp).container.voice
        if (intent?.action == ACTION_STOP) {
            voice.disable()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(voice.state.value), type)
        }
        // Sans permission ou hors premier plan, Android refuse : le mode vocal s'éteint au lieu d'écouter à moitié.
        if (started.isFailure) {
            voice.disable()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: VoiceState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(if (state.paused) R.string.voice_notification_paused else R.string.voice_notification_title))
        .setContentText(
            state.lastKeyword?.let { getString(R.string.voice_last_keyword, it) }
                ?: resources.getQuantityString(R.plurals.voice_keyword_count, state.keywordCount, state.keywordCount),
        )
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            getString(R.string.voice_stop),
            PendingIntent.getService(
                this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /** Démarre et arrête le service depuis le contrôleur. */
    class Launcher(private val context: Context) : VoiceServiceLauncher {
        override fun start() {
            ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
        }

        override fun stop() {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }

    private companion object {
        const val CHANNEL = "voice"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "io.github.mrlouix.sonorama.voice.STOP"
    }
}
