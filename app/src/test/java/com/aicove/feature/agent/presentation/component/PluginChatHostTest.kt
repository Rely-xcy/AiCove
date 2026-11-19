package com.aicove.feature.agent.presentation.component

import com.aicove.core.plugin.PluginSlotEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginChatHostTest {
    @Test
    fun `chat slots retain pages and actions without truncating entries`() {
        val slots = listOf("chat.header", "chat.welcome", "chat.session.menu", "files.tree.menu")
        val entries = slots.flatMap { slot ->
            (0..4).map { index ->
                PluginSlotEntry(
                    pluginId = "sample",
                    localId = "$slot-$index",
                    title = "Entry $index",
                    icon = "package",
                    slot = slot,
                    kind = if (index % 2 == 0) "page" else "action",
                    confirm = index == 3
                )
            }
        }
        slots.forEach { slot ->
            val selected = chatPluginEntries(entries, slot)
            assertEquals(entries.filter { it.slot == slot }, selected)
            assertEquals(5, selected.size)
            assertTrue(selected[3].confirm)
        }
        assertTrue(chatPluginEntries(entries, "drawer.footer").isEmpty())
    }

    @Test
    fun `unsupported kinds are not rendered in chat slots`() {
        val entry = PluginSlotEntry("sample", "one", "Entry", "package", "chat.header", "overlay")
        assertTrue(chatPluginEntries(listOf(entry), "chat.header").isEmpty())
    }

    @Test
    fun `panel forwards header and welcome entries and callback to rendered hosts`() {
        val panel = source("AIChatPanel.kt")
        val emptyState = source("PluginChatHost.kt")
        val header = source("ChatHeaderComponents.kt")
        assertTrue(panel.contains("pluginEntries = chatPluginEntries(headerPluginEntries, \"chat.header\")"))
        assertTrue(panel.contains("pluginEntries = welcomePluginEntries"))
        assertEquals(2, Regex("onOpenPluginEntry = onOpenPluginEntry").findAll(panel).count())
        assertTrue(emptyState.contains("pluginEntries = chatPluginEntries(pluginEntries, \"chat.welcome\")"))
        assertTrue(emptyState.contains("onOpenPluginEntry = onOpenPluginEntry"))
        assertTrue(header.contains("TextButton(onClick = { onOpenPluginEntry(entry) })"))
        assertFalse(header.contains("pluginEntries.take(3)"))
    }

    @Test
    fun `drawer dispatches clicked session and file node instead of active context`() {
        val drawer = source("ChatDrawer.kt")
        val menus = source("ChatDrawerMenus.kt")
        assertTrue(drawer.contains("pluginEntries = sessionMenuPluginEntries"))
        assertTrue(drawer.contains("onPluginEntry = onSessionPluginEntry"))
        assertTrue(drawer.contains("pluginEntries = fileTreePluginEntries"))
        assertTrue(drawer.contains("onPluginEntry = onFileTreePluginEntry"))
        assertTrue(menus.contains("onPluginEntry(entry, session.id)"))
        assertTrue(menus.contains("onPluginEntry(entry, node.path, node.entry.isDirectory)"))
        assertFalse(menus.contains("currentSessionId"))
        assertTrue(drawer.contains("onClick = { onOpenPluginPage(entry) }"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/aicove/feature/agent/presentation/component/$name"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }
}
