package com.aicove

import android.os.Bundle
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.aicove.core.plugin.PluginSlotEntry
import com.aicove.feature.plugin.presentation.PluginEntryContext
import com.aicove.feature.plugin.presentation.PluginsViewModel

private const val PLUGIN_CONTEXT_KEY = "plugin_entry_context"

private data class PendingPluginAction(val entry: PluginSlotEntry, val context: PluginEntryContext)

@Composable
internal fun rememberPluginHostNavigation(
    viewModel: PluginsViewModel,
    navController: NavHostController
): (PluginSlotEntry, PluginEntryContext) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingPluginAction?>(null) }
    val invoke: (PendingPluginAction) -> Unit = { action ->
        viewModel.runUiAction(action.entry, action.context) { message ->
            if (!message.isNullOrBlank()) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.common_confirm)) },
            text = { Text(action.entry.title) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    invoke(action)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
    return { entry, entryContext ->
        when (entry.kind) {
            "page" -> {
                navController.navigate("plugin_slot_page/${android.net.Uri.encode(entry.key)}")
                navController.currentBackStackEntry?.savedStateHandle?.set(
                    PLUGIN_CONTEXT_KEY, entryContext.toBundle()
                )
            }
            "action" -> {
                val action = PendingPluginAction(entry, entryContext)
                if (entry.confirm) pending = action else invoke(action)
            }
        }
    }
}

internal fun pluginPageContext(entry: NavBackStackEntry): PluginEntryContext {
    val data = entry.savedStateHandle.get<Bundle>(PLUGIN_CONTEXT_KEY) ?: return PluginEntryContext()
    return PluginEntryContext(
        sessionId = data.getString("session"),
        path = data.getString("path"),
        isDirectory = if (data.containsKey("directory")) data.getBoolean("directory") else null,
        selection = data.getString("selection"),
        language = data.getString("language"),
        lineStart = if (data.containsKey("lineStart")) data.getInt("lineStart") else null,
        lineEnd = if (data.containsKey("lineEnd")) data.getInt("lineEnd") else null
    )
}

private fun PluginEntryContext.toBundle() = Bundle().apply {
    putString("session", sessionId)
    putString("path", path)
    isDirectory?.let { putBoolean("directory", it) }
    putString("selection", selection)
    putString("language", language)
    lineStart?.let { putInt("lineStart", it) }
    lineEnd?.let { putInt("lineEnd", it) }
}
