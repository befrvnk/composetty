package dev.befrvnk.composetty.consumer

import dev.befrvnk.composetty.GhosttyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalRgb
import dev.befrvnk.composetty.TerminalSize
import dev.befrvnk.composetty.TerminalTheme
import dev.befrvnk.composetty.TerminalTransport
import kotlin.test.assertEquals

internal fun assertPublishedTerminalRoundTrip() {
    val theme =
        TerminalTheme(
            foreground = TerminalRgb(230, 230, 230),
            background = TerminalRgb(25, 25, 25),
            cursor = TerminalRgb(230, 230, 230),
        )
    val session =
        GhosttyTerminalSessionFactory()
            .create(
                initialTheme = theme,
                transport =
                    object : TerminalTransport {
                        override fun write(bytes: ByteArray) = Unit

                        override fun resize(size: TerminalSize) = Unit
                    },
            )
    try {
        session.receive("published 世界".encodeToByteArray())
        val snapshot = session.snapshot.value
        assertEquals("p", snapshot.cells.grapheme(0))
        assertEquals("世", snapshot.cells.grapheme(10))
        assertEquals("界", snapshot.cells.grapheme(12))
    } finally {
        session.close()
    }
}
