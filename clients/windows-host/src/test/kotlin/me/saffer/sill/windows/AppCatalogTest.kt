package me.saffer.sill.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class AppCatalogTest {
    @Test
    fun noiseIsFilteredByName() {
        val raw = listOf(
            AppCatalog.Raw("Notepad", "C:\\a\\Notepad.lnk", user = false),
            AppCatalog.Raw("Uninstall Notepad", "C:\\a\\Uninstall Notepad.lnk", user = false),
            AppCatalog.Raw("Readme", "C:\\a\\Readme.lnk", user = false),
            AppCatalog.Raw("Help", "C:\\a\\Help.lnk", user = false),
            AppCatalog.Raw("Documentation", "C:\\a\\Documentation.lnk", user = false),
            AppCatalog.Raw("Website", "C:\\a\\Website.lnk", user = false),
        )
        assertEquals(listOf("Notepad"), AppCatalog.build(raw).map { it.name })
    }

    @Test
    fun theUsersStartMenuWinsADuplicateName() {
        val raw = listOf(
            AppCatalog.Raw("Slack", "C:\\ProgramData\\Slack.lnk", user = false),
            AppCatalog.Raw("Slack", "C:\\Users\\me\\Slack.lnk", user = true),
        )
        assertEquals(listOf("C:\\Users\\me\\Slack.lnk"), AppCatalog.build(raw).map { it.bundleID })
    }

    @Test
    fun aSharedEntryIsKeptWhenNoUserOneMatchesIt() {
        val raw = listOf(
            AppCatalog.Raw("Slack", "C:\\Users\\me\\Slack.lnk", user = true),
            AppCatalog.Raw("Slack", "C:\\ProgramData\\Slack.lnk", user = false),
        )
        // The user's came first here, so the shared one does not overwrite it.
        assertEquals(listOf("C:\\Users\\me\\Slack.lnk"), AppCatalog.build(raw).map { it.bundleID })
    }

    @Test
    fun sortedCaseInsensitivelyByName() {
        val raw = listOf(
            AppCatalog.Raw("zoom", "C:\\a\\zoom.lnk", user = false),
            AppCatalog.Raw("Excel", "C:\\a\\Excel.lnk", user = false),
            AppCatalog.Raw("adobe Reader", "C:\\a\\adobe Reader.lnk", user = false),
        )
        assertEquals(listOf("adobe Reader", "Excel", "zoom"), AppCatalog.build(raw).map { it.name })
    }

    @Test
    fun theListIsCapped() {
        val raw = (1..600).map { AppCatalog.Raw("App %03d".format(it), "C:\\a\\App$it.lnk", user = false) }
        assertEquals(AppCatalog.CAP, AppCatalog.build(raw).size)
    }
}
