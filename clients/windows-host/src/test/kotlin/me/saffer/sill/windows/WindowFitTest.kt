package me.saffer.sill.windows

import me.saffer.sill.core.Viewport
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowFitTest {
    @Test
    fun theScaleDividesThePanelAndTheDisplayScaleMultipliesIt() {
        // An iPad's 1180×820 panel at 1x on a 125 % display (120 dpi): 1475×1025 pixels.
        assertEquals(1475 to 1025, WindowFit.pixels(Viewport(1180.0, 820.0, 1.0), 120))
        // At 0.5x the window is twice as big (smaller text); at 2x half.
        assertEquals(2360 to 1640, WindowFit.pixels(Viewport(1180.0, 820.0, 0.5), 96))
        assertEquals(590 to 410, WindowFit.pixels(Viewport(1180.0, 820.0, 2.0), 96))
    }

    @Test
    fun aaOffAndAViewportOutsideTheBoundsAskForNothing() {
        assertNull(WindowFit.pixels(Viewport(1180.0, 820.0, null), 96))
        assertNull(WindowFit.pixels(Viewport(1e300, 820.0, 1.0), 96))
        assertNull(WindowFit.pixels(Viewport(1180.0, 820.0, 1e-300), 96))
        assertNull(WindowFit.pixels(Viewport(Double.NaN, 820.0, 1.0), 96))
        assertNull(WindowFit.pixels(Viewport(0.5, 820.0, 1.0), 96))
    }

    @Test
    fun aWindowStaysOnItsMonitor() {
        val m = Rectangle(0, 0, 3840, 2160)
        assertEquals(Rectangle(100, 100, 1475, 1025), WindowFit.placed(100, 100, 1475 to 1025, m))
        // Too far right and down: moved back on.
        assertEquals(Rectangle(2365, 1135, 1475, 1025), WindowFit.placed(3000, 1800, 1475 to 1025, m))
        // Larger than the monitor: the monitor.
        assertEquals(Rectangle(0, 0, 3840, 2160), WindowFit.placed(50, 50, 5000 to 3000, m))
        // A monitor left of the primary.
        assertEquals(Rectangle(-1920, 300, 800, 600), WindowFit.placed(-2500, 100, 800 to 600, Rectangle(-1920, 300, 1920, 1080)))
    }
}
