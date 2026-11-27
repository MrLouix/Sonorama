package io.github.mrlouix.sonorama.ui.tour

import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Page intégrée où se déroule la visite. */
const val TOUR_PAGE = "nature"

/** Éléments de l'écran principal que la visite met en lumière. */
enum class TourTarget { CARD, PLAY, NOW_PLAYING, PAGES, MODE, MIC, SETUP }

/**
 * Étapes de la visite guidée, dans l'ordre. Une étape [interactive] laisse toucher l'élément montré
 * et avance quand le geste est fait ; « Suivant » reste toujours possible.
 */
enum class TourStep(val target: TourTarget?, @StringRes val text: Int, val interactive: Boolean = false) {
    WELCOME(null, R.string.tour_welcome),
    CARD(TourTarget.CARD, R.string.tour_card, interactive = true),
    VOLUME(TourTarget.CARD, R.string.tour_volume, interactive = true),
    PLAY(TourTarget.PLAY, R.string.tour_play),
    NOW_PLAYING(TourTarget.NOW_PLAYING, R.string.tour_now_playing),
    PAGES(TourTarget.PAGES, R.string.tour_pages),
    PLAN(TourTarget.MODE, R.string.tour_plan),
    VOICE(TourTarget.MIC, R.string.tour_voice),
    KEYWORDS(TourTarget.SETUP, R.string.tour_keywords),
}

/** Visite en cours : l'étape affichée et la carte qui sert d'exemple. */
data class TourState(val step: TourStep, val cardId: String) {
    val isLast: Boolean get() = step == TourStep.entries.last()
}

/** Position à l'écran, dans la fenêtre, des éléments que la visite peut montrer. */
class TourTargets {
    internal val bounds = mutableStateMapOf<TourTarget, Rect>()
}

val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/** Déclare cet élément comme [target] de la visite ; sans effet hors de l'écran principal. */
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed Modifier
    DisposableEffect(targets, target) { onDispose { targets.bounds.remove(target) } }
    Modifier.onGloballyPositioned { targets.bounds[target] = it.boundsInWindow() }
}

/** Vrai quand TalkBack (ou un autre lecteur d'écran à exploration tactile) est actif. */
@Composable
fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Contour de l'élément montré : l'ambre d'accent du thème sombre, dans les deux thèmes. Il ressort sur le voile,
 * sur le vert de l'appli et sur un fond clair, sans être criard.
 */
private val RING_COLOR = Color(0xFFF0BE6D)

/** Délai avant de montrer le geste du volume à qui ne l'a pas encore fait. */
private const val GESTURE_HINT_MS = 8_000L

/**
 * Voile de la visite : assombrit l'écran sauf l'élément montré, bloque les appuis ailleurs
 * (et sur l'élément lui-même hors des étapes interactives) et affiche la bulle d'explication.
 */
@Composable
fun TourOverlay(
    tour: TourState,
    targets: TourTargets,
    touchExploration: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val padding = with(density) { 6.dp.toPx() }
    val hole = tour.step.target
        ?.let { targets.bounds[it] }
        ?.translate(-origin)
        ?.inflate(padding)
    val radius = with(density) { 20.dp.toPx() }
    val ring = with(density) { 3.dp.toPx() }
    val glow = with(density) { 10.dp.toPx() }
    val scrim = Color.Black.copy(alpha = 0.7f)
    var showHint by remember(tour.step) { mutableStateOf(false) }
    LaunchedEffect(tour.step, touchExploration) {
        if (tour.step == TourStep.VOLUME && !touchExploration) {
            delay(GESTURE_HINT_MS)
            showHint = true
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(scrim)
            if (hole != null) {
                drawRoundRect(Color.Black, hole.topLeft, hole.size, CornerRadius(radius), blendMode = BlendMode.Clear)
                // Halo diffus puis trait net, posés juste autour de l'ouverture pour ne rien masquer de l'élément.
                val halo = hole.inflate(glow / 2)
                drawRoundRect(RING_COLOR.copy(alpha = 0.3f), halo.topLeft, halo.size, CornerRadius(radius + glow / 2), style = Stroke(glow))
                val edge = hole.inflate(ring / 2)
                drawRoundRect(RING_COLOR, edge.topLeft, edge.size, CornerRadius(radius + ring / 2), style = Stroke(ring))
            }
        }
        TouchBlockers(hole.takeIf { tour.step.interactive })
        if (showHint && hole != null) GestureHint(hole)
        TourBubble(tour, hole, touchExploration, onNext, onSkip)
    }
}

