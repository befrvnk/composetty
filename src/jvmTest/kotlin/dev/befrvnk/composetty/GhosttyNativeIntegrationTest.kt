package dev.befrvnk.composetty

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class GhosttyNativeIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `native terminal parses styled output and encodes keyboard input`() {
        assumeSupportedPlatform()
        val native = loadGhosttyNativeLibrary().getOrThrow()
        val handle = native.create(columns = 24, rows = 4, maxScrollback = 100)
        try {
            native.setColors(handle, LightTheme)
            native.write(handle, "hello \u001b[1;32mghostty\u001b[0m".encodeToByteArray())

            val snapshot = native.snapshot(handle)
            assertEquals(4, snapshot.rows)
            assertEquals(24, snapshot.columns)
            assertEquals("hello ghostty", snapshot.rowText(0).trimEnd())
            assertEquals(
                TerminalCellFlagBold,
                snapshot.cells.flags(6) and TerminalCellFlagBold,
            )

            val encoded =
                native.encodeKey(
                    handle,
                    TerminalKeyEvent(
                        key = TerminalKey.A,
                        action = TerminalKeyAction.Press,
                        modifiers = emptySet(),
                        unshiftedCodepoint = 'a'.code,
                        text = "a",
                    ),
                )
            assertEquals("a", encoded.decodeToString())
        } finally {
            native.destroy(handle)
        }
    }

    @Test
    fun `native snapshot preserves grapheme clusters and wide cell structure`() {
        assumeSupportedPlatform()
        val native = loadGhosttyNativeLibrary().getOrThrow()
        val handle = native.create(columns = 12, rows = 2, maxScrollback = 100)
        try {
            native.write(handle, "\u001b[?2027he\u0301界👩🏽\u200d💻".encodeToByteArray())

            val cells = native.snapshot(handle).cells
            assertEquals("e\u0301", cells.grapheme(0))
            assertEquals(TerminalCellWidth.Narrow, cells.width(0))
            assertEquals("界", cells.grapheme(1))
            assertEquals(TerminalCellWidth.Wide, cells.width(1))
            assertEquals("", cells.grapheme(2))
            assertEquals(TerminalCellWidth.WideSpacerTail, cells.width(2))
            assertEquals("👩🏽\u200d💻", cells.grapheme(3))
            assertEquals(TerminalCellWidth.Wide, cells.width(3))
            assertEquals(TerminalCellWidth.WideSpacerTail, cells.width(4))

            native.write(handle, "\u001b[2J\u001b[H界\u001b[D".encodeToByteArray())
            val wideCursor = native.snapshot(handle)
            assertEquals(1, wideCursor.cursorColumn)
            assertTrue(wideCursor.cursorWide)
        } finally {
            native.destroy(handle)
        }
    }

    @Test
    fun `native terminal selects text and encodes bracketed paste`() {
        assumeSupportedPlatform()
        val native = loadGhosttyNativeLibrary().getOrThrow()
        val handle = native.create(columns = 20, rows = 3, maxScrollback = 100)
        try {
            native.write(handle, "hello world\r\nsecond line".encodeToByteArray())
            native.select(
                handle,
                TerminalCellPosition(column = 0, row = 0),
                TerminalCellPosition(column = 4, row = 0),
            )

            val selected = native.snapshot(handle)
            assertEquals("hello", native.selectedText(handle))
            repeat(5) { index ->
                assertEquals(
                    TerminalCellFlagSelected,
                    selected.cells.flags(index) and TerminalCellFlagSelected,
                )
            }

            assertEquals("one\rtwo", native.encodePaste(handle, "one\ntwo").decodeToString())
            assertArrayEquals(
                byteArrayOf(3),
                native.encodeKey(
                    handle,
                    TerminalKeyEvent(
                        key = TerminalKey.C,
                        action = TerminalKeyAction.Press,
                        modifiers = setOf(TerminalKeyModifier.Control),
                        unshiftedCodepoint = 'c'.code,
                        text = "",
                    ),
                ),
            )
            native.write(handle, "\u001b[?2004h".encodeToByteArray())
            assertEquals(
                "\u001b[200~one\ntwo\u001b[201~",
                native.encodePaste(handle, "one\ntwo").decodeToString(),
            )

            native.clearSelection(handle)
            assertEquals(null, native.selectedText(handle))
            assertEquals(
                0,
                native.snapshot(handle).cells.flags(0) and TerminalCellFlagSelected,
            )
        } finally {
            native.destroy(handle)
        }
    }

    @Test
    fun `transport neutral session exchanges remote terminal bytes and resize events`() {
        assumeSupportedPlatform()
        val transport = RecordingTransport()
        val session = GhosttyTerminalSessionFactory().create(LightTheme, transport)
        try {
            session.receive("remote output".encodeToByteArray())
            assertEquals("remote output", session.snapshot.value.rowText(0).trimEnd())

            session.sendText("echo hello\r")
            assertArrayEquals("echo hello\r".encodeToByteArray(), transport.writes.single())

            val size = TerminalSize(columns = 32, rows = 6, cellWidth = 8, cellHeight = 16)
            session.resize(size)
            assertEquals(size, transport.sizes.single())
            assertEquals(32, session.snapshot.value.columns)
            assertEquals(6, session.snapshot.value.rows)
        } finally {
            session.close()
        }
    }

    @Test(timeout = StressTestTimeoutMillis)
    fun `native terminal handles repeated create snapshot and destroy cycles`() {
        assumeSupportedPlatform()
        val native = loadGhosttyNativeLibrary().getOrThrow()

        repeat(LifecycleIterations) { iteration ->
            val handle = native.create(columns = 20, rows = 4, maxScrollback = 100)
            try {
                native.write(handle, "lifecycle-$iteration 世界".encodeToByteArray())
                val snapshot = native.snapshot(handle)
                assertEquals(20 * 4, snapshot.cells.size)
                assertEquals("l", snapshot.cells.grapheme(0))
            } finally {
                native.destroy(handle)
            }
        }
    }

    @Test(timeout = StressTestTimeoutMillis)
    fun `session serializes concurrent input resize theme and close calls`() {
        assumeSupportedPlatform()
        val transport = ConcurrentRecordingTransport()
        val session = GhosttyTerminalSessionFactory().create(LightTheme, transport)
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val workers =
                List(6) { worker ->
                    executor.submit {
                        start.await()
                        repeat(StressIterations) { iteration ->
                            when (worker) {
                                0 -> session.receive("worker-$iteration\r\n".encodeToByteArray())
                                1 -> session.sendText("input-$iteration")
                                2 ->
                                    session.resize(
                                        TerminalSize(
                                            columns = 40 + iteration % 3,
                                            rows = 12 + iteration % 2,
                                            cellWidth = 8,
                                            cellHeight = 16,
                                        )
                                    )
                                3 -> session.scroll(if (iteration % 2 == 0) 1 else -1)
                                4 ->
                                    session.updateTheme(
                                        if (iteration % 2 == 0) LightTheme else DarkTheme
                                    )
                                5 -> {
                                    val snapshot = session.snapshot.value
                                    assertEquals(
                                        snapshot.columns * snapshot.rows,
                                        snapshot.cells.size,
                                    )
                                    if (iteration == StressIterations / 2) session.close()
                                }
                            }
                        }
                    }
                }
            start.countDown()
            workers.forEach { it.get(StressTestTimeoutMillis, TimeUnit.MILLISECONDS) }

            session.receive("ignored after close".encodeToByteArray())
            session.resize(TerminalSize(80, 24, 8, 16))
            assertTrue(session.snapshot.value.cells.size > 0)
        } finally {
            session.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(StressTestTimeoutMillis, TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout = StressTestTimeoutMillis)
    fun `large chunked terminal replay keeps snapshots consistent`() {
        assumeSupportedPlatform()
        val session =
            GhosttyTerminalSessionFactory().create(LightTheme, ConcurrentRecordingTransport())
        try {
            val replay =
                buildString {
                        append("\u001b[?2027h")
                        repeat(ReplayLines) { line ->
                            append("\u001b[3")
                            append(line % 8)
                            append("mreplay-")
                            append(line)
                            append(" 世界 👩🏽\u200d💻\u001b[0m\r\n")
                        }
                    }
                    .encodeToByteArray()
            var offset = 0
            while (offset < replay.size) {
                val end = (offset + ReplayChunkBytes).coerceAtMost(replay.size)
                session.receive(replay.copyOfRange(offset, end))
                offset = end
            }

            val snapshot = session.snapshot.value
            assertEquals(snapshot.columns * snapshot.rows, snapshot.cells.size)
            assertTrue(snapshot.allText().contains("replay-${ReplayLines - 1}"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `session executes commands in its working directory through a real pty`() {
        assumeSupportedPlatform()
        val workspace = temporaryFolder.newFolder("ghostty-workspace").toPath()
        val marker = workspace.resolve("marker.txt")
        val session = LocalPtyTerminalSessionFactory().create(workspace.toString(), LightTheme)
        try {
            Thread.sleep(ShellStartupDelayMillis)
            session.sendText("/bin/sh -c 'pwd > marker.txt'\r")
            val deadline = System.nanoTime() + PtyCommandTimeoutNanos
            while (!Files.isRegularFile(marker) && System.nanoTime() < deadline) {
                Thread.sleep(PtyPollIntervalMillis)
            }

            assertTrue(session.snapshot.value.allText(), Files.isRegularFile(marker))
            assertEquals(
                workspace.toRealPath(),
                Path.of(Files.readString(marker).trim()).toRealPath(),
            )
        } finally {
            session.close()
        }
    }

    private fun TerminalSnapshot.allText(): String =
        (0 until rows).joinToString("\n") { row -> rowText(row) }

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

    private fun assumeSupportedPlatform() {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture =
            when (System.getProperty("os.arch", "").lowercase()) {
                "aarch64",
                "arm64" -> "arm64"
                "x86_64",
                "amd64" -> "x86_64"
                else -> null
            }
        val platform =
            when {
                os.contains("mac") && architecture != null -> "macos-$architecture"
                os.contains("linux") && architecture != null -> "linux-$architecture"
                else -> null
            }
        val extension = if (os.contains("mac")) "dylib" else "so"
        val bundled = platform?.let {
            GhosttyNativeIntegrationTest::class
                .java
                .getResource("/native/ghostty/$it/libcomposetty-ghostty.$extension")
        }
        val configured = System.getenv("COMPOSETTY_GHOSTTY_LIBRARY")?.let(Path::of)
        assumeTrue(
            "Ghostty native library unavailable on this platform",
            platform != null && (bundled != null || configured?.let(Files::isRegularFile) == true),
        )
    }

    private class ConcurrentRecordingTransport : TerminalTransport {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        val sizes = Collections.synchronizedList(mutableListOf<TerminalSize>())

        override fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
        }

        override fun resize(size: TerminalSize) {
            sizes += size
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
        val LightTheme =
            TerminalTheme(
                foreground = TerminalRgb(32, 32, 32),
                background = TerminalRgb(250, 250, 250),
                cursor = TerminalRgb(32, 32, 32),
            )
        val DarkTheme =
            TerminalTheme(
                foreground = TerminalRgb(230, 230, 230),
                background = TerminalRgb(25, 25, 25),
                cursor = TerminalRgb(230, 230, 230),
            )
        const val LifecycleIterations = 100
        const val StressIterations = 200
        const val StressTestTimeoutMillis = 30_000L
        const val ReplayLines = 5_000
        const val ReplayChunkBytes = 4_096
        const val ShellStartupDelayMillis = 500L
        const val PtyCommandTimeoutNanos = 10_000_000_000L
        const val PtyPollIntervalMillis = 20L
    }
}
