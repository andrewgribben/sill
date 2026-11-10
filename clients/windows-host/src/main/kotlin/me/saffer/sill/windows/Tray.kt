package me.saffer.sill.windows

import java.awt.Color
import java.awt.Desktop
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File

/**
 * The notification area icon: the host runs windowless (javaw), so this is how the person sees it
 * runs and stops it. Its menu: who is connected, Pair a Device…, Show Log, Quit Sill.
 * shortcut: the icon is drawn here (a rounded square with a window sill); upgrade with the app's own.
 */
class Tray(private val pcName: String, private val logFile: File, private val onPair: () -> Unit,
           private val connected: () -> List<String>, private val onQuit: () -> Unit) {
    private var icon: TrayIcon? = null
    private val status = MenuItem("Sill is running").apply { isEnabled = false }

    fun show() {
        if (!SystemTray.isSupported()) { Log.warn("no notification area: Sill runs without its icon"); return }
        val menu = PopupMenu()
        menu.add(status)
        menu.addSeparator()
        menu.add(MenuItem("Pair a Device…").apply { addActionListener { onPair() } })
        menu.add(MenuItem("Show Log").apply { addActionListener { showLog() } })
        menu.addSeparator()
        menu.add(MenuItem("Quit Sill").apply { addActionListener { onQuit() } })
        val t = TrayIcon(drawIcon(), "Sill on $pcName", menu).apply { isImageAutoSize = true }
        // Right-click opens the menu; refresh who is connected just before it can show.
        t.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = refresh()
        })
        try { SystemTray.getSystemTray().add(t); icon = t; refresh() } catch (e: Exception) { Log.warn("couldn't add the notification area icon: ${e.message}") }
    }

    fun refresh() {
        val who = connected()
        status.label = when (who.size) { 0 -> "Sill is running: no device connected"; 1 -> "Connected: ${who[0]}"; else -> "Connected: ${who.size} devices" }
        icon?.toolTip = "Sill on $pcName" + if (who.isEmpty()) "" else " (${who.joinToString()})"
    }

    fun remove() { icon?.let { try { SystemTray.getSystemTray().remove(it) } catch (_: Exception) {} } }

    private fun showLog() {
        try { Desktop.getDesktop().open(logFile) } catch (e: Exception) {
            try { ProcessBuilder("notepad.exe", logFile.absolutePath).start() } catch (_: Exception) { Log.warn("couldn't open the log: ${e.message}") }
        }
    }

    private fun drawIcon(): BufferedImage {
        val n = 64
        val img = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x2F, 0x6F, 0xEB); g.fillRoundRect(2, 2, n - 4, n - 4, 16, 16)
        g.color = Color.WHITE
        g.fillRoundRect(13, 12, n - 26, 30, 6, 6)          // the window
        g.fillRoundRect(8, 46, n - 16, 7, 4, 4)            // its sill
        g.color = Color(0x2F, 0x6F, 0xEB); g.fillRect(n / 2 - 2, 12, 4, 30); g.fillRect(13, 25, n - 26, 4)
        g.dispose()
        return img
    }
}
