package me.saffer.sill.windows

import me.saffer.sill.core.Dns
import me.saffer.sill.core.RecognitionTag
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Advertises `_sill._tcp.local.` the way the Mac does (docs/home-pairing-plan.md): one instance
 * named after this PC, SRV to the door's port, TXT `r` (a fresh [RecognitionTag] per announcement)
 * and `p=1`, A records for this PC's non-loopback, non-link-local LAN IPv4 addresses. A small
 * responder of its own (java.net.MulticastSocket, 224.0.0.251:5353, SO_REUSEADDR) rather than a
 * library: Windows' own mDNS/dnscache answers on 5353 too, so SO_REUSEADDR is required for the two
 * to coexist (shortcut: not verified yet on a real Windows box outside this task's smoke test).
 */
class MdnsResponder(
    private val instanceName: String,
    private val port: Int,
    private val recognitionKeyProvider: () -> ByteArray,
    private val addressesProvider: () -> List<InetAddress> = { lanAddresses() },
) {
    private val serviceType = "_sill._tcp.local."
    private val serviceName = "${escape(instanceName)}.$serviceType"
    private val hostName = "${escape(instanceName)}.local."
    private val sockets = ArrayList<Triple<MulticastSocket, NetworkInterface, InetSocketAddress>>()
    @Volatile private var running = false

    fun start() {
        running = true
        val group = InetSocketAddress(InetAddress.getByName("224.0.0.251"), MDNS_PORT)
        for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
            try {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast() || ni.isPointToPoint) continue
                if (ni.inetAddresses.toList().none { it is Inet4Address }) continue
                val s = MulticastSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(MDNS_PORT))
                s.networkInterface = ni
                s.joinGroup(group, ni)
                s.timeToLive = 255
                s.soTimeout = 1000
                sockets.add(Triple(s, ni, group))
            } catch (e: Exception) {
                Log.warn("mDNS: interface ${ni.name} did not take the multicast socket: ${e.message}")
            }
        }
        if (sockets.isEmpty()) Log.warn("mDNS: no interface could bind 5353; this PC will not be discovered automatically")
        for ((s, ni, group) in sockets) {
            Thread({
                val buf = ByteArray(9000)
                while (running && !s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                    try { handleQuery(buf.copyOf(p.length), s, InetSocketAddress(p.address, p.port), group) } catch (_: Exception) {}
                }
            }, "sill-mdns-responder-${ni.name}").apply { isDaemon = true }.start()
        }
        announce(unsolicited = true)
    }

    fun stop() {
        running = false
        announce(unsolicited = true, ttl = 0)
        synchronized(sockets) { sockets.forEach { (s, _, _) -> try { s.close() } catch (_: Exception) {} } }
    }

    private fun announce(unsolicited: Boolean, ttl: Int = TTL) {
        val packet = response(ttl, includeAll = true)
        for ((s, _, group) in sockets) try { s.send(DatagramPacket(packet, packet.size, group)) } catch (_: Exception) {}
    }

    /** One question we were asked, if it names our records. */
    private fun handleQuery(p: ByteArray, s: MulticastSocket, from: InetSocketAddress, group: InetSocketAddress) {
        if (p.size < 12) return
        val b = ByteBuffer.wrap(p)
        b.short
        val flags = b.short.toInt() and 0xFFFF
        if (flags and 0x8000 != 0) return             // a response, not a query
        val qd = b.short.toInt() and 0xFFFF
        var off = 12
        var wantsOurs = false
        repeat(qd) {
            val (name, after) = Dns.readName(p, off)
            val qtype = ((p[after].toInt() and 0xFF) shl 8) or (p[after + 1].toInt() and 0xFF)
            off = after + 4
            if (name.equals(serviceType, true) || name.equals(serviceName, true) || name.equals(hostName, true)) {
                if (qtype == Dns.PTR || qtype == Dns.SRV || qtype == Dns.TXT || qtype == Dns.A || qtype == Dns.ANY) wantsOurs = true
            }
        }
        if (!wantsOurs) return
        val packet = response(TTL, includeAll = true)
        try { s.send(DatagramPacket(packet, packet.size, from)) } catch (_: Exception) {}
    }

    private fun response(ttl: Int, includeAll: Boolean): ByteArray {
        val addrs = addressesProvider()
        val txt = Dns.encodeTxt(mapOf(RecognitionTag.TXT_KEY to RecognitionTag.make(recognitionKeyProvider()), "p" to "1"))
        val answers = ArrayList<ByteArray>()
        answers.add(rr(serviceType, Dns.PTR, ttl, Dns.encodeName(serviceName)))
        if (includeAll) {
            answers.add(rr(serviceName, Dns.SRV, ttl, srvData(port, hostName)))
            answers.add(rr(serviceName, Dns.TXT, ttl, txt))
            for (a in addrs) if (a is Inet4Address) answers.add(rr(hostName, Dns.A, ttl, a.address))
        }
        val header = ByteBuffer.allocate(12)
        header.putShort(0); header.putShort(0x8400.toShort())   // response, authoritative
        header.putShort(0); header.putShort(answers.size.toShort()); header.putShort(0); header.putShort(0)
        val o = ByteArrayOutputStream()
        o.write(header.array())
        answers.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun srvData(port: Int, target: String): ByteArray {
        val b = ByteBuffer.allocate(6).putShort(0).putShort(0).putShort(port.toShort())
        return b.array() + Dns.encodeName(target)
    }

    private fun rr(name: String, type: Int, ttl: Int, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(Dns.encodeName(name))
        o.write(type ushr 8); o.write(type and 0xFF)
        o.write(0x80); o.write(0x01)                 // IN with the cache-flush bit
        o.write(ByteBuffer.allocate(4).putInt(ttl).array())
        o.write(data.size ushr 8); o.write(data.size and 0xFF)
        o.write(data)
        return o.toByteArray()
    }

    companion object {
        const val MDNS_PORT = 5353
        const val TTL = 120

        private fun escape(s: String) = s.replace("\\", "\\\\").replace(".", "\\.")

        /** This PC's LAN IPv4 addresses, skipping loopback and link-local (169.254.*); Tailscale's
         * 100.x is a normal private range here so it is included, harmlessly, like any other NIC. */
        fun lanAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }
}
