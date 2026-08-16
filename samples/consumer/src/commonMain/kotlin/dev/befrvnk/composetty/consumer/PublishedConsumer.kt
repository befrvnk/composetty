package dev.befrvnk.composetty.consumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.befrvnk.composetty.GhosttyTerminal
import dev.befrvnk.composetty.GhosttyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalKeyboardAccessory
import dev.befrvnk.composetty.TerminalSession
import dev.befrvnk.composetty.TerminalSize
import dev.befrvnk.composetty.TerminalTheme
import dev.befrvnk.composetty.TerminalTransport

@Composable
fun PublishedTerminal(
    session: TerminalSession,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    GhosttyTerminal(session = session, theme = theme, modifier = modifier)
}

@Composable
fun PublishedAccessory(session: TerminalSession, modifier: Modifier = Modifier) {
    TerminalKeyboardAccessory(session = session, modifier = modifier)
}

fun createPublishedSession(theme: TerminalTheme): TerminalSession =
    GhosttyTerminalSessionFactory()
        .create(
            initialTheme = theme,
            transport =
                object : TerminalTransport {
                    override fun write(bytes: ByteArray) = Unit

                    override fun resize(size: TerminalSize) = Unit
                },
        )
