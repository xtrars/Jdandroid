package com.jdandroid.engine.nfs

import com.jdandroid.data.NfsSettings
import java.io.File
import java.io.IOException

/** Result of a connection check: listing of the target folder and free space. */
data class NfsProbe(val entries: List<String>, val freeBytes: Long, val totalBytes: Long)

/** One directory entry of a listing; [size] is 0 for directories. */
data class NfsEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * Minimal view of a mounted NFS export, relative to [NfsSettings.rootPath].
 * Paths are "/"-separated and relative to the root ("film/a.mkv").
 */
interface NfsShare : AutoCloseable {
    @Throws(IOException::class)
    fun list(dir: String): List<String>

    /** Like [list], with type and size of each entry. */
    @Throws(IOException::class)
    fun entries(dir: String): List<NfsEntry>

    @Throws(IOException::class)
    fun exists(path: String): Boolean

    @Throws(IOException::class)
    fun mkdirs(dir: String)

    /** Uploads [source] to [path]; [progress] receives written bytes. Partial files are removed on failure. */
    @Throws(IOException::class)
    fun upload(source: File, path: String, progress: ((Long) -> Unit)? = null)

    @Throws(IOException::class)
    fun download(path: String, dest: File)

    @Throws(IOException::class)
    fun delete(path: String)

    @Throws(IOException::class)
    fun probe(): NfsProbe
}

/** Opens shares; replaced by a fake in tests. */
fun interface NfsShareFactory {
    @Throws(IOException::class)
    fun open(settings: NfsSettings): NfsShare
}

/**
 * Why an NFS operation failed. Transient failures (NAS off, network, timeout)
 * keep the file local for a later retry; permanent ones (export missing,
 * permission denied) are reported to the user.
 */
sealed class NfsFailure(message: String, cause: Throwable?) : IOException(message, cause) {
    class Transient(message: String, cause: Throwable? = null) : NfsFailure(message, cause)
    class Permanent(message: String, cause: Throwable? = null) : NfsFailure(message, cause)
}
