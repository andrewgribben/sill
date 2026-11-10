package me.saffer.sill.windows

import me.saffer.sill.core.Clipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostClipboardTest {
    private class FakeBoard(var text: String? = null) : HostClipboard.Board {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private class Device : HostClipboard.Subscriber {
        val got = mutableListOf<String?>()
        override fun send(c: Clipboard) { got.add(c.text) }
    }

    @Test
    fun whatWasThereBeforeIsNeverSentOnlyWhatIsCopiedAfter() {
        val board = FakeBoard("old secret")
        val clip = HostClipboard(board)
        val ipad = Device()
        clip.receive(ipad, Clipboard())
        clip.look()
        assertTrue(ipad.got.isEmpty())
        board.text = "copied on the PC"
        clip.look(); clip.look()
        assertEquals(listOf<String?>("copied on the PC"), ipad.got)
    }

    @Test
    fun aDevicesTextBecomesTheClipboardAndGoesToTheOthersOnly() {
        val board = FakeBoard()
        val clip = HostClipboard(board)
        val ipad = Device(); val phone = Device()
        clip.receive(ipad, Clipboard()); clip.receive(phone, Clipboard())
        clip.receive(ipad, Clipboard(text = "from the iPad"))
        assertEquals("from the iPad", board.text)
        assertEquals(emptyList(), ipad.got)
        assertEquals(listOf<String?>("from the iPad"), phone.got)
        clip.look()
        assertEquals(1, phone.got.size)   // the look does not send it again
    }

    @Test
    fun stopAndLeaveEndTheSubscriptionAndOversizedTextIsDropped() {
        val board = FakeBoard()
        val clip = HostClipboard(board)
        val ipad = Device()
        clip.receive(ipad, Clipboard())
        clip.receive(ipad, Clipboard(stop = true))
        board.text = "after stop"; clip.look()
        assertTrue(ipad.got.isEmpty())
        clip.receive(ipad, Clipboard(text = "x".repeat(HostClipboard.MAX_TEXT_BYTES + 1)))
        assertEquals("after stop", board.text)
        clip.leave(ipad)
        board.text = "after leave"; clip.look()
        assertTrue(ipad.got.isEmpty())
    }
}
