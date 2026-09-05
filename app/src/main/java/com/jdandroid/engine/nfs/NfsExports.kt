package com.jdandroid.engine.nfs

import com.jdandroid.core.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lists the exports of an NFS server like `showmount -e`: asks the
 * portmapper for the mountd port, then calls MOUNTPROC3_EXPORT. Plain ONC
 * RPC over TCP with AUTH_NULL; the library's RPC layer is tied to NFS
 * requests and cannot carry a MOUNT call.
 */
object NfsExports {
    private const val PORTMAP_PORT = 111
    private const val PORTMAP_PROG = 100000
    private const val PORTMAP_VERS = 2
    private const val PMAPPROC_GETPORT = 3
    private const val MOUNT_PROG = 100005
    private const val MOUNT_VERS = 3
    private const val MOUNTPROC3_EXPORT = 5
    private const val IPPROTO_TCP = 6
    private const val TIMEOUT_MS = 5_000
    private const val MAX_REPLY = 1 shl 20

    private val xids = AtomicInteger((System.nanoTime() and 0x7fffffff).toInt())

    /** Export paths of [host] in server order; empty when nothing is exported. */
    suspend fun exports(host: String): List<String> = withContext(Dispatchers.IO) {
        val server = host.trim()
        if (server.isEmpty()) throw NfsFailure.Permanent(Texts.t("engine_nfs_export_missing"))
        try {
            val query = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).apply {
                    writeInt(MOUNT_PROG); writeInt(MOUNT_VERS); writeInt(IPPROTO_TCP); writeInt(0)
                }
            }.toByteArray()
            val port = XdrReader(call(server, PORTMAP_PORT, PORTMAP_PROG, PORTMAP_VERS, PMAPPROC_GETPORT, query)).int()
            if (port !in 1..65535) throw NfsFailure.Permanent(Texts.t("engine_nfs_exports_failed", "mountd not registered"))
            parseExports(call(server, port, MOUNT_PROG, MOUNT_VERS, MOUNTPROC3_EXPORT, ByteArray(0)))
        } catch (e: NfsFailure) {
            throw e
        } catch (e: RpcError) {
            throw NfsFailure.Permanent(Texts.t("engine_nfs_exports_failed", e.message ?: "RPC"), e)
        } catch (e: Exception) {
            throw NfsClientShare.classify(e)
        }
    }

    /**
     * Decodes the body of a MOUNTPROC3_EXPORT reply: a linked list of
     * (dirpath, groups) where each list uses a "follows" flag. Group names
     * are skipped; duplicates keep their first position.
     */
    fun parseExports(body: ByteArray): List<String> {
        val reader = XdrReader(body)
        val paths = LinkedHashSet<String>()
        while (reader.bool()) {
            paths += reader.string()
            while (reader.bool()) reader.string()
        }
        return paths.toList()
    }

    /** Wrong or refused RPC reply (program unavailable, auth rejected); never a network problem. */
    class RpcError(message: String) : IOException(message)

    /** One RPC call over a fresh TCP connection; returns the result body after the accepted-reply header. */
    private fun call(host: String, port: Int, prog: Int, vers: Int, proc: Int, args: ByteArray): ByteArray {
        val xid = xids.incrementAndGet()
        val message = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(xid); writeInt(0)               // msg_type CALL
                writeInt(2); writeInt(prog); writeInt(vers); writeInt(proc)
                writeInt(0); writeInt(0)                 // cred AUTH_NULL, empty body
                writeInt(0); writeInt(0)                 // verf AUTH_NULL, empty body
                write(args)
            }
        }.toByteArray()
        val reply = Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(message.size or Int.MIN_VALUE)  // single fragment, last-fragment bit set
            out.write(message)
            out.flush()
            readRecord(DataInputStream(socket.getInputStream()))
        }
        val reader = XdrReader(reply)
        if (reader.int() != xid) throw RpcError("xid mismatch")
        if (reader.int() != 1) throw RpcError("not a reply")
        when (val status = reader.int()) {
            0 -> Unit
            1 -> throw RpcError("call rejected (auth or version)")
            else -> throw RpcError("reply status $status")
        }
        reader.int(); reader.skip(reader.int())          // verifier flavor and body
        when (val accepted = reader.int()) {
            0 -> Unit
            1 -> throw RpcError("program not available")
            2 -> throw RpcError("program version mismatch")
            3 -> throw RpcError("procedure unavailable")
            4 -> throw RpcError("garbage arguments")
            else -> throw RpcError("accept status $accepted")
        }
        return reply.copyOfRange(reader.offset, reply.size)
    }

    /** Reads a record-marked message: fragments with a 4-byte header until the last-fragment bit. */
    private fun readRecord(input: DataInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val header = input.readInt()
            val length = header and Int.MAX_VALUE
            if (out.size() + length > MAX_REPLY) throw RpcError("reply too large")
            val fragment = ByteArray(length)
            input.readFully(fragment)
            out.write(fragment)
            if (header < 0) return out.toByteArray()
        }
    }

    /** Minimal XDR decoder for ints, booleans and strings (4-byte aligned). */
    class XdrReader(private val data: ByteArray) {
        var offset = 0
            private set

        fun int(): Int {
            if (offset + 4 > data.size) throw EOFException("truncated XDR")
            val v = ((data[offset].toInt() and 0xff) shl 24) or ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
            offset += 4
            return v
        }

        fun bool(): Boolean = int() != 0

        fun string(): String {
            val length = int()
            if (length < 0 || offset + length > data.size) throw EOFException("truncated XDR string")
            val s = String(data, offset, length, Charsets.UTF_8)
            skip(length)
            return s
        }

        /** Skips [length] bytes plus padding to the next 4-byte boundary. */
        fun skip(length: Int) {
            val padded = (length + 3) and 3.inv()
            if (length < 0 || offset + padded > data.size) throw EOFException("truncated XDR")
            offset += padded
        }
    }
}
