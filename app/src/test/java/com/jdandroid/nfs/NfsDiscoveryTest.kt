package com.jdandroid.nfs

import com.jdandroid.engine.nfs.NfsDiscovery
import com.jdandroid.engine.nfs.NfsServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the server search: subnet enumeration and merging of both sources. */
class NfsDiscoveryTest {

    @Test
    fun zaehltHostsOhneNetzBroadcastUndEigeneAdresse() {
        val hosts = NfsDiscovery.hostAddresses("192.168.1.5", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.5" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun begrenztGrosseNetzeAufSlash22() {
        val hosts = NfsDiscovery.hostAddresses("10.20.33.7", 8)
        assertEquals(1021, hosts.size)
        assertEquals("10.20.32.1", hosts.first())
        assertEquals("10.20.35.254", hosts.last())
        assertTrue("10.20.34.200" in hosts)
        assertFalse("10.20.36.1" in hosts)
    }

    @Test
    fun kleineNetzeUndUngueltigeAdressen() {
        assertEquals(listOf("172.16.0.1", "172.16.0.2"), NfsDiscovery.hostAddresses("172.16.0.3", 30))
        assertTrue(NfsDiscovery.hostAddresses("172.16.0.3", 31).isEmpty())
        assertTrue(NfsDiscovery.hostAddresses("nas.local", 24).isEmpty())
        assertTrue(NfsDiscovery.hostAddresses("192.168.1.300", 24).isEmpty())
    }

    @Test
    fun hostsOberhalbVon128() {
        val hosts = NfsDiscovery.hostAddresses("192.168.0.1", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.0.2", hosts.first())
    }

    @Test
    fun fuehrtQuellenNachIpZusammenUndBehaeltDenNamen() {
        var list = NfsDiscovery.merge(emptyList(), NfsServer("192.168.1.20"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.3", "nas"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.20", "diskstation"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.3", "other"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.3"))
        assertEquals(listOf(NfsServer("192.168.1.3", "nas"), NfsServer("192.168.1.20", "diskstation")), list)
    }

    @Test
    fun sortiertNumerischNachIp() {
        var list = NfsDiscovery.merge(emptyList(), NfsServer("192.168.1.100"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.20"))
        list = NfsDiscovery.merge(list, NfsServer("192.168.1.3"))
        assertEquals(listOf("192.168.1.3", "192.168.1.20", "192.168.1.100"), list.map { it.host })
    }
}
