package me.saffer.sill.windows

import java.awt.AWTException
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent as AwtInputEvent
import java.awt.event.KeyEvent

/**
 * Drives java.awt.Robot: the real input sink. Run the JVM with `-Dsun.java2d.uiScale=1` so Robot's
 * coordinates are physical pixels even on a scaled display (shortcut: not verified on a real HiDPI
 * Windows box by this task; check with a monitor above 100% scaling before relying on it).
 */
class RobotInputSink : InputSink {
    private val robot: Robot? = try { Robot() } catch (e: Exception) { Log.error("Robot unavailable (no interactive desktop session?)", e); null }
        catch (e: Error) { Log.error("Robot unavailable", e); null }   // java.awt.HeadlessException is an Error subtype on some JDKs

    init { robot?.isAutoWaitForIdle = false }

    override fun moveTo(px: Int, py: Int) { robot?.mouseMove(px, py) }

    override fun button(left: Boolean, down: Boolean) {
        val mask = if (left) AwtInputEvent.BUTTON1_DOWN_MASK else AwtInputEvent.BUTTON3_DOWN_MASK
        if (down) robot?.mousePress(mask) else robot?.mouseRelease(mask)
    }

    override fun scroll(dxPixels: Int, dyPixels: Int) {
        // Robot only scrolls vertically; a horizontal component is dropped (shortcut: Shift+wheel
        // is Windows' usual trick for horizontal scroll, not implemented in phase 1).
        if (dyPixels != 0) robot?.mouseWheel(-(dyPixels / WHEEL_PIXELS_PER_CLICK).coerceIn(-50, 50))
    }

    override fun key(hidUsage: Int, down: Boolean, modifiers: Long) {
        val vk = HidToAwt.vkFor(hidUsage) ?: run { Log.warn("no PC key for HID usage 0x${hidUsage.toString(16)}"); return }
        if (down) HidToAwt.modifierVks(modifiers).forEach { try { robot?.keyPress(it) } catch (_: Exception) {} }
        try { if (down) robot?.keyPress(vk) else robot?.keyRelease(vk) } catch (_: Exception) {}
        if (!down) HidToAwt.modifierVks(modifiers).reversed().forEach { try { robot?.keyRelease(it) } catch (_: Exception) {} }
    }

    override fun text(s: String) {
        for (ch in s) {
            if (ch.code > 0x7F) { Log.warn("no clipboard-free way to type U+${ch.code.toString(16)}; skipped"); continue }
            val extended = KeyEvent.getExtendedKeyCodeForChar(ch.code)
            if (extended == KeyEvent.VK_UNDEFINED) continue
            val needsShift = ch.isUpperCase() || ch in "~!@#$%^&*()_+{}|:\"<>?"
            try {
                if (needsShift) robot?.keyPress(KeyEvent.VK_SHIFT)
                robot?.keyPress(extended); robot?.keyRelease(extended)
                if (needsShift) robot?.keyRelease(KeyEvent.VK_SHIFT)
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val WHEEL_PIXELS_PER_CLICK = 40

        fun monitorBounds(index: Int): java.awt.Rectangle {
            if (GraphicsEnvironment.isHeadless()) return java.awt.Rectangle(0, 0, 1920, 1080)   // tests, and SSH with no desktop session
            return try {
                val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                val d = screens.getOrNull(index) ?: screens.first()
                d.defaultConfiguration.bounds
            } catch (_: Exception) { java.awt.Rectangle(0, 0, 1920, 1080) }
        }
    }
}
