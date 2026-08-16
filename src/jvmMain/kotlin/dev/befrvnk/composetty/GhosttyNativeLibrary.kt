package dev.befrvnk.composetty

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import java.nio.file.Path

internal class JvmGhosttyNativeLibrary private constructor(private val bindings: GhosttyBindings) :
    GhosttyNativeLibrary {
    override fun create(columns: Int, rows: Int, maxScrollback: Int): GhosttyTerminalHandle {
        val pointer =
            bindings.composetty_terminal_create(
                columns.toShort(),
                rows.toShort(),
                NativeLong(maxScrollback.toLong()),
            )
        check(pointer != null && Pointer.nativeValue(pointer) != 0L) {
            "libghostty could not create a terminal"
        }
        return GhosttyTerminalHandle(Pointer.nativeValue(pointer))
    }

    override fun destroy(handle: GhosttyTerminalHandle) {
        bindings.composetty_terminal_destroy(handle.pointer)
    }

    override fun write(handle: GhosttyTerminalHandle, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val input = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        check(
            bindings.composetty_terminal_write(
                handle.pointer,
                input,
                NativeLong(bytes.size.toLong()),
            ) == NativeSuccess
        )
    }

    override fun resize(
        handle: GhosttyTerminalHandle,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ) {
        check(
            bindings.composetty_terminal_resize(
                handle.pointer,
                columns.toShort(),
                rows.toShort(),
                cellWidth,
                cellHeight,
            ) == NativeSuccess
        )
    }

    override fun scroll(handle: GhosttyTerminalHandle, rows: Long) {
        bindings.composetty_terminal_scroll(handle.pointer, NativeLong(rows))
    }

    override fun setColors(handle: GhosttyTerminalHandle, theme: TerminalTheme) {
        check(
            bindings.composetty_terminal_set_colors(
                handle.pointer,
                theme.foreground.red.toByte(),
                theme.foreground.green.toByte(),
                theme.foreground.blue.toByte(),
                theme.background.red.toByte(),
                theme.background.green.toByte(),
                theme.background.blue.toByte(),
                theme.cursor.red.toByte(),
                theme.cursor.green.toByte(),
                theme.cursor.blue.toByte(),
            ) == NativeSuccess
        )
    }

    override fun snapshot(handle: GhosttyTerminalHandle): TerminalSnapshot {
        val size = NativeLongByReference()
        val pointer = bindings.composetty_terminal_snapshot(handle.pointer, size)
        val byteCount = size.value.toLong()
        check(pointer != null && byteCount > 0) { "libghostty returned no snapshot" }
        require(byteCount <= Int.MAX_VALUE)
        return decodeSnapshot(pointer.getByteArray(0, byteCount.toInt()))
    }

    override fun encodeKey(handle: GhosttyTerminalHandle, event: TerminalKeyEvent): ByteArray {
        val textBytes = event.text.encodeToByteArray()
        val textMemory =
            textBytes.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            }
        val output = Memory(MaximumEncodedKeyBytes.toLong())
        val written =
            bindings
                .composetty_terminal_encode_key(
                    terminal = handle.pointer,
                    key = event.key.ghosttyCode,
                    action = event.action.ghosttyCode,
                    modifiers = event.modifiers.ghosttyMask.toShort(),
                    unshiftedCodepoint = event.unshiftedCodepoint,
                    utf8 = textMemory,
                    utf8Length = NativeLong(textBytes.size.toLong()),
                    output = output,
                    outputCapacity = NativeLong(MaximumEncodedKeyBytes.toLong()),
                )
                .toLong()
        return if (written <= 0) ByteArray(0) else output.getByteArray(0, written.toInt())
    }

    override fun encodePaste(handle: GhosttyTerminalHandle, text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val input = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val outputSize = Math.addExact(bytes.size, PasteMarkerBytes)
        val output = Memory(outputSize.toLong())
        val written =
            bindings
                .composetty_terminal_encode_paste(
                    handle.pointer,
                    input,
                    NativeLong(bytes.size.toLong()),
                    output,
                    NativeLong(outputSize.toLong()),
                )
                .toLong()
        check(written > 0 && written <= outputSize)
        return output.getByteArray(0, written.toInt())
    }

    override fun select(
        handle: GhosttyTerminalHandle,
        start: TerminalCellPosition,
        end: TerminalCellPosition,
    ) {
        check(
            bindings.composetty_terminal_select(
                handle.pointer,
                start.column.toShort(),
                start.row.toShort(),
                end.column.toShort(),
                end.row.toShort(),
            ) == NativeSuccess
        )
    }

    override fun clearSelection(handle: GhosttyTerminalHandle) {
        bindings.composetty_terminal_clear_selection(handle.pointer)
    }

    override fun selectedText(handle: GhosttyTerminalHandle): String? {
        val size = NativeLongByReference()
        val pointer = bindings.composetty_terminal_selection(handle.pointer, size) ?: return null
        val byteCount = size.value.toLong()
        if (byteCount <= 0) return null
        require(byteCount <= Int.MAX_VALUE)
        return pointer.getByteArray(0, byteCount.toInt()).decodeToString()
    }

    override fun drainPtyWrites(handle: GhosttyTerminalHandle): ByteArray {
        val output = Memory(MaximumPtyResponseBytes.toLong())
        val written =
            bindings
                .composetty_terminal_drain_pty_writes(
                    handle.pointer,
                    output,
                    NativeLong(MaximumPtyResponseBytes.toLong()),
                )
                .toLong()
        return if (written <= 0) ByteArray(0) else output.getByteArray(0, written.toInt())
    }

    companion object {
        fun load(path: String): JvmGhosttyNativeLibrary {
            val absolutePath = Path.of(path).toAbsolutePath().toString()
            return JvmGhosttyNativeLibrary(Native.load(absolutePath, GhosttyBindings::class.java))
        }
    }
}

