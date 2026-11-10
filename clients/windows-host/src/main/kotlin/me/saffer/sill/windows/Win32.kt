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
    private val getWindowRect by lazy { fn(user32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
    private val getDpiForWindow by lazy { fn(user32, "GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val isZoomed by lazy { fn(user32, "IsZoomed", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val setWindowPos by lazy { fn(user32, "SetWindowPos", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)) }
    private val getCursorPos by lazy { fn(user32, "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val windowFromPoint by lazy { fn(user32, "WindowFromPoint", FunctionDescriptor.of(ADDRESS, JAVA_LONG)) }
    private val postMessageW by lazy { fn(user32, "PostMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG)) }
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
     * input, so a key press goes first: 0xE8, unassigned, as Alt would leave a window's menu keys showing. */
    fun bringForward(id: Long) {
        if (!available) return
        val h = hwnd(id)
        if ((isWindow.invokeExact(h) as Int) == 0) return
        if ((isIconic.invokeExact(h) as Int) != 0) showWindow.invokeExact(h, 9 /* SW_RESTORE */) as Int
        keybdEvent.invoke(0xE8.toByte(), 0.toByte(), 0, 0L)
        keybdEvent.invoke(0xE8.toByte(), 0.toByte(), 2, 0L)            // KEYEVENTF_KEYUP
        setForegroundWindow.invokeExact(h) as Int
    }

    /** What a window looked like before Sill sized it: its outer size, and whether it was maximised. */
    data class Before(val outerWidth: Int, val outerHeight: Int, val maximised: Boolean)

    /** The window's DPI (96 = 100 %), 96 if it can't say. */
    fun dpi(id: Long): Int = if (!available) 96 else (getDpiForWindow.invokeExact(hwnd(id)) as Int).takeIf { it > 0 } ?: 96

    /** Sizes and places a window so its visible frame is [frame] (physical pixels). Returns what it was, to put back. */
    fun setFrame(id: Long, frame: java.awt.Rectangle): Before? {
        if (!available) return null
        val h = hwnd(id)
        if ((isWindow.invokeExact(h) as Int) == 0) return null
        Arena.ofConfined().use { a ->
            val outer = a.allocate(JAVA_INT, 4)
            val wasMax = (isZoomed.invokeExact(h) as Int) != 0
            if (wasMax) showWindow.invokeExact(h, 9 /* SW_RESTORE */) as Int
            if ((getWindowRect.invokeExact(h, outer) as Int) == 0) return null
            val f = frame(h, a) ?: return null
            val o = IntArray(4) { outer.getAtIndex(JAVA_INT, it.toLong()) }
            // The outer rectangle holds an invisible resize border (the drop shadow) around the visible frame.
            val l = f[0] - o[0]; val t = f[1] - o[1]; val rr = o[2] - f[2]; val b = o[3] - f[3]
            setWindowPos.invokeExact(h, MemorySegment.NULL, frame.x - l, frame.y - t, frame.width + l + rr, frame.height + t + b, 0x0014 /* NOZORDER|NOACTIVATE */) as Int
            return Before(o[2] - o[0], o[3] - o[1], wasMax)
        }
    }

    /** Puts a window back as [before] says, where it is now. */
    fun restore(id: Long, before: Before) {
        if (!available) return
        val h = hwnd(id)
        if ((isWindow.invokeExact(h) as Int) == 0) return
        if (before.maximised) { showWindow.invokeExact(h, 3 /* SW_MAXIMIZE */) as Int; return }
        setWindowPos.invokeExact(h, MemorySegment.NULL, 0, 0, before.outerWidth, before.outerHeight, 0x0016 /* NOMOVE|NOZORDER|NOACTIVATE */) as Int
    }

    /** A wheel turn in 120ths of a notch, posted to the window under the pointer, so apps that scroll by
     * pixels scroll smoothly. Positive is up (vertical) or right (horizontal), as WHEEL_DELTA. Posted, not
     * injected: on GRIBPC an injected wheel (SendInput, Robot) never arrived (a low-level mouse hook,
     * Deskflow's server likely, ate it) while keys did.
     * shortcut: no MK_ modifier flags in wParam, and an app reading only raw input won't see it; upgrade
     * with SendInput when no hook is in the way. */
    fun wheel(delta: Int, horizontal: Boolean) {
        if (!available || delta == 0) return
        Arena.ofConfined().use { a ->
            val pt = a.allocate(JAVA_INT, 2)
            if ((getCursorPos.invokeExact(pt) as Int) == 0) return
            val x = pt.getAtIndex(JAVA_INT, 0); val y = pt.getAtIndex(JAVA_INT, 1)
            val h = windowFromPoint.invokeExact((y.toLong() shl 32) or (x.toLong() and 0xffffffffL)) as MemorySegment
            if (h.address() == 0L) return
            val wp = (delta.toLong() and 0xffff) shl 16
            val lp = ((y.toLong() and 0xffff) shl 16) or (x.toLong() and 0xffff)
            postMessageW.invokeExact(h, if (horizontal) 0x020E else 0x020A, wp, lp) as Int
        }
    }
}
