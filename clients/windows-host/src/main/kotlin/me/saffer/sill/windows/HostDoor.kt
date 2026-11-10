package me.saffer.sill.windows

import me.saffer.sill.core.Base64Url
import me.saffer.sill.core.ClientStats
import me.saffer.sill.core.Clipboard
import me.saffer.sill.core.DisplayInfo
import me.saffer.sill.core.Goodbye
import me.saffer.sill.core.Hello
import me.saffer.sill.core.HostSettingsState
import me.saffer.sill.core.InputEvent
import me.saffer.sill.core.Json
import me.saffer.sill.core.JsonException
import me.saffer.sill.core.JsonObj
import me.saffer.sill.core.Kind
import me.saffer.sill.core.MacId
import me.saffer.sill.core.MacInfo
import me.saffer.sill.core.MacInfoSignature
import me.saffer.sill.core.MessageReader
import me.saffer.sill.core.PairRequest
import me.saffer.sill.core.PairResult
import me.saffer.sill.core.PairingCode
import me.saffer.sill.core.PairingProof
import me.saffer.sill.core.ParameterSets
import me.saffer.sill.core.SillProtocol
import me.saffer.sill.core.SillTls
import me.saffer.sill.core.SillTlsServer
import me.saffer.sill.core.Spki
import me.saffer.sill.core.StreamMessage
import me.saffer.sill.core.StreamSettings
import me.saffer.sill.core.StreamSource
import me.saffer.sill.core.Viewport
import me.saffer.sill.core.WindowInfo
import me.saffer.sill.core.WindowList
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocket

/**
 * The real home door: TLS on [port], all interfaces, pairing exactly as
 * clients/core's testFixtures `ModelHost` models it (which this class is adapted from; it does not
 * depend on testFixtures at runtime). The Mac's port, 7455, is reused so an unmodified iPhone/iPad
 * app finds this host wherever it would find a Mac.
 */
