package com.jdandroid.nfs

import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.jdandroid.core.Texts
import com.jdandroid.engine.nfs.NfsClientShare
import com.jdandroid.engine.nfs.NfsFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Failure table of [NfsClientShare.classify]; the library exceptions are built directly, no network. */
class NfsClassifyTest {

    private fun classify(e: Throwable) = NfsClientShare.classify(e)

    @Test
    fun `Netzfehler sind voruebergehend`() {
        listOf(
            ConnectException("refused"),
            SocketTimeoutException("read timed out"),
            UnknownHostException("nas"),
            NoRouteToHostException("no route"),
            EOFException(),
            IOException("Connection reset"),
            IOException("RPC call timed out"),
            RpcException(RpcStatus.NETWORK_ERROR, "network"),
            IOException("something odd")
        ).forEach { e ->
            val f = classify(e)
            assertTrue("$e", f is NfsFailure.Transient)
            assertTrue(f.message!!.startsWith(Texts.t("engine_nfs_unreachable", "").trimEnd()))
        }
    }

    @Test
    fun `Zugriff verweigert ist dauerhaft`() {
        listOf(
            NfsException(NfsStatus.NFS3ERR_ACCES, "acces"),
            NfsException(NfsStatus.NFS3ERR_PERM, "perm"),
            IOException("NFS3ERR_ACCES on write"),
            IOException("EACCES"),
            IOException("Permission denied")
        ).forEach { e ->
            val f = classify(e)
            assertTrue("$e", f is NfsFailure.Permanent)
            assertEquals(Texts.t("engine_nfs_denied"), f.message)
        }
    }

    @Test
    fun `fehlende oder nicht freigegebene Freigabe ist dauerhaft`() {
        listOf(
            MountException(MountStatus.MNT3ERR_ACCES, "not exported"),
            MountException(MountStatus.MNT3ERR_NOENT, "no such export"),
            NfsException(NfsStatus.NFS3ERR_NOENT, "noent"),
            IOException("MNT3ERR_ACCES")
        ).forEach { e ->
            val f = classify(e)
            assertTrue("$e", f is NfsFailure.Permanent)
            assertEquals(Texts.t("engine_nfs_export_missing"), f.message)
        }
    }

    @Test
    fun `Serverfehler beim Einhaengen sind voruebergehend`() {
        assertTrue(classify(MountException(MountStatus.MNT3ERR_IO, "io")) is NfsFailure.Transient)
        assertTrue(classify(NfsException(NfsStatus.NFS3ERR_STALE, "stale")) is NfsFailure.Transient)
        assertTrue(classify(NfsException(NfsStatus.NFS3ERR_JUKEBOX, "later")) is NfsFailure.Transient)
    }

    @Test
    fun `voller Speicher und ungueltige Eingaben sind dauerhaft`() {
        assertTrue(classify(NfsException(NfsStatus.NFS3ERR_NOSPC, "full")) is NfsFailure.Permanent)
        assertTrue(classify(IllegalArgumentException("server blank")) is NfsFailure.Permanent)
    }

    @Test
    fun `Ursachenkette wird ausgewertet und NfsFailure bleibt erhalten`() {
        val wrapped = IOException("mount failed", MountException(MountStatus.MNT3ERR_ACCES, "x"))
        assertTrue(classify(wrapped) is NfsFailure.Permanent)
        val own = NfsFailure.Permanent("eigen")
        assertSame(own, classify(own))
        assertSame(own, classify(RuntimeException("outer", own)))
    }
}