private val GhosttyTerminalHandle.pointer: Pointer
    get() = Pointer(value)

private interface GhosttyBindings : Library {
    fun composetty_terminal_create(columns: Short, rows: Short, maxScrollback: NativeLong): Pointer?

    fun composetty_terminal_destroy(terminal: Pointer)

    fun composetty_terminal_write(terminal: Pointer, data: Pointer, length: NativeLong): Int

    fun composetty_terminal_resize(
        terminal: Pointer,
        columns: Short,
        rows: Short,
        cellWidth: Int,
        cellHeight: Int,
    ): Int

    fun composetty_terminal_scroll(terminal: Pointer, rows: NativeLong)

    fun composetty_terminal_set_colors(
        terminal: Pointer,
        foregroundRed: Byte,
        foregroundGreen: Byte,
        foregroundBlue: Byte,
        backgroundRed: Byte,
        backgroundGreen: Byte,
        backgroundBlue: Byte,
        cursorRed: Byte,
        cursorGreen: Byte,
        cursorBlue: Byte,
    ): Int

    fun composetty_terminal_snapshot(terminal: Pointer, size: NativeLongByReference): Pointer?

    fun composetty_terminal_encode_key(
        terminal: Pointer,
        key: Int,
        action: Int,
        modifiers: Short,
        unshiftedCodepoint: Int,
        utf8: Pointer?,
        utf8Length: NativeLong,
        output: Pointer,
        outputCapacity: NativeLong,
    ): NativeLong

    fun composetty_terminal_encode_paste(
        terminal: Pointer,
        data: Pointer,
        dataLength: NativeLong,
        output: Pointer,
        outputCapacity: NativeLong,
    ): NativeLong

    fun composetty_terminal_select(
        terminal: Pointer,
        startColumn: Short,
        startRow: Short,
        endColumn: Short,
        endRow: Short,
    ): Int

    fun composetty_terminal_clear_selection(terminal: Pointer)

    fun composetty_terminal_selection(terminal: Pointer, size: NativeLongByReference): Pointer?

    fun composetty_terminal_drain_pty_writes(
        terminal: Pointer,
        output: Pointer,
        outputCapacity: NativeLong,
    ): NativeLong
}

private const val NativeSuccess = 0
private const val PasteMarkerBytes = 12
private const val MaximumEncodedKeyBytes = 256
private const val MaximumPtyResponseBytes = 4096
