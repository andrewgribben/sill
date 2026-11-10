package me.saffer.sill.windows

import me.saffer.sill.core.AppInfo
import java.io.File

/**
 * The Start Menu as an app list: every `.lnk` and `.url` shortcut under
 * `%ProgramData%\Microsoft\Windows\Start Menu\Programs` and
 * `%APPDATA%\Microsoft\Windows\Start Menu\Programs`, recursively (`.url` needs no parsing: `cmd /c
 * start` opens it the same way a `.lnk` is opened, so it rides the same path as a bundleID).
 * `scan()` walks the filesystem (impure, Windows only); `build()` is the pure part (filtering,
 * de-duplication, sorting, capping) a JVM test can run anywhere.
 */
object AppCatalog {
    /** StreamMessage.swift's per-kind cap is generous, but a Start Menu is never this big in practice. */
    const val CAP = 500

    private val EXTENSIONS = setOf("lnk", "url")

    // No cheap way to read a .lnk's target here (the binary shortcut format), so noise is caught by
    // name alone, as the task allows: a shortcut actually named "Readme" or "Uninstall ‹App›" is
    // filtered; one merely pointing at a readme is not.
    private val NOISE = listOf("uninstall", "readme", "help", "documentation", "website")

    /** One shortcut found on disk before filtering: its display name, the absolute path that
     * becomes the wire's bundleID, and whether it came from the user's own Start Menu (wins a name
     * collision over the shared one, as the task asks). */
    data class Raw(val name: String, val path: String, val user: Boolean)

    /** Filters obvious noise by name, de-duplicates by name (the user's Start Menu wins), sorts
     * case-insensitively by name, and caps at [CAP]. Pure: no filesystem access. */
    fun build(raw: List<Raw>): List<AppInfo> {
        val kept = raw.filterNot { r -> val n = r.name.lowercase(); NOISE.any { n.contains(it) } }
        val byName = LinkedHashMap<String, Raw>()
        for (r in kept) {
            val existing = byName[r.name]
            if (existing == null || (r.user && !existing.user)) byName[r.name] = r
        }
        return byName.values.sortedBy { it.name.lowercase() }.take(CAP).map { AppInfo(it.name, it.path) }
    }

    /** Scans both Start Menu folders for shortcuts and returns the list [build] would from what is
     * found. Empty off Windows, or if neither folder exists. */
    fun scan(): List<AppInfo> = build(rawShortcuts())

    private fun rawShortcuts(): List<Raw> {
        val folders = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") to false },
            System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") to true },
        )
        return folders.flatMap { (dir, user) ->
            if (!dir.isDirectory) emptyList()
            else dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .map { Raw(it.nameWithoutExtension, it.absolutePath, user) }.toList()
        }
    }
}
