package me.saffer.sill.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinch to hide the bar, and a two-finger double tap, in front of the picture's touch. */
class ChromeTouchTest {
    private val fills = ArrayList<Boolean>()
    private val keys = ArrayList<String>()
    private val sent = ArrayList<String>()

    private fun touch() = ChromeTouch(10.0, { fills.add(it) }, { keys.add("keyboard") },
        { id, x, _, _ -> sent.add("down $id ${x.toInt()}") },
        { id, x, _, _ -> sent.add("move $id ${x.toInt()}") },
        { id, _, _, _ -> sent.add("up $id") },
        { sent.add("cancel") })

    @Test fun pinchOpenHidesTheBarOnce() {
        val t = touch()
        t.down(1, 100.0, 100.0, 0.0)
        t.down(2, 140.0, 100.0, 0.01)
        t.move(1, 80.0, 100.0, 0.05)
        t.move(2, 180.0, 100.0, 0.05)
        t.endSample(0.05)
        t.move(1, 60.0, 100.0, 0.08)
        t.endSample(0.08)
        t.up(1, 60.0, 100.0, 0.1)
        t.up(2, 180.0, 100.0, 0.1)
        assertEquals(listOf(true), fills)
        assertTrue(sent.contains("cancel"))
        assertTrue(sent.none { it.startsWith("move") })
    }

    @Test fun pinchClosedBringsTheBarBack() {
        val t = touch()
        t.down(1, 40.0, 100.0, 0.0)
        t.down(2, 200.0, 100.0, 0.01)
        t.move(1, 90.0, 100.0, 0.05)
        t.move(2, 150.0, 100.0, 0.05)
        t.endSample(0.05)
        assertEquals(listOf(false), fills)
    }

    @Test fun twoFingersMovingTogetherStillScroll() {
        val t = touch()
        t.down(1, 100.0, 100.0, 0.0)
        t.down(2, 140.0, 100.0, 0.01)
        t.move(1, 100.0, 140.0, 0.05)
        t.move(2, 140.0, 140.0, 0.05)
        t.endSample(0.05)
        t.move(1, 100.0, 180.0, 0.08)
        t.move(2, 140.0, 180.0, 0.08)
        t.endSample(0.08)
        assertEquals(emptyList<Boolean>(), fills)
        assertTrue(sent.contains("down 1 100"))
        assertTrue(sent.contains("down 2 140"))
        assertTrue(sent.any { it.startsWith("move 1") })
    }

    @Test fun pinchDoesNothingWhileTheSwitchIsOff() {
        val t = touch()
        t.pinchEnabled = false
        t.down(1, 100.0, 100.0, 0.0)
        t.down(2, 140.0, 100.0, 0.01)
        t.move(1, 40.0, 100.0, 0.05)
        t.move(2, 220.0, 100.0, 0.05)
        t.endSample(0.05)
        assertEquals(emptyList<Boolean>(), fills)
        assertTrue(sent.any { it.startsWith("down 2") })
    }

    @Test fun twoFingerDoubleTapShowsTheKeyboard() {
        val t = touch()
        t.keyboardEnabled = true
        tap(t, 0.0)
        tap(t, 0.2)
        assertEquals(listOf("keyboard"), keys)
        assertTrue(sent.none { it.startsWith("up") })
    }

    @Test fun doubleTapIsOffUntilTheSwitchIsOn() {
        val t = touch()
        tap(t, 0.0); tap(t, 0.2)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun doubleTapDoesNothingWithAHardwareKeyboard() {
        val t = touch()
        t.keyboardEnabled = true
        t.hardwareKeyboard = true
        tap(t, 0.0); tap(t, 0.2)
        assertEquals(emptyList<String>(), keys)
    }

    @Test fun oneFingerStillGoesThrough() {
        val t = touch()
        t.down(1, 10.0, 10.0, 0.0)
        t.move(1, 40.0, 10.0, 0.05)
        t.up(1, 40.0, 10.0, 0.1)
        assertEquals(listOf("down 1 10", "move 1 40", "up 1"), sent)
    }

    private fun tap(t: ChromeTouch, at: Double) {
        t.down(1, 80.0, 80.0, at)
        t.down(2, 120.0, 80.0, at + 0.01)
        t.up(2, 120.0, 80.0, at + 0.05)
        t.up(1, 80.0, 80.0, at + 0.06)
    }
}
