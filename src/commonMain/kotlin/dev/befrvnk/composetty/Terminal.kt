package dev.befrvnk.composetty

import kotlinx.coroutines.flow.StateFlow

/**
 * A running terminal whose visible cells are exposed as immutable snapshots.
 *
 * Library-provided sessions serialize concurrent calls. Calls made after [close] return without
 * changing terminal or transport state.
 */
public interface TerminalSession : AutoCloseable {
    /** The latest immutable viewport snapshot. */
    public val snapshot: StateFlow<TerminalSnapshot>

    /**
     * Feeds bytes received from a remote process into the terminal emulator.
     *
     * The byte array is consumed synchronously and is not retained.
     */
    public fun receive(bytes: ByteArray)

    /** Encodes and forwards one physical or synthetic keyboard event. */
    public fun sendKey(event: TerminalKeyEvent)

    /** Sends text as typed input without paste filtering or bracketed-paste markers. */
    public fun sendText(text: String)

    /** Sends clipboard text using the terminal's current bracketed-paste mode and safety rules. */
    public fun paste(text: String)

    /** Selects an inclusive range of cells in the visible viewport. */
    public fun select(start: TerminalCellPosition, end: TerminalCellPosition)

    /** Clears the active selection. */
    public fun clearSelection()

    /** Returns the active selection as plain text, or null when nothing is selected. */
    public fun selectedText(): String?

    /** Updates the terminal grid and reports the new dimensions to the transport. */
    public fun resize(size: TerminalSize)

    /** Moves the visible viewport by [rows]; positive values move into older scrollback. */
    public fun scroll(rows: Int)

    /** Applies default foreground, background, and cursor colors. */
    public fun updateTheme(theme: TerminalTheme)
}

/**
 * Receives input and resize requests produced by a terminal session.
 *
 * Implementations must return promptly. A transport backed by suspending I/O should enqueue these
 * events and perform the actual I/O in its own coroutine. Callbacks run while the session is
 * serializing native state; implementations must not synchronously call back into the session.
 */
public interface TerminalTransport {
    /** Writes terminal-generated input bytes to the remote process. The array may be retained. */
    public fun write(bytes: ByteArray)

    /** Reports a terminal grid resize to the remote process. */
    public fun resize(size: TerminalSize)
}

/** Creates a terminal session connected to a consumer-owned [TerminalTransport]. */
public fun interface TerminalSessionFactory {
    public fun create(initialTheme: TerminalTheme, transport: TerminalTransport): TerminalSession
}

/** A zero-based position in the currently visible terminal viewport. */
public data class TerminalCellPosition(public val column: Int, public val row: Int) {
    init {
        require(column >= 0)
        require(row >= 0)
    }
}

/** Terminal grid dimensions and the rendered pixel dimensions of one cell. */
public data class TerminalSize(
    public val columns: Int,
    public val rows: Int,
    public val cellWidth: Int,
    public val cellHeight: Int,
) {
    init {
        require(columns > 0)
        require(rows > 0)
        require(cellWidth > 0)
        require(cellHeight > 0)
    }
}

/** A keyboard event in the platform-neutral form consumed by Ghostty's key encoder. */
public data class TerminalKeyEvent(
    public val key: TerminalKey,
    public val action: TerminalKeyAction,
    public val modifiers: Set<TerminalKeyModifier>,
    public val unshiftedCodepoint: Int,
    public val text: String,
)

