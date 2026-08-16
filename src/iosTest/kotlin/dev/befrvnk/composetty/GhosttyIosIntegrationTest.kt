package dev.befrvnk.composetty

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class GhosttyIosIntegrationTest {
    @Test
    fun nativeTerminalParsesOutputAndEncodesInput() {
        val transport = RecordingTransport()
        val session = GhosttyTerminalSessionFactory().create(LightTheme, transport)
        try {
            session.receive("hello \u001b[1;32mios\u001b[0m".encodeToByteArray())

            val snapshot = session.snapshot.value
            assertEquals("hello ios", snapshot.rowText(0).trimEnd())
            assertEquals(
                TerminalCellFlagBold,
                snapshot.cells.flags(6) and TerminalCellFlagBold,
            )

            session.sendText("echo hello\r")
            assertContentEquals("echo hello\r".encodeToByteArray(), transport.writes.single())

            val size = TerminalSize(columns = 100, rows = 30, cellWidth = 8, cellHeight = 16)
            session.resize(size)
            assertEquals(size, transport.sizes.single())
            assertEquals(100, session.snapshot.value.columns)
            assertEquals(30, session.snapshot.value.rows)
        } finally {
            session.close()
        }
    }

    @Test
    fun nativeSnapshotPreservesGraphemesAndWideCells() {
        val session = GhosttyTerminalSessionFactory().create(LightTheme, RecordingTransport())
        try {
            session.receive("\u001b[?2027he\u0301界👩🏽\u200d💻".encodeToByteArray())

            val cells = session.snapshot.value.cells
            assertEquals("e\u0301", cells.grapheme(0))
            assertEquals(TerminalCellWidth.Narrow, cells.width(0))
            assertEquals("界", cells.grapheme(1))
            assertEquals(TerminalCellWidth.Wide, cells.width(1))
            assertEquals(TerminalCellWidth.WideSpacerTail, cells.width(2))
            assertEquals("👩🏽\u200d💻", cells.grapheme(3))
            assertEquals(TerminalCellWidth.Wide, cells.width(3))
            assertEquals(TerminalCellWidth.WideSpacerTail, cells.width(4))
        } finally {
            session.close()
        }
    }

    @Test
    fun nativeSelectionAndBracketedPasteRoundTrip() {
        val transport = RecordingTransport()
        val session = GhosttyTerminalSessionFactory().create(LightTheme, transport)
        try {
            session.receive("hello world\u001b[?2004h".encodeToByteArray())
            session.select(
                TerminalCellPosition(column = 0, row = 0),
                TerminalCellPosition(column = 4, row = 0),
            )

            assertEquals("hello", session.selectedText())
            assertEquals(
                TerminalCellFlagSelected,
                session.snapshot.value.cells.flags(0) and TerminalCellFlagSelected,
            )

            session.paste("one\ntwo")
            assertContentEquals(
                "\u001b[200~one\ntwo\u001b[201~".encodeToByteArray(),
                transport.writes.single(),
            )

            session.clearSelection()
            assertEquals(null, session.selectedText())
        } finally {
            session.close()
        }
    }

    @Test
    fun repeatedLifecycleAndChunkedReplayStayConsistent() {
        repeat(IosLifecycleIterations) { iteration ->
            val session = GhosttyTerminalSessionFactory().create(LightTheme, RecordingTransport())
            try {
                session.receive("lifecycle-$iteration 世界".encodeToByteArray())
                assertEquals("l", session.snapshot.value.cells.grapheme(0))
            } finally {
                session.close()
            }
        }

        val session = GhosttyTerminalSessionFactory().create(LightTheme, RecordingTransport())
        try {
            val replay =
                buildString {
                        repeat(IosReplayLines) { line -> append("ios-replay-$line 世界\r\n") }
                    }
                    .encodeToByteArray()
            var offset = 0
            while (offset < replay.size) {
                val end = (offset + IosReplayChunkBytes).coerceAtMost(replay.size)
                session.receive(replay.copyOfRange(offset, end))
                offset = end
            }

            val snapshot = session.snapshot.value
            assertEquals(snapshot.columns * snapshot.rows, snapshot.cells.size)
            assertTrue(
                (0 until snapshot.rows)
                    .joinToString("\n") { row -> snapshot.rowText(row) }
                    .contains("ios-replay-${IosReplayLines - 1}")
            )
        } finally {
            session.close()
        }
    }

    private fun TerminalSnapshot.rowText(row: Int): String = buildString {
        val start = row * columns
        repeat(columns) { column ->
            val index = start + column
            when (cells.width(index)) {
                TerminalCellWidth.Narrow -> append(cells.grapheme(index).ifEmpty { " " })
                TerminalCellWidth.Wide -> append(cells.grapheme(index))
                TerminalCellWidth.WideSpacerTail,
                TerminalCellWidth.WideSpacerHead -> Unit
            }
        }
    }

    private class RecordingTransport : TerminalTransport {
        val writes = mutableListOf<ByteArray>()
        val sizes = mutableListOf<TerminalSize>()

        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override fun resize(size: TerminalSize) {
            sizes += size
        }
    }

    private companion object {
        const val IosLifecycleIterations = 50
        const val IosReplayLines = 1_000
        const val IosReplayChunkBytes = 4_096

        val LightTheme =
            TerminalTheme(
                foreground = TerminalRgb(32, 32, 32),
                background = TerminalRgb(250, 250, 250),
                cursor = TerminalRgb(32, 32, 32),
            )
    }
}
