package io.github.mrlouix.sonorama.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.audio.TimedPlay
import io.github.mrlouix.sonorama.audio.VolumeCurve
import io.github.mrlouix.sonorama.audio.monotonicMillis
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class CardStatus { INACTIVE, ACTIVE, PENDING_START, PENDING_STOP }

/** Rafraîchissement de l'anneau des cartes déclenchées à la voix. */
private const val RING_STEP_MS = 100L

/** Durée d'appui avant d'entrer en réglage de volume. */
private const val LONG_PRESS_MS = 400L

/** Course complète du geste de volume. */
private val VOLUME_TRAVEL = 200.dp

private const val VOLUME_STEP = 10

/**
 * Une carte de la grille. Appui court : [onTap]. Appui long puis glissement vertical :
 * réglage du volume, [onVolumeChange] pendant le geste et [onVolumeCommit] au relâchement.
 * En paramétrage ([setup]), il n'y a plus de geste de volume : tout appui, court ou long, est un [onTap].
 */
@Composable
fun CardTile(
    name: String,
    icon: ImageVector,
    status: CardStatus,
    volume: Int,
    volumePlanned: Boolean,
    available: Boolean,
    /** Carte lancée à la voix : un anneau montre le temps restant. */
    timed: TimedPlay?,
    /** Mode paramétrage : l'appui ouvre les mots-clés de la carte. */
    setup: Boolean,
    onTap: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onVolumeCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragVolume by remember { mutableStateOf<Int?>(null) }
    val currentVolume by rememberUpdatedState(volume)
    val currentTap by rememberUpdatedState(onTap)
    val currentChange by rememberUpdatedState(onVolumeChange)
    val currentCommit by rememberUpdatedState(onVolumeCommit)

    val colors = MaterialTheme.colorScheme
    val lit = status == CardStatus.ACTIVE || status == CardStatus.PENDING_STOP
    val pending = status == CardStatus.PENDING_START || status == CardStatus.PENDING_STOP
    val container = if (lit) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (lit) colors.onPrimaryContainer else colors.onSurface
    val outline = when {
        pending -> colors.tertiary
        lit -> colors.primary
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(18.dp)

    val statusText = stringResource(
        when {
            !available -> R.string.card_state_unavailable
            status == CardStatus.ACTIVE -> R.string.card_state_active
            status == CardStatus.PENDING_START -> R.string.card_state_pending_start
            status == CardStatus.PENDING_STOP -> R.string.card_state_pending_stop
            else -> R.string.card_state_inactive
        },
    )
    val volumeText = stringResource(R.string.card_volume, dragVolume ?: volume)
    val remaining by produceState(1f, timed) {
        if (timed == null) return@produceState
        while (true) {
            value = ((timed.endsAtMs - monotonicMillis()).toFloat() / timed.durationMs).coerceIn(0f, 1f)
            if (value <= 0f) break
            delay(RING_STEP_MS)
        }
    }
    val voiceText = if (timed != null) ", " + stringResource(R.string.card_state_voice) else ""
    val voiceCard = timed != null && status == CardStatus.ACTIVE && !setup
    // Sur une carte lancée à la voix, l'appui la garde en lecture ; un second appui l'arrête.
    val toggleLabel = stringResource(
        when {
            setup -> R.string.action_edit_keywords
            voiceCard -> R.string.action_keep_voice_card
            lit -> R.string.action_deactivate
            else -> R.string.action_activate
        },
    )
    val louder = stringResource(R.string.action_volume_up)
    val quieter = stringResource(R.string.action_volume_down)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 112.dp)
            .alpha(if (available) 1f else 0.45f)
            .clip(shape)
            .background(container)
            .drawWithContent {
                drawContent()
                if (outline != Color.Transparent) {
                    val width = 3.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = androidx.compose.ui.geometry.Offset(width / 2, width / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
                        cornerRadius = CornerRadius(18.dp.toPx() - width / 2),
                        style = Stroke(
                            width = width,
                            // Trait plein : état en cours. Pointillés : changement en attente.
                            pathEffect = if (pending) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null,
                        ),
                    )
                }
            }
            .pointerInput(available, setup) {
                val travel = VOLUME_TRAVEL.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (setup) {
                        if (waitForUpOrCancellation() != null) currentTap()
                        return@awaitEachGesture
                    }
                    var longPress = false
                    var released = false
                    try {
                        withTimeout(LONG_PRESS_MS) { released = waitForUpOrCancellation() != null }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        longPress = true
                    }
                    if (!longPress) {
                        if (released) currentTap()
                        return@awaitEachGesture
                    }
                    if (!available) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val start = currentVolume
                    var latest = start
                    dragVolume = start
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // Consommer le geste bloque le défilement de la grille et des pages.
                            change.consume()
                            if (!change.pressed) break
                            val delta = (down.position.y - change.position.y) / travel * 100f
                            val next = (start + delta).roundToInt().coerceIn(VolumeCurve.MIN_VOLUME, VolumeCurve.MAX_VOLUME)
                            if (next != latest) {
                                latest = next
                                dragVolume = next
                                currentChange(next)
                            }
                        }
                    } finally {
                        dragVolume = null
                        currentCommit(latest)
                    }
                }
            }
            .clearAndSetSemantics {
                contentDescription = "$name, $volumeText"
                stateDescription = statusText + voiceText
                role = if (setup) Role.Button else Role.Switch
                onClick(label = toggleLabel) {
                    currentTap()
                    true
                }
                if (available && !setup) {
                    customActions = listOf(
                        CustomAccessibilityAction(louder) {
                            currentCommit((currentVolume + VOLUME_STEP).coerceAtMost(VolumeCurve.MAX_VOLUME))
                            true
                        },
                        CustomAccessibilityAction(quieter) {
                            currentCommit((currentVolume - VOLUME_STEP).coerceAtLeast(VolumeCurve.MIN_VOLUME))
                            true
                        },
                    )
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Un mot plus large que la carte rétrécit légèrement au lieu d'être coupé en son milieu.
                autoSize = TextAutoSize.StepBased(
                    minFontSize = MaterialTheme.typography.labelLarge.fontSize * 0.7f,
                    maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                ),
            )
        }

        // L'état se lit aussi par une icône, jamais par la couleur seule.
        val badge = when {
            setup -> Icons.Rounded.Edit
            !available -> Icons.Rounded.CloudOff
            voiceCard -> null
            status == CardStatus.ACTIVE -> Icons.Rounded.GraphicEq
            status == CardStatus.PENDING_START -> Icons.Rounded.AddCircle
            status == CardStatus.PENDING_STOP -> Icons.Rounded.RemoveCircle
            else -> null
        }
        if (voiceCard) {
            // Anneau qui se vide pendant la durée de jeu d'une carte déclenchée à la voix. L'épingle rappelle
            // qu'un appui sur la carte la garde en lecture.
            val ringColor = content
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .drawWithContent {
                            val stroke = 2.5.dp.toPx()
                            val inset = stroke / 2
                            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                            drawArc(ringColor.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                            drawArc(ringColor, -90f, 360f * remaining, false, topLeft, arcSize, style = Stroke(stroke))
                            drawContent()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (badge != null) {
            Icon(
                badge,
                contentDescription = null,
                tint = if (pending) colors.tertiary else content,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
            )
        }

        if (status != CardStatus.INACTIVE || volumePlanned) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(content.copy(alpha = 0.2f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(volume / 100f)
                        .background(if (volumePlanned) colors.tertiary else content.copy(alpha = 0.75f)),
                )
            }
        }

        dragVolume?.let { shown ->
            Box(Modifier.matchParentSize().background(colors.inverseSurface.copy(alpha = 0.92f))) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(shown / 100f)
                        .background(colors.primary.copy(alpha = 0.55f)),
                )
                Text(
                    text = stringResource(R.string.percent, shown),
                    color = colors.inverseOnSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

