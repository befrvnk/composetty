package dev.befrvnk.composetty

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

internal class TerminalTextInputTest {
    @Test
    fun convertsCommittedTextIntoTerminalInput() {
        assertEquals(
            listOf(TerminalTextInputAction.Text("hello 世界")),
            TextFieldValue("\u200bhello 世界").terminalInputActions(),
        )
    }

    @Test
    fun convertsDeletedSentinelIntoBackspace() {
        assertEquals(
            listOf(TerminalTextInputAction.Backspace),
            TextFieldValue("").terminalInputActions(),
        )
    }

    @Test
    fun convertsNewlinesIntoEnterKeysWithoutDuplicatingCrLf() {
        assertEquals(
            listOf(
                TerminalTextInputAction.Text("first"),
                TerminalTextInputAction.Enter,
                TerminalTextInputAction.Text("second"),
                TerminalTextInputAction.Enter,
                TerminalTextInputAction.Text("third"),
            ),
            TextFieldValue("\u200bfirst\nsecond\r\nthird").terminalInputActions(),
        )
    }

    @Test
    fun ignoresTheUnmodifiedSentinel() {
        assertEquals(emptyList(), TextFieldValue("\u200b").terminalInputActions())
    }
}
