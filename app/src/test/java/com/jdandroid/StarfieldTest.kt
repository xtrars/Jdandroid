package com.jdandroid

import android.view.Surface
import androidx.compose.ui.geometry.Offset
import com.jdandroid.ui.Star
import com.jdandroid.ui.Starfield
import com.jdandroid.ui.Starfield.Companion.FADE_DEPTH
import com.jdandroid.ui.Starfield.Companion.LAPS_PER_LOOP
import com.jdandroid.ui.Starfield.Companion.LAP_SECONDS
import com.jdandroid.ui.Starfield.Companion.LOOP_SECONDS
import com.jdandroid.ui.Starfield.Companion.MIN_LATERAL
import com.jdandroid.ui.Starfield.Companion.RADIUS_FAR_DP
import com.jdandroid.ui.Starfield.Companion.RADIUS_NEAR_DP
import com.jdandroid.ui.Starfield.Companion.TWINKLE_MIN
import com.jdandroid.ui.Starfield.Companion.Z_NEAR
import com.jdandroid.ui.Tilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop is deterministic, every star approaches from the vanishing
 * point on a ~30 s lap, leaves the screen before it restarts, the loop closes
 * seamlessly and a tilt shifts near stars more than far ones.
 */
class StarfieldTest {

    private val field = Starfield(count = 50, seed = 3)
    private val tilt = Offset(0.5f, 0f)

    /** Half the long side of the tallest common phone, in units of the short side. */
    private val longestHalfExtent = 1.2f

    @Test
    fun gleicherSeedGleicheSterne() {
        assertEquals(Starfield(seed = 3).stars, Starfield(seed = 3).stars)
        assertEquals(140, Starfield().stars.size)
        assertTrue(Starfield(seed = 3).stars != Starfield(seed = 4).stars)
    }

    @Test
    fun tiefeGroesseUndHelligkeitBleibenImBereich() {
        for (star in field.stars) for (time in listOf(0f, 1f, 29.99f, 30f, 59.9f, 120f, 1234.5f)) {
            val z = field.depthAt(star, time)
            assertTrue("z=$z", z > Z_NEAR && z <= 1f)
            assertTrue(field.radiusDp(z) in RADIUS_FAR_DP..RADIUS_NEAR_DP)
            val alpha = field.alphaAt(star, time)
            assertTrue("alpha=$alpha", alpha in 0f..1.0001f)
            assertTrue(field.lap(star, time) in 0 until LAPS_PER_LOOP)
        }
        assertEquals(RADIUS_FAR_DP, field.radiusDp(1f), 1e-6f)
        assertEquals(RADIUS_NEAR_DP, field.radiusDp(Z_NEAR), 1e-6f)
    }

    @Test
    fun abstandZurMitteWaechstInnerhalbEinerRundeMonoton() {
        val samples = 300
        for (star in field.stars) {
            val start = lapStart(star)
            val lap = field.lap(star, start + 0.5f)
            var last = -1f
            for (k in 0 until samples) {
                val time = start + (k + 0.5f) * LAP_SECONDS / samples
                assertEquals(lap, field.lap(star, time))
                val distance = field.project(star, time).getDistance()
                assertTrue("star=$star k=$k", distance > last)
                last = distance
            }
        }
    }

    @Test
    fun ruhigFernSchnellNah() {
        for (star in field.stars) {
            val start = lapStart(star)
            val far = field.project(star, start + 0.1f).getDistance()
            val mid = field.project(star, start + LAP_SECONDS / 2f).getDistance()
            val near = field.project(star, start + LAP_SECONDS - 0.1f).getDistance()
            // The first half of a lap covers less ground than the last tenth
            val lastTenth = near - field.project(star, start + LAP_SECONDS * 0.9f).getDistance()
            assertTrue(mid - far < lastTenth)
            assertTrue("near=$near", near >= longestHalfExtent)
        }
    }

