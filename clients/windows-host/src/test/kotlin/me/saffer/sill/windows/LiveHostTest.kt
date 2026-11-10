package me.saffer.sill.windows

import me.saffer.sill.core.Base64Url
import me.saffer.sill.core.ClientInfo
import me.saffer.sill.core.Clock
import me.saffer.sill.core.JsonObj
import me.saffer.sill.core.Kind
import me.saffer.sill.core.MessageReader
import me.saffer.sill.core.PairRequest
import me.saffer.sill.core.PairResult
import me.saffer.sill.core.PairingProof
import me.saffer.sill.core.ParameterSets
import me.saffer.sill.core.SessionListener
import me.saffer.sill.core.SillSession
import me.saffer.sill.core.SillTls
import me.saffer.sill.core.StreamMessage
import me.saffer.sill.core.StreamSource
import me.saffer.sill.core.Trust
import me.saffer.sill.core.WindowList
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MANUAL: a real host on the network, skipped unless SILL_LIVE_HOST=host:port is set. With no
 * SILL_LIVE_CODE it asks (the host shows a code); with one it pairs by that code, then streams the
 * Desktop for SILL_LIVE_SECONDS and writes the Annex B it got to SILL_LIVE_OUT for ffmpeg to decode.
 * The device key lives in SILL_LIVE_DIR so both runs are the same device.
 */
class LiveHostTest {
    @Test
    fun liveHost() {
        val target = System.getenv("SILL_LIVE_HOST") ?: return
        val (host, port) = target.split(":").let { it[0] to it[1].toInt() }
        val addr = InetSocketAddress(host, port)
        val device = HostState(File(System.getenv("SILL_LIVE_DIR") ?: error("SILL_LIVE_DIR"))).identity

        fun pairRequest(r: PairRequest): Pair<PairResult, ByteArray> {
            val c = SillTls.dial(addr, device, SillTls.PAIRING_ALPN, Trust.AnyKey)
            c.use {
                c.output.write(StreamMessage.json(Kind.PAIR_REQUEST, r.toJson()).serialized()); c.output.flush()
                val m = MessageReader(c.input, cap = { StreamMessage.MAX_PAIRING_PAYLOAD.toLong() }).next() ?: error("no reply")
                return PairResult.fromJson(JsonObj.parse(m.payload)) to c.peerFingerprint!!
            }
        }

        val code = System.getenv("SILL_LIVE_CODE")
        val (asked, hostFp) = pairRequest(PairRequest(method = PairRequest.ASK, proof = "", name = "Live test", model = "JVM"))
        println("LIVE ask: ok=${asked.ok} reason=${asked.reason}")
        if (!asked.ok) {
            if (code == null) return
            val proof = PairingProof.deviceProof(PairingProof.codeKey(code, hostFp)!!, device.fingerprint, hostFp)
            val (paired, _) = pairRequest(PairRequest(method = PairRequest.CODE, proof = Base64Url.encode(proof), name = "Live test", model = "JVM"))
            println("LIVE pair: ok=${paired.ok} reason=${paired.reason} name=${paired.name} macID=${paired.macID}")
            assertTrue(paired.ok, "$paired")
        }

        val out = File(System.getenv("SILL_LIVE_OUT") ?: "live.hevc").outputStream()
        var frames = 0; var keys = 0; var bytes = 0L
        val listener = object : SessionListener {
            override fun onConnected(list: WindowList) { println("LIVE connected: ${list.macName} hostVersion=${list.hostVersion} displays=${list.displays?.size}") }
            override fun onVideoFormat(sets: ParameterSets) { println("LIVE parameter sets: ${sets.sets.size} NALs"); out.write(sets.annexB()) }
            override fun onFrame(annexB: ByteArray, keyframe: Boolean, timestamp: Double) { frames++; if (keyframe) keys++; bytes += annexB.size; out.write(annexB) }
        }
        val session = SillSession(SillTls.dial(addr, device, SillTls.SESSION_ALPN, Trust.Pinned(hostFp)),
            ClientInfo("0.6.0", "live", "Live test (JVM)", "PC", "JVM", "live-test"), listener, Clock.System)
        session.start(null)
        Thread.sleep(500)
        session.select(StreamSource.Desktop)
        val seconds = (System.getenv("SILL_LIVE_SECONDS") ?: "5").toLong()
        Thread.sleep(seconds * 1000)
        session.close(); out.close()
        println("LIVE video: $frames frames ($keys key) in $seconds s, ${bytes / 1024} KiB")
        assertTrue(frames > 0, "no frames from the live host")
    }
}
