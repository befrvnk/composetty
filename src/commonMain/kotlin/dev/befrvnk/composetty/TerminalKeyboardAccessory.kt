package dev.befrvnk.composetty

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A horizontally scrollable row of keys commonly missing from mobile software keyboards.
 *
 * Copy reads the terminal's active selection. Paste applies Ghostty's safety filtering and current
 * bracketed-paste mode.
 */
@Suppress("DEPRECATION")
@Composable
public fun TerminalKeyboardAccessory(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xff202020.toInt()),
    contentColor: Color = Color.White,
) {
    val clipboardManager = LocalClipboardManager.current
    Row(
        modifier =
            modifier
                .background(backgroundColor)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        AccessoryButton("Esc", contentColor) { session.sendAccessoryKey(TerminalKey.Escape) }
        AccessoryButton("Tab", contentColor) { session.sendAccessoryKey(TerminalKey.Tab) }
        AccessoryButton("Ctrl-C", contentColor) {
            session.sendAccessoryKey(
                key = TerminalKey.C,
                modifiers = setOf(TerminalKeyModifier.Control),
                unshiftedCodepoint = 'c'.code,
            )
        }
        AccessoryButton("Ctrl-D", contentColor) {
            session.sendAccessoryKey(
                key = TerminalKey.D,
                modifiers = setOf(TerminalKeyModifier.Control),
                unshiftedCodepoint = 'd'.code,
            )
        }
        AccessoryButton("←", contentColor) { session.sendAccessoryKey(TerminalKey.ArrowLeft) }
        AccessoryButton("↑", contentColor) { session.sendAccessoryKey(TerminalKey.ArrowUp) }
        AccessoryButton("↓", contentColor) { session.sendAccessoryKey(TerminalKey.ArrowDown) }
        AccessoryButton("→", contentColor) { session.sendAccessoryKey(TerminalKey.ArrowRight) }
        AccessoryButton("Copy", contentColor) {
            session.selectedText()?.let { clipboardManager.setText(AnnotatedString(it)) }
        }
        AccessoryButton("Paste", contentColor) {
            clipboardManager.getText()?.text?.takeIf(String::isNotEmpty)?.let(session::paste)
        }
    }
}

@Composable
private fun AccessoryButton(label: String, contentColor: Color, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier.defaultMinSize(
                    minWidth = AccessoryButtonWidth,
                    minHeight = AccessoryButtonHeight,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                TextStyle(
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = AccessoryFontSize,
                ),
        )
    }
}

private fun TerminalSession.sendAccessoryKey(
    key: TerminalKey,
    modifiers: Set<TerminalKeyModifier> = emptySet(),
    unshiftedCodepoint: Int = 0,
) {
    sendKey(
        TerminalKeyEvent(
            key = key,
            action = TerminalKeyAction.Press,
            modifiers = modifiers,
            unshiftedCodepoint = unshiftedCodepoint,
            text = "",
        )
    )
}

private val AccessoryButtonWidth = 48.dp
private val AccessoryButtonHeight = 40.dp
private val AccessoryFontSize = 13.sp
