package com.example.vbantx

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** A device on the LAN that answered a VBAN-PING. */
data class FoundReceiver(
    val ip: String, val port: Int, val label: String,
    /** e.g. "Receiver", "Mixer"; derived from the PING reply. */
    val roles: List<String>,
    /** Heuristic: routes audio (mixer/matrix/server), or receives *and* is seen sending audio. */
    val isHub: Boolean,
    val canReceive: Boolean,
)

/** An audio stream observed on the wire while scanning. */
data class ActiveStream(val name: String, val fromIp: String, val sampleRate: Int, val channels: Int)

data class DiscoveryResult(
    val receivers: List<FoundReceiver>,
    val activeStreams: List<ActiveStream>,
)

object VbanDiscovery {

    /**
     * Broadcasts a VBAN-PING and listens for [durationMs]. Collects replying devices and,
     * if the socket can listen on the VBAN port, the names of audio streams seen in flight.
     */
    suspend fun discover(ctx: Context, port: Int = Vban.DEFAULT_PORT, durationMs: Long = 3000): DiscoveryResult =
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Many phones drop broadcast/multicast datagrams unless a lock is held.
            val lock = wifi.createMulticastLock("vban-discovery").apply { setReferenceCounted(false); acquire() }
            val receivers = LinkedHashMap<String, FoundReceiver>()
            val streams = LinkedHashMap<String, ActiveStream>()
            val replies = LinkedHashMap<String, VbanService.ServiceReply>()
            val local = localAddresses()
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.broadcast = true
                try {
                    socket.bind(InetSocketAddress(port))
                } catch (_: java.net.SocketException) {
                    // Port busy: we can still ping, just won't see passive audio traffic.
                    socket.bind(InetSocketAddress(0))
                }

                val ping = VbanService.buildPingRequest()
                for (target in broadcastTargets(ctx)) {
                    try { socket.send(DatagramPacket(ping, ping.size, target, port)) } catch (_: Exception) {}
                }

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + durationMs
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = minOf(left, 250).toInt()
                    val pkt = DatagramPacket(buf, buf.size)
                    try { socket.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val ip = pkt.address.hostAddress ?: continue
                    if (ip in local) continue // our own broadcast looping back

                    when (val parsed = VbanService.parse(buf, pkt.length)) {
                        is VbanService.ServiceReply -> {
                            replies[ip] = parsed
                        }
                        is VbanService.AudioSeen -> if (parsed.streamName.isNotBlank()) {
                            streams.putIfAbsent(
                                "${parsed.streamName}@$ip",
                                ActiveStream(parsed.streamName, ip, parsed.sampleRate, parsed.channels),
                            )
                        }
                        null -> {}
                    }
                }
            } finally {
                socket.close()
                if (lock.isHeld) lock.release()
            }
            val senderIps = streams.values.map { it.fromIp }.toSet()
            for ((ip, r) in replies) {
                val name = listOf(r.application, r.host, r.device)
                    .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                val canReceive = VbanService.isReceiver(r.roles)
                receivers[ip] = FoundReceiver(
                    ip, port, name.ifBlank { "VBAN device" }, VbanService.roleNames(r.roles),
                    isHub = (r.roles and VbanService.HUB_ROLES != 0) || (canReceive && ip in senderIps),
                    canReceive = canReceive,
                )
            }
            // Receivers first, then everything else that answered.
            DiscoveryResult(
                receivers.values.sortedByDescending { it.canReceive },
                streams.values.toList(),
            )
        }

    private fun localAddresses(): Set<String> =
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }.toSet()

    /** Subnet-directed broadcast of the active network, plus the limited broadcast address. */
    private fun broadcastTargets(ctx: Context): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val props = cm.getLinkProperties(cm.activeNetwork)
            props?.linkAddresses?.forEach { la ->
                val addr = la.address
                if (addr is Inet4Address) {
                    val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                    val ip = addr.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                    val bc = ip or mask.inv()
                    out += InetAddress.getByAddress(
                        byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())
                    )
                }
            }
        } catch (_: Exception) {}
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }
}
