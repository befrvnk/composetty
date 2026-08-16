package dev.befrvnk.composetty

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class GhosttyTerminalSessionTest {
    @Test
    fun routesRemoteOutputAndTerminalResponses() {
        val native = FakeNativeLibrary()
        val transport = RecordingTransport()
        val session = GhosttyTerminalSession(native, Theme, transport)
        try {
            native.pendingPtyWrites += "\u001b[0n".encodeToByteArray()
            session.receive("remote output".encodeToByteArray())

            assertContentEquals("remote output".encodeToByteArray(), native.received.single())
            assertContentEquals("\u001b[0n".encodeToByteArray(), transport.writes.single())
        } finally {
            session.close()
        }
    }

    @Test
    fun routesUserInputAndResizeEvents() {
        val native = FakeNativeLibrary()
        val transport = RecordingTransport()
        val session = GhosttyTerminalSession(native, Theme, transport)
        try {
            session.sendText("typed")
            session.paste("pasted")
            session.sendKey(
                TerminalKeyEvent(
                    key = TerminalKey.A,
                    action = TerminalKeyAction.Press,
                    modifiers = emptySet(),
                    unshiftedCodepoint = 'a'.code,
                    text = "a",
                )
            )
            val size = TerminalSize(columns = 100, rows = 30, cellWidth = 8, cellHeight = 16)
            session.resize(size)

            assertContentEquals("typed".encodeToByteArray(), transport.writes[0])
            assertContentEquals("encoded-paste".encodeToByteArray(), transport.writes[1])
            assertContentEquals("encoded-key".encodeToByteArray(), transport.writes[2])
            assertEquals(size, transport.sizes.single())
            assertEquals(100, session.snapshot.value.columns)
            assertEquals(30, session.snapshot.value.rows)
        } finally {
            session.close()
        }
    }

    @Test
    fun updatesAndReadsNativeSelection() {
        val native = FakeNativeLibrary()
        val session = GhosttyTerminalSession(native, Theme, RecordingTransport())
        try {
            val start = TerminalCellPosition(column = 1, row = 2)
            val end = TerminalCellPosition(column = 4, row = 3)

            session.select(start, end)

            assertEquals(start to end, native.selection)
            assertEquals("selected text", session.selectedText())

            session.clearSelection()
            assertEquals(null, native.selection)
            assertEquals(null, session.selectedText())
        } finally {
            session.close()
        }
    }

    @Test
    fun closeIsIdempotentAndStopsFurtherInput() {
        val native = FakeNativeLibrary()
        val transport = RecordingTransport()
        val session = GhosttyTerminalSession(native, Theme, transport)

        session.close()
        session.close()
        session.sendText("ignored")
        session.receive("ignored".encodeToByteArray())

        assertEquals(1, native.destroyCount)
        assertEquals(0, native.received.size)
        assertEquals(0, transport.writes.size)
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

    private class FakeNativeLibrary : GhosttyNativeLibrary {
        val received = mutableListOf<ByteArray>()
        val pendingPtyWrites = mutableListOf<ByteArray>()
        var destroyCount = 0
        var selection: Pair<TerminalCellPosition, TerminalCellPosition>? = null
        private var snapshot = TerminalSnapshot.empty(Theme)

        override fun create(
            columns: Int,
            rows: Int,
            maxScrollback: Int,
        ): GhosttyTerminalHandle = GhosttyTerminalHandle(1)

        override fun destroy(handle: GhosttyTerminalHandle) {
            destroyCount++
        }

        override fun write(handle: GhosttyTerminalHandle, bytes: ByteArray) {
            received += bytes.copyOf()
        }

        override fun resize(
            handle: GhosttyTerminalHandle,
            columns: Int,
            rows: Int,
            cellWidth: Int,
            cellHeight: Int,
        ) {
            snapshot = TerminalSnapshot.empty(Theme, columns, rows)
        }

        override fun scroll(handle: GhosttyTerminalHandle, rows: Long) = Unit

        override fun setColors(handle: GhosttyTerminalHandle, theme: TerminalTheme) = Unit

        override fun snapshot(handle: GhosttyTerminalHandle): TerminalSnapshot = snapshot

        override fun encodeKey(
            handle: GhosttyTerminalHandle,
            event: TerminalKeyEvent,
        ): ByteArray = "encoded-key".encodeToByteArray()

        override fun encodePaste(handle: GhosttyTerminalHandle, text: String): ByteArray =
            "encoded-paste".encodeToByteArray()

        override fun select(
            handle: GhosttyTerminalHandle,
            start: TerminalCellPosition,
            end: TerminalCellPosition,
        ) {
            selection = start to end
        }

        override fun clearSelection(handle: GhosttyTerminalHandle) {
            selection = null
        }

        override fun selectedText(handle: GhosttyTerminalHandle): String? = selection?.let {
            "selected text"
        }

        override fun drainPtyWrites(handle: GhosttyTerminalHandle): ByteArray =
            if (pendingPtyWrites.isEmpty()) ByteArray(0) else pendingPtyWrites.removeAt(0)
    }

    private companion object {
        val Theme =
            TerminalTheme(
                foreground = TerminalRgb(230, 230, 230),
                background = TerminalRgb(25, 25, 25),
                cursor = TerminalRgb(230, 230, 230),
            )
    }
}
