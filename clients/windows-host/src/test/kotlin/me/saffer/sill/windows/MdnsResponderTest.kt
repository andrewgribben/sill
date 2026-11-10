package me.saffer.sill.windows

import me.saffer.sill.core.Dns
import me.saffer.sill.core.RecognitionTag
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mDNS responder answers a PTR query for `_sill._tcp.local.` with our instance, SRV, TXT and A records. */
class MdnsResponderTest {
    private var responder: MdnsResponder? = null

    @AfterTest
    fun stop() { responder?.stop() }

    @Test
    fun answersAPtrQueryOverMulticast() {
        val key = ByteArray(32) { it.toByte() }
        val r = MdnsResponder("Test PC", 7455, { key }, { listOf(InetAddress.getByName("192.0.2.10")) })
        responder = r
        r.start()

        val query = Dns.query(listOf("_sill._tcp.local." to Dns.PTR))
        val probe = MulticastSocket(0)
        probe.soTimeout = 3000
        try {
            probe.send(DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName("224.0.0.251"), MdnsResponder.MDNS_PORT)))
            val buf = ByteArray(9000)
            var sawPtr = false; var sawTxt = false; var sawA = false
            val deadline = System.nanoTime() + 3_000_000_000
            while (!sawPtr && System.nanoTime() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try { probe.receive(p) } catch (_: java.net.SocketTimeoutException) { break }
                val records = Dns.parseResponse(buf.copyOf(p.length)) ?: continue
                for (rec in records) when (rec.type) {
                    Dns.PTR -> if (rec.name.equals("_sill._tcp.local.", true)) sawPtr = true
                    Dns.TXT -> { @Suppress("UNCHECKED_CAST") val txt = rec.data as Map<String, String?>; if (RecognitionTag.matches(txt["r"] ?: "", key)) sawTxt = true }
                    Dns.A -> sawA = true
                }
            }
            assertTrue(sawPtr, "no PTR answer for _sill._tcp.local.")
            assertTrue(sawTxt, "no TXT with a recognition tag matching our key")
            assertTrue(sawA, "no A record")
        } finally { probe.close() }
    }
}
