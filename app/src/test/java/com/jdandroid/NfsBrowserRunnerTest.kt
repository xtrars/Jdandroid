package com.jdandroid

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsEntry
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsShares
import com.jdandroid.nfs.FakeNfsShare
import com.jdandroid.ui.NfsBrowserError
import com.jdandroid.ui.NfsBrowserRunner
import com.jdandroid.ui.NfsBrowserState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The folder browser lists relative to the export root and keeps its state outside the screen. */
class NfsBrowserRunnerTest {

    private val fake = FakeNfsShare()
    private val original = NfsShares.factory
    private val target = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/dl", subDir = "old")

    @Before
    fun setUp() {
        NfsShares.factory = fake.factory
        fake.mkdirs("film/2024")
        fake.mkdirs("serien")
        fake.files["readme.txt"] = ByteArray(2048)
        fake.files["film/a.mkv"] = ByteArray(5)
    }

    @After
    fun tearDown() {
        NfsShares.factory = original
    }

    private suspend fun NfsBrowserRunner.settled(): NfsBrowserState = state.first { it?.loading == false }!!

    @Test
    fun oeffnetAmExportUndNavigiertHineinUndZurueck() = runBlocking {
        val runner = NfsBrowserRunner(this)
        runner.open(target)
        assertTrue(runner.state.value!!.loading)

        var s = runner.settled()
        assertEquals("", s.path)
        assertNull(s.error)
        assertEquals(
            listOf(NfsEntry("film", true, 0), NfsEntry("serien", true, 0), NfsEntry("readme.txt", false, 2048)),
            s.entries
        )
        assertEquals("", fake.opened.single().subDir)

        runner.enter("film")
        s = runner.settled()
        assertEquals("film", s.path)
        assertEquals(listOf(NfsEntry("2024", true, 0), NfsEntry("a.mkv", false, 5)), s.entries)

        runner.enter("2024")
        assertEquals("film/2024", runner.settled().path)
        runner.up()
        assertEquals("film", runner.settled().path)
        runner.up()
        s = runner.settled()
        assertEquals("", s.path)
        runner.up()
        assertEquals(s, runner.state.value)

        runner.close()
        assertNull(runner.state.value)
    }

    @Test
    fun meldetFehlerAlsEineZeile() = runBlocking {
        val runner = NfsBrowserRunner(this)
        fake.failure = NfsFailure.Transient("timeout")
        runner.open(target)
        var s = runner.settled()
        assertEquals(NfsBrowserError("timeout", unreachable = true), s.error)
        assertTrue(s.entries.isEmpty())

        fake.failure = NfsFailure.Permanent("denied")
        runner.open(target)
        s = runner.settled()
        assertEquals(NfsBrowserError("denied", unreachable = false), s.error)
    }

    @Test
    fun neuerOrdnerErscheintInDerListe() = runBlocking {
        val runner = NfsBrowserRunner(this)
        runner.open(target)
        runner.settled()
        runner.enter("serien")
        runner.settled()

        runner.createFolder(" Neu ")
        val s = runner.settled()
        assertEquals("serien", s.path)
        assertEquals(listOf(NfsEntry("Neu", true, 0)), s.entries)
        assertTrue("serien/Neu" in fake.dirs)

        runner.createFolder("../x")
        assertEquals(s, runner.state.value)
        assertFalse("x" in fake.dirs)
    }

    @Test
    fun schliessenWaehrendDesLadensLaesstDenDialogZu() = runBlocking {
        val gate = CompletableDeferred<List<NfsEntry>>()
        val runner = NfsBrowserRunner(this, browse = { _, _ -> gate.await() })
        runner.open(target)
        yield()
        runner.close()
        gate.complete(emptyList())
        yield()
        assertNull(runner.state.value)
    }
}
