package com.jdandroid

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsEntry
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsServer
import com.jdandroid.ui.NfsWizardResult
import com.jdandroid.ui.NfsWizardRunner
import com.jdandroid.ui.NfsWizardState
import com.jdandroid.ui.NfsWizardStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The NAS wizard walks server, export and folder in one dialog and back again. */
class NfsWizardRunnerTest {

    private val nas = NfsServer("192.168.1.10", "diskstation")
    private val folders = mapOf(
        "" to listOf(NfsEntry("film", true, 0), NfsEntry("readme.txt", false, 12)),
        "film" to listOf(NfsEntry("2024", true, 0))
    )
    private val listed = mutableListOf<String>()

    private fun runner(scope: kotlinx.coroutines.CoroutineScope) = NfsWizardRunner(
        scope,
        discover = { onFound -> onFound(nas) },
        exports = { host -> if (host == nas.host) listOf("/volume1/dl") else throw NfsFailure.Transient("no route to $host") },
        browse = { settings, path ->
            listed += "${settings.server}:${settings.export}:$path"
            folders[path] ?: throw NfsFailure.Permanent("missing $path")
        },
        mkdir = { _, _ -> }
    )

    private suspend fun NfsWizardRunner.settled(): NfsWizardState = state.first {
        it != null && it.discovery?.searching != true && it.discovery?.loadingExports != true && it.browser?.loading != true
    }!!

    @Test
    fun ohneEinstellungenVonDerSucheBisZumOrdner() = runBlocking {
        val runner = runner(this)
        runner.start(NfsSettings())
        var s = runner.settled()
        assertEquals(NfsWizardStep.SERVER, s.step)
        assertEquals(listOf(nas), s.discovery!!.servers)

        runner.selectServer(nas)
        s = runner.settled()
        assertEquals(NfsWizardStep.EXPORT, s.step)
        assertEquals(nas, s.server)
        assertEquals(listOf("/volume1/dl"), s.discovery!!.exports)
        assertEquals(NfsWizardResult(nas.host, null, null), runner.result(null))

        runner.selectExport("/volume1/dl")
        s = runner.settled()
        assertEquals(NfsWizardStep.FOLDER, s.step)
        assertEquals("/volume1/dl", s.export)
        assertEquals(listOf("192.168.1.10:/volume1/dl:"), listed)

        runner.enter("film")
        s = runner.settled()
        assertEquals("film", s.browser!!.path)
        assertEquals(NfsWizardResult(nas.host, "/volume1/dl", "film"), runner.result(s.browser!!.path))

        runner.back()
        s = runner.settled()
        assertEquals(NfsWizardStep.EXPORT, s.step)
        assertEquals(listOf("/volume1/dl"), s.discovery!!.exports)
        assertNull(s.browser)

        runner.back()
        s = runner.settled()
        assertEquals(NfsWizardStep.SERVER, s.step)
        assertEquals(listOf(nas), s.discovery!!.servers)

        runner.close()
        assertNull(runner.state.first())
    }

    @Test
    fun getippteEinstellungenOeffnenBeimOrdnerUndZurueckLaedtDieFreigaben() = runBlocking {
        val runner = runner(this)
        runner.start(NfsSettings(server = " 192.168.1.10 ", export = "/volume1/dl"))
        var s = runner.settled()
        assertEquals(NfsWizardStep.FOLDER, s.step)
        assertNull(s.discovery)
        assertEquals(NfsWizardResult(nas.host, "/volume1/dl", ""), runner.result(""))

        runner.back()
        s = runner.settled()
        assertEquals(NfsWizardStep.EXPORT, s.step)
        assertEquals(listOf("/volume1/dl"), s.discovery!!.exports)

        runner.back()
        s = runner.settled()
        assertEquals(NfsWizardStep.SERVER, s.step)
        assertEquals(listOf(nas), s.discovery!!.servers)
        runner.close()
    }

    @Test
    fun nurServerGetipptStartetBeiDenFreigabenUndMeldetFehlerAlsEineZeile() = runBlocking {
        val runner = runner(this)
        runner.start(NfsSettings(server = "10.0.0.9"))
        val s = runner.settled()
        assertEquals(NfsWizardStep.EXPORT, s.step)
        assertEquals("no route to 10.0.0.9", s.discovery!!.error!!.message)
        assertTrue(s.discovery!!.error!!.unreachable)
        assertEquals(NfsWizardResult("10.0.0.9", null, null), runner.result(null))
        assertNull(runner.result("sub"))
        runner.close()
    }

    @Test
    fun andererServerVerwirftDenAltenExport() = runBlocking {
        val runner = runner(this)
        runner.start(NfsSettings(server = "10.0.0.9", export = "/old"))
        runner.settled()
        runner.selectServer(nas)
        val s = runner.settled()
        assertEquals(NfsWizardStep.EXPORT, s.step)
        assertNull(s.export)
        assertNull(s.browser)
        assertNull(runner.result(""))
        runner.close()
    }
}
