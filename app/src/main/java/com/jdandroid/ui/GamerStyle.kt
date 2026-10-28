package com.jdandroid.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
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

/**
 * One star of the backdrop: [seed] picks its lateral position per lap,
 * [offset] (0..1) is where in its lap it stands at time 0, [phase] the
 * twinkle phase.
 */
internal data class Star(val seed: Int, val offset: Float, val phase: Float)

/**
 * Deterministic star field flown through: every star approaches on a lap of
 * [LAP_SECONDS] from depth 1 (far) to [Z_NEAR], projected away from the
 * vanishing point at the canvas centre, and restarts far away at a lateral
 * position hashed from its lap number, so the flight never repeats. Tilting
 * the device shifts near stars more than far ones. Everything is a pure
 * function of time and tilt, so the canvas keeps no state.
 */
internal class Starfield(count: Int = 140, seed: Int = 7) {
    val stars: List<Star> = Random(seed).let { r ->
        List(count) { Star(seed = r.nextInt(), offset = r.nextFloat(), phase = r.nextFloat()) }
    }

    private fun laps(star: Star, time: Double): Double = time / LAP_SECONDS + star.offset

    /** Depth after [time] seconds: a sawtooth from 1 (far) down towards [Z_NEAR]. */
    fun depthAt(star: Star, time: Double): Float = 1f - (1f - Z_NEAR) * fraction(laps(star, time))

    /** Number of the lap the star is on at [time]; it only ever counts up. */
    fun lap(star: Star, time: Double): Int = floor(laps(star, time)).toInt()

    /**
     * Lateral position for [lap], spread evenly over the unit disc but at
     * least [MIN_LATERAL] off axis; a star on the axis would never leave the
     * vanishing point.
     */
    fun lateral(star: Star, lap: Int): Offset {
        val h = mix(star.seed, lap)
        val inner = MIN_LATERAL * MIN_LATERAL
        val r = sqrt(inner + (1f - inner) * unit(h))
        val angle = 2f * PI.toFloat() * unit(mix(h, 1))
        return Offset(r * cos(angle), r * sin(angle))
    }

    /**
     * Offset from the canvas centre in fractions of the smaller canvas side:
     * the lateral position divided by depth, so a star speeds up as it nears,
     * plus a tilt shift of -1..1 that grows with nearness.
     */
    fun project(star: Star, time: Double, tilt: Offset = Offset.Zero): Offset {
        val z = depthAt(star, time)
        val parallax = PARALLAX * (PARALLAX_FAR_SHARE + (1f - z))
        return lateral(star, lap(star, time)) * (FOCAL / z) - tilt * parallax
    }

    /** Dot radius in dp, growing from [RADIUS_FAR_DP] to [RADIUS_NEAR_DP] with nearness. */
    fun radiusDp(z: Float): Float = RADIUS_FAR_DP + (RADIUS_NEAR_DP - RADIUS_FAR_DP) * (1f - z) / (1f - Z_NEAR)

    /** 0 at the far end rising to 1 within [FADE_DEPTH], so stars do not pop up in the centre. */
    fun fadeIn(z: Float): Float = ((1f - z) / FADE_DEPTH).coerceIn(0f, 1f)

    /** Twinkle in [TWINKLE_MIN]..1, scaled by the far-end fade. */
    fun alphaAt(star: Star, time: Double): Float {
        val wave = sin(2f * PI.toFloat() * fraction(time * TWINKLE_HZ + star.phase))
        val twinkle = (1f + TWINKLE_MIN) / 2f + (1f - TWINKLE_MIN) / 2f * wave
        return twinkle * fadeIn(depthAt(star, time))
    }

    companion object {
        /** Seconds of one approach from the far end to past the screen edge. */
        const val LAP_SECONDS = 30f

        /** Nearest depth; below it the star restarts at depth 1. */
        const val Z_NEAR = 0.06f

        /** Projection scale: lateral position / depth * FOCAL, in fractions of the smaller canvas side. */
        const val FOCAL = 0.3f

        /** Least lateral distance from the axis; with [FOCAL] / [Z_NEAR] it puts every star off screen before the wrap. */
        const val MIN_LATERAL = 0.3f

        /** Canvas fraction the nearest stars shift at full tilt, and the share of it far stars keep. */
        const val PARALLAX = 0.12f
        const val PARALLAX_FAR_SHARE = 0.2f

        const val RADIUS_FAR_DP = 0.6f
        const val RADIUS_NEAR_DP = 2.4f

        /** Depth range below 1 over which a new star fades in. */
        const val FADE_DEPTH = 0.15f

        const val TWINKLE_MIN = 0.35f
        const val TWINKLE_HZ = 0.4f
    }
}

/**
 * Fractional part of [x]. Time is wall-clock seconds since 1970, where a
 * Float only resolves steps of 128 s, so it is split off in Double.
 */
private fun fraction(x: Double): Float = (x - floor(x)).toFloat()

/** Scrambles ([seed], [salt]) into a well-mixed Int (murmur3 finaliser). */
private fun mix(seed: Int, salt: Int): Int {
    var h = seed + salt * GOLDEN
    h = (h xor (h ushr 16)) * MIX_1
    h = (h xor (h ushr 13)) * MIX_2
    return h xor (h ushr 16)
}

private const val GOLDEN = -0x61C88647
private const val MIX_1 = -0x7A143595
private const val MIX_2 = -0x3D4D51CB

/** Top 24 bits of a hash as a float in 0..1. */
private fun unit(h: Int): Float = (h ushr 8) / (1 shl 24).toFloat()