/** Bloque les appuis partout, sauf dans [opening] : le reste de l'écran ne réagit pas pendant la visite. */
@Composable
private fun TouchBlockers(opening: Rect?) {
    val block = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
    if (opening == null) {
        Box(Modifier.fillMaxSize().then(block))
        return
    }
    // Quatre bandes autour de l'ouverture : les appuis qui tombent dedans atteignent l'élément montré.
    Layout(
        content = { repeat(4) { Box(block) } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val left = opening.left.roundToInt().coerceIn(0, width)
        val right = opening.right.roundToInt().coerceIn(left, width)
        val top = opening.top.roundToInt().coerceIn(0, height)
        val bottom = opening.bottom.roundToInt().coerceIn(top, height)
        val bands = listOf(
            IntOffset(0, 0) to Constraints.fixed(width, top),
            IntOffset(0, bottom) to Constraints.fixed(width, height - bottom),
            IntOffset(0, top) to Constraints.fixed(left, bottom - top),
            IntOffset(right, top) to Constraints.fixed(width - right, bottom - top),
        )
        val placeables = measurables.zip(bands).map { (measurable, band) -> measurable.measure(band.second) to band.first }
        layout(width, height) { placeables.forEach { (placeable, at) -> placeable.place(at) } }
    }
}

/** Un doigt qui appuie puis glisse sur la carte : le geste du volume. */
@Composable
private fun GestureHint(hole: Rect) {
    val density = LocalDensity.current
    val travel = with(density) { 28.dp.toPx() }
    val size = with(density) { 48.dp.toPx() }
    val transition = rememberInfiniteTransition(label = "hint")
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "lift",
    )
    Box(
        Modifier
            .offset {
                IntOffset(
                    (hole.center.x - size / 2).roundToInt(),
                    (hole.center.y - size / 2 + travel - lift * travel * 2).roundToInt(),
                )
            }
            .size(48.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    ) {
        Icon(
            Icons.Rounded.TouchApp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(10.dp).fillMaxSize(),
        )
    }
}

/** Bulle placée sous l'élément montré, ou au-dessus faute de place ; au centre sans élément. */
@Composable
private fun TourBubble(
    tour: TourState,
    hole: Rect?,
    touchExploration: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val number = tour.step.ordinal + 1
    val total = TourStep.entries.size
    val title = stringResource(R.string.tour_title)
    val counter = stringResource(R.string.tour_counter, number, total)
    val counterSpoken = stringResource(R.string.tour_counter_spoken, number, total)
    val text = stringResource(
        if (tour.step == TourStep.VOLUME && touchExploration) R.string.tour_volume_talkback else tour.step.text,
    )
    Layout(
        content = {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.semantics { paneTitle = title },
            ) {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(
                        counter,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { contentDescription = counterSpoken },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (tour.isLast) Arrangement.End else Arrangement.SpaceBetween,
                    ) {
                        if (!tour.isLast) {
                            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.tour_skip))
                            }
                        }
                        Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (tour.isLast) R.string.tour_finish else R.string.tour_next))
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 14.dp.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bubble = measurables.first().measure(
            Constraints(maxWidth = minOf(width - 2 * margin, 420.dp.roundToPx()), maxHeight = height - 2 * margin),
        )
        layout(width, height) {
            if (hole == null) {
                bubble.place((width - bubble.width) / 2, (height - bubble.height) / 2)
                return@layout
            }
            val x = (hole.center.x - bubble.width / 2f).roundToInt().coerceIn(margin, width - margin - bubble.width)
            val below = hole.bottom.roundToInt() + gap
            val y = if (below + bubble.height <= height - margin) {
                below
            } else {
                (hole.top.roundToInt() - gap - bubble.height).coerceAtLeast(margin)
            }
            bubble.place(x, y)
        }
    }
}
