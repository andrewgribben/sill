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

    // In 120ths of a notch: 40 points a notch, as before, but every point goes, so it scrolls smoothly.
    private val wheel = WheelCarry(40.0 / 120, limit = 6000)
    private val sideways = WheelCarry(40.0 / 120, limit = 6000)

    override fun scroll(dxPoints: Double, dyPoints: Double) {
        // A device's positive delta moves the content down (or right): Windows' wheel up (or left).
        Win32.wheel(wheel.notches(dyPoints), horizontal = false)
        Win32.wheel(-sideways.notches(dxPoints), horizontal = true)
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
        private val wakeRobot: Robot? by lazy { try { Robot() } catch (_: Throwable) { null } }

        /** Wakes a sleeping display and ends a screen saver (whose own desktop capture is refused on). */
        fun wake() {
            // Synthetic input never reaches the screen saver's desktop, so end it: it runs as this user.
            // A secure one ("On resume, display logon screen") then shows the lock screen, as at the PC.
            ProcessHandle.allProcesses().filter { it.info().command().orElse("").endsWith(".scr", ignoreCase = true) }
                .forEach { Log.info("ending the screen saver (${it.info().command().orElse("")}) so the desktop can be captured"); it.destroy() }
            val r = wakeRobot ?: return
            val at = try { java.awt.MouseInfo.getPointerInfo()?.location } catch (_: Throwable) { null }
            if (at == null) return
            r.mouseMove(at.x + 1, at.y); r.mouseMove(at.x, at.y)
        }


        /** Every monitor's bounds, in the order ddagrab's output_idx counts them (shortcut: assumed, as
         * the Desktop's capture always has; check when a second monitor is on). */
        fun monitors(): List<java.awt.Rectangle> =
            if (GraphicsEnvironment.isHeadless()) listOf(java.awt.Rectangle(0, 0, 1920, 1080))
            else try { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }
            catch (_: Exception) { listOf(java.awt.Rectangle(0, 0, 1920, 1080)) }

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

/**
 * A device's scroll arrives as small point deltas; the wheel takes whole units (notches, or 120ths
 * of one). The remainder carries into the next delta (as the Mac's host carries sub-point
 * remainders), so a slow drag still scrolls.
 */
class WheelCarry(private val pointsPerNotch: Double = 40.0, private val limit: Int = 50) {
    private var carry = 0.0

    fun notches(points: Double): Int {
        if (!points.isFinite()) return 0
        val exact = carry + points
        val whole = (exact / pointsPerNotch).toInt().coerceIn(-limit, limit)
        carry = (exact - whole * pointsPerNotch).let { if (kotlin.math.abs(it) >= pointsPerNotch) 0.0 else it }   // after a clamp
        return whole
    }
}
