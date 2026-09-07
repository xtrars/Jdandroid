package com.jdandroid.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** True while the gamer scheme is active; components switch to their neon variants. */
val LocalGamer = compositionLocalOf { false }

/** Neon accents of the gamer scheme, in the order the gradients run. */
internal object Neon {
    val cyan = Color(0xFF00E5FF)
    val magenta = Color(0xFFFF4FD8)
    val lime = Color(0xFF8CFF5A)
    val gradient = listOf(cyan, magenta, lime)
}

/** One star of the backdrop; coordinates are fractions of the canvas. */
internal data class Star(val x: Float, val y: Float, val radius: Float, val speed: Float, val phase: Float)

/**
 * Deterministic star field: [count] stars drifting downwards at their own
 * speed, twinkling with their own phase. Positions are pure functions of the
 * animation time so the canvas redraws without keeping state.
 */
internal class Starfield(count: Int = 140, seed: Int = 7) {
    val stars: List<Star> = Random(seed).let { r ->
        List(count) {
            Star(
                x = r.nextFloat(),
                y = r.nextFloat(),
                radius = 0.6f + r.nextFloat() * 1.6f,
                speed = 0.02f + r.nextFloat() * 0.08f,
                phase = r.nextFloat()
            )
        }
    }

    /** Vertical position after [time] seconds, wrapped into 0..1. */
    fun yAt(star: Star, time: Float): Float = ((star.y + star.speed * time) % 1f + 1f) % 1f

    /** Twinkle alpha in 0.35..1. */
    fun alphaAt(star: Star, time: Float): Float =
        0.675f + 0.325f * sin(2f * PI.toFloat() * (time * 0.4f + star.phase))
}

/** Seconds of one drift loop; long enough that the wrap is invisible. */
private const val LOOP_SECONDS = 120f

/**
 * Space backdrop behind the whole app: two drifting nebula glows, a faint
 * horizon grid and the twinkling star field. Animates only while the
 * activity is started, so a backgrounded app costs nothing.
 */
