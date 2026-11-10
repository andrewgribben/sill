package me.saffer.sill.windows

import me.saffer.sill.core.Clipboard
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The clipboard, the host's side (kind 32), as the Mac's HostClipboard.swift does it with text only:
 * a device subscribes with any kind 32 and stops with `stop`; while one subscribes the clipboard is
 * looked at four times a second, and what is copied then (never what was there when the first
 * subscribed) goes to every subscriber. A device's text becomes the clipboard and goes to every
 * other subscriber at once; the look never sends it back. Never logs what was copied.
 */
class HostClipboard(private val board: Board = SystemBoard) {
    interface Board { fun read(): String?; fun write(text: String) }

    /** Whoever can be sent a kind 32. */
    fun interface Subscriber { fun send(c: Clipboard) }

    private val subscribers = CopyOnWriteArraySet<Subscriber>()
    @Volatile private var lastSeen: String? = null
    private val lock = Any()
    @Volatile private var looking: Thread? = null

    fun receive(from: Subscriber, c: Clipboard) {
        if (c.stop == true) { subscribers.remove(from); return }
        synchronized(lock) {
            if (subscribers.add(from)) Log.info("clipboard: a device shares the clipboard")
            if (looking == null) { lastSeen = read(); startLooking() }
        }
        val text = c.text ?: return
        if (text.isEmpty() || text.toByteArray().size > MAX_TEXT_BYTES) return
        try { board.write(text) } catch (e: Exception) { Log.warn("clipboard: couldn't write a device's text: ${e.javaClass.simpleName}"); return }
        lastSeen = text
        for (s in subscribers) if (s !== from) s.send(Clipboard(text = text))
    }

    fun leave(s: Subscriber) { subscribers.remove(s) }

    /** One look: sends what changed since the last. Public for tests. */
    fun look() {
        if (subscribers.isEmpty()) return
        val now = read() ?: return
        if (now == lastSeen) return
        lastSeen = now
        if (now.isEmpty() || now.toByteArray().size > MAX_TEXT_BYTES) return
        for (s in subscribers) s.send(Clipboard(text = now))
    }

    private fun read(): String? = try { board.read() } catch (_: Exception) { null }   // busy, or not text

    private fun startLooking() {
        looking = Thread({
            while (true) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                look()
            }
        }, "sill-clipboard").apply { isDaemon = true; start() }
    }

    /** The PC's clipboard through AWT. */
    object SystemBoard : Board {
        private val clipboard by lazy { Toolkit.getDefaultToolkit().systemClipboard }
        override fun read(): String? =
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
        override fun write(text: String) { val s = StringSelection(text); clipboard.setContents(s, s) }
    }

    companion object {
        /** The Mac's ClipboardMessage.maxTextBytes: 256 KB of UTF-8 either way. */
        const val MAX_TEXT_BYTES = 256 * 1024
    }
}
