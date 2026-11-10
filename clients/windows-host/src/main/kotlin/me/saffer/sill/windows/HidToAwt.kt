package me.saffer.sill.windows

import me.saffer.sill.core.HidKeys
import java.awt.event.KeyEvent

/**
 * HID usage (page 7) to AWT virtual key, the opposite direction from core's `HidKeys.awt`
 * (AWT → HID, built for the Windows client dialling a Mac). Built once from the same table so the
 * two sides never drift apart.
 */
object HidToAwt {
    val map: Map<Int, Int> = HidKeys.awt.entries.associate { (vk, hid) -> hid to vk }

    /**
     * The Mac's modifier keys mapped to the PC's (the mirror of core's `DesktopModifiers`, since
     * here the PC is the host, not the client): Command -> Ctrl, Option -> Alt, Control -> Ctrl too
     * (Windows has only one Ctrl key; shortcut: a PC keyboard cannot tell a device's own Control
     * key held apart from its Command sent as Ctrl, which this phase accepts).
     */
    fun modifierVk(hidUsage: Int): Int? = when (hidUsage) {
        HidKeys.LEFT_SHIFT, HidKeys.RIGHT_SHIFT -> KeyEvent.VK_SHIFT
        HidKeys.LEFT_CONTROL, HidKeys.RIGHT_CONTROL -> KeyEvent.VK_CONTROL
        HidKeys.LEFT_COMMAND, HidKeys.RIGHT_COMMAND -> KeyEvent.VK_CONTROL
        HidKeys.LEFT_OPTION, HidKeys.RIGHT_OPTION -> KeyEvent.VK_ALT
        else -> null
    }

    /** The VK to hold for InputEvent.Key's [me.saffer.sill.core.Modifiers] bits, Command/Control -> Ctrl, Option -> Alt. */
    fun modifierVks(bits: Long): List<Int> {
        val out = ArrayList<Int>()
        if (bits and me.saffer.sill.core.Modifiers.SHIFT != 0L) out.add(KeyEvent.VK_SHIFT)
        if (bits and (me.saffer.sill.core.Modifiers.CONTROL or me.saffer.sill.core.Modifiers.COMMAND) != 0L) out.add(KeyEvent.VK_CONTROL)
        if (bits and me.saffer.sill.core.Modifiers.OPTION != 0L) out.add(KeyEvent.VK_ALT)
        return out
    }

    /** A HID usage to the VK that types it, modifiers aside: [modifierVk] first, else the inverse table. */
    fun vkFor(hidUsage: Int): Int? = modifierVk(hidUsage) ?: map[hidUsage]
}
