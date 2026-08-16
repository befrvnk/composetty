package dev.befrvnk.composetty

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class TerminalTextInputUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun committedTextFromComposeInputIsForwardedToTheTerminal() = runComposeUiTest {
        val session = RecordingSession()
        setContent {
            GhosttyTerminal(
                session = session,
                theme = Theme,
                modifier = Modifier.fillMaxSize(),
            )
        }

        onNodeWithTag(TerminalInputTestTag).performTextInput("hello 世界")
        waitForIdle()

        assertEquals(listOf("hello 世界"), session.sentText)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun completeGraphemeSnapshotRendersOnIos() = runComposeUiTest {
        val foreground = Theme.foreground.argb
        val background = Theme.background.argb
        val snapshot =
            TerminalSnapshot.create(
                columns = 5,
                rows = 1,
                cursorColumn = 4,
                cursorRow = 0,
                cursorVisible = true,
                cursorStyle = TerminalCursorStyle.Block,
                cursorWide = true,
                defaultBackground = background,
                defaultForeground = foreground,
                cells =
                    listOf(
                        TerminalCell("e\u0301", TerminalCellWidth.Narrow, foreground, background),
                        TerminalCell("界", TerminalCellWidth.Wide, foreground, background),
                        TerminalCell("", TerminalCellWidth.WideSpacerTail, foreground, background),
                        TerminalCell(
                            "👩🏽\u200d💻",
                            TerminalCellWidth.Wide,
                            foreground,
                            background,
                        ),
                        TerminalCell("", TerminalCellWidth.WideSpacerTail, foreground, background),
                    ),
            )
        val session = RecordingSession(snapshot)

        setContent {
            GhosttyTerminal(
                session = session,
                theme = Theme,
                modifier = Modifier.fillMaxSize(),
                requestFocus = false,
            )
        }

        waitForIdle()
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun longPressDragSelectsTerminalCells() = runComposeUiTest {
        val session = RecordingSession()
        val clipboardManager = RecordingClipboardManager("")
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboardManager) {
                GhosttyTerminal(
                    session = session,
                    theme = Theme,
                    modifier = Modifier.fillMaxSize(),
                    requestFocus = false,
                )
            }
        }

        onNodeWithTag(TerminalCanvasTestTag).performTouchInput {
            down(Offset(4f, 4f))
            advanceEventTime(SelectionLongPressMillis)
            moveTo(Offset(60f, 4f), delayMillis = 100)
            up()
        }
        waitForIdle()

        assertTrue(session.selections.isNotEmpty())
        assertTrue(
            session.selections.last().second.column > session.selections.first().first.column
        )
        assertEquals("selection", clipboardManager.getText()?.text)
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mobileAccessoryForwardsKeysAndClipboardPaste() = runComposeUiTest {
        val session = RecordingSession()
        val clipboardManager = RecordingClipboardManager("clipboard text")
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboardManager) {
                TerminalKeyboardAccessory(session)
            }
        }

        onNodeWithText("Esc").performClick()
        onNodeWithText("Ctrl-C").performClick()
        onNodeWithText("Paste").performClick()
        waitForIdle()

        assertEquals(TerminalKey.Escape, session.sentKeys[0].key)
        assertEquals(emptySet(), session.sentKeys[0].modifiers)
        assertEquals(TerminalKey.C, session.sentKeys[1].key)
        assertEquals(setOf(TerminalKeyModifier.Control), session.sentKeys[1].modifiers)
        assertEquals(listOf("clipboard text"), session.pastedText)
    }

    @Suppress("DEPRECATION")
    private class RecordingClipboardManager(initialText: String) : ClipboardManager {
        private var text: AnnotatedString? = AnnotatedString(initialText)

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString
        }

        override fun getText(): AnnotatedString? = text
    }

    private class RecordingSession(
        initialSnapshot: TerminalSnapshot = TerminalSnapshot.empty(Theme)
    ) : TerminalSession {
        override val snapshot: StateFlow<TerminalSnapshot> = MutableStateFlow(initialSnapshot)
        val sentText = mutableListOf<String>()
        val sentKeys = mutableListOf<TerminalKeyEvent>()
        val pastedText = mutableListOf<String>()
        val selections = mutableListOf<Pair<TerminalCellPosition, TerminalCellPosition>>()

        override fun receive(bytes: ByteArray) = Unit

        override fun sendKey(event: TerminalKeyEvent) {
            sentKeys += event
        }

        override fun sendText(text: String) {
            sentText += text
        }

        override fun paste(text: String) {
            pastedText += text
        }

        override fun select(start: TerminalCellPosition, end: TerminalCellPosition) {
            selections += start to end
        }

        override fun clearSelection() = Unit

        override fun selectedText(): String = "selection"

        override fun resize(size: TerminalSize) = Unit

        override fun scroll(rows: Int) = Unit

        override fun updateTheme(theme: TerminalTheme) = Unit

        override fun close() = Unit
    }

    private companion object {
        const val SelectionLongPressMillis = 700L

        val Theme =
            TerminalTheme(
                foreground = TerminalRgb(230, 230, 230),
                background = TerminalRgb(25, 25, 25),
                cursor = TerminalRgb(230, 230, 230),
            )
    }
}
