package com.jdandroid.engine.nfs

import com.jdandroid.data.NfsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entry point for the engine and the settings screen; [factory] is swapped in tests. */
object NfsShares {
    @Volatile
    var factory: NfsShareFactory = NfsClientShare.factory

    /** Connection check for the settings screen: mounts, lists the target folder and reads free space. */
    suspend fun probe(settings: NfsSettings): NfsProbe = withContext(Dispatchers.IO) {
        factory.open(settings).use { it.probe() }
    }

    /** Folder browser: lists [dir] relative to the export root, ignoring the configured sub directory. */
    suspend fun browse(settings: NfsSettings, dir: String): List<NfsEntry> = withContext(Dispatchers.IO) {
        factory.open(exportRoot(settings)).use { it.entries(NfsSettings.relativePath(dir)) }
    }

    /** Creates [dir] (relative to the export root) including missing parents. */
    suspend fun mkdir(settings: NfsSettings, dir: String) = withContext(Dispatchers.IO) {
        factory.open(exportRoot(settings)).use { it.mkdirs(NfsSettings.relativePath(dir)) }
    }

    private fun exportRoot(settings: NfsSettings) = settings.copy(subDir = "")
}
