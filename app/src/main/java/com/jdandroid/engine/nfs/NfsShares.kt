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
}