@Composable
fun GamerBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val field = remember { Starfield() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val time = if (lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        val transition = rememberInfiniteTransition(label = "space")
        val t by transition.animateFloat(
            initialValue = 0f, targetValue = LOOP_SECONDS,
            animationSpec = infiniteRepeatable(tween((LOOP_SECONDS * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
            label = "time"
        )
        t
    } else 0f
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(background)
            drawNebula(time)
            drawHorizonGrid()
            for (star in field.stars) {
                drawCircle(
                    Color.White.copy(alpha = field.alphaAt(star, time)),
                    radius = star.radius.dp.toPx(),
                    center = Offset(star.x * size.width, field.yAt(star, time) * size.height)
                )
            }
        }
        content()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNebula(time: Float) {
    val drift = sin(2f * PI.toFloat() * time / LOOP_SECONDS)
    val radius = size.maxDimension * 0.55f
    drawCircle(
        Brush.radialGradient(listOf(Neon.magenta.copy(alpha = 0.22f), Color.Transparent), radius = radius,
            center = Offset(size.width * (0.15f + 0.1f * drift), size.height * 0.2f)),
        radius = radius,
        center = Offset(size.width * (0.15f + 0.1f * drift), size.height * 0.2f)
    )
    drawCircle(
        Brush.radialGradient(listOf(Neon.cyan.copy(alpha = 0.18f), Color.Transparent), radius = radius,
            center = Offset(size.width * (0.9f - 0.1f * drift), size.height * 0.75f)),
        radius = radius,
        center = Offset(size.width * (0.9f - 0.1f * drift), size.height * 0.75f)
    )
}

/** Perspective grid in the lower third: lines converge towards a vanishing point above the screen. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHorizonGrid() {
    val color = Neon.cyan.copy(alpha = 0.07f)
    val top = size.height * 0.68f
    val stroke = 1.dp.toPx()
    var y = top
    var gap = 6.dp.toPx()
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        y += gap
        gap *= 1.28f
    }
    val vanish = Offset(size.width / 2f, top - size.height * 0.6f)
    val columns = 12
    for (i in 0..columns) {
        val x = size.width * i / columns
        val bottom = Offset(vanish.x + (x - vanish.x) * 2.2f, size.height)
        drawLine(color, Offset(x, top), bottom, stroke)
    }
}

/** Border alpha of a neon frame: steady, or breathing while [pulse] is set (transferring entry). */
@Composable
fun neonAlpha(pulse: Boolean): Float {
    if (!pulse) return 0.75f
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    return alpha
}

/** Neon frame for cards: gradient border at [alpha], coloured glow and optional HUD corner marks. */
fun Modifier.neonFrame(shape: Shape, alpha: Float, corners: Boolean = false, glow: Dp = 12.dp): Modifier {
    val brush = Brush.linearGradient(Neon.gradient.map { it.copy(alpha = alpha) })
    val corner = Neon.cyan.copy(alpha = alpha)
    return this
        .shadow(glow, shape, ambientColor = Neon.cyan, spotColor = Neon.magenta)
        .border(1.dp, brush, shape)
        .drawBehind {
            if (!corners) return@drawBehind
            val len = 10.dp.toPx()
            val inset = 4.dp.toPx()
            val w = 2.dp.toPx()
            val cap = StrokeCap.Round
            fun mark(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(corner, Offset(x, y), Offset(x + dx * len, y), w, cap)
                drawLine(corner, Offset(x, y), Offset(x, y + dy * len), w, cap)
            }
            mark(inset, inset, 1f, 1f)
            mark(size.width - inset, inset, -1f, 1f)
            mark(inset, size.height - inset, 1f, -1f)
            mark(size.width - inset, size.height - inset, -1f, -1f)
        }
}

/** Thin gradient line with glow, used above the navigation bar. */
@Composable
fun NeonLine(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val brush = Brush.horizontalGradient(Neon.gradient)
        drawRect(brush, topLeft = Offset(0f, size.height / 2 - 0.5.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        drawRect(Brush.horizontalGradient(Neon.gradient.map { it.copy(alpha = 0.35f) }))
    }
}

/**
 * Gradient progress bar with a travelling highlight and a glow underneath;
 * null is the indeterminate sweep.
 */
@Composable
fun NeonProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val h = size.height
        val stroke = Stroke(h, cap = StrokeCap.Round)
        drawLine(track, Offset(h / 2, h / 2), Offset(size.width - h / 2, h / 2), h, StrokeCap.Round)
        val (start, end) = if (fraction == null) {
            val w = size.width * 0.3f
            val x = (size.width + w) * sweep - w
            x.coerceAtLeast(0f) to (x + w).coerceAtMost(size.width)
        } else 0f to size.width * fraction.coerceIn(0f, 1f)
        if (end - start < h) return@Canvas
        val brush = Brush.horizontalGradient(Neon.gradient, startX = 0f, endX = size.width)
        drawLine(brush, Offset(start + h / 2, h / 2), Offset(end - h / 2, h / 2), h * 1.9f, StrokeCap.Round, alpha = 0.25f)
        drawLine(brush, Offset(start + h / 2, h / 2), Offset(end - h / 2, h / 2), stroke.width, StrokeCap.Round)
        if (fraction != null) {
            val x = start + (end - start) * sweep
            drawLine(Color.White.copy(alpha = 0.7f), Offset(x, 0f), Offset(x + 3.dp.toPx(), h), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Monospace HUD typography of the gamer scheme; body text keeps the readable default. */
internal fun gamerTypography(base: Typography): Typography = base.copy(
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
    labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp),
    labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
)

/** Scaffold ground: transparent over the space backdrop, the scheme background otherwise. */
@Composable
fun jdScaffoldColor(): Color = if (LocalGamer.current) Color.Transparent else MaterialTheme.colorScheme.background
