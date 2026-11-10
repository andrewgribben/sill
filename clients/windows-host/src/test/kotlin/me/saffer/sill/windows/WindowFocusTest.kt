package me.saffer.sill.windows

import me.saffer.sill.core.InputEvent
import me.saffer.sill.core.PointerAction
import me.saffer.sill.core.ScrollPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WindowFocus (Sources... clients/windows-host): the pure decision behind HostDoor's
 * raiseBeforeInput, the Windows host's own version of the Mac's raiseIfInteracting gap. A click,
 * a key down or text needs the streamed window on top before it is posted; a move or a scroll do
 * not. The throttle is one attempt per 0.5 s, independent of any real Win32 call.
 */
class WindowFocusTest {
    private fun pointer(action: PointerAction) = InputEvent.Pointer(action, 0.5, 0.5)
    private fun key(down: Boolean) = InputEvent.Key(0x04, down, 0L)
    private val text = InputEvent.Text("a")
    private val scroll = InputEvent.Scroll(0.5, 0.5, 0.0, 1.0)
    private fun scrollGesture(phase: ScrollPhase) = InputEvent.ScrollGesture(phase, 0.5, 0.5)

    @Test fun aLeftOrRightDownNeedsFocus() {
        assertTrue(WindowFocus.needsFocus(pointer(PointerAction.LEFT_DOWN)))
        assertTrue(WindowFocus.needsFocus(pointer(PointerAction.RIGHT_DOWN)))
    }

    @Test fun aButtonUpOrAMoveDoesNotNeedFocus() {
        assertFalse(WindowFocus.needsFocus(pointer(PointerAction.LEFT_UP)))
        assertFalse(WindowFocus.needsFocus(pointer(PointerAction.RIGHT_UP)))
        assertFalse(WindowFocus.needsFocus(pointer(PointerAction.MOVE)))
    }

    @Test fun aKeyDownNeedsFocusAKeyUpDoesNot() {
        assertTrue(WindowFocus.needsFocus(key(true)))
        assertFalse(WindowFocus.needsFocus(key(false)))
    }

    @Test fun textNeedsFocus() = assertTrue(WindowFocus.needsFocus(text))

    @Test fun aScrollOrAScrollGestureNeverNeedsFocus() {
        assertFalse(WindowFocus.needsFocus(scroll))
        for (phase in ScrollPhase.entries) assertFalse(WindowFocus.needsFocus(scrollGesture(phase)))
    }

    @Test fun oneAttemptPer500ms() {
        val base = 1_000_000_000L
        assertTrue(WindowFocus.shouldAttempt(lastAttemptNanos = 0, nowNanos = base), "the first attempt always runs")
        assertFalse(WindowFocus.shouldAttempt(base, base + 100_000_000L), "a second attempt inside 0.5 s is throttled")
        assertFalse(WindowFocus.shouldAttempt(base, base + 500_000_000L), "exactly at the boundary is still throttled (strict >)")
        assertTrue(WindowFocus.shouldAttempt(base, base + 500_000_001L), "an attempt past 0.5 s runs again")
    }

    @Test fun theIntervalMatchesTheMacHostsOwnRaiseCheck() = assertEquals(500_000_000L, WindowFocus.INTERVAL_NANOS)
}
