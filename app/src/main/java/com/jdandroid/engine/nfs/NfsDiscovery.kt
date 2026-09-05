package com.jdandroid.engine.nfs

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/** An NFS server on the local network; [host] is the IPv4 address, [name] the mDNS or reverse-DNS name. */
data class NfsServer(val host: String, val name: String? = null, val port: Int = NfsDiscovery.NFS_PORT)

/**
 * Finds NFS servers on the local network from two sources at once: mDNS
 * ("_nfs._tcp" via NsdManager) and a TCP scan of the Wi-Fi subnet on port
 * 2049. Runs about four seconds and reports each server once.
 */
object NfsDiscovery {
    const val NFS_PORT = 2049
    private const val SERVICE_TYPE = "_nfs._tcp"
    private const val NSD_TIMEOUT_MS = 4_000L
    private const val RESOLVE_TIMEOUT_MS = 2_000L
    private const val SCAN_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 300
    private const val NAME_TIMEOUT_MS = 500L
    private const val PARALLEL = 64
    /** Scans never exceed /22: 1024 addresses at 64 parallel connects stay within the time budget. */
    private const val MIN_PREFIX = 22

    suspend fun discover(context: Context, onFound: (NfsServer) -> Unit) {
        val found = ArrayList<NfsServer>()
        val lock = Any()
        val report: (NfsServer) -> Unit = { server ->
            val changed = synchronized(lock) {
                val merged = merge(found, server)
                val changed = merged != found
                found.clear()
                found += merged
                changed
            }
            if (changed) onFound(server)
        }
        coroutineScope {
            launch { viaNsd(context, report) }
            launch { viaScan(context, report) }
        }
    }

    /**
     * Adds [server] to [servers], one entry per IP: a known host keeps its
     * name unless it had none. The list stays ordered by IP.
     */
    fun merge(servers: List<NfsServer>, server: NfsServer): List<NfsServer> {
        val existing = servers.find { it.host == server.host }
        val combined = when {
            existing == null -> server
            existing.name.isNullOrBlank() && !server.name.isNullOrBlank() -> existing.copy(name = server.name)
            else -> existing
        }
        return (servers.filter { it.host != server.host } + combined).sortedWith(compareBy({ ipOrder(it.host) }, { it.host }))
    }

    /**
     * All host addresses of the subnet of [address]/[prefixLength] except the
     * network, broadcast and [address] itself; prefixes shorter than /22 are
     * cut down to /22 around [address].
     */
    fun hostAddresses(address: String, prefixLength: Int): List<String> {
        val own = toInt(address) ?: return emptyList()
        val prefix = prefixLength.coerceAtLeast(MIN_PREFIX)
        if (prefix >= 31) return emptyList()
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).filter { it != own }.map { toDotted(it) }
    }

    private suspend fun viaNsd(context: Context, onFound: (NfsServer) -> Unit) = withContext(Dispatchers.IO) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@withContext
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                services.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                services.close()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                services.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            return@withContext
        }
        try {
            withTimeoutOrNull(NSD_TIMEOUT_MS) {
                // NsdManager resolves one service at a time; the loop keeps that order.
                for (info in services) resolve(nsd, info)?.let(onFound)
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NfsServer? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
                        val server = host?.let {
                            NfsServer(it, serviceInfo.serviceName.takeIf { n -> n.isNotBlank() }, serviceInfo.port)
                        }
                        if (cont.isActive) cont.resume(server)
                    }
                })
            }
        }

    private suspend fun viaScan(context: Context, onFound: (NfsServer) -> Unit) = withContext(Dispatchers.IO) {
        val (address, prefix) = localAddress(context) ?: return@withContext
        val limiter = Semaphore(PARALLEL)
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            coroutineScope {
                hostAddresses(address, prefix).forEach { host ->
                    launch {
                        limiter.withPermit {
                            if (portOpen(host)) onFound(NfsServer(host, hostName(host)))
                        }
                    }
                }
            }
        }
    }

    /** IPv4 address and prefix of the active Wi-Fi or Ethernet link. */
    private fun localAddress(context: Context): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return null
        val link = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null
        return link.address.hostAddress?.let { it to link.prefixLength }
    }

    private fun portOpen(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, NFS_PORT), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** Reverse lookup with a short budget; null when there is no name or it does not answer in time. */
    private suspend fun hostName(host: String): String? = withTimeoutOrNull(NAME_TIMEOUT_MS) {
        runCatching {
            runInterruptible(Dispatchers.IO) { InetAddress.getByName(host).canonicalHostName }
        }.getOrNull()
    }?.takeIf { it.isNotBlank() && it != host }

    private fun toInt(address: String): Int? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var value = 0
        parts.forEach { part ->
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun toDotted(value: Int): String =
        "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"

    private fun ipOrder(host: String): Long = toInt(host)?.toLong()?.and(0xffffffffL) ?: Long.MAX_VALUE
}
