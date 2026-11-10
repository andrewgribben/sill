package me.saffer.sill.windows

import me.saffer.sill.core.PairingCode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Label
import java.awt.Panel
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The pairing window shown on the Windows screen while a code is open (the AWT equivalent of the
 * Mac's "Pair a Device…" window): the PC's name, the 12-digit code in big grouped digits, and a
 * Cancel button. Always-on-top so it is seen even if another window has focus. Headless-safe: if
 * AWT cannot make a window (no display, e.g. over SSH with no desktop session), it logs instead and
 * [show]/[close] become no-ops, since Robot and real capture need an interactive desktop anyway.
 */
class PairingWindow(private val pcName: String, private val onCancel: () -> Unit) {
    @Volatile private var frame: Frame? = null

    fun show(code: String) {
        Log.info("pairing code for $pcName: ${PairingCode.grouped(code)} (sill://pair link logged by the caller)")
        if (GraphicsEnvironment.isHeadless()) { Log.warn("no display: the pairing window cannot be shown here"); return }
        try {
            close()
            val f = Frame("Sill")
            f.layout = BorderLayout(0, 12)
            f.background = Color(24, 24, 27)
            f.isAlwaysOnTop = true
            f.isResizable = false

            val title = Label("Pair with $pcName", Label.CENTER)
            title.font = Font("SansSerif", Font.PLAIN, 18)
            title.foreground = Color.WHITE
            f.add(title, BorderLayout.NORTH)

            val codeLabel = Label(PairingCode.grouped(code), Label.CENTER)
            codeLabel.font = Font("Monospaced", Font.BOLD, 48)
            codeLabel.foreground = Color.WHITE
            f.add(codeLabel, BorderLayout.CENTER)

            val buttons = Panel(FlowLayout(FlowLayout.CENTER))
            val cancel = java.awt.Button("Cancel")
            cancel.addActionListener(ActionListener { onCancel(); close() })
            buttons.add(cancel)
            f.add(buttons, BorderLayout.SOUTH)

            f.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) { onCancel(); close() }
            })
            f.setSize(420, 260)
            f.setLocationRelativeTo(null)
            f.isVisible = true
            frame = f
        } catch (e: Exception) {
            Log.warn("could not show the pairing window: ${e.message}")
        }
    }

    fun close() {
        frame?.let { try { it.dispose() } catch (_: Exception) {} }
        frame = null
    }
}

// setLocationRelativeTo isn't on java.awt.Frame before placing; this is a small helper for it.
private fun Frame.setLocationRelativeTo(ignored: Any?) {
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    setLocation((screen.width - 420) / 2, (screen.height - 260) / 2)
}
