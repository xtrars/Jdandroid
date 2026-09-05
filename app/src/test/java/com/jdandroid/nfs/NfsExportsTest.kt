package com.jdandroid.nfs

import com.jdandroid.engine.nfs.NfsExports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

/** XDR decoding of a MOUNTPROC3_EXPORT reply, bytes built by hand. */
class NfsExportsTest {

    private fun xdr(build: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).build() }.toByteArray()

    private fun DataOutputStream.string(s: String) {
        val bytes = s.toByteArray()
        writeInt(bytes.size)
        write(bytes)
        repeat((4 - bytes.size % 4) % 4) { writeByte(0) }
    }

    @Test
    fun zweiExporteMitGruppen() {
        val body = xdr {
            writeInt(1); string("/volume1/downloads")
            writeInt(1); string("192.168.1.0/24")
            writeInt(1); string("nas-client")
            writeInt(0)
            writeInt(1); string("/volume1/video")
            writeInt(0)
            writeInt(0)
        }
        assertEquals(listOf("/volume1/downloads", "/volume1/video"), NfsExports.parseExports(body))
    }

    @Test
    fun leereListe() {
        assertTrue(NfsExports.parseExports(xdr { writeInt(0) }).isEmpty())
    }

    @Test
    fun doppelteExporteNurEinmal() {
        val body = xdr {
            writeInt(1); string("/a"); writeInt(0)
            writeInt(1); string("/a"); writeInt(1); string("*"); writeInt(0)
            writeInt(0)
        }
        assertEquals(listOf("/a"), NfsExports.parseExports(body))
    }

    @Test(expected = EOFException::class)
    fun abgeschnitteneAntwortWirftEof() {
        NfsExports.parseExports(xdr { writeInt(1); writeInt(40) })
    }
}
