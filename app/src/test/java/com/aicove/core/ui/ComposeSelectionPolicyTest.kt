package com.aicove.core.ui

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class ComposeSelectionPolicyTest {
    @Test
    fun `startup disables the classifier request path for editable and static selection`() {
        val original = ComposeFoundationFlags.isSmartSelectionEnabled
        try {
            ComposeFoundationFlags.isSmartSelectionEnabled = true
            configureComposeSelection()
            assertFalse(ComposeFoundationFlags.isSmartSelectionEnabled)
        } finally {
            ComposeFoundationFlags.isSmartSelectionEnabled = original
        }
    }
}