/** Depth below which a star trails a streak from its position [STREAK_SECONDS] earlier. */
private const val STREAK_DEPTH = 0.25f
private const val STREAK_SECONDS = 0.08f
private const val STREAK_ALPHA = 0.5f

/** Canvas fraction beyond the edge up to which stars are still drawn (streak and radius). */
private const val OFFSCREEN_MARGIN = 0.1f

/** Seconds of one swing of the nebula drift. */
private const val NEBULA_SWING_SECONDS = 120.0

/**
 * Device tilt from the gravity sensor as screen-relative -1..1 (x right, y
 * down), low-pass filtered, mapped through the display rotation. Listens only
 * while the activity is started; zero without a sensor.
 */
@Composable
fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val tilt = remember { mutableStateOf(Offset.Zero) }
    val listening = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(listening) {
        if (!listening) return@DisposableEffect onDispose {}
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose {}
        val rotation = { ContextCompat.getDisplayOrDefault(context).rotation }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val target = Tilt.fromGravity(event.values[0], event.values[1], rotation())
                tilt.value = tilt.value + (target - tilt.value) * 0.12f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    return tilt
}

/** Gravity vector to screen tilt; pure so the rotation mapping is testable. */
internal object Tilt {
    private const val G = 9.81f

    /**
     * [gx], [gy] are the device-frame gravity components; [rotation] is the
     * display rotation (Surface.ROTATION_*). Result x is positive when the
     * screen's right edge is lower, y when its bottom edge is lower.
     */
    fun fromGravity(gx: Float, gy: Float, rotation: Int): Offset {
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> gy to gx
            Surface.ROTATION_180 -> gx to -gy
            Surface.ROTATION_270 -> -gy to -gx
            else -> -gx to gy
        }
        return Offset((sx / G).coerceIn(-1f, 1f), (sy / G).coerceIn(-1f, 1f))
    }
}

/**
 * Space backdrop behind the whole app, drawn only while [enabled]. The
 * content sits at the same slot either way, so switching the scheme keeps
 * the screen state (chosen tab, scroll positions) instead of rebuilding it.
 */
@Composable
fun GamerBackdrop(enabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        if (enabled) SpaceCanvas()
        content()
    }
}

/**
 * Wall-clock seconds, read when a run starts and then advanced with the frame
 * clock while [running], so setting the clock cannot make the stars jump. The
 * star field is a function of it: the flight never repeats, not even across
 * device restarts, and goes on after a rotation or a return to the app.
 */
@Composable
private fun rememberFlightTime(running: Boolean): State<Double> {
    val time = remember { mutableDoubleStateOf(System.currentTimeMillis() / 1e3) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.currentTimeMillis() / 1e3
        time.doubleValue = start
        val first = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { frame -> time.doubleValue = start + (frame - first) / 1e9 }
    }
    return time
}

/**
 * Two drifting nebula glows, a faint horizon grid and the star field flying
 * past. Animates only while the activity is started, so a backgrounded app
 * costs nothing.
 */
@Composable
private fun SpaceCanvas() {
    val field = remember { Starfield() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val clock = rememberFlightTime(lifecycle.isAtLeast(Lifecycle.State.STARTED))
    val tilt by rememberTilt()
    val background = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        // Read in the draw phase only, so a frame redraws without recomposing.
        val time = clock.value
        drawRect(background)
        drawNebula(time, tilt)
        drawHorizonGrid()
        val unit = size.minDimension
        val centre = Offset(size.width / 2f, size.height / 2f)
        val limitX = centre.x / unit + OFFSCREEN_MARGIN
        val limitY = centre.y / unit + OFFSCREEN_MARGIN
        for (star in field.stars) {
            val p = field.project(star, time, tilt)
            if (abs(p.x) > limitX || abs(p.y) > limitY) continue
            val z = field.depthAt(star, time)
            val colour = Color.White.copy(alpha = field.alphaAt(star, time))
            val radius = field.radiusDp(z).dp.toPx()
            val at = centre + p * unit
            if (z < STREAK_DEPTH) {
                val from = centre + field.project(star, time - STREAK_SECONDS, tilt) * unit
                drawLine(colour.copy(alpha = colour.alpha * STREAK_ALPHA), from, at, radius * 2f, StrokeCap.Round)
            }
            drawCircle(colour, radius, at)
        }
    }
}

/** Nebulae sit far back: a slow drift plus a small share of the tilt parallax. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNebula(time: Double, tilt: Offset) {
    val drift = sin(2f * PI.toFloat() * fraction(time / NEBULA_SWING_SECONDS))
    val radius = size.maxDimension * 0.55f
    val shift = Offset(-tilt.x * Starfield.PARALLAX * 0.25f * size.width, -tilt.y * Starfield.PARALLAX * 0.25f * size.height)
    val magenta = Offset(size.width * (0.15f + 0.1f * drift), size.height * 0.2f) + shift
    val cyan = Offset(size.width * (0.9f - 0.1f * drift), size.height * 0.75f) + shift
    drawCircle(
        Brush.radialGradient(listOf(Neon.magenta.copy(alpha = 0.22f), Color.Transparent), radius = radius, center = magenta),
        radius = radius,
        center = magenta
    )
    drawCircle(
        Brush.radialGradient(listOf(Neon.cyan.copy(alpha = 0.18f), Color.Transparent), radius = radius, center = cyan),
        radius = radius,
        center = cyan
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
