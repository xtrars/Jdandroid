package com.jdandroid

import com.jdandroid.ui.Starfield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backdrop is deterministic and every star stays inside the canvas at any time. */
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
            assertTrue(star.x in 0f..1f)
            assertTrue(star.radius in 0.6f..2.2f)
            for (time in listOf(0f, 1f, 59.9f, 120f, 1234.5f)) {
                val y = field.yAt(star, time)
                assertTrue("y=$y", y >= 0f && y < 1f)
                val alpha = field.alphaAt(star, time)
                assertTrue("alpha=$alpha", alpha in 0.35f..1.0001f)
            }
        }
    }

    @Test
    fun sterneDriftenNachUnten() {
        val field = Starfield(count = 5)
        val star = field.stars.first()
        val later = field.yAt(star, 1f)
        assertTrue(later > field.yAt(star, 0f) || later < 0.1f)
    }
}
