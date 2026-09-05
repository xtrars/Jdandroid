package com.jdandroid

import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsServer
import com.jdandroid.ui.NfsBrowserError
import com.jdandroid.ui.NfsDiscoveryRunner
import com.jdandroid.ui.NfsDiscoveryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server search dialog: search, server list, export list, back and close, all without network. */
class NfsDiscoveryRunnerTest {

    private val nas = NfsServer("192.168.1.10", "diskstation")
    private val other = NfsServer("192.168.1.3")

    private suspend fun NfsDiscoveryRunner.searched(): NfsDiscoveryState =
        state.first { it != null && !it.searching }!!

    private suspend fun NfsDiscoveryRunner.listed(): NfsDiscoveryState =
        state.first { it != null && !it.loadingExports }!!

    @Test
    fun findetServerUndListetFreigaben() = runBlocking {
        val runner = NfsDiscoveryRunner(
            this,
            discover = { onFound -> onFound(nas); onFound(other); onFound(nas.copy(name = null)) },
            exports = { host -> if (host == nas.host) listOf("/volume1/dl", "/volume1/video") else emptyList() }
        )
        runner.search()
        assertTrue(runner.state.value!!.searching)
        var s = runner.searched()
        assertEquals(listOf(other, nas), s.servers)
        assertNull(s.selected)

        runner.select(nas)
        assertTrue(runner.state.value!!.loadingExports)
        s = runner.listed()
        assertEquals(nas, s.selected)
        assertEquals(listOf("/volume1/dl", "/volume1/video"), s.exports)
        assertNull(s.error)

        runner.back()
        s = runner.state.value!!
        assertNull(s.selected)
        assertEquals(listOf(other, nas), s.servers)
        assertTrue(s.exports.isEmpty())

        runner.close()
        assertNull(runner.state.value)
    }

    @Test
    fun keineTrefferUndFehlerAlsEineZeile() = runBlocking {
        val runner = NfsDiscoveryRunner(
            this,
            discover = { },
            exports = { throw NfsFailure.Transient("connect timed out") }
        )
        runner.search()
        val s = runner.searched()
        assertTrue(s.servers.isEmpty())
        assertFalse(s.searching)

        runner.select(other)
        val listed = runner.listed()
        assertEquals(NfsBrowserError("connect timed out", unreachable = true), listed.error)
        assertTrue(listed.exports.isEmpty())
    }

    @Test
    fun getippterServerOeffnetDieZweiteEbeneUndZurueckStartetDieSuche() = runBlocking {
        var searches = 0
        val runner = NfsDiscoveryRunner(
            this,
            discover = { onFound -> searches++; onFound(nas) },
            exports = { throw NfsFailure.Permanent("program not available") }
        )
        runner.select(NfsServer("nas.local"))
        val s = runner.listed()
        assertEquals("nas.local", s.selected!!.host)
        assertEquals(NfsBrowserError("program not available", unreachable = false), s.error)
        assertEquals(0, searches)

        runner.back()
        val searched = runner.searched()
        assertEquals(1, searches)
        assertEquals(listOf(nas), searched.servers)
        assertNull(searched.selected)
    }

    @Test
    fun schliessenWaehrendDerSucheLaesstDenDialogZu() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runner = NfsDiscoveryRunner(this, discover = { gate.await() }, exports = { emptyList() })
        runner.search()
        yield()
        runner.close()
        gate.complete(Unit)
        yield()
        assertNull(runner.state.value)
    }

    @Test
    fun spaeteAntwortEinesAbgewaehltenServersWirdVerworfen() = runBlocking {
        val gate = CompletableDeferred<List<String>>()
        val runner = NfsDiscoveryRunner(
            this,
            discover = { },
            exports = { host -> if (host == nas.host) gate.await() else listOf("/b") }
        )
        runner.select(nas)
        yield()
        runner.select(other)
        val s = runner.listed()
        gate.complete(listOf("/a"))
        yield()
        assertEquals(other, s.selected)
        assertEquals(listOf("/b"), runner.state.value!!.exports)
    }
}
