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
        fun normalizePath(path: String): String = "/" + segments(path).joinToString("/")

        /**
         * Path relative to the export as the share expects it: "a/b", root is "".
         * ".." is rejected because share paths must never leave the export.
         */
        fun relativePath(path: String): String {
            val parts = segments(path)
            require(parts.none { it == ".." }) { "path leaves the export: $path" }
            return parts.joinToString("/")
        }

        /** [dir] plus [name] as relative path; [name] must pass [isValidName]. */
        fun joinPath(dir: String, name: String): String {
            require(isValidName(name)) { "invalid folder name: $name" }
            return relativePath("$dir/$name")
        }

        /** Relative parent of [dir]; the root stays the root. */
        fun parentPath(dir: String): String = relativePath(dir).substringBeforeLast('/', "")

        /** A single folder name: not blank, no separators, not "." or "..". */
        fun isValidName(name: String): Boolean =
            name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".."

        private fun segments(path: String): List<String> =
            path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
    }
}
