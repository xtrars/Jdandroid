package com.jdandroid

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsProbe
import com.jdandroid.ui.NfsProbeOutcome
import com.jdandroid.ui.NfsProbeRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connection check keeps its state outside the screen: one run at a time, result stays. */
class NfsProbeRunnerTest {

    private val target = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/dl")

    @Test
    fun laeuftEinmalUndBehaeltDasErgebnis() = runBlocking {
        val gate = CompletableDeferred<NfsProbe>()
        var calls = 0
        val runner = NfsProbeRunner(this) { calls++; gate.await() }

        runner.start(target)
        yield()
        assertTrue(runner.probing.value)
        assertNull(runner.outcome.value)

        runner.start(target)
        yield()
        assertEquals(1, calls)

        gate.complete(NfsProbe(listOf("a", "b"), freeBytes = 10, totalBytes = 20))
        yield()
        assertFalse(runner.probing.value)
        assertEquals(NfsProbeOutcome.Ok(2, 10, 20), runner.outcome.value)
    }

    @Test
    fun neuerStartLoeschtAltesErgebnisUndMeldetFehler() = runBlocking {
        var fail = true
        val gate = CompletableDeferred<NfsProbe>()
        val runner = NfsProbeRunner(this) { if (fail) throw NfsFailure.Transient("timeout") else gate.await() }
        runner.start(target)
        yield()
        assertEquals(NfsProbeOutcome.Unreachable("timeout"), runner.outcome.value)

        fail = false
        runner.start(target)
        assertNull(runner.outcome.value)
        assertTrue(runner.probing.value)
        gate.complete(NfsProbe(emptyList(), 0, 0))
        yield()
        assertEquals(NfsProbeOutcome.Ok(0, 0, 0), runner.outcome.value)
    }
}
