package com.aicove.feature.settings.presentation.component

import com.aicove.core.plugin.PluginSlotEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsEntriesTest {
    @Test
    fun `each settings group renders only its own pages`() {
        val slots = listOf(
            "settings.general", "settings.ai", "settings.environment",
            "settings.permissions", "settings.data", "settings.help"
        )
        val pages = slots.map { slot ->
            PluginSlotEntry("sample", slot, "Page", "package", slot, "page")
        }
        val actions = pages.map { it.copy(localId = "${it.localId}-action", kind = "action") }
        val otherSlot = pages.first().copy(localId = "header", slot = "chat.header")
        slots.forEach { slot ->
            assertEquals(
                pages.filter { it.slot == slot },
                settingsPluginEntries(pages + actions + otherSlot, slot)
            )
        }
        assertTrue(settingsPluginEntries(actions, "settings.general").isEmpty())
    }

    @Test
    fun `settings pages retain manifest order identity and confirmation`() {
        val pages = (0..4).map { index ->
            PluginSlotEntry(
                "sample", "page-$index", "Page $index", "package",
                "settings.data", "page", confirm = index == 4
            )
        }
        assertEquals(pages, settingsPluginEntries(pages, "settings.data"))
        assertTrue(settingsPluginEntries(pages, "settings.data").last().confirm)
    }

    @Test
    fun `screen forwards entries to six menu groups and row callback`() {
        val screen = source("SettingsScreen.kt")
        val menu = source("SettingsMenu.kt")
        val rows = source("PluginSettingsEntries.kt")
        assertTrue(screen.contains("pluginEntries = pluginEntries"))
        assertTrue(screen.contains("onOpenPluginEntry = onOpenPluginEntry"))
        val renderedSlots = Regex("slot = \"(settings\\.[a-z]+)\"")
            .findAll(menu).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("settings.general", "settings.ai", "settings.environment",
                "settings.permissions", "settings.data", "settings.help"),
            renderedSlots
        )
        assertEquals(6, Regex("onOpen = onOpenPluginEntry").findAll(menu).count())
        assertTrue(rows.contains("onClick = { onOpen(entry) }"))
        listOf(screen, menu, rows).forEach { source ->
            assertTrue(!source.contains("import com.aicove.feature.plugin"))
        }
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/aicove/feature/settings/presentation/component/$name"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
}
