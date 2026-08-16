package dev.befrvnk.composetty

internal actual fun loadGhosttyNativeLibrary(): Result<GhosttyNativeLibrary> = runCatching {
    AndroidGhosttyBindings.ensureLoaded()
    AndroidGhosttyNativeLibrary
}

private object AndroidGhosttyNativeLibrary : GhosttyNativeLibrary {
    override fun create(columns: Int, rows: Int, maxScrollback: Int): GhosttyTerminalHandle =
        withNativeLock {
            val handle = AndroidGhosttyBindings.create(columns, rows, maxScrollback.toLong())
            check(handle != 0L) { "libghostty could not create a terminal" }
            GhosttyTerminalHandle(handle)
        }

    override fun destroy(handle: GhosttyTerminalHandle): Unit = withNativeLock {
        AndroidGhosttyBindings.destroy(handle.value)
    }

    override fun write(handle: GhosttyTerminalHandle, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withNativeLock { check(AndroidGhosttyBindings.write(handle.value, bytes) == NativeSuccess) }
    }

    override fun resize(
        handle: GhosttyTerminalHandle,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Unit = withNativeLock {
        check(
            AndroidGhosttyBindings.resize(
                handle.value,
                columns,
                rows,
                cellWidth,
                cellHeight,
            ) == NativeSuccess
        )
    }

    override fun scroll(handle: GhosttyTerminalHandle, rows: Long): Unit = withNativeLock {
        AndroidGhosttyBindings.scroll(handle.value, rows)
    }

    override fun setColors(handle: GhosttyTerminalHandle, theme: TerminalTheme): Unit =
        withNativeLock {
            check(
                AndroidGhosttyBindings.setColors(
                    handle.value,
                    theme.foreground.red,
                    theme.foreground.green,
                    theme.foreground.blue,
                    theme.background.red,
                    theme.background.green,
                    theme.background.blue,
                    theme.cursor.red,
                    theme.cursor.green,
                    theme.cursor.blue,
                ) == NativeSuccess
            )
        }

    override fun snapshot(handle: GhosttyTerminalHandle): TerminalSnapshot = withNativeLock {
        val bytes = AndroidGhosttyBindings.snapshot(handle.value)
        check(bytes.isNotEmpty()) { "libghostty returned no snapshot" }
        decodeSnapshot(bytes)
    }

    override fun encodeKey(handle: GhosttyTerminalHandle, event: TerminalKeyEvent): ByteArray =
        withNativeLock {
            AndroidGhosttyBindings.encodeKey(
                handle.value,
                event.key.ghosttyCode,
                event.action.ghosttyCode,
                event.modifiers.ghosttyMask,
                event.unshiftedCodepoint,
                event.text.encodeToByteArray(),
            )
        }

    override fun encodePaste(handle: GhosttyTerminalHandle, text: String): ByteArray =
        withNativeLock {
            AndroidGhosttyBindings.encodePaste(handle.value, text.encodeToByteArray()).also {
                check(it.isNotEmpty())
            }
        }

    override fun select(
        handle: GhosttyTerminalHandle,
        start: TerminalCellPosition,
        end: TerminalCellPosition,
    ): Unit = withNativeLock {
        check(
            AndroidGhosttyBindings.select(
                handle.value,
                start.column,
                start.row,
                end.column,
                end.row,
            ) == NativeSuccess
        )
    }

    override fun clearSelection(handle: GhosttyTerminalHandle): Unit = withNativeLock {
        AndroidGhosttyBindings.clearSelection(handle.value)
    }

    override fun selectedText(handle: GhosttyTerminalHandle): String? = withNativeLock {
        AndroidGhosttyBindings.selection(handle.value)
            .takeIf(ByteArray::isNotEmpty)
            ?.decodeToString()
    }

    override fun drainPtyWrites(handle: GhosttyTerminalHandle): ByteArray = withNativeLock {
        AndroidGhosttyBindings.drainPtyWrites(handle.value)
    }

    private inline fun <T> withNativeLock(action: () -> T): T =
        synchronized(AndroidGhosttyBindings, action)
}

private object AndroidGhosttyBindings {
    init {
        System.loadLibrary("composetty-ghostty")
    }

    fun ensureLoaded() = Unit

    external fun create(columns: Int, rows: Int, maxScrollback: Long): Long

    external fun destroy(handle: Long)

    external fun write(handle: Long, bytes: ByteArray): Int

    external fun resize(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int

    external fun scroll(handle: Long, rows: Long)

    external fun setColors(
        handle: Long,
        foregroundRed: Int,
        foregroundGreen: Int,
        foregroundBlue: Int,
        backgroundRed: Int,
        backgroundGreen: Int,
        backgroundBlue: Int,
        cursorRed: Int,
        cursorGreen: Int,
        cursorBlue: Int,
    ): Int

    external fun snapshot(handle: Long): ByteArray

    external fun encodeKey(
        handle: Long,
        key: Int,
        action: Int,
        modifiers: Int,
        unshiftedCodepoint: Int,
        utf8: ByteArray,
    ): ByteArray

    external fun encodePaste(handle: Long, bytes: ByteArray): ByteArray

    external fun select(
        handle: Long,
        startColumn: Int,
        startRow: Int,
        endColumn: Int,
        endRow: Int,
    ): Int

    external fun clearSelection(handle: Long)

    external fun selection(handle: Long): ByteArray

    external fun drainPtyWrites(handle: Long): ByteArray
}

private const val NativeSuccess = 0
