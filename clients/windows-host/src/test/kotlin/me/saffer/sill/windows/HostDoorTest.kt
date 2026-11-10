package me.saffer.sill.windows

import me.saffer.sill.core.Base64Url
import me.saffer.sill.core.ClientInfo
import me.saffer.sill.core.Clock
import me.saffer.sill.core.Hello
import me.saffer.sill.core.Identity
import me.saffer.sill.core.InputEvent
import me.saffer.sill.core.Json
import me.saffer.sill.core.JsonObj
import me.saffer.sill.core.Kind
import me.saffer.sill.core.MacInfo
import me.saffer.sill.core.ModelHost
import me.saffer.sill.core.PairRequest
import me.saffer.sill.core.PairResult
import me.saffer.sill.core.PairingProof
import me.saffer.sill.core.ParameterSets
import me.saffer.sill.core.PointerAction
import me.saffer.sill.core.SessionEnd
import me.saffer.sill.core.SessionListener
import me.saffer.sill.core.SillSession
import me.saffer.sill.core.SillTls
import me.saffer.sill.core.StreamMessage
import me.saffer.sill.core.StreamSource
import me.saffer.sill.core.Trust
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The real host (HostDoor) against core's own client-side primitives: pairing by ask then code, a
 * refused unpaired key, a session's hello/catalog/video, and input mapped to pixels.
 */
class HostDoorTest {
    private val dirs = CopyOnWriteArrayList<File>()
    private fun tempState(): HostState {
        val dir = File.createTempFile("sill-host-test", "").apply { delete(); mkdirs() }
        dirs.add(dir)
        return HostState(dir)
    }

    @AfterTest
    fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private fun loopback(port: Int) = InetSocketAddress(InetAddress.getLoopbackAddress(), port)

    @Test
    fun anUnpairedKeyIsRefusedAtSillOne() {
        val door = HostDoor(tempState(), "Test PC", port = 0, videoSourceFactory = { FakeVideoSource(ModelHost.defaultVideo().first, emptyList()) })
        door.use {
            val device = Identity.generate()
            assertFails("an unpaired device must not reach sill/1") {
                SillTls.dial(loopback(door.boundPort), device, SillTls.SESSION_ALPN, Trust.AnyKey)
            }
        }
    }

    @Test
    fun pairByAskThenCodeThenASessionWithVideoAndInput() {
        val sink = RecordingInputSink()
        val fakeSets = ModelHost.defaultVideo().first
        val fakeFrames = ModelHost.defaultVideo().second
        val door = HostDoor(tempState(), "Test PC", port = 0, videoSourceFactory = { FakeVideoSource(fakeSets, fakeFrames, intervalMs = 10) }, inputSink = sink)
        door.use {
            val device = Identity.generate()

            // ask: the window opens, "shown" comes back.
            val askConn = SillTls.dial(loopback(door.boundPort), device, SillTls.PAIRING_ALPN, Trust.AnyKey)
            askConn.output.write(StreamMessage.json(Kind.PAIR_REQUEST, PairRequest(method = PairRequest.ASK, proof = "", name = "Test PC", model = "JVM").toJson()).serialized())
            askConn.output.flush()
            val askResult = PairResult.fromJson(JsonObj.parse(nextMessage(askConn).payload))
            assertEquals(PairResult.SHOWN, askResult.reason)
            askConn.close()

            val code = door.codeForTests() ?: error("no pairing window open after ask")
            val key = PairingProof.codeKey(code, door.fingerprint)!!
            val proof = PairingProof.deviceProof(key, device.fingerprint, door.fingerprint)

            val codeConn = SillTls.dial(loopback(door.boundPort), device, SillTls.PAIRING_ALPN, Trust.AnyKey)
            codeConn.output.write(StreamMessage.json(Kind.PAIR_REQUEST, PairRequest(method = PairRequest.CODE, proof = Base64Url.encode(proof), name = "Test PC", model = "JVM").toJson()).serialized())
            codeConn.output.flush()
            val paired = PairResult.fromJson(JsonObj.parse(nextMessage(codeConn).payload))
            assertTrue(paired.ok, "$paired")
            assertEquals(door.macID, paired.macID)
            codeConn.close()

            // sill/1, now pinned: hello first, then the catalog.
            class Rec : SessionListener {
                val connected = CountDownLatch(1)
                val frames = CopyOnWriteArrayList<ByteArray>()
                val formats = CopyOnWriteArrayList<ParameterSets>()
                var info: MacInfo? = null
                override fun onConnected(list: me.saffer.sill.core.WindowList) { connected.countDown() }
                override fun onVideoFormat(sets: ParameterSets) { formats.add(sets) }
                override fun onFrame(annexB: ByteArray, keyframe: Boolean, timestamp: Double) { frames.add(annexB) }
                override fun onMacInfo(info: MacInfo, fingerprint: ByteArray) { this.info = info }
            }
            val r = Rec()
            val sessionConn = SillTls.dial(loopback(door.boundPort), device, SillTls.SESSION_ALPN, Trust.Pinned(door.fingerprint))
            val info = ClientInfo("0.6.0", "test", "Test (JVM)", "PC", "JVM", "test-pc")
            val session = SillSession(sessionConn, info, r, Clock.System)
            session.start(null)
            assertTrue(r.connected.await(5, TimeUnit.SECONDS), "connected")
            run {
                val deadline = System.nanoTime() + 3_000_000_000
                while (r.info == null && System.nanoTime() < deadline) Thread.sleep(20)
            }
            assertEquals(door.macID, r.info?.macID)

            session.select(StreamSource.Desktop)
            val deadline = System.nanoTime() + 3_000_000_000
            while (r.frames.size < 3 && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(r.frames.isNotEmpty(), "no frames arrived")
            assertTrue(r.formats.isNotEmpty(), "no parameter sets arrived")

            // Input: a move at the centre of the fraction maps to the centre of the fake monitor.
            session.input(InputEvent.Pointer(PointerAction.MOVE, 0.5, 0.5))
            val moveDeadline = System.nanoTime() + 2_000_000_000
            while (sink.moves.isEmpty() && System.nanoTime() < moveDeadline) Thread.sleep(20)
            assertTrue(sink.moves.isNotEmpty(), "no move reached the input sink")
            val b = RobotInputSink.monitorBounds(0)
            assertEquals(RecordingInputSink.Move(b.x + b.width / 2, b.y + b.height / 2), sink.moves.last())

            session.close()
        }
    }

    private fun nextMessage(c: me.saffer.sill.core.Connection): StreamMessage =
        me.saffer.sill.core.MessageReader(c.input, cap = { StreamMessage.MAX_PAIRING_PAYLOAD.toLong() }).next() ?: error("no reply")
}
