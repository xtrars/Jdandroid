package com.jdandroid

import android.view.Surface
import com.jdandroid.ui.Starfield
import com.jdandroid.ui.Tilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backdrop is deterministic, stays inside the canvas and answers a tilt with depth-scaled parallax. */
class StarfieldTest {

    @Test
    fun gleicherSeedGleicheSterne() {
        assertEquals(Starfield(seed = 3).stars, Starfield(seed = 3).stars)
        assertEquals(140, Starfield().stars.size)
        assertTrue(Starfield(seed = 3).stars != Starfield(seed = 4).stars)
    }

    @Test
    fun positionenUndHelligkeitBleibenImBereich() {
        val field = Starfield(count = 50)
        for (star in field.stars) {
            assertTrue(star.radius in 0.6f..2.2f)
            for (time in listOf(0f, 1f, 59.9f, 120f, 1234.5f)) for (tilt in listOf(-1f, 0f, 0.7f, 1f)) {
                val x = field.xAt(star, tilt)
                val y = field.yAt(star, time, tilt)
                assertTrue("x=$x", x >= 0f && x < 1f)
                assertTrue("y=$y", y >= 0f && y < 1f)
                val alpha = field.alphaAt(star, time)
                assertTrue("alpha=$alpha", alpha in 0.35f..1.0001f)
            }
        }
    }

    @Test
    fun naheSterneVerschiebenSichStaerkerAlsFerne() {
        val field = Starfield(count = 200)
        val near = field.stars.maxBy { it.depth }
        val far = field.stars.minBy { it.depth }
        fun shift(star: com.jdandroid.ui.Star) = wrapDelta(field.xAt(star, 0.5f) - star.x)
        assertTrue(shift(near) < 0f)
        assertTrue(shift(near) < shift(far))
        assertTrue(shift(far) < 0f)
        // No tilt: no shift, and the drift is slow (well under a canvas height per minute)
        assertEquals(near.x, field.xAt(near, 0f), 1e-6f)
        assertTrue(wrapDelta(field.yAt(near, 30f) - near.y) in 0.15f..0.35f)
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

    private fun wrapDelta(d: Float): Float = when {
        d > 0.5f -> d - 1f
        d < -0.5f -> d + 1f
        else -> d
    }
}
