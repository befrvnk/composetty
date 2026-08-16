package dev.befrvnk.composetty

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal fun TerminalTextInput(
    session: TerminalSession,
    focusRequester: FocusRequester,
) {
    val currentSession by rememberUpdatedState(session)
    var value by remember(session) { mutableStateOf(EmptyTerminalInput) }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.composition != null) {
                value = next
            } else {
                next.terminalInputActions().forEach { action ->
                    when (action) {
                        TerminalTextInputAction.Backspace ->
                            currentSession.sendSyntheticKey(TerminalKey.Backspace)
                        TerminalTextInputAction.Enter ->
                            currentSession.sendSyntheticKey(TerminalKey.Enter)
                        is TerminalTextInputAction.Text -> currentSession.sendText(action.value)
                    }
                }
                value = EmptyTerminalInput
            }
        },
        modifier =
            Modifier.size(HiddenInputSize)
                .focusRequester(focusRequester)
                .testTag(TerminalInputTestTag)
                .alpha(HiddenInputAlpha),
        textStyle = TextStyle(color = Color.Transparent),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
            ),
        cursorBrush = SolidColor(Color.Transparent),
    )
}

internal sealed interface TerminalTextInputAction {
    data class Text(val value: String) : TerminalTextInputAction

    data object Backspace : TerminalTextInputAction

    data object Enter : TerminalTextInputAction
}

internal fun TextFieldValue.terminalInputActions(): List<TerminalTextInputAction> {
    if (text.isEmpty()) return listOf(TerminalTextInputAction.Backspace)

    val inserted = text.replace(TerminalInputSentinel, "")
    if (inserted.isEmpty()) return emptyList()

    return buildList {
        val textRun = StringBuilder()

        fun flushText() {
            if (textRun.isNotEmpty()) {
                add(TerminalTextInputAction.Text(textRun.toString()))
                textRun.clear()
            }
        }

        var index = 0
        while (index < inserted.length) {
            when (inserted[index]) {
                '\r' -> {
                    flushText()
                    add(TerminalTextInputAction.Enter)
                    if (index + 1 < inserted.length && inserted[index + 1] == '\n') index++
                }
                '\n' -> {
                    flushText()
                    add(TerminalTextInputAction.Enter)
                }
                else -> textRun.append(inserted[index])
            }
            index++
        }
        flushText()
    }
}

private fun TerminalSession.sendSyntheticKey(key: TerminalKey) {
    sendKey(
        TerminalKeyEvent(
            key = key,
            action = TerminalKeyAction.Press,
            modifiers = emptySet(),
            unshiftedCodepoint = 0,
            text = "",
        )
    )
}

internal const val TerminalInputTestTag = "ComposettyTerminalInput"

private const val TerminalInputSentinel = "\u200b"
private val EmptyTerminalInput =
    TextFieldValue(
        text = TerminalInputSentinel,
        selection = TextRange(TerminalInputSentinel.length),
    )
private val HiddenInputSize = 1.dp
private const val HiddenInputAlpha = 0f
