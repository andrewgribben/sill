package me.saffer.sill.android

import kotlin.math.hypot

/**
 * Pinch to hide the bar, and a two-finger double tap for the keyboard, in front of the picture's
 * own touch (TouchGestures). A two-finger scroll is left alone until the fingers have clearly
 * spread or closed. One decision per pinch. A stationary two-finger tap is not a click.
 */
class ChromeTouch(
    private val slopPx: Double,
    private val onFill: (Boolean) -> Unit,
    private val onKeyboard: () -> Unit,
    private val forwardDown: (Int, Double, Double, Double) -> Unit,
    private val forwardMove: (Int, Double, Double, Double) -> Unit,
    private val forwardUp: (Int, Double, Double, Double) -> Unit,
    private val forwardCancel: () -> Unit,
) {
    var pinchEnabled = true
    var keyboardEnabled = false
    /** A hardware keyboard is attached: the double tap does nothing. */
    var hardwareKeyboard = false

    private val pointers = LinkedHashMap<Int, Pt>()
    private val origins = HashMap<Int, Pt>()
    private var span0 = 0.0
    private var held = false
    private var solo = false
    private var scrolling = false
    private var travelled = false
    private var decided = false
    private var lastTap = 0.0

    fun down(id: Int, x: Double, y: Double, t: Double) {
        pointers[id] = Pt(x, y)
        origins[id] = Pt(x, y)
        if (decided) return
        when (pointers.size) {
            1 -> {
                solo = true
                held = false
                forwardDown(id, x, y, t)
            }
            2 -> {
                if (solo) { forwardCancel(); solo = false }
                held = true
                scrolling = false
                travelled = false
                span0 = span().coerceAtLeast(1.0)
            }
            else -> {
                if (held) { held = false; replay(t) }
                forwardDown(id, x, y, t)
            }
        }
    }

    fun move(id: Int, x: Double, y: Double, t: Double) {
        if (id in pointers) pointers[id] = Pt(x, y)
        if (decided) return
        if (!held && (scrolling || solo)) forwardMove(id, x, y, t)
    }

    /** After every pointer in one move has been updated, so a scroll is not read as a pinch halfway. */
    fun endSample(t: Double) {
        if (decided || pointers.size < 2 || span0 <= 0.0) return
        val scale = span() / span0
        if (pinchEnabled && (scale >= OPEN || scale <= CLOSE)) {
            decide(scale >= OPEN)
            return
        }
        if (held && travelled()) {
            lastTap = 0.0
            held = false
            scrolling = true
            replay(t)
        }
    }

    fun up(id: Int, x: Double, y: Double, t: Double) {
        pointers.remove(id)
        origins.remove(id)
        if (decided) {
            if (pointers.isEmpty()) resetPinch()
            return
        }
        if (held) {
            if (pointers.isEmpty()) {
                if (!travelled) noteTap(t)
                held = false
                travelled = false
                span0 = 0.0
            }
            return
        }
        if (scrolling || solo) forwardUp(id, x, y, t)
        if (pointers.isEmpty()) { scrolling = false; solo = false }
    }

    fun cancel() {
        pointers.clear(); origins.clear()
        held = false; solo = false; scrolling = false; travelled = false
        resetPinch()
        forwardCancel()
    }

    private fun decide(open: Boolean) {
        decided = true
        held = false
        scrolling = false
        solo = false
        lastTap = 0.0
        forwardCancel()
        onFill(open)
    }

    private fun resetPinch() {
        decided = false
        span0 = 0.0
    }

    private fun replay(t: Double) {
        for ((pid, p) in pointers) forwardDown(pid, p.x, p.y, t)
    }

    private fun noteTap(t: Double) {
        if (!keyboardEnabled || hardwareKeyboard) { lastTap = 0.0; return }
        if (lastTap > 0.0 && t - lastTap <= TAP_GAP) {
            lastTap = 0.0
            onKeyboard()
        } else lastTap = t
    }

    private fun span(): Double {
        val pts = pointers.values.toList()
        if (pts.size < 2) return 0.0
        return hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y)
    }

    private fun travelled(): Boolean {
        if (travelled) return true
        for ((id, p) in pointers) {
            val o = origins[id] ?: continue
            if (hypot(p.x - o.x, p.y - o.y) > slopPx) { travelled = true; return true }
        }
        return false
    }

    private data class Pt(val x: Double, val y: Double)

    companion object {
        /** The same band as the iPad: open at 1.28, closed at 0.78, one decision per gesture. */
        const val OPEN = 1.28
        const val CLOSE = 0.78
        const val TAP_GAP = 0.4
    }
}