class HostDoor(
    private val state: HostState,
    private val pcName: String,
    private val port: Int = DEFAULT_PORT,
    /** One capture per region; swapped out by tests for [FakeVideoSource]. */
    private val videoSourceFactory: (CaptureRegion) -> VideoSource = { FfmpegVideoSource(it, wake = RobotInputSink::wake) },
    private val inputSink: InputSink = RobotInputSink(),
    private val pairingLifetimeSeconds: Double = 300.0,
    private val clipboard: HostClipboard? = HostClipboard(),
) : AutoCloseable {
    val identity get() = state.identity
    val fingerprint: ByteArray get() = identity.fingerprint
    val macID: String = MacId.make(identity.fingerprint)
    val launchID: String = state.launchID
    private val server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
    val boundPort get() = server.localPort
    @Volatile private var running = true

    // One pairing window at a time, as the Mac's is (PairingWindow.swift).
    @Volatile private var code: String? = null
    @Volatile private var secret: ByteArray? = null
    @Volatile private var triesLeft = 5
    @Volatile private var expiresAtNanos = 0L
    private val pairingWindow = PairingWindow(pcName) { cancelPairing() }
    private val pairingLock = Any()

    val sessions = CopyOnWriteArrayList<HostSession>()
    val violations = CopyOnWriteArrayList<String>()

    init {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (_: IOException) { break }
                Thread({ serve(s) }, "sill-door").apply { isDaemon = true }.start()
            }
        }, "sill-door-accept").apply { isDaemon = true }.start()
    }

    private fun serve(raw: java.net.Socket) {
        val ctx = SillTlsServer.context(identity) { fp, alpn ->
            fp != null && (alpn == SillTls.PAIRING_ALPN || state.isTrusted(fp))
        }
        val s = try {
            ctx.socketFactory.let { (it as javax.net.ssl.SSLSocketFactory).createSocket(raw, null, raw.port, true) } as SSLSocket
        } catch (e: IOException) { try { raw.close() } catch (_: IOException) {}; return }
        SillTlsServer.configure(s)
        try {
            s.startHandshake()
            s.soTimeout = 15_000
            if (s.applicationProtocol == SillTls.PAIRING_ALPN) pair(s) else HostSession(s).also { sessions.add(it) }.run()
        } catch (_: IOException) {
            try { s.close() } catch (_: IOException) {}
        }
    }

    // MARK: Pairing (sill-pair/1): one kind 19, one kind 20, then close.

    private fun openWindow(): String = synchronized(pairingLock) {
        val c = PairingCode.generate()
        code = c; secret = ByteArray(16).also(SecureRandom()::nextBytes); triesLeft = 5
        expiresAtNanos = System.nanoTime() + (pairingLifetimeSeconds * 1e9).toLong()
        val link = pairLink()
        Log.info("pairing window open: code ${PairingCode.grouped(c)}, link $link")
        pairingWindow.show(c)
        c
    }

    private fun pairLink(): String {
        val addrs = MdnsResponder.lanAddresses().map { me.saffer.sill.core.ParsedAddress(it.hostAddress, port, me.saffer.sill.core.ParsedAddress.Kind.IPV4) }
        return me.saffer.sill.core.PairLink(fingerprint, secret ?: ByteArray(16), pcName, port, addrs).url
    }

    private fun closeWindow(): Unit = synchronized(pairingLock) { code = null; secret = null; pairingWindow.close() }

    private fun cancelPairing(): Unit = closeWindow()

    /** The tray's Pair a Device…: the code window, for the device's Add a Mac by code or the link. */
    fun openPairing() { openWindow() }

    private fun expired() = synchronized(pairingLock) { code != null && System.nanoTime() > expiresAtNanos }

    /** TEST ONLY: the open pairing window's code, if any. */
    internal fun codeForTests(): String? = code

    private fun pair(s: SSLSocket) {
        s.use {
            val m = MessageReader(it.inputStream, cap = { StreamMessage.MAX_PAIRING_PAYLOAD.toLong() }).next() ?: return
            if (m.kind != Kind.PAIR_REQUEST) { violations.add("pairing connection's first message was kind ${m.kind}"); return }
            val req = try { PairRequest.fromJson(JsonObj.parse(m.payload)) } catch (e: JsonException) { return }
            val devFp = (it.session.peerCertificates[0] as java.security.cert.X509Certificate).let { c -> Spki.fingerprint(c.publicKey)!! }
            fun answer(r: PairResult) { it.outputStream.write(StreamMessage.json(Kind.PAIR_RESULT, r.toJson()).serialized()); it.outputStream.flush() }
            if (req.v != PairRequest.VERSION) return answer(PairResult(ok = false, reason = PairResult.CLOSED))
            if (expired()) closeWindow()
            when (req.method) {
                PairRequest.ASK -> {
                    if (state.isTrusted(devFp)) return answer(okResult(null, devFp))
                    if (code == null) openWindow()
                    answer(PairResult(ok = false, reason = PairResult.SHOWN))
                }
                PairRequest.CANCEL -> { closeWindow(); answer(PairResult(ok = false, reason = PairResult.CLOSED)) }
                PairRequest.CODE, PairRequest.QR -> {
                    val key = when {
                        req.method == PairRequest.CODE -> code?.let { c -> PairingProof.codeKey(c, fingerprint) }
                        else -> secret
                    } ?: return answer(PairResult(ok = false, reason = PairResult.CLOSED))
                    val proof = Base64Url.decode(req.proof)
                    if (proof == null || !PairingProof.isValidDeviceProof(proof, key, devFp, fingerprint)) {
                        synchronized(pairingLock) { triesLeft-- }
                        if (triesLeft <= 0) { closeWindow(); return answer(PairResult(ok = false, reason = PairResult.STOPPED)) }
                        return answer(PairResult(ok = false, reason = PairResult.CODE, triesLeft = triesLeft))
                    }
                    state.trust(devFp)
                    closeWindow()
                    Log.info("paired with ${MacId.shortName(devFp)} (${req.name})")
                    answer(okResult(key, devFp))
                }
                else -> answer(PairResult(ok = false, reason = PairResult.CLOSED))
            }
        }
    }

    private fun okResult(key: ByteArray?, devFp: ByteArray) = PairResult(ok = true,
        proof = key?.let { Base64Url.encode(PairingProof.macProof(it, fingerprint, devFp)) }, macID = macID, name = pcName,
        recognitionKey = Base64Url.encode(state.recognitionKey))

    fun macInfo(): me.saffer.sill.core.SignedMacInfo = MacInfoSignature.sign(
        MacInfo(macID = macID, name = pcName, issuedAt = System.currentTimeMillis() / 1e3, remoteAccess = false, remotePort = port, internet = false, addresses = emptyList()),
        identity.keyPair,
    )

    fun txt(): Map<String, String?> = mapOf(
        me.saffer.sill.core.RecognitionTag.TXT_KEY to me.saffer.sill.core.RecognitionTag.make(state.recognitionKey),
        me.saffer.sill.core.HomeDoorTxt.KEY to "1",
    )

    override fun close() {
        running = false
        try { server.close() } catch (_: IOException) {}
        sessions.forEach { it.close() }
    }

    /** One session connection (sill/1): hello, catalog, video, input, pings, goodbye. */
    inner class HostSession(private val s: SSLSocket) : HostClipboard.Subscriber {
        val deviceFingerprint: ByteArray? = (s.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate)?.let { Spki.fingerprint(it.publicKey) }
        @Volatile var hello: Hello? = null
        @Volatile var active: StreamSource = StreamSource.None
        private var activeMonitor = -1
        /** What the running capture covers, where input lands. */
        @Volatile private var region: CaptureRegion? = null
        private val out = s.outputStream
        @Volatile private var video: VideoSource? = null
        @Volatile private var videoBackedUp = false
        val received = CopyOnWriteArrayList<StreamMessage>()

        override fun send(c: Clipboard) = send(StreamMessage.json(Kind.CLIPBOARD, c.toJson()))

        @Synchronized fun send(m: StreamMessage) { try { out.write(m.serialized()); out.flush() } catch (_: IOException) {} }

        private fun monitorList(): List<DisplayInfo> {
            if (java.awt.GraphicsEnvironment.isHeadless()) return emptyList()
            val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            return screens.mapIndexed { i, d -> val b = d.defaultConfiguration.bounds; DisplayInfo(i.toLong(), d.iDstring, b.width, b.height) }
        }

        @Volatile private var windows: List<Win32.Window> = Win32.windows()

        fun list() = WindowList(
            macName = pcName, windows = windows.map { WindowInfo(it.id, it.title, it.exe.removeSuffix(".exe"), it.exe, it.width, it.height) }, active = active, launchID = launchID, hostVersion = "0.6.0",
            protocol = SillProtocol.CURRENT, gestures = 0, clipboard = if (clipboard != null) 1 else null,
            displays = monitorList().takeIf { it.size > 1 }, display = (activeMonitor.takeIf { it >= 0 } ?: 0).toLong(),
        )

        fun run() {
            val reader = MessageReader(s.inputStream, cap = { StreamMessage.MAX_CLIENT_PAYLOAD.toLong() })
            s.soTimeout = 0
            try {
                val first = reader.next() ?: return
                if (first.kind != Kind.HELLO) { violations.add("a session's first message was kind ${first.kind}, not the hello"); return }
                hello = Hello.fromJson(JsonObj.parse(first.payload))
                Log.info("session hello: $hello")
                received.add(first)
                send(StreamMessage.json(Kind.WINDOW_LIST, list().toJson()))
                send(StreamMessage.json(Kind.HOST_SETTINGS, HostSettingsState(
                    StreamSettings(60, 15_000_000, 1.0, false, false, false), persistent = true, virtualDisplayAvailable = false, softwareEncoder = false,
                ).toJson()))
                send(StreamMessage.json(Kind.MAC_INFO, macInfo().toJson()))
                if (Win32.available) watcher.start()
                while (true) {
                    val m = reader.next() ?: break
                    received.add(m)
                    when (m.kind) {
                        Kind.PING -> send(StreamMessage(Kind.PONG, 0.0, false, m.payload))
                        Kind.SELECT_SOURCE -> select(StreamSource.fromJson(JsonObj.parse(m.payload)))
                        Kind.INPUT -> handleInput(InputEvent.fromJson(JsonObj.parse(m.payload)))
                        Kind.VIEWPORT -> {}
                        Kind.CLIENT_STATS -> {}
                        Kind.KEYFRAME_REQUEST -> video?.requestKeyframe()
                        Kind.CLIPBOARD -> clipboard?.receive(this, Clipboard.fromJson(JsonObj.parse(m.payload)))
                        Kind.HELLO -> violations.add("a second hello")
                        else -> {}
                    }
                }
            } catch (_: IOException) {
            } finally {
                clipboard?.leave(this)
                video?.stop()
                videoSender.interrupt()
                watcher.interrupt()
                try { s.close() } catch (_: IOException) {}
                sessions.remove(this)
            }
        }

        private var lastViewport: Viewport? = null

        private fun handleInput(e: InputEvent) {
            val b = region ?: CaptureRegion.desktop(0, RobotInputSink.monitorBounds(0))
            fun px(x: Double) = (b.screenX + x * b.width).toInt()
            fun py(y: Double) = (b.screenY + y * b.height).toInt()
            when (e) {
                is InputEvent.Pointer -> when (e.action) {
                    me.saffer.sill.core.PointerAction.MOVE -> inputSink.moveTo(px(e.x), py(e.y))
                    me.saffer.sill.core.PointerAction.LEFT_DOWN -> { inputSink.moveTo(px(e.x), py(e.y)); inputSink.button(true, true) }
                    me.saffer.sill.core.PointerAction.LEFT_UP -> inputSink.button(true, false)
                    me.saffer.sill.core.PointerAction.RIGHT_DOWN -> { inputSink.moveTo(px(e.x), py(e.y)); inputSink.button(false, true) }
                    me.saffer.sill.core.PointerAction.RIGHT_UP -> inputSink.button(false, false)
                }
                // x, y say where the finger is: the wheel goes to the window under the pointer, so move it there first.
                is InputEvent.Scroll -> { inputSink.moveTo(px(e.x), py(e.y)); inputSink.scroll(e.dx, e.dy) }
                is InputEvent.Text -> inputSink.text(e.text)
                is InputEvent.Key -> inputSink.key(e.hidUsage, e.down, e.modifiers)
                is InputEvent.ScrollGesture -> {}
            }
        }

        @Synchronized private fun select(src: StreamSource) {
            // A device asks for the Desktop by itself and may ask again: keep the capture that runs.
            if (src == active && video != null) { send(StreamMessage.json(Kind.WINDOW_LIST, list().toJson())); return }
            video?.stop(); video = null
            region = when (src) {
                StreamSource.Desktop -> CaptureRegion.desktop(0, RobotInputSink.monitorBounds(0))
                is StreamSource.Window -> {
                    Win32.bringForward(src.id)
                    Thread.sleep(150)   // its restore and redraw
                    Win32.window(src.id)?.let { CaptureRegion.forWindow(it.x, it.y, it.width, it.height, RobotInputSink.monitors()) }
                }
                else -> null
            }
            active = if (region == null) StreamSource.None else src
            activeMonitor = region?.monitor ?: -1
            windows = Win32.windows()
            send(StreamMessage.json(Kind.WINDOW_LIST, list().toJson()))
            startCapture()
        }

        private fun startCapture() {
            val r = region ?: return
            val v = videoSourceFactory(r)
            video = v
            v.start(
                onParams = { sets -> queueVideo(StreamMessage(Kind.PARAMETER_SETS, now(), false, sets.encoded()), true) },
                onFrame = { key, au -> queueVideo(StreamMessage(Kind.FRAME, now(), key, au), key) },
            )
        }

        /** Every 1.5 s: a changed window list goes to the device, and the window streaming is followed
         * (moved or resized: the capture restarts on its new frame; closed or minimised: nothing streams). */
        private val watcher = Thread({
            try {
                while (true) {
                    Thread.sleep(1500)
                    val now = Win32.windows()
                    val src = active
                    var changed = now.map { Triple(it.id, it.title, it.width to it.height) } != windows.map { Triple(it.id, it.title, it.width to it.height) }
                    if (src is StreamSource.Window) synchronized(this) {
                        val w = now.firstOrNull { it.id == src.id }
                        val r = w?.let { CaptureRegion.forWindow(it.x, it.y, it.width, it.height, RobotInputSink.monitors()) }
                        if (r != region) {
                            Log.info(if (r == null) "the window streaming went (closed or minimised)" else "the window streaming moved: capturing ${r.width}×${r.height} again")
                            video?.stop(); video = null; region = r
                            if (r == null) active = StreamSource.None else startCapture()
                            changed = true
                        }
                    }
                    windows = now
                    if (changed) send(StreamMessage.json(Kind.WINDOW_LIST, list().toJson()))
                }
            } catch (_: InterruptedException) {}
        }, "sill-windows").apply { isDaemon = true }

        // Latency beats quality: video goes through a short queue drained by its own thread, so a slow
        // link never stalls ffmpeg's pipe. When it fills, frames are dropped until the next keyframe,
        // which (with its parameter sets) replaces whatever was still waiting.
        private val videoQueue = java.util.concurrent.ArrayBlockingQueue<StreamMessage>(VIDEO_QUEUE)
        private val videoSender = Thread({
            try { while (true) send(videoQueue.take()) } catch (_: InterruptedException) {}
        }, "sill-video-send").apply { isDaemon = true; start() }

        // One producer (ffmpeg's reader thread). Parameter sets are never dropped; frames are.
        internal fun queueVideo(m: StreamMessage, key: Boolean) {
            val sets = m.kind == Kind.PARAMETER_SETS
            if (videoBackedUp && !key && !sets) return
            if (videoBackedUp && key && !sets) { videoQueue.removeIf { it.kind == Kind.FRAME }; videoBackedUp = false }
            if (!videoQueue.offer(m)) {
                videoQueue.removeIf { it.kind == Kind.FRAME }
                videoBackedUp = sets || !key
                if (sets || key) videoQueue.offer(m)
            }
        }

        fun goodbye(g: Goodbye) { send(StreamMessage.json(Kind.GOODBYE, g.toJson())); close() }
        fun close() = try { s.close() } catch (_: IOException) {}
        private fun now() = System.currentTimeMillis() / 1e3
    }

    companion object {
        /** Sources/SillHost/HostConfig.swift's `defaultRemotePort`: the same port on both platforms. */
        const val DEFAULT_PORT = 7455
        /** About an eighth of a second of video at 60 fps. */
        const val VIDEO_QUEUE = 8
    }
}
