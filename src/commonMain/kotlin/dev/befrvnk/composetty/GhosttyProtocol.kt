package dev.befrvnk.composetty

internal fun decodeSnapshot(bytes: ByteArray): TerminalSnapshot {
    require(bytes.size >= SnapshotHeaderBytes)
    val reader = LittleEndianReader(bytes)
    val columns = reader.readInt()
    val rows = reader.readInt()
    val cursorColumn = reader.readInt()
    val cursorRow = reader.readInt()
    val cursorVisible = reader.readInt() != 0
    val cursorStyle =
        when (reader.readInt()) {
            0 -> TerminalCursorStyle.Bar
            1 -> TerminalCursorStyle.Block
            2 -> TerminalCursorStyle.Underline
            3 -> TerminalCursorStyle.BlockHollow
            else -> TerminalCursorStyle.Block
        }
    val defaultBackground = rgb(reader.readInt(), reader.readInt(), reader.readInt())
    val defaultForeground = rgb(reader.readInt(), reader.readInt(), reader.readInt())
    require(reader.readInt() == SnapshotVersion)
    val cursorWide = reader.readInt() != 0
    require(columns > 0 && rows > 0)
    val cellCountLong = columns.toLong() * rows.toLong()
    require(cellCountLong <= Int.MAX_VALUE)
    val recordsEndLong = SnapshotHeaderBytes.toLong() + cellCountLong * SnapshotCellBytes.toLong()
    require(recordsEndLong <= bytes.size.toLong())
    val cellCount = cellCountLong.toInt()
    val graphemeBytesStart = recordsEndLong.toInt()

    val graphemeOffsets = IntArray(cellCount)
    val graphemeLengths = IntArray(cellCount)
    val foregrounds = IntArray(cellCount)
    val backgrounds = IntArray(cellCount)
    val flags = ByteArray(cellCount)
    val widths = ByteArray(cellCount)
    repeat(cellCount) { index ->
        graphemeOffsets[index] = reader.readInt()
        graphemeLengths[index] = reader.readInt()
        foregrounds[index] = rgb(reader.readByte(), reader.readByte(), reader.readByte())
        backgrounds[index] = rgb(reader.readByte(), reader.readByte(), reader.readByte())
        flags[index] = reader.readRawByte()
        widths[index] = reader.readRawByte()
    }

    val graphemes =
        Array(cellCount) { index ->
            val offset = graphemeOffsets[index]
            val length = graphemeLengths[index]
            require(offset >= 0 && length >= 0)
            val start = graphemeBytesStart.toLong() + offset.toLong()
            val end = start + length.toLong()
            require(start <= end && end <= bytes.size.toLong())
            bytes.decodeGrapheme(start.toInt(), end.toInt())
        }

    return TerminalSnapshot(
        columns = columns,
        rows = rows,
        cursorColumn = cursorColumn,
        cursorRow = cursorRow,
        cursorVisible = cursorVisible,
        cursorStyle = cursorStyle,
        cursorWide = cursorWide,
        defaultBackground = defaultBackground,
        defaultForeground = defaultForeground,
        cells =
            TerminalCells(
                graphemes = graphemes,
                widths = widths,
                foregrounds = foregrounds,
                backgrounds = backgrounds,
                flags = flags,
            ),
    )
}

