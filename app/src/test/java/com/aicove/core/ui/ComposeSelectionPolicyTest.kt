package com.aicove.core.ui

import android.view.textclassifier.TextSelection
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ComposeSelectionPolicyTest {
    @Test
    fun `selection retained after deletion is rejected before classifier execution`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextSelection.Request.Builder("a", 0, 4).build()
        }
    }

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
