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
import me.saffer.sill.core.InputEvent
import me.saffer.sill.core.Viewport
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
        var listed: WindowList? = null
        val listener = object : SessionListener {
            override fun onConnected(list: WindowList) { listed = list; println("LIVE connected: ${list.macName} hostVersion=${list.hostVersion} displays=${list.displays?.size} windows=${list.windows.map { "${it.appName}: ${it.title.take(30)} ${it.width}x${it.height}" }}") }
            override fun onWindowList(list: WindowList) { listed = list; println("LIVE list: active=${list.active} windows=${list.windows.size}") }
            override fun onVideoFormat(sets: ParameterSets) { println("LIVE parameter sets: ${sets.sets.size} NALs"); out.write(sets.annexB()) }
            override fun onFrame(annexB: ByteArray, keyframe: Boolean, timestamp: Double) { frames++; if (keyframe) keys++; bytes += annexB.size; out.write(annexB) }
            override fun onClipboard(text: String) { println("LIVE clipboard from the host: ${text.take(80)}") }
        }
        val session = SillSession(SillTls.dial(addr, device, SillTls.SESSION_ALPN, Trust.Pinned(hostFp)),
            ClientInfo("0.6.0", "live", "Live test (JVM)", "PC", "JVM", "live-test"), listener, Clock.System)
        session.start(null)
        Thread.sleep(500)
        // SILL_LIVE_WINDOW: stream the first window whose title or app has this in it, not the Desktop.
        val want = System.getenv("SILL_LIVE_WINDOW")?.takeIf { it.isNotEmpty() }
        val pick = want?.let { w -> listed?.windows?.firstOrNull { it.title.contains(w, true) || it.appName.contains(w, true) } }
        println("LIVE picking ${pick?.let { "${it.appName}: ${it.title}" } ?: "the Desktop"}")
        session.select(pick?.let { StreamSource.Window(it.id) } ?: StreamSource.Desktop)
        // SILL_LIVE_PASTE: subscribe to the clipboard and put this text on the host's.
        System.getenv("SILL_LIVE_PASTE")?.takeIf { it.isNotEmpty() }?.let { session.updateShareClipboard(true); Thread.sleep(300); println("LIVE clipboard sent: ${session.sendClipboard(it)}") }
        // SILL_LIVE_VIEWPORT="w,h,scale[;w,h,scale…]": viewports 2 s apart (scale "off": Aa Off).
        System.getenv("SILL_LIVE_VIEWPORT")?.takeIf { it.isNotEmpty() }?.split(';')?.forEach { spec ->
            val (w, h, sc) = spec.split(','); Thread.sleep(2000)
            session.setViewport(Viewport(w.toDouble(), h.toDouble(), sc.toDoubleOrNull())); println("LIVE viewport $spec")
        }
        // SILL_LIVE_SCROLL=dy: a slow two-finger scroll in the middle of the picture, 3 points every 16 ms.
        System.getenv("SILL_LIVE_SCROLL")?.toDoubleOrNull()?.let { dy -> Thread.sleep(1500); repeat(60) { session.input(InputEvent.Scroll(0.5, 0.5, 0.0, dy)); Thread.sleep(16) } }
        val seconds = (System.getenv("SILL_LIVE_SECONDS") ?: "5").toLong()
        Thread.sleep(seconds * 1000)
        session.close(); out.close()
        println("LIVE video: $frames frames ($keys key) in $seconds s, ${bytes / 1024} KiB")
        assertTrue(frames > 0, "no frames from the live host")
    }
}
