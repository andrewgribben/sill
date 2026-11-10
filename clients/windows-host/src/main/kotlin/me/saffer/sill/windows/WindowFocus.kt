package me.saffer.sill.windows

import me.saffer.sill.core.InputEvent
import me.saffer.sill.core.PointerAction

/**
 * The pure decision behind [HostDoor.HostSession]'s raise-before-input: whether an input event is
 * ever a reason to bring the streamed window to the front before posting it (a click, a key down
 * or text need the window on top to land there; a move or a scroll do not, since Windows routes a
 * wheel to whatever is under the pointer once it is moved there), and the throttle that keeps a
 * burst of keys or clicks to one bring-forward attempt, not one per event. Kept free of Win32/AWT
 * so it is tested on its own (WindowFocusTest); [HostDoor] is the only caller.
 *
 * The gap this closes (the same class as the Mac host's, see Sources/SillHost/RaisePolicy.swift):
 * [Win32.bringForward] used to run only from `select` (a device's pick), never from the input
 * path, so once another window took the front on the PC (the user's own click, or another
 * session's own `select`), this session's clicks, keys and text kept landing on it instead of the
 * window Sill is actually streaming, even though a Robot event is posted at the right pixels.
 */
object WindowFocus {
    /** One bring-forward attempt per 0.5 s, the Mac host's own raise-check interval. */
    const val INTERVAL_NANOS: Long = 500_000_000L

    fun needsFocus(e: InputEvent): Boolean = when (e) {
        is InputEvent.Pointer -> e.action == PointerAction.LEFT_DOWN || e.action == PointerAction.RIGHT_DOWN
        is InputEvent.Key -> e.down
        is InputEvent.Text -> true
        is InputEvent.Scroll -> false
        is InputEvent.ScrollGesture -> false
    }

    /** True at most once per [INTERVAL_NANOS] since `lastAttemptNanos` (java.lang.System.nanoTime
     * units); the clock itself lives in the caller (one per [HostDoor.HostSession]), not here. */
    fun shouldAttempt(lastAttemptNanos: Long, nowNanos: Long): Boolean = nowNanos - lastAttemptNanos > INTERVAL_NANOS
}
