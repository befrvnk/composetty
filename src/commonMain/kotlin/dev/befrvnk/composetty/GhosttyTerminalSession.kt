package dev.befrvnk.composetty

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Creates transport-neutral terminal sessions backed by libghostty-vt. */
public class GhosttyTerminalSessionFactory : TerminalSessionFactory {
    private val native: GhosttyNativeLibrary by lazy { loadGhosttyNativeLibrary().getOrThrow() }

    /** Creates a native terminal and connects its generated input to [transport]. */
    override fun create(
        initialTheme: TerminalTheme,
        transport: TerminalTransport,
    ): TerminalSession = GhosttyTerminalSession(native, initialTheme, transport)
}

internal class GhosttyTerminalSession(
    private val native: GhosttyNativeLibrary,
    initialTheme: TerminalTheme,
    private val transport: TerminalTransport,
) : TerminalSession {
    private val nativeHandle =
        native.create(
            columns = InitialTerminalColumns,
            rows = InitialTerminalRows,
            maxScrollback = TerminalScrollbackLines,
        )
    private val lock = reentrantLock()
    private var closed = false
    private val mutableSnapshot =
        MutableStateFlow(
            TerminalSnapshot.empty(
                theme = initialTheme,
                columns = InitialTerminalColumns,
                rows = InitialTerminalRows,
            )
        )
    private var currentTheme = initialTheme
    override val snapshot: StateFlow<TerminalSnapshot> = mutableSnapshot.asStateFlow()

    init {
        try {
            lock.withLock {
                native.setColors(nativeHandle, initialTheme)
                mutableSnapshot.value = native.snapshot(nativeHandle)
            }
        } catch (error: Throwable) {
            closed = true
            runCatching { native.destroy(nativeHandle) }
            throw error
        }
    }

    override fun receive(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.withLock {
            if (closed) return@withLock
            native.write(nativeHandle, bytes)
            mutableSnapshot.value = native.snapshot(nativeHandle)
            flushNativePtyWrites()
        }
    }

    override fun sendKey(event: TerminalKeyEvent) {
        lock.withLock {
            if (closed) return@withLock
            writeTransport(native.encodeKey(nativeHandle, event))
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        lock.withLock {
            if (closed) return@withLock
            writeTransport(text.encodeToByteArray())
        }
    }

    override fun paste(text: String) {
        if (text.isEmpty()) return
        lock.withLock {
            if (closed) return@withLock
            writeTransport(native.encodePaste(nativeHandle, text))
        }
    }

    override fun select(start: TerminalCellPosition, end: TerminalCellPosition) {
        lock.withLock {
            if (closed) return@withLock
            val visible = mutableSnapshot.value
            require(start.column < visible.columns && end.column < visible.columns)
            require(start.row < visible.rows && end.row < visible.rows)
            native.select(nativeHandle, start, end)
            mutableSnapshot.value = native.snapshot(nativeHandle)
        }
    }

    override fun clearSelection() {
        lock.withLock {
            if (closed) return@withLock
            native.clearSelection(nativeHandle)
            mutableSnapshot.value = native.snapshot(nativeHandle)
        }
    }

    override fun selectedText(): String? = lock.withLock {
        if (closed) return@withLock null
        native.selectedText(nativeHandle)
    }

    override fun resize(size: TerminalSize) {
        lock.withLock {
            if (closed) return@withLock
            native.resize(
                nativeHandle,
                columns = size.columns,
                rows = size.rows,
                cellWidth = size.cellWidth,
                cellHeight = size.cellHeight,
            )
            mutableSnapshot.value = native.snapshot(nativeHandle)
            transport.resize(size)
            flushNativePtyWrites()
        }
    }

    override fun scroll(rows: Int) {
        if (rows == 0) return
        lock.withLock {
            if (closed) return@withLock
            native.scroll(nativeHandle, rows.toLong())
            mutableSnapshot.value = native.snapshot(nativeHandle)
            flushNativePtyWrites()
        }
    }

    override fun updateTheme(theme: TerminalTheme) {
        lock.withLock {
            if (closed || currentTheme == theme) return@withLock
            native.setColors(nativeHandle, theme)
            currentTheme = theme
            mutableSnapshot.value = native.snapshot(nativeHandle)
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            native.destroy(nativeHandle)
        }
    }

    private fun flushNativePtyWrites() {
        while (true) {
            val bytes = native.drainPtyWrites(nativeHandle)
            if (bytes.isEmpty()) return
            writeTransport(bytes)
        }
    }

    private fun writeTransport(bytes: ByteArray) {
        if (bytes.isNotEmpty()) transport.write(bytes)
    }
}

internal data class GhosttyTerminalHandle(internal val value: Long)

internal interface GhosttyNativeLibrary {
    fun create(columns: Int, rows: Int, maxScrollback: Int): GhosttyTerminalHandle

    fun destroy(handle: GhosttyTerminalHandle)

    fun write(handle: GhosttyTerminalHandle, bytes: ByteArray)

    fun resize(
        handle: GhosttyTerminalHandle,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    )

    fun scroll(handle: GhosttyTerminalHandle, rows: Long)

    fun setColors(handle: GhosttyTerminalHandle, theme: TerminalTheme)

    fun snapshot(handle: GhosttyTerminalHandle): TerminalSnapshot

    fun encodeKey(handle: GhosttyTerminalHandle, event: TerminalKeyEvent): ByteArray

    fun encodePaste(handle: GhosttyTerminalHandle, text: String): ByteArray

    fun select(
        handle: GhosttyTerminalHandle,
        start: TerminalCellPosition,
        end: TerminalCellPosition,
    )

    fun clearSelection(handle: GhosttyTerminalHandle)

    fun selectedText(handle: GhosttyTerminalHandle): String?

    fun drainPtyWrites(handle: GhosttyTerminalHandle): ByteArray
}

internal expect fun loadGhosttyNativeLibrary(): Result<GhosttyNativeLibrary>

private const val InitialTerminalColumns = 80
private const val InitialTerminalRows = 24
private const val TerminalScrollbackLines = 10_000