/** Physical key identities supported by the terminal key encoder. */
public enum class TerminalKey {
    Backquote,
    Backslash,
    BracketLeft,
    BracketRight,
    Comma,
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
    Equal,
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    Minus,
    Period,
    Quote,
    Semicolon,
    Slash,
    Backspace,
    Enter,
    Space,
    Tab,
    Delete,
    End,
    Home,
    Insert,
    PageDown,
    PageUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    ArrowUp,
    Escape,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

/** Whether a key was pressed or released. */
public enum class TerminalKeyAction {
    Release,
    Press,
}

/** Modifier keys active for a [TerminalKeyEvent]. */
public enum class TerminalKeyModifier {
    Shift,
    Control,
    Alt,
    Super,
}

/** An immutable view of the terminal's visible grid at a point in time. */
public class TerminalSnapshot
internal constructor(
    public val columns: Int,
    public val rows: Int,
    public val cursorColumn: Int,
    public val cursorRow: Int,
    public val cursorVisible: Boolean,
    public val cursorStyle: TerminalCursorStyle,
    /** Whether the cursor is on the trailing cell of a double-width grapheme. */
    public val cursorWide: Boolean,
    public val defaultBackground: Int,
    public val defaultForeground: Int,
    public val cells: TerminalCells,
) {
    init {
        require(columns > 0 && rows > 0)
        require(columns.toLong() * rows.toLong() == cells.size.toLong())
    }

    public companion object {
        /** Creates a snapshot from a row-major list of [TerminalCell] values. */
        public fun create(
            columns: Int,
            rows: Int,
            cursorColumn: Int,
            cursorRow: Int,
            cursorVisible: Boolean,
            cursorStyle: TerminalCursorStyle,
            defaultBackground: Int,
            defaultForeground: Int,
            cells: List<TerminalCell>,
            cursorWide: Boolean = false,
        ): TerminalSnapshot {
            require(columns > 0 && rows > 0)
            require(columns.toLong() * rows.toLong() == cells.size.toLong())
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
                cells = TerminalCells.from(cells),
            )
        }

        /** Creates a blank snapshot using [theme] for every cell and the cursor. */
        public fun empty(
            theme: TerminalTheme,
            columns: Int = DefaultTerminalColumns,
            rows: Int = DefaultTerminalRows,
        ): TerminalSnapshot {
            require(columns > 0 && rows > 0)
            val cellCountLong = columns.toLong() * rows.toLong()
            require(cellCountLong <= Int.MAX_VALUE)
            val cellCount = cellCountLong.toInt()
            return TerminalSnapshot(
                columns = columns,
                rows = rows,
                cursorColumn = 0,
                cursorRow = 0,
                cursorVisible = true,
                cursorStyle = TerminalCursorStyle.Block,
                cursorWide = false,
                defaultBackground = theme.background.argb,
                defaultForeground = theme.foreground.argb,
                cells =
                    TerminalCells(
                        graphemes = Array(cellCount) { "" },
                        widths = ByteArray(cellCount),
                        foregrounds = IntArray(cellCount) { theme.foreground.argb },
                        backgrounds = IntArray(cellCount) { theme.background.argb },
                        flags = ByteArray(cellCount),
                    ),
            )
        }
    }
}

/**
 * Read-only, row-major terminal cell storage.
 *
 * A cell at `(column, row)` has index `row * TerminalSnapshot.columns + column`.
 */
public class TerminalCells
internal constructor(
    internal val graphemes: Array<String>,
    internal val widths: ByteArray,
    internal val foregrounds: IntArray,
    internal val backgrounds: IntArray,
    internal val flags: ByteArray,
) {
    public val size: Int
        get() = graphemes.size

    init {
        require(widths.size == size)
        require(foregrounds.size == size)
        require(backgrounds.size == size)
        require(flags.size == size)
        widths.forEach { TerminalCellWidth.fromProtocol(it) }
    }

    /** Returns the complete Unicode grapheme for [index], or an empty string for no text. */
    public fun grapheme(index: Int): String = graphemes[index]

    /** Returns the grid-width role of the cell at [index]. */
    public fun width(index: Int): TerminalCellWidth = TerminalCellWidth.fromProtocol(widths[index])

    /** Returns the cell's foreground as an ARGB color. */
    public fun foreground(index: Int): Int = foregrounds[index]

    /** Returns the cell's background as an ARGB color. */
    public fun background(index: Int): Int = backgrounds[index]

    /** Returns the bitwise combination of `TerminalCellFlag*` values for [index]. */
    public fun flags(index: Int): Int = flags[index].toInt() and 0xff

    /** Materializes the cell at [index]. Prefer individual accessors in rendering loops. */
    public operator fun get(index: Int): TerminalCell =
        TerminalCell(
            grapheme = grapheme(index),
            width = width(index),
            foreground = foreground(index),
            background = background(index),
            flags = flags(index),
        )

    internal companion object {
        fun from(cells: List<TerminalCell>): TerminalCells =
            TerminalCells(
                graphemes = Array(cells.size) { cells[it].grapheme },
                widths = ByteArray(cells.size) { cells[it].width.protocolValue },
                foregrounds = IntArray(cells.size) { cells[it].foreground },
                backgrounds = IntArray(cells.size) { cells[it].background },
                flags = ByteArray(cells.size) { cells[it].flags.toByte() },
            )
    }
}