    @Test
    fun rundendauerEtwa30Sekunden() {
        assertTrue(LAP_SECONDS in 25f..35f)
        assertEquals(0f, LOOP_SECONDS % LAP_SECONDS, 1e-6f)
        val step = 0.05f
        for (star in field.stars.take(10)) {
            val wraps = mutableListOf<Float>()
            var time = 0f
            var z = field.depthAt(star, 0f)
            while (time < 2 * LOOP_SECONDS) {
                val next = field.depthAt(star, time + step)
                if (next > z) wraps += time
                z = next
                time += step
            }
            assertEquals((2 * LOOP_SECONDS / LAP_SECONDS).toInt(), wraps.size)
            wraps.zipWithNext { a, b -> assertEquals(LAP_SECONDS, b - a, 2 * step) }
        }
    }

    @Test
    fun schleifeIstNahtlos() {
        for (star in field.stars) for (time in listOf(0f, 1f, 17.3f, 59.9f, 119.95f)) {
            val later = time + LOOP_SECONDS
            assertEquals(field.lap(star, time), field.lap(star, later))
            assertEquals(field.depthAt(star, time), field.depthAt(star, later), 1e-4f)
            val a = field.project(star, time, tilt)
            val b = field.project(star, later, tilt)
            assertEquals(a.x, b.x, 1e-3f)
            assertEquals(a.y, b.y, 1e-3f)
            assertEquals(field.alphaAt(star, time), field.alphaAt(star, later), 1e-3f)
        }
    }

    @Test
    fun seitlichePositionNieImFluchtpunkt() {
        for (star in field.stars) for (lap in 0 until LAPS_PER_LOOP) {
            val distance = field.lateral(star, lap).getDistance()
            assertTrue("distance=$distance", distance >= MIN_LATERAL - 1e-6f && distance <= 1f + 1e-6f)
        }
        // A new lap, a new position
        val star = field.stars.first()
        assertTrue(field.lateral(star, 0) != field.lateral(star, 1))
    }

    @Test
    fun naheSterneVerschiebenSichStaerkerAlsFerne() {
        for (star in field.stars) {
            val start = lapStart(star)
            val far = shift(star, start + 0.5f)
            val near = shift(star, start + LAP_SECONDS - 0.5f)
            assertTrue(far.x < 0f)
            assertTrue(near.x < far.x)
            assertEquals(0f, far.y, 1e-6f)
            assertEquals(Offset.Zero, shift(star, start + 5f, Offset.Zero))
        }
    }

    @Test
    fun einblendenAmFernenEnde() {
        assertEquals(0f, field.fadeIn(1f), 1e-6f)
        assertEquals(0.5f, field.fadeIn(1f - FADE_DEPTH / 2f), 1e-6f)
        assertEquals(1f, field.fadeIn(1f - FADE_DEPTH), 1e-6f)
        assertEquals(1f, field.fadeIn(Z_NEAR), 1e-6f)
        for (star in field.stars) {
            val start = lapStart(star)
            assertTrue(field.alphaAt(star, start + 0.01f) < 0.01f)
            assertTrue(field.alphaAt(star, start + FADE_DEPTH * LAP_SECONDS + 1f) >= TWINKLE_MIN)
        }
    }

    @Test
    fun neigungFolgtDerBildschirmdrehung() {
        // Right edge down in portrait: gravity along -x of the device
        assertTrue(Tilt.fromGravity(-9.81f, 0f, Surface.ROTATION_0).x > 0.99f)
        // Bottom edge down in portrait: gravity along +y
        assertTrue(Tilt.fromGravity(0f, 9.81f, Surface.ROTATION_0).y > 0.99f)
        // Landscape (rotated counter-clockwise): the device's +y points to the screen's right
        assertTrue(Tilt.fromGravity(0f, 9.81f, Surface.ROTATION_90).x > 0.99f)
        assertTrue(Tilt.fromGravity(-9.81f, 0f, Surface.ROTATION_270).y > 0.99f)
        // Clamped to -1..1
        assertEquals(-1f, Tilt.fromGravity(30f, 0f, Surface.ROTATION_0).x, 1e-6f)
    }

    /** First time at or after 0 at which the star is at depth 1. */
    private fun lapStart(star: Star): Float = ((1f - star.offset) % 1f) * LAP_SECONDS

    private fun shift(star: Star, time: Float, tilt: Offset = this.tilt): Offset =
        field.project(star, time, tilt) - field.project(star, time)
}
