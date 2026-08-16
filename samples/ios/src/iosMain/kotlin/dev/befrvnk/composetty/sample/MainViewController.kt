package dev.befrvnk.composetty.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.befrvnk.composetty.GhosttyTerminal
import dev.befrvnk.composetty.GhosttyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalKeyboardAccessory
import dev.befrvnk.composetty.TerminalRgb
import dev.befrvnk.composetty.TerminalSession
import dev.befrvnk.composetty.TerminalSize
import dev.befrvnk.composetty.TerminalTheme
import dev.befrvnk.composetty.TerminalTransport
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { IosTerminalSample() }

@Composable
private fun IosTerminalSample() {
    val theme = remember { SampleTheme }
    val session = remember { createEchoSession(theme) }
    DisposableEffect(session) { onDispose(session::close) }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        GhosttyTerminal(
            session = session,
            theme = theme,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        TerminalKeyboardAccessory(
            session = session,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun createEchoSession(theme: TerminalTheme): TerminalSession {
    var session: TerminalSession? = null
    val transport =
        object : TerminalTransport {
            override fun write(bytes: ByteArray) {
                val echoed = buildString {
                    bytes.decodeToString().forEach { character ->
                        when (character) {
                            '\b',
                            '\u007f' -> append("\b \b")
                            '\r' -> append("\r\n$ ")
                            else -> append(character)
                        }
                    }
                }
                session?.receive(echoed.encodeToByteArray())
            }

            override fun resize(size: TerminalSize) = Unit
        }
    return GhosttyTerminalSessionFactory().create(theme, transport).also { created ->
        session = created
        created.receive(
            buildString {
                    append("\u001b[?2027h")
                    append("Composetty iOS keyboard and touch sample\r\n")
                    append("Connect TerminalTransport to SSH in an app.\r\n")
                    append("Unicode: e\u0301 · 界 · 👩🏽\u200d💻\r\n\r\n$ ")
                }
                .encodeToByteArray()
        )
    }
}

private val SampleTheme =
    TerminalTheme(
        foreground = TerminalRgb(230, 230, 230),
        background = TerminalRgb(25, 25, 25),
        cursor = TerminalRgb(230, 230, 230),
    )
