package com.jdandroid.ui

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsEntry
import com.jdandroid.engine.nfs.NfsExports
import com.jdandroid.engine.nfs.NfsServer
import com.jdandroid.engine.nfs.NfsShares
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

internal enum class NfsWizardStep { SERVER, EXPORT, FOLDER }

/** What the NAS wizard dialog shows; [step] follows which level is open. */
internal data class NfsWizardState(
    val server: NfsServer?,
    val export: String?,
    val discovery: NfsDiscoveryState?,
    val browser: NfsBrowserState?
) {
    val step: NfsWizardStep = when {
        browser != null -> NfsWizardStep.FOLDER
        discovery?.selected != null -> NfsWizardStep.EXPORT
        else -> NfsWizardStep.SERVER
    }
}

/** Outcome of the wizard; [export] and [subDir] are null when only the server was taken over. */
internal data class NfsWizardResult(val server: String, val export: String?, val subDir: String?)

/**
 * One dialog for server search, export list and folder browser in a row.
 * Wraps the two level runners and adds the chosen server and export, so a
 * step back lands on the previous level even when the wizard was opened
 * further in from typed settings. Null state means the dialog is closed.
 */
internal class NfsWizardRunner(
    scope: CoroutineScope,
    discover: suspend ((NfsServer) -> Unit) -> Unit,
    exports: suspend (String) -> List<String> = NfsExports::exports,
    browse: suspend (NfsSettings, String) -> List<NfsEntry> = NfsShares::browse,
    mkdir: suspend (NfsSettings, String) -> Unit = NfsShares::mkdir
) {
    private data class Selection(val settings: NfsSettings, val server: NfsServer?, val export: String?)

    private val discovery = NfsDiscoveryRunner(scope, discover, exports)
    private val browser = NfsBrowserRunner(scope, browse, mkdir)
    private val selection = MutableStateFlow<Selection?>(null)

    val state: Flow<NfsWizardState?> = combine(selection, discovery.state, browser.state) { chosen, found, folders ->
        chosen?.let { NfsWizardState(it.server, it.export, found, folders) }
    }

    /**
     * Opens at the deepest level the typed settings allow: folders with server
     * and export, exports with a server only, otherwise the server search.
     */
    fun start(settings: NfsSettings) {
        val server = settings.server.trim().takeIf { it.isNotEmpty() }?.let { NfsServer(it) }
        val export = settings.export.trim().takeIf { it.isNotEmpty() }
        selection.value = Selection(settings, server, export)
        when {
            server != null && export != null -> browser.open(settings.copy(server = server.host, export = export))
            server != null -> discovery.select(server)
            else -> discovery.search()
        }
    }

    fun search() = discovery.search()

    fun selectServer(server: NfsServer) {
        selection.update { it?.copy(server = server, export = null) }
        browser.close()
        discovery.select(server)
    }

    fun selectExport(path: String) {
        val chosen = selection.value ?: return
        val server = chosen.server ?: return
        selection.value = chosen.copy(export = path)
        browser.open(chosen.settings.copy(server = server.host, export = path))
    }

    fun enter(name: String) = browser.enter(name)

    fun up() = browser.up()

    fun createFolder(name: String) = browser.createFolder(name)

    /** One level back: folders to the exports of the server, exports to the server list. */
    fun back() {
        val chosen = selection.value ?: return
        if (browser.state.value != null) {
            browser.close()
            // Opened at the folder level from typed settings: the export list is not loaded yet.
            if (discovery.state.value == null) chosen.server?.let { discovery.select(it) }
        } else {
            discovery.back()
        }
    }

    /** Server only, or server, export and the folder [subDir] relative to the export. */
    fun result(subDir: String?): NfsWizardResult? {
        val chosen = selection.value ?: return null
        val server = chosen.server?.host ?: return null
        return if (subDir == null) NfsWizardResult(server, null, null)
        else NfsWizardResult(server, chosen.export ?: return null, subDir)
    }

    fun close() {
        discovery.close()
        browser.close()
        selection.value = null
    }
}
