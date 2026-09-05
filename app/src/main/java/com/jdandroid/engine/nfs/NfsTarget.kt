package com.jdandroid.engine.nfs

import com.jdandroid.core.FileNames
import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.FileTrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NFS counterpart of the SAF/MediaStore targets: finished files land below
 * [NfsSettings.rootPath], extracted folders in a sub folder named after the
 * package. One share is opened per operation. Display paths read
 * "nfs://server/export/sub/name".
 */
internal class NfsTarget(private val factory: () -> NfsShareFactory = { NfsShares.factory }) {

    sealed interface Outcome {
        data class Done(val displayPath: String) : Outcome
        data class Failed(val failure: NfsFailure) : Outcome
    }

    /** Uploads [temp] under [fileName] ("(2)" when taken); the local file is left to the caller. */
    suspend fun finish(
        settings: NfsSettings,
        temp: File,
        fileName: String,
        progress: ((Long) -> Unit)? = null
    ): Outcome = withContext(Dispatchers.IO) {
        attempt(settings) { share ->
            val taken = share.list("").toHashSet()
            val name = FileNames.uniqueName(fileName) { it in taken }
            share.upload(temp, name, progress)
            displayPath(settings, name)
        }
    }

    /**
     * Uploads every regular file below [dir] into [base] (sub folders kept),
     * deleting each local file after its upload and the folder at the end.
     * On failure the remaining files stay for a later retry.
     */
    suspend fun exportDirectory(settings: NfsSettings, dir: File, base: String): Outcome =
        withContext(Dispatchers.IO) {
            attempt(settings) { share ->
                val listings = HashMap<String, HashSet<String>>()
                share.mkdirs(base)
                FileTrees.regularFiles(dir).sortedBy { it.path }.forEach { file ->
                    val relDir = file.parentFile!!.relativeTo(dir).path.replace(File.separatorChar, '/')
                    val remoteDir = if (relDir.isEmpty()) base else "$base/$relDir"
                    val taken = listings.getOrPut(remoteDir) {
                        share.mkdirs(remoteDir)
                        share.list(remoteDir).toHashSet()
                    }
                    val name = FileNames.uniqueName(file.name) { it in taken }
                    share.upload(file, "$remoteDir/$name")
                    taken += name
                    file.delete()
                }
                FileTrees.deleteTree(dir)
                displayPath(settings, base)
            }
        }

    /** Copies the file [name] from the target folder back to [dest]; false if absent or unreachable. */
    suspend fun restoreExported(settings: NfsSettings, name: String, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            val outcome = attempt(settings) { share ->
                if (!share.exists(name)) return@attempt null
                share.download(name, dest)
                name
            }
            outcome is Outcome.Done
        }

    private inline fun attempt(settings: NfsSettings, block: (NfsShare) -> String?): Outcome = try {
        factory().open(settings).use { share ->
            block(share)?.let { Outcome.Done(it) }
                ?: Outcome.Failed(NfsFailure.Permanent("not found"))
        }
    } catch (e: NfsFailure) {
        Outcome.Failed(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(NfsClientShare.classify(e))
    }

    companion object {
        fun displayPath(settings: NfsSettings, name: String): String =
            "nfs://${settings.server.trim()}${settings.rootPath}/$name"
    }
}
