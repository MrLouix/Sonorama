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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.mrlouix.sonorama.audio.VolumeCurve
import kotlin.math.roundToInt

enum class CardStatus { INACTIVE, ACTIVE, PENDING_START, PENDING_STOP }

/** Durée d'appui avant d'entrer en réglage de volume. */
private const val LONG_PRESS_MS = 400L

/** Course complète du geste de volume. */
private val VOLUME_TRAVEL = 200.dp

private const val VOLUME_STEP = 10

/**
 * Une carte de la grille. Appui court : [onTap]. Appui long puis glissement vertical :
 * réglage du volume, [onVolumeChange] pendant le geste et [onVolumeCommit] au relâchement.
 */
@Composable
fun CardTile(
    name: String,
    icon: ImageVector,
    status: CardStatus,
    volume: Int,
    volumePlanned: Boolean,
    available: Boolean,
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
    val toggleLabel = stringResource(if (lit) R.string.action_deactivate else R.string.action_activate)
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
            .pointerInput(available) {
                val travel = VOLUME_TRAVEL.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
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
                stateDescription = statusText
                role = Role.Switch
                onClick(label = toggleLabel) {
                    currentTap()
                    true
                }
                if (available) {
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
            )
        }

        // L'état se lit aussi par une icône, jamais par la couleur seule.
        val badge = when {
            !available -> Icons.Rounded.CloudOff
            status == CardStatus.ACTIVE -> Icons.Rounded.GraphicEq
            status == CardStatus.PENDING_START -> Icons.Rounded.AddCircle
            status == CardStatus.PENDING_STOP -> Icons.Rounded.RemoveCircle
            else -> null
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

