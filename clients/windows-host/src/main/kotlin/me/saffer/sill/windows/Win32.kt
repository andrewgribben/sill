package me.saffer.sill.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/** The few Win32 calls the JDK has no API for, through Java 22's FFM. Every call is a no-op off Windows. */
object Win32 {
    val available = FfmpegVideoSource.isWindows()

    /** A top-level window: its handle (32 bits on every Windows), title, exe, and frame in physical pixels. */
    data class Window(val id: Long, val title: String, val exe: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private val linker by lazy { Linker.nativeLinker() }
    private val user32 by lazy { SymbolLookup.libraryLookup("user32", Arena.global()) }
    private val dwmapi by lazy { SymbolLookup.libraryLookup("dwmapi", Arena.global()) }
    private fun fn(lib: SymbolLookup, name: String, desc: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lib.find(name).orElseThrow(), desc)

    private val getTopWindow by lazy { fn(user32, "GetTopWindow", FunctionDescriptor.of(ADDRESS, ADDRESS)) }
    private val getWindow by lazy { fn(user32, "GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    private val isWindowVisible by lazy { fn(user32, "IsWindowVisible", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val isWindow by lazy { fn(user32, "IsWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val isIconic by lazy { fn(user32, "IsIconic", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val getWindowTextW by lazy { fn(user32, "GetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) }
    private val getWindowLongPtrW by lazy { fn(user32, "GetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT)) }
    private val getWindowThreadProcessId by lazy { fn(user32, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
    private val setForegroundWindow by lazy { fn(user32, "SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val showWindow by lazy { fn(user32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val keybdEvent by lazy { fn(user32, "keybd_event", FunctionDescriptor.ofVoid(JAVA_BYTE, JAVA_BYTE, JAVA_INT, JAVA_LONG)) }
    private val mouseEvent by lazy { fn(user32, "mouse_event", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)) }
    private val dwmGetWindowAttribute by lazy { fn(dwmapi, "DwmGetWindowAttribute", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)) }

    private const val GW_HWNDNEXT = 2
    private const val GW_OWNER = 4
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TOOLWINDOW = 0x80L
    private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9
    private const val DWMWA_CLOAKED = 14

    private fun hwnd(id: Long) = MemorySegment.ofAddress(id)

    /** The windows a person would switch to, front to back (Alt+Tab's rule, roughly). */
    fun windows(): List<Window> {
        if (!available) return emptyList()
        val out = mutableListOf<Window>()
        Arena.ofConfined().use { a ->
            val text = a.allocate(JAVA_CHAR, 512)
            val pid = a.allocate(JAVA_INT)
            var h = getTopWindow.invokeExact(MemorySegment.NULL) as MemorySegment
            var guard = 0
            while (h.address() != 0L && guard++ < 4096) {
                if (switchable(h, a)) {
                    val n = getWindowTextW.invokeExact(h, text, 512) as Int
                    val r = frame(h, a)
                    if (n > 0 && r != null && r[2] > r[0] && r[3] > r[1]) {
                        getWindowThreadProcessId.invokeExact(h, pid) as Int
                        val exe = ProcessHandle.of(pid.get(JAVA_INT, 0).toLong()).flatMap { it.info().command() }
                            .map { java.io.File(it).name }.orElse("")
                        val title = String(CharArray(n) { text.getAtIndex(JAVA_CHAR, it.toLong()) })
                        out.add(Window(h.address(), title, exe, r[0], r[1], r[2] - r[0], r[3] - r[1]))
                    }
                }
                h = getWindow.invokeExact(h, GW_HWNDNEXT) as MemorySegment
            }
        }
        return out
    }

    private fun switchable(h: MemorySegment, a: Arena): Boolean {
        if ((isWindowVisible.invokeExact(h) as Int) == 0 || (isIconic.invokeExact(h) as Int) != 0) return false
        if ((getWindow.invokeExact(h, GW_OWNER) as MemorySegment).address() != 0L) return false
        if (((getWindowLongPtrW.invokeExact(h, GWL_EXSTYLE) as Long) and WS_EX_TOOLWINDOW) != 0L) return false
        val cloaked = a.allocate(JAVA_INT)   // a Store app's window that isn't showing, another virtual desktop's
        return (dwmGetWindowAttribute.invokeExact(h, DWMWA_CLOAKED, cloaked, 4) as Int) != 0 || cloaked.get(JAVA_INT, 0) == 0
    }

    /** The visible frame (no drop shadow) in physical pixels: left, top, right, bottom. */
    private fun frame(h: MemorySegment, a: Arena): IntArray? {
        val rect = a.allocate(JAVA_INT, 4)
        if ((dwmGetWindowAttribute.invokeExact(h, DWMWA_EXTENDED_FRAME_BOUNDS, rect, 16) as Int) != 0) return null
        return IntArray(4) { rect.getAtIndex(JAVA_INT, it.toLong()) }
    }

    /** The window's frame now, or null once it is gone or minimised. */
    fun window(id: Long): Window? = windows().firstOrNull { it.id == id }

    /** Restores and brings a window forward. Windows lets a background process do that only right after
     * input, so an Alt press and release goes first (the documented workaround's usual form). */
    fun bringForward(id: Long) {
        if (!available) return
        val h = hwnd(id)
        if ((isWindow.invokeExact(h) as Int) == 0) return
        if ((isIconic.invokeExact(h) as Int) != 0) showWindow.invokeExact(h, 9 /* SW_RESTORE */) as Int
        keybdEvent.invoke(0x12.toByte(), 0.toByte(), 0, 0L)            // VK_MENU down
        keybdEvent.invoke(0x12.toByte(), 0.toByte(), 2, 0L)            // KEYEVENTF_KEYUP
        setForegroundWindow.invokeExact(h) as Int
    }

    /** A wheel turn in 120ths of a notch, which Windows passes on whole: apps that scroll by pixels
     * scroll smoothly. Positive is up (vertical) or right (horizontal), as WHEEL_DELTA. */
    fun wheel(delta: Int, horizontal: Boolean) {
        if (!available || delta == 0) return
        mouseEvent.invoke(if (horizontal) 0x01000 else 0x0800, 0, 0, delta, 0L)
    }
}
