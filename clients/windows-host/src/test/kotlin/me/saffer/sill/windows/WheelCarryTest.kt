package me.saffer.sill.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelCarryTest {
    @Test
    fun slowDragsAddUpToNotches() {
        val w = WheelCarry(40.0)
        // Forty 3-point steps are 120 points: three notches, none lost to rounding.
        assertEquals(3, (1..40).sumOf { w.notches(3.0) })
        assertEquals(-2, (1..20).sumOf { w.notches(-4.0) })
    }

    @Test
    fun hugeOrMalformedDeltasAreHeld() {
        val w = WheelCarry(40.0)
        assertEquals(50, w.notches(1e9)); assertEquals(0, w.notches(Double.NaN)); assertEquals(0, w.notches(1.0))
    }
}
