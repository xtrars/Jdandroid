package com.jdandroid.engine.nfs

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.emc.ecs.nfsclient.rpc.RpcException
import com.jdandroid.core.Texts
import com.jdandroid.data.NfsSettings
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [NfsShare] over the NFSv3 client library. The mount happens in [open];
 * every failure leaves as [NfsFailure] via [classify]. Paths are relative to
 * [NfsSettings.rootPath], i.e. the sub directory below the export.
 */
internal class NfsClientShare private constructor(
    private val settings: NfsSettings,
    private val nfs: Nfs3
) : NfsShare {

    /** Sub directory inside the mount ("" or "/sub"). */
    private val mountRoot: String =
        settings.rootPath.removePrefix(NfsSettings.normalizePath(settings.export)).trimEnd('/')

    private fun file(path: String): Nfs3File =
        nfs.newFile(NfsSettings.normalizePath("$mountRoot/$path"))

    override fun list(dir: String): List<String> = guard {
        file(dir).list().filter { it != "." && it != ".." }
    }

    override fun entries(dir: String): List<NfsEntry> = guard {
        file(dir).listFiles()
            .filter { it.name != "." && it.name != ".." }
            .map { f ->
                val isDir = f.isDirectory
                NfsEntry(f.name, isDir, if (isDir) 0 else f.length())
            }
    }

    override fun exists(path: String): Boolean = guard { file(path).exists() }

    override fun mkdirs(dir: String) = guard {
        val f = file(dir)
        if (!f.exists()) f.mkdirs()
    }

    override fun upload(source: File, path: String, progress: ((Long) -> Unit)?) {
        val part = file("$path.part")
        try {
            val target = file(path)
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) mkdirs(parent)
            if (part.exists()) part.delete()
            NfsFileOutputStream(part).use { out ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        progress?.invoke(written)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed: $path")
        } catch (e: Exception) {
            runCatching { if (part.exists()) part.delete() }
            throw classify(e)
        }
    }

    override fun download(path: String, dest: File) {
        try {
            NfsFileInputStream(file(path)).use { input ->
                dest.outputStream().use { input.copyTo(it, BUFFER) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw classify(e)
        }
    }

    override fun delete(path: String) = guard {
        val f = file(path)
        if (f.exists()) f.delete()
    }

    override fun probe(): NfsProbe = guard {
        val root = file("")
        if (!root.exists() || !root.isDirectory) {
            throw NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"))
        }
        NfsProbe(list(""), root.freeSpace, root.totalSpace)
    }

    /** The library pools its connections process-wide; nothing to release per share. */
    override fun close() = Unit

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw classify(e)
    }

    companion object {
        /** Streams of the library are unbuffered; 1 MiB matches the NFS write size on large files. */
        private const val BUFFER = 1 shl 20
        private const val RETRIES = 3

        /** Factory used by [NfsShares]; mounts the export, so it may block and throw. */
        val factory = NfsShareFactory { settings -> open(settings) }

        @Throws(IOException::class)
        fun open(settings: NfsSettings): NfsClientShare {
            if (settings.server.isBlank() || settings.export.isBlank()) {
                throw NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"))
            }
            val nfs = try {
                Nfs3(
                    settings.server.trim(),
                    NfsSettings.normalizePath(settings.export),
                    CredentialUnix(settings.uid, settings.gid, emptySet()),
                    RETRIES
                )
            } catch (e: Exception) {
                throw classify(e)
            }
            return NfsClientShare(settings, nfs)
        }

        /**
         * Sorts a failure into transient (NAS off, network, timeout: keep the
         * file and retry later) or permanent (access denied, export missing,
         * bad input: tell the user). Unknown errors count as transient so a
         * hiccup never strands a file with an error.
         */
        fun classify(e: Throwable): NfsFailure {
            generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.forEach { cause ->
                classifyOne(cause)?.let { return it }
            }
            return NfsFailure.Transient(Texts.t("engine_nfs_unreachable", describe(e)), e)
        }

        private fun classifyOne(e: Throwable): NfsFailure? {
            val message = e.message.orEmpty()
            return when (e) {
                is NfsFailure -> e
                is MountException -> when (e.status) {
                    MountStatus.MNT3ERR_ACCES, MountStatus.MNT3ERR_PERM, MountStatus.MNT3ERR_NOENT,
                    MountStatus.MNT3ERR_NOTDIR, MountStatus.MNT3ERR_INVAL, MountStatus.MNT3ERR_NAMETOOLONG ->
                        NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"), e)
                    else -> null
                }
                is NfsException -> when (e.status) {
                    NfsStatus.NFS3ERR_ACCES, NfsStatus.NFS3ERR_PERM, NfsStatus.NFS3ERR_ROFS ->
                        NfsFailure.Permanent(Texts.t("engine_nfs_denied"), e)
                    NfsStatus.NFS3ERR_NOENT, NfsStatus.NFS3ERR_NOTDIR ->
                        NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"), e)
                    NfsStatus.NFS3ERR_NOSPC, NfsStatus.NFS3ERR_DQUOT, NfsStatus.NFS3ERR_FBIG,
                    NfsStatus.NFS3ERR_NAMETOOLONG, NfsStatus.NFS3ERR_INVAL ->
                        NfsFailure.Permanent("NFS: $message", e)
                    else -> null
                }
                is RpcException -> NfsFailure.Transient(Texts.t("engine_nfs_unreachable", describe(e)), e)
                is ConnectException, is SocketTimeoutException, is UnknownHostException,
                is NoRouteToHostException, is EOFException, is SocketException ->
                    NfsFailure.Transient(Texts.t("engine_nfs_unreachable", describe(e)), e)
                is IllegalArgumentException -> NfsFailure.Permanent("NFS: $message", e)
                else -> byMessage(e, message)
            }
        }

        /** Errors the library wraps as plain IOException carry the NFS status name in the text. */
        private fun byMessage(e: Throwable, message: String): NfsFailure? = when {
            message.contains("MNT3ERR_ACCES") || message.contains("MNT3ERR_NOENT") ->
                NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"), e)
            message.contains("NFS3ERR_ACCES") || message.contains("EACCES") ||
                message.contains("Permission denied", ignoreCase = true) ->
                NfsFailure.Permanent(Texts.t("engine_nfs_denied"), e)
            message.contains("NFS3ERR_NOENT") -> NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"), e)
            message.contains("Connection reset", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) ->
                NfsFailure.Transient(Texts.t("engine_nfs_unreachable", describe(e)), e)
            else -> null
        }

        private fun describe(e: Throwable): String =
            e.message?.takeIf { it.isNotBlank() }?.let { it.lineSequence().first().take(120) } ?: e.javaClass.simpleName
    }
}