internal val TerminalKey.ghosttyCode: Int
    get() =
        when (this) {
            TerminalKey.Backquote -> 1
            TerminalKey.Backslash -> 2
            TerminalKey.BracketLeft -> 3
            TerminalKey.BracketRight -> 4
            TerminalKey.Comma -> 5
            TerminalKey.Digit0 -> 6
            TerminalKey.Digit1 -> 7
            TerminalKey.Digit2 -> 8
            TerminalKey.Digit3 -> 9
            TerminalKey.Digit4 -> 10
            TerminalKey.Digit5 -> 11
            TerminalKey.Digit6 -> 12
            TerminalKey.Digit7 -> 13
            TerminalKey.Digit8 -> 14
            TerminalKey.Digit9 -> 15
            TerminalKey.Equal -> 16
            TerminalKey.A -> 20
            TerminalKey.B -> 21
            TerminalKey.C -> 22
            TerminalKey.D -> 23
            TerminalKey.E -> 24
            TerminalKey.F -> 25
            TerminalKey.G -> 26
            TerminalKey.H -> 27
            TerminalKey.I -> 28
            TerminalKey.J -> 29
            TerminalKey.K -> 30
            TerminalKey.L -> 31
            TerminalKey.M -> 32
            TerminalKey.N -> 33
            TerminalKey.O -> 34
            TerminalKey.P -> 35
            TerminalKey.Q -> 36
            TerminalKey.R -> 37
            TerminalKey.S -> 38
            TerminalKey.T -> 39
            TerminalKey.U -> 40
            TerminalKey.V -> 41
            TerminalKey.W -> 42
            TerminalKey.X -> 43
            TerminalKey.Y -> 44
            TerminalKey.Z -> 45
            TerminalKey.Minus -> 46
            TerminalKey.Period -> 47
            TerminalKey.Quote -> 48
            TerminalKey.Semicolon -> 49
            TerminalKey.Slash -> 50
            TerminalKey.Backspace -> 53
            TerminalKey.Enter -> 58
            TerminalKey.Space -> 63
            TerminalKey.Tab -> 64
            TerminalKey.Delete -> 68
            TerminalKey.End -> 69
            TerminalKey.Home -> 71
            TerminalKey.Insert -> 72
            TerminalKey.PageDown -> 73
            TerminalKey.PageUp -> 74
            TerminalKey.ArrowDown -> 75
            TerminalKey.ArrowLeft -> 76
            TerminalKey.ArrowRight -> 77
            TerminalKey.ArrowUp -> 78
            TerminalKey.Escape -> 120
            TerminalKey.F1 -> 121
            TerminalKey.F2 -> 122
            TerminalKey.F3 -> 123
            TerminalKey.F4 -> 124
            TerminalKey.F5 -> 125
            TerminalKey.F6 -> 126
            TerminalKey.F7 -> 127
            TerminalKey.F8 -> 128
            TerminalKey.F9 -> 129
            TerminalKey.F10 -> 130
            TerminalKey.F11 -> 131
            TerminalKey.F12 -> 132
        }

internal val TerminalKeyAction.ghosttyCode: Int
    get() =
        when (this) {
            TerminalKeyAction.Release -> 0
            TerminalKeyAction.Press -> 1
        }

internal val Set<TerminalKeyModifier>.ghosttyMask: Int
    get() =
        fold(0) { mask, modifier ->
            mask or
                when (modifier) {
                    TerminalKeyModifier.Shift -> 1 shl 0
                    TerminalKeyModifier.Control -> 1 shl 1
                    TerminalKeyModifier.Alt -> 1 shl 2
                    TerminalKeyModifier.Super -> 1 shl 3
                }
        }

private class LittleEndianReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readInt(): Int {
        require(offset <= bytes.size - Int.SIZE_BYTES)
        val result =
            readByteAt(offset) or
                (readByteAt(offset + 1) shl 8) or
                (readByteAt(offset + 2) shl 16) or
                (readByteAt(offset + 3) shl 24)
        offset += Int.SIZE_BYTES
        return result
    }

    fun readByte(): Int = readRawByte().toInt() and 0xff

    fun readRawByte(): Byte {
        require(offset < bytes.size)
        return bytes[offset++]
    }

    private fun readByteAt(index: Int): Int = bytes[index].toInt() and 0xff
}

private fun ByteArray.decodeGrapheme(startIndex: Int, endIndex: Int): String {
    if (startIndex == endIndex) return ""
    if (endIndex == startIndex + 1) {
        val value = this[startIndex].toInt() and 0xff
        if (value < AsciiGraphemes.size) return AsciiGraphemes[value]
    }
    return decodeToString(
        startIndex = startIndex,
        endIndex = endIndex,
        throwOnInvalidSequence = true,
    )
}

private fun rgb(red: Int, green: Int, blue: Int): Int =
    OpaqueAlpha or (red shl 16) or (green shl 8) or blue

private val AsciiGraphemes = Array(128) { it.toChar().toString() }
private const val SnapshotVersion = 2
private const val SnapshotHeaderBytes = 14 * Int.SIZE_BYTES
private const val SnapshotCellBytes = 16
private const val OpaqueAlpha = -0x1000000
