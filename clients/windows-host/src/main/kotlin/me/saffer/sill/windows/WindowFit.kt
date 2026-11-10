package me.saffer.sill.windows

import me.saffer.sill.core.Viewport
import java.awt.Rectangle
import kotlin.math.roundToInt

/** Aa, as the Mac's host: a streamed window sized to the device's panel divided by its text scale. */
object WindowFit {
    /**
     * The window's frame in physical pixels for [v] on a window at [dpi] (96 = 100 %), or null when
     * the device asks for no fit (Aa Off: no scale) or sends a viewport outside the Mac's bounds
     * (each side 1 to 16384 points, a scale 0.25 to 4: ViewportBounds), which is refused whole.
     */
    fun pixels(v: Viewport, dpi: Int): Pair<Int, Int>? {
        val s = v.scale ?: return null
        if (!(v.width in 1.0..16384.0 && v.height in 1.0..16384.0 && s in 0.25..4.0) || dpi !in 48..960) return null
        return (v.width / s * dpi / 96).roundToInt() to (v.height / s * dpi / 96).roundToInt()
    }

    /** [want] (width, height) placed at [x], [y], held inside [monitor]: smaller if it must be, moved back onto it. */
    fun placed(x: Int, y: Int, want: Pair<Int, Int>, monitor: Rectangle): Rectangle {
        val w = want.first.coerceIn(1, monitor.width); val h = want.second.coerceIn(1, monitor.height)
        return Rectangle(x.coerceIn(monitor.x, monitor.x + monitor.width - w), y.coerceIn(monitor.y, monitor.y + monitor.height - h), w, h)
    }
}
