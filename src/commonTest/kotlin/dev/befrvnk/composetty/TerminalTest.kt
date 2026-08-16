package dev.befrvnk.composetty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class TerminalTest {
    @Test
    fun `rgb converts to opaque argb`() {
        assertEquals(0xff123456.toInt(), TerminalRgb(0x12, 0x34, 0x56).argb)
    }

    @Test
    fun `rgb rejects invalid components`() {
        assertFailsWith<IllegalArgumentException> { TerminalRgb(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TerminalRgb(0, 256, 0) }
    }

    @Test
    fun `snapshot protocol decodes explicitly little endian data`() {
        val grapheme = "🚀\u200d✨"
        val graphemeBytes = grapheme.encodeToByteArray()
        val bytes = ByteArray(14 * Int.SIZE_BYTES + 16 + graphemeBytes.size)
        var offset = 0
        listOf(
                1,
                1,
                0,
                0,
                1,
                2,
                1,
                2,
                3,
                4,
                5,
                6,
                2,
                1,
                0,
                graphemeBytes.size,
            )
            .forEach { value ->
                bytes.writeLittleEndianInt(offset, value)
                offset += Int.SIZE_BYTES
            }
        bytes[offset++] = 7
        bytes[offset++] = 8
        bytes[offset++] = 9
        bytes[offset++] = 10
        bytes[offset++] = 11
        bytes[offset++] = 12
        bytes[offset++] = (TerminalCellFlagBold or TerminalCellFlagUnderline).toByte()
        bytes[offset++] = 1
        graphemeBytes.copyInto(bytes, offset)

        val snapshot = decodeSnapshot(bytes)

        assertEquals(1, snapshot.columns)
        assertEquals(1, snapshot.rows)
        assertEquals(TerminalCursorStyle.Underline, snapshot.cursorStyle)
        assertEquals(true, snapshot.cursorWide)
        assertEquals(0xff010203.toInt(), snapshot.defaultBackground)
        assertEquals(0xff040506.toInt(), snapshot.defaultForeground)
        assertEquals(grapheme, snapshot.cells.grapheme(0))
        assertEquals(TerminalCellWidth.Wide, snapshot.cells.width(0))
        assertEquals(0xff070809.toInt(), snapshot.cells.foreground(0))
        assertEquals(0xff0a0b0c.toInt(), snapshot.cells.background(0))
        assertEquals(
            TerminalCellFlagBold or TerminalCellFlagUnderline,
            snapshot.cells.flags(0),
        )
    }

    @Test
    fun `snapshot factory copies caller-owned cells into immutable storage`() {
        val source =
            mutableListOf(
                TerminalCell(
                    grapheme = "界",
                    width = TerminalCellWidth.Wide,
                    foreground = LightTheme.foreground.argb,
                    background = LightTheme.background.argb,
                ),
                TerminalCell(
                    grapheme = "",
                    width = TerminalCellWidth.WideSpacerTail,
                    foreground = LightTheme.foreground.argb,
                    background = LightTheme.background.argb,
                ),
            )
        val snapshot =
            TerminalSnapshot.create(
                columns = 2,
                rows = 1,
                cursorColumn = 0,
                cursorRow = 0,
                cursorVisible = true,
                cursorStyle = TerminalCursorStyle.Block,
                defaultBackground = LightTheme.background.argb,
                defaultForeground = LightTheme.foreground.argb,
                cells = source,
            )

        source[0] = source[0].copy(grapheme = "X", width = TerminalCellWidth.Narrow)

        assertEquals("界", snapshot.cells.grapheme(0))
        assertEquals(TerminalCellWidth.Wide, snapshot.cells.width(0))
        assertEquals(TerminalCellWidth.WideSpacerTail, snapshot.cells.width(1))
    }

    @Test
    fun `unicode codepoints convert without JVM character APIs`() {
        assertEquals("A", 'A'.code.toUnicodeString())
        assertEquals("🚀", 0x1f680.toUnicodeString())
        assertEquals("�", (-1).toUnicodeString())
        assertEquals("�", 0xd800.toUnicodeString())
    }

    @Test
    fun `terminal size requires positive cell and grid dimensions`() {
        assertFailsWith<IllegalArgumentException> { TerminalSize(0, 24, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, -1, 8, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, 24, 0, 16) }
        assertFailsWith<IllegalArgumentException> { TerminalSize(80, 24, 8, 0) }
    }

    @Test
    fun `empty snapshot uses requested dimensions and theme`() {
        val snapshot = TerminalSnapshot.empty(LightTheme, columns = 3, rows = 2)

        assertEquals(3, snapshot.columns)
        assertEquals(2, snapshot.rows)
        assertEquals(6, snapshot.cells.size)
        assertEquals(List(6) { "" }, List(6) { snapshot.cells.grapheme(it) })
        assertEquals(
            List(6) { LightTheme.foreground.argb },
            List(6) { snapshot.cells.foreground(it) },
        )
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        val LightTheme =
            TerminalTheme(
                foreground = TerminalRgb(32, 32, 32),
                background = TerminalRgb(250, 250, 250),
                cursor = TerminalRgb(32, 32, 32),
            )
    }
}
