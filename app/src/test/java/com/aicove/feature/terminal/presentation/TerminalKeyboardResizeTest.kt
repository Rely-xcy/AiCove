package com.aicove.feature.terminal.presentation

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalKeyboardResizeTest {
    private val session = mockk<TerminalSession>()
    private var emulator: TerminalEmulator? = null
    private lateinit var view: TerminalView
    private val notifiedSizes = mutableListOf<Pair<Int, Int>>()

    @Before
    fun setUp() {
        every { session.emulator } answers { emulator }
        every { session.updateSize(any(), any()) } answers {
            val columns = firstArg<Int>()
            val rows = secondArg<Int>()
            notifiedSizes += columns to rows
            val current = emulator
            if (current == null) {
                emulator = TerminalEmulator(
                    mockk<TerminalOutput>(relaxed = true), columns, rows, null,
                    mockk<TerminalSessionClient>(relaxed = true)
                )
            } else {
                current.resize(columns, rows)
            }
        }
        view = TerminalView(ApplicationProvider.getApplicationContext(), null)
        view.setTerminalViewClient(mockk<TerminalViewClient>(relaxed = true))
        view.setTextSize(24)
        view.attachSession(session)
        layout(600)
        clearMocks(session, answers = false)
        notifiedSizes.clear()
    }

    @Test
    fun keyboardClosingFramesNotifyOnlyFinalSizeAndDrawProgressAtFinalBottom() {
        val originalRows = requireNotNull(emulator).mRows
        for (height in listOf(650, 700, 750, 800, 850, 900)) {
            layout(height)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertEquals(originalRows, requireNotNull(emulator).mRows)
        }
        verify(exactly = 0) { session.updateSize(any(), any()) }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))

        verify(exactly = 1) { session.updateSize(any(), any()) }
        val terminal = requireNotNull(emulator)
        assertNotEquals(originalRows, terminal.mRows)
        assertEquals(listOf(terminal.mColumns to terminal.mRows), notifiedSizes)
        val progress = "\u001b7\u001b[${notifiedSizes.single().second};1HProgress 50%\u001b8".toByteArray()
        terminal.append(progress, progress.size)
        assertEquals("Progress 50%", terminal.screen.getSelectedText(0, terminal.mRows - 1, 20, terminal.mRows - 1).trim())
    }

    @Test
    fun immediateFontUpdateCancelsPendingLayoutResize() {
        layout(900)

        view.setTextSize(28)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))

        verify(exactly = 1) { session.updateSize(any(), any()) }
        assertEquals(requireNotNull(emulator).mRows, notifiedSizes.single().second)
    }

    @Test
    fun detachingViewCancelsPendingResize() {
        layout(900)
        TerminalView::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
            invoke(view)
        }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))

        verify(exactly = 0) { session.updateSize(any(), any()) }
    }

    private fun layout(height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 800, height)
    }
}
