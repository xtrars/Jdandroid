package com.jdandroid.engine.nfs

import com.emc.ecs.nfsclient.nfs.NfsSetAttributes

/** POSIX modes the app sends with NFS create and mkdir calls. */
internal object NfsModes {
    /** rwxr-xr-x */
    const val DIR = 0b111_101_101L

    /** rw-r--r-- */
    const val FILE = 0b110_100_100L

    private const val PERMISSION_BITS = 0b111_111_111L

    /** True when no permission bit is set, the server default for a mkdir without attributes. */
    fun sealed(mode: Long): Boolean = mode and PERMISSION_BITS == 0L

    fun attributes(mode: Long): NfsSetAttributes = NfsSetAttributes(mode, null, null, null, null)
}
