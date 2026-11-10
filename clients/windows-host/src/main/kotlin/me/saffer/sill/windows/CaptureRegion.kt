package me.saffer.sill.windows

import java.awt.Rectangle

/**
 * What a capture covers: monitor [monitor] (its top-left at [monitorX], [monitorY] on the virtual
 * desktop), and within it [x], [y], [width] × [height], in physical pixels, sizes even (HEVC 4:2:0).
 * A device's input lands as the same fraction of this rectangle.
 */
data class CaptureRegion(val monitor: Int, val monitorX: Int, val monitorY: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
    val screenX get() = monitorX + x
    val screenY get() = monitorY + y

    companion object {
        fun desktop(monitor: Int, bounds: Rectangle) =
            CaptureRegion(monitor, bounds.x, bounds.y, 0, 0, bounds.width and 1.inv(), bounds.height and 1.inv())

        /**
         * A window's frame, on the monitor holding its centre, clipped to it.
         * shortcut: the window's part of the screen is captured, not the window itself, so whatever covers
         * it shows (it is brought forward when picked) and a window across two monitors shows one's part;
         * upgrade with Windows.Graphics.Capture's window capture, which ffmpeg has no grabber for.
         */
        fun forWindow(left: Int, top: Int, width: Int, height: Int, monitors: List<Rectangle>): CaptureRegion? {
            if (monitors.isEmpty() || width <= 0 || height <= 0) return null
            val cx = left + width / 2; val cy = top + height / 2
            val i = monitors.indexOfFirst { it.contains(cx, cy) }.takeIf { it >= 0 }
                ?: monitors.indices.maxBy { monitors[it].intersection(Rectangle(left, top, width, height)).let { r -> if (r.isEmpty) 0L else r.width.toLong() * r.height } }
            val m = monitors[i]
            val clip = m.intersection(Rectangle(left, top, width, height))
            if (clip.isEmpty || clip.width < 2 || clip.height < 2) return null
            return CaptureRegion(i, m.x, m.y, clip.x - m.x, clip.y - m.y, clip.width and 1.inv(), clip.height and 1.inv())
        }
    }
}