/**
 * A materialized terminal cell, primarily used to construct custom snapshots.
 *
 * [foreground] and [background] are ARGB colors. [flags] combines `TerminalCellFlag*` values.
 */
public data class TerminalCell(
    public val grapheme: String,
    public val width: TerminalCellWidth,
    public val foreground: Int,
    public val background: Int,
    public val flags: Int = 0,
) {
    init {
        require(flags in 0..0xff)
        when (width) {
            TerminalCellWidth.Wide -> require(grapheme.isNotEmpty())
            TerminalCellWidth.WideSpacerTail,
            TerminalCellWidth.WideSpacerHead -> require(grapheme.isEmpty())
            TerminalCellWidth.Narrow -> Unit
        }
    }
}

/** A cell's role in the terminal grid when rendering single- and double-width graphemes. */
public enum class TerminalCellWidth(internal val protocolValue: Byte) {
    /** An ordinary one-column cell. */
    Narrow(0),

    /** The leading cell of a two-column grapheme. */
    Wide(1),

    /** The non-rendering trailing cell of a two-column grapheme. */
    WideSpacerTail(2),

    /** A non-rendering placeholder at a soft-wrapped row boundary. */
    WideSpacerHead(3);

    internal companion object {
        fun fromProtocol(value: Byte): TerminalCellWidth =
            when (value.toInt()) {
                0 -> Narrow
                1 -> Wide
                2 -> WideSpacerTail
                3 -> WideSpacerHead
                else -> throw IllegalArgumentException("Unknown terminal cell width: $value")
            }
    }
}

/** Visual shape of the terminal cursor. */
public enum class TerminalCursorStyle {
    Bar,
    Block,
    Underline,
    BlockHollow,
}

/** Default terminal colors used when cells do not provide explicit colors. */
public data class TerminalTheme(
    public val foreground: TerminalRgb,
    public val background: TerminalRgb,
    public val cursor: TerminalRgb,
)

/** An opaque RGB color whose components are each in the range 0 through 255. */
public data class TerminalRgb(public val red: Int, public val green: Int, public val blue: Int) {
    init {
        require(red in ColorComponentRange)
        require(green in ColorComponentRange)
        require(blue in ColorComponentRange)
    }

    /** This color encoded as opaque ARGB. */
    public val argb: Int
        get() = OpaqueAlpha or (red shl 16) or (green shl 8) or blue
}

/** Cell text uses a bold font variant. */
public const val TerminalCellFlagBold: Int = 1

/** Cell text uses an italic font variant. */
public const val TerminalCellFlagItalic: Int = 2

/** Cell text is underlined. */
public const val TerminalCellFlagUnderline: Int = 4

/** Cell text is rendered with reduced opacity. */
public const val TerminalCellFlagFaint: Int = 8

/** Cell text has a strikethrough decoration. */
public const val TerminalCellFlagStrikethrough: Int = 16

/** Cell is part of the active selection. */
public const val TerminalCellFlagSelected: Int = 32

private const val DefaultTerminalColumns = 80
private const val DefaultTerminalRows = 24
private const val OpaqueAlpha = -0x1000000
private val ColorComponentRange = 0..255
