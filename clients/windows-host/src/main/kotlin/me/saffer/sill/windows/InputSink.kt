package me.saffer.sill.windows

/** Where input lands: pixel coordinates on the captured monitor, HID usages, text. A fake in tests
 * records what arrived; [RobotInputSink] drives java.awt.Robot. */
interface InputSink {
    fun moveTo(px: Int, py: Int)
    fun button(left: Boolean, down: Boolean)
    /** Point deltas as the device sends them (the Mac posts them as pixel wheel deltas). */
    fun scroll(dxPoints: Double, dyPoints: Double)
    fun key(hidUsage: Int, down: Boolean, modifiers: Long)
    /** Typed text ("\n", "\t", "\u0008" arrive as those keys upstream; anything else lands here). */
    fun text(s: String)
}

class RecordingInputSink : InputSink {
    data class Move(val x: Int, val y: Int)
    data class Button(val left: Boolean, val down: Boolean)
    data class Scroll(val dx: Double, val dy: Double)
    data class Key(val hidUsage: Int, val down: Boolean, val modifiers: Long)

    val moves = java.util.concurrent.CopyOnWriteArrayList<Move>()
    val buttons = java.util.concurrent.CopyOnWriteArrayList<Button>()
    val scrolls = java.util.concurrent.CopyOnWriteArrayList<Scroll>()
    val keys = java.util.concurrent.CopyOnWriteArrayList<Key>()
    val texts = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun moveTo(px: Int, py: Int) { moves.add(Move(px, py)) }
    override fun button(left: Boolean, down: Boolean) { buttons.add(Button(left, down)) }
    override fun scroll(dxPoints: Double, dyPoints: Double) { scrolls.add(Scroll(dxPoints, dyPoints)) }
    override fun key(hidUsage: Int, down: Boolean, modifiers: Long) { keys.add(Key(hidUsage, down, modifiers)) }
    override fun text(s: String) { texts.add(s) }
}
