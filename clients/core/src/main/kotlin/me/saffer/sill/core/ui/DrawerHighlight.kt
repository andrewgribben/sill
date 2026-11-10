package me.saffer.sill.core.ui

/**
 * The Apps drawer's keyboard highlight: the iOS app's `DrawerHighlight` (iOSClient/DrawerHighlight.swift),
 * rule for rule. An index into the filtered list of rows (open apps then closed apps, the order the
 * drawer shows them in), or none. Pure: no Android, no platform types.
 */
object DrawerHighlight {
    enum class Key { Up, Down }

    /** The search text changed: the one result highlighted so Return/Enter launches it at once,
     *  else none until an arrow is pressed. */
    fun afterFilterChange(count: Int): Int? = if (count == 1) 0 else null

    /** An arrow key, clamped to the list. From none, both Down and Up land on the first row (there
     *  is nothing above it to move to). An empty list highlights nothing. */
    fun move(key: Key, current: Int?, count: Int): Int? {
        if (count <= 0) return null
        if (current == null) return 0
        return when (key) {
            Key.Down -> (current + 1).coerceAtMost(count - 1)
            Key.Up -> (current - 1).coerceAtLeast(0)
        }
    }
}
