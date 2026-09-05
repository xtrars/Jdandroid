package com.jdandroid.nfs

import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsEntry
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsProbe
import com.jdandroid.engine.nfs.NfsShare
import com.jdandroid.engine.nfs.NfsShareFactory
import java.io.File

/**
 * In-memory NFS share: [files] maps "dir/name" to content, [dirs] holds the
 * directories. Failures can be injected per operation; uploads go through a
 * ".part" entry like the real share so aborted transfers leave nothing.
 */
class FakeNfsShare : NfsShare {
    val dirs = HashSet<String>().apply { add("") }
    val files = LinkedHashMap<String, ByteArray>()
    var freeBytes = 10L shl 30
    var totalBytes = 20L shl 30

    /** Thrown by every operation while set (share unreachable, access denied). */
    var failure: NfsFailure? = null

    /** Upload aborts with a transient failure once this many bytes were written. */
    var failUploadAfterBytes: Long = -1

    /** Upload of exactly this path aborts (partial folder upload). */
    var failUploadPath: String? = null

    val opened = ArrayList<NfsSettings>()
    var closed = 0
    val uploads = ArrayList<String>()

    val factory = NfsShareFactory { settings ->
        opened += settings
        failure?.let { throw it }
        this
    }

    override fun list(dir: String): List<String> {
        check()
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        if (prefix.trimEnd('/') !in dirs) throw NfsFailure.Permanent("no such dir: $dir")
        val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { '/' !in it } +
            dirs.filter { it.startsWith(prefix) && it != dir }.map { it.removePrefix(prefix) }.filter { '/' !in it }
        return names.distinct()
    }

    override fun entries(dir: String): List<NfsEntry> = list(dir).map { name ->
        val path = if (dir.isEmpty()) name else "$dir/$name"
        val content = files[path]
        NfsEntry(name, isDirectory = content == null, size = content?.size?.toLong() ?: 0)
    }

    override fun exists(path: String): Boolean {
        check()
        return path in files || path in dirs
    }

    override fun mkdirs(dir: String) {
        check()
        var current = ""
        dir.split('/').filter { it.isNotEmpty() }.forEach {
            current = if (current.isEmpty()) it else "$current/$it"
            dirs += current
        }
    }

    override fun upload(source: File, path: String, progress: ((Long) -> Unit)?) {
        check()
        val part = "$path.part"
        val bytes = source.readBytes()
        files[part] = ByteArray(0)
        if (failUploadAfterBytes in 0..bytes.size.toLong() || path == failUploadPath) {
            files.remove(part)
            throw NfsFailure.Transient("connection lost")
        }
        progress?.invoke(bytes.size.toLong())
        files.remove(part)
        files[path] = bytes
        uploads += path
    }

    override fun download(path: String, dest: File) {
        check()
        val bytes = files[path] ?: throw NfsFailure.Permanent("no such file: $path")
        dest.writeBytes(bytes)
    }

    override fun delete(path: String) {
        check()
        files.remove(path)
    }

    override fun probe(): NfsProbe {
        check()
        return NfsProbe(list(""), freeBytes, totalBytes)
    }

    override fun close() {
        closed++
    }

    private fun check() {
        failure?.let { throw it }
    }
}
