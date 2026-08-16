package dev.befrvnk.composetty

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class GhosttyAndroidIntegrationTest {
    @Test
    fun nativeTerminalParsesOutputAndEncodesInput() {
        val transport = RecordingTransport()
        val session = GhosttyTerminalSessionFactory().create(LightTheme, transport)
        try {
            session.receive("hello \u001b[1;32mandroid\u001b[0m".encodeToByteArray())

            val snapshot = session.snapshot.value
            assertEquals("hello android", snapshot.rowText(0).trimEnd())
            assertEquals(
                TerminalCellFlagBold,
                snapshot.cells.flags(6) and TerminalCellFlagBold,
            )

            session.sendText("echo hello\r")
            assertContentEquals("echo hello\r".encodeToByteArray(), transport.writes.single())
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
    fun concurrentCallsAndCloseAreSerialized() {
        val session = GhosttyTerminalSessionFactory().create(LightTheme, RecordingTransport())
        val executor = Executors.newFixedThreadPool(AndroidStressWorkers)
        val start = CountDownLatch(1)
        try {
            val workers =
                List(AndroidStressWorkers) { worker ->
                    executor.submit {
                        start.await()
                        repeat(AndroidStressIterations) { iteration ->
                            when (worker) {
                                0 -> session.receive("android-$iteration\r\n".encodeToByteArray())
                                1 -> session.sendText("input-$iteration")
                                2 ->
                                    session.resize(
                                        TerminalSize(
                                            columns = 40 + iteration % 2,
                                            rows = 12,
                                            cellWidth = 8,
                                            cellHeight = 16,
                                        )
                                    )
                                3 -> {
                                    assertTrue(session.snapshot.value.cells.size > 0)
                                    if (iteration == AndroidStressIterations / 2) session.close()
                                }
                            }
                        }
                    }
                }
            start.countDown()
            workers.forEach { it.get(AndroidStressTimeoutSeconds, TimeUnit.SECONDS) }
            session.receive("ignored after close".encodeToByteArray())
            assertTrue(session.snapshot.value.cells.size > 0)
        } finally {
            session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(AndroidStressTimeoutSeconds, TimeUnit.SECONDS))
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

        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override fun resize(size: TerminalSize) = Unit
    }

    private companion object {
        const val AndroidStressWorkers = 4
        const val AndroidStressIterations = 100
        const val AndroidStressTimeoutSeconds = 20L

        val LightTheme =
            TerminalTheme(
                foreground = TerminalRgb(32, 32, 32),
                background = TerminalRgb(250, 250, 250),
                cursor = TerminalRgb(32, 32, 32),
            )
    }
}
