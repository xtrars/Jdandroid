package com.jdandroid.data

/**
 * Connection data of the NFS target. [isUsable] is the single switch the
 * engine consults: enabled and both server and export path present.
 */
data class NfsSettings(
    val enabled: Boolean = false,
    val server: String = "",
    val export: String = "",
    val uid: Int = DEFAULT_UID,
    val gid: Int = DEFAULT_GID,
    val subDir: String = ""
) {
    val isUsable: Boolean get() = enabled && server.isNotBlank() && export.isNotBlank()

    /** Export path plus optional sub directory, normalised to "/a/b" without trailing slash. */
    val rootPath: String
        get() = normalizePath(listOf(export, subDir).filter { it.isNotBlank() }.joinToString("/"))

    companion object {
        const val DEFAULT_UID = 1000
        const val DEFAULT_GID = 1000

        /** Collapses slashes and strips a trailing one; "" becomes "/". */
        fun normalizePath(path: String): String {
            val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
            return "/" + parts.joinToString("/")
        }
    }
}
