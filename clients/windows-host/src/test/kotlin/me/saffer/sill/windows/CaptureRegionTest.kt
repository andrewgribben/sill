package me.saffer.sill.windows

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureRegionTest {
    // The PC's layout: a 4K primary, a 1080p to its left.
    private val monitors = listOf(Rectangle(0, 0, 3840, 2160), Rectangle(-1920, 300, 1920, 1080))

    @Test
    fun aWindowOnTheSecondMonitorIsRelativeToItAndEven() {
        val r = CaptureRegion.forWindow(-1500, 400, 801, 601, monitors)!!
        assertEquals(CaptureRegion(1, -1920, 300, 420, 100, 800, 600), r)
        assertEquals(-1500, r.screenX); assertEquals(400, r.screenY)
    }

    @Test
    fun aWindowPastTheEdgeIsClippedToItsMonitor() {
        assertEquals(CaptureRegion(0, 0, 0, 3000, 0, 840, 500), CaptureRegion.forWindow(3000, -20, 1000, 520, monitors))
    }

    @Test
    fun aWindowOffEveryMonitorOrEmptyHasNoRegion() {
        assertNull(CaptureRegion.forWindow(9000, 9000, 100, 100, monitors))
        assertNull(CaptureRegion.forWindow(0, 0, 0, 100, monitors))
    }

    @Test
    fun theDesktopIsTheWholeMonitor() {
        assertEquals(CaptureRegion(1, -1920, 300, 0, 0, 1920, 1080), CaptureRegion.desktop(1, monitors[1]))
    }
}
