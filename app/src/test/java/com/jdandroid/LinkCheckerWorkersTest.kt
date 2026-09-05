package com.jdandroid

import com.jdandroid.data.LinkChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Worker pool of the link grabber check: one bulk mark, three workers, every id once. */
class LinkCheckerWorkersTest {

    @Test
    fun markiertGesammeltUndPrueftMitDreiArbeitern() = runBlocking {
        val ids = (1L..600L).toList()
        val marked = mutableListOf<List<Long>>()
        val checked = mutableListOf<Long>()
        var active = 0
        var maxActive = 0
        LinkChecker.runChecks(ids, { marked += it }) { id ->
            active++
            maxActive = maxOf(maxActive, active)
            delay(1)
            checked += id
            active--
        }
        assertEquals(listOf(500, 100), marked.map { it.size })
        assertEquals(ids, marked.flatten())
        assertEquals(ids, checked.sorted())
        assertEquals(3, maxActive)
    }

    @Test
    fun leereListeStartetNichts() = runBlocking {
        var marks = 0
        LinkChecker.runChecks(emptyList(), { marks++ }) { }
        assertEquals(0, marks)
    }
}
