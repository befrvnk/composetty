@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.befrvnk.composetty

import cnames.structs.ComposettyTerminal
import dev.befrvnk.composetty.native.composetty_terminal_clear_selection
import dev.befrvnk.composetty.native.composetty_terminal_create
import dev.befrvnk.composetty.native.composetty_terminal_destroy
import dev.befrvnk.composetty.native.composetty_terminal_drain_pty_writes
import dev.befrvnk.composetty.native.composetty_terminal_encode_key
import dev.befrvnk.composetty.native.composetty_terminal_encode_paste
import dev.befrvnk.composetty.native.composetty_terminal_resize
import dev.befrvnk.composetty.native.composetty_terminal_scroll
import dev.befrvnk.composetty.native.composetty_terminal_select
import dev.befrvnk.composetty.native.composetty_terminal_selection
import dev.befrvnk.composetty.native.composetty_terminal_set_colors
import dev.befrvnk.composetty.native.composetty_terminal_snapshot
import dev.befrvnk.composetty.native.composetty_terminal_write
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.posix.size_tVar

internal actual fun loadGhosttyNativeLibrary(): Result<GhosttyNativeLibrary> =
    Result.success(IosGhosttyNativeLibrary)

private object IosGhosttyNativeLibrary : GhosttyNativeLibrary {
    override fun create(columns: Int, rows: Int, maxScrollback: Int): GhosttyTerminalHandle {
        val pointer =
            composetty_terminal_create(
                columns.toUShort(),
                rows.toUShort(),
                maxScrollback.toULong(),
            )
        check(pointer != null) { "libghostty could not create a terminal" }
        return GhosttyTerminalHandle(pointer.toLong())
    }

    override fun destroy(handle: GhosttyTerminalHandle) {
        composetty_terminal_destroy(handle.pointer)
    }

    override fun write(handle: GhosttyTerminalHandle, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val result = bytes.usePinned { pinned ->
            composetty_terminal_write(
                handle.pointer,
                pinned.addressOf(0).reinterpret(),
                bytes.size.toULong(),
            )
        }
        check(result == NativeSuccess)
    }

    override fun resize(
        handle: GhosttyTerminalHandle,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ) {
        check(
            composetty_terminal_resize(
                handle.pointer,
                columns.toUShort(),
                rows.toUShort(),
                cellWidth.toUInt(),
                cellHeight.toUInt(),
            ) == NativeSuccess
        )
    }

    override fun scroll(handle: GhosttyTerminalHandle, rows: Long) {
        composetty_terminal_scroll(handle.pointer, rows)
    }

    override fun setColors(handle: GhosttyTerminalHandle, theme: TerminalTheme) {
        check(
            composetty_terminal_set_colors(
                handle.pointer,
                theme.foreground.red.toUByte(),
                theme.foreground.green.toUByte(),
                theme.foreground.blue.toUByte(),
                theme.background.red.toUByte(),
                theme.background.green.toUByte(),
                theme.background.blue.toUByte(),
                theme.cursor.red.toUByte(),
                theme.cursor.green.toUByte(),
                theme.cursor.blue.toUByte(),
            ) == NativeSuccess
        )
    }

    override fun snapshot(handle: GhosttyTerminalHandle): TerminalSnapshot = memScoped {
        val size = alloc<size_tVar>()
        val pointer = composetty_terminal_snapshot(handle.pointer, size.ptr)
        val byteCount = size.value
        check(pointer != null && byteCount > 0uL) { "libghostty returned no snapshot" }
        require(byteCount <= Int.MAX_VALUE.toULong())
        decodeSnapshot(copyBytes(pointer, byteCount.toInt()))
    }

    override fun encodeKey(handle: GhosttyTerminalHandle, event: TerminalKeyEvent): ByteArray {
        val textBytes = event.text.encodeToByteArray()
        val output = ByteArray(MaximumEncodedKeyBytes)
        val written = output.usePinned { pinned ->
            composetty_terminal_encode_key(
                terminal = handle.pointer,
                key = event.key.ghosttyCode,
                action = event.action.ghosttyCode,
                mods = event.modifiers.ghosttyMask.toUShort(),
                unshifted_codepoint = event.unshiftedCodepoint.toUInt(),
                utf8 = event.text.takeIf(String::isNotEmpty),
                utf8_len = textBytes.size.toULong(),
                out = pinned.addressOf(0).reinterpret(),
                out_capacity = output.size.toULong(),
            )
        }
        return output.copyOf(written.toInt())
    }

    override fun encodePaste(handle: GhosttyTerminalHandle, text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= Int.MAX_VALUE - PasteMarkerBytes)
        val output = ByteArray(bytes.size + PasteMarkerBytes)
        val written = bytes.usePinned { inputPinned ->
            output.usePinned { outputPinned ->
                composetty_terminal_encode_paste(
                    terminal = handle.pointer,
                    data = inputPinned.addressOf(0).reinterpret(),
                    data_len = bytes.size.toULong(),
                    out = outputPinned.addressOf(0).reinterpret(),
                    out_capacity = output.size.toULong(),
                )
            }
        }
        check(written > 0uL && written <= output.size.toULong())
        return output.copyOf(written.toInt())
    }

    override fun select(
        handle: GhosttyTerminalHandle,
        start: TerminalCellPosition,
        end: TerminalCellPosition,
    ) {
        check(
            composetty_terminal_select(
                handle.pointer,
                start.column.toUShort(),
                start.row.toUShort(),
                end.column.toUShort(),
                end.row.toUShort(),
            ) == NativeSuccess
        )
    }

    override fun clearSelection(handle: GhosttyTerminalHandle) {
        composetty_terminal_clear_selection(handle.pointer)
    }

    override fun selectedText(handle: GhosttyTerminalHandle): String? = memScoped {
        val size = alloc<size_tVar>()
        val pointer = composetty_terminal_selection(handle.pointer, size.ptr) ?: return null
        if (size.value == 0uL) return null
        require(size.value <= Int.MAX_VALUE.toULong())
        copyBytes(pointer, size.value.toInt()).decodeToString()
    }

    override fun drainPtyWrites(handle: GhosttyTerminalHandle): ByteArray {
        val output = ByteArray(MaximumPtyResponseBytes)
        val written = output.usePinned { pinned ->
            composetty_terminal_drain_pty_writes(
                handle.pointer,
                pinned.addressOf(0).reinterpret(),
                output.size.toULong(),
            )
        }
        return output.copyOf(written.toInt())
    }
}

private val GhosttyTerminalHandle.pointer: CPointer<ComposettyTerminal>
    get() = requireNotNull(value.toCPointer())

private fun copyBytes(source: CPointer<UByteVar>, size: Int): ByteArray =
    ByteArray(size).also { result ->
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), source, size.convert())
        }
    }

private const val NativeSuccess = 0
private const val PasteMarkerBytes = 12
private const val MaximumEncodedKeyBytes = 256
private const val MaximumPtyResponseBytes = 4096
