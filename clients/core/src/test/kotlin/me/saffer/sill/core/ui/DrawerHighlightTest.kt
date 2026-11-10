package me.saffer.sill.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

/** DrawerHighlight, as the iOS app's checks keep it (Tests/checks/drawer-highlight). */
class DrawerHighlightTest {
    @Test
    fun afterFilterChange() {
        assertEquals(null, DrawerHighlight.afterFilterChange(0))
        assertEquals(0, DrawerHighlight.afterFilterChange(1))
        assertEquals(null, DrawerHighlight.afterFilterChange(2))
        assertEquals(null, DrawerHighlight.afterFilterChange(50))
    }

    @Test
    fun move() {
        // From none, both Down and Up land on the first row.
        assertEquals(0, DrawerHighlight.move(DrawerHighlight.Key.Down, null, 5))
        assertEquals(0, DrawerHighlight.move(DrawerHighlight.Key.Up, null, 5))
        // An empty list highlights nothing, whatever the key or the starting point.
        assertEquals(null, DrawerHighlight.move(DrawerHighlight.Key.Down, null, 0))
        assertEquals(null, DrawerHighlight.move(DrawerHighlight.Key.Down, 2, 0))
        // Clamped at both ends.
        assertEquals(4, DrawerHighlight.move(DrawerHighlight.Key.Down, 3, 5))
        assertEquals(4, DrawerHighlight.move(DrawerHighlight.Key.Down, 4, 5))
        assertEquals(0, DrawerHighlight.move(DrawerHighlight.Key.Up, 1, 5))
        assertEquals(0, DrawerHighlight.move(DrawerHighlight.Key.Up, 0, 5))
    }

    /** 2,000 random walks of Up/Down against a shrinking and growing count stay in bounds (the
     *  drawer resets the highlight itself on a filter change: modelled here as the same reset). */
    @Test
    fun randomWalksStayInBounds() {
        val random = Random(0xA17)
        repeat(2_000) {
            var current: Int? = null
            var count = random.nextInt(12)
            repeat(40) {
                val key = if (random.nextBoolean()) DrawerHighlight.Key.Down else DrawerHighlight.Key.Up
                current = DrawerHighlight.move(key, current, count)
                val c = current
                if (c != null) assertTrue(c in 0 until count, "in bounds (count $count, got $c)")
                else assertEquals(0, count, "none only with an empty list")
                count = random.nextInt(12)
                val afterReset = current
                if (afterReset != null && afterReset >= count) current = null
            }
        }
    }
}
