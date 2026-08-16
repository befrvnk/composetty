package dev.befrvnk.composetty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Monospace font configuration used to measure and render terminal cells. */
public data class TerminalTextStyle(
    public val fontFamily: FontFamily = FontFamily.Monospace,
    public val fontSize: TextUnit = 13.sp,
)

/** Renders a [TerminalSession] and forwards keyboard, pointer, and scroll input to it. */
@Composable
public fun GhosttyTerminal(
    session: TerminalSession,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
    textStyle: TerminalTextStyle = TerminalTextStyle(),
    requestFocus: Boolean = true,
) {
    val snapshot by session.snapshot.collectAsState()
    @Suppress("DEPRECATION") val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentSession by rememberUpdatedState(session)
    val textMeasurer = rememberTextMeasurer(cacheSize = GlyphCacheSize)
    val normalStyle =
        remember(textStyle) {
            TextStyle(fontFamily = textStyle.fontFamily, fontSize = textStyle.fontSize)
        }
    val referenceLayout =
        remember(textMeasurer, normalStyle) {
            textMeasurer.measure(
                text = "M",
                style = normalStyle,
                softWrap = false,
                maxLines = 1,
            )
        }
    val cellWidth = referenceLayout.size.width.toFloat().coerceAtLeast(1f)
    val cellHeight = referenceLayout.size.height.toFloat().coerceAtLeast(1f)
    val baselineOffset = referenceLayout.firstBaseline
    val glyphLayouts =
        remember(snapshot, textMeasurer, normalStyle) {
            measureVisibleGlyphs(snapshot, textMeasurer, normalStyle)
        }

    LaunchedEffect(session, theme) { session.updateTheme(theme) }
    LaunchedEffect(session, requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }

    Box(
        modifier =
            modifier.onPreviewKeyEvent { composeEvent ->
                val terminalEvent = composeEvent.toTerminalKeyEvent()
                if (terminalEvent == null) {
                    false
                } else {
                    currentSession.sendKey(terminalEvent)
                    true
                }
            }
    ) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .testTag(TerminalCanvasTestTag)
                    .onSizeChanged { size ->
                        val columns = floor(size.width / cellWidth).toInt().coerceAtLeast(1)
                        val rows = floor(size.height / cellHeight).toInt().coerceAtLeast(1)
                        val visible = currentSnapshot
                        if (columns != visible.columns || rows != visible.rows) {
                            currentSession.resize(
                                TerminalSize(
                                    columns = columns,
                                    rows = rows,
                                    cellWidth = cellWidth.roundToInt(),
                                    cellHeight = cellHeight.roundToInt(),
                                )
                            )
                        }
                    }
                    .pointerInput(session, cellHeight) {
                        var scrollRemainder = 0f
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press) {
                                    focusRequester.requestFocus()
                                    if (
                                        currentSnapshot.cells.flags.any { flags ->
                                            flags.toInt() and TerminalCellFlagSelected != 0
                                        }
                                    ) {
                                        currentSession.clearSelection()
                                    }
                                }
                                if (event.type == PointerEventType.Scroll) {
                                    scrollRemainder +=
                                        event.changes
                                            .sumOf { it.scrollDelta.y.toDouble() }
                                            .toFloat()
                                    val scrollRows = (scrollRemainder / cellHeight).toInt()
                                    if (scrollRows != 0) {
                                        currentSession.scroll(scrollRows)
                                        scrollRemainder -= scrollRows * cellHeight
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(session, cellWidth, cellHeight) {
                        var selectionStart: TerminalCellPosition? = null
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                focusRequester.requestFocus()
                                val cell =
                                    position.toTerminalCellPosition(
                                        currentSnapshot,
                                        cellWidth,
                                        cellHeight,
                                    )
                                selectionStart = cell
                                currentSession.select(cell, cell)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                selectionStart?.let { start ->
                                    currentSession.select(
                                        start,
                                        change.position.toTerminalCellPosition(
                                            currentSnapshot,
                                            cellWidth,
                                            cellHeight,
                                        ),
                                    )
                                }
                            },
                            onDragEnd = {
                                currentSession.selectedText()?.let { selected ->
                                    @Suppress("DEPRECATION")
                                    clipboardManager.setText(AnnotatedString(selected))
                                }
                                selectionStart = null
                            },
                            onDragCancel = { selectionStart = null },
                        )
                    }
                    .pointerInput(session, cellHeight) {
                        var dragRemainder = 0f
                        detectVerticalDragGestures(
                            onDragStart = { focusRequester.requestFocus() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragRemainder -= dragAmount
                                val scrollRows = (dragRemainder / cellHeight).toInt()
                                if (scrollRows != 0) {
                                    currentSession.scroll(scrollRows)
                                    dragRemainder -= scrollRows * cellHeight
                                }
                            },
                        )
                    }
        ) {
            drawRect(Color(snapshot.defaultBackground))
            val columns = snapshot.columns
            val rows = snapshot.rows
            repeat(rows) { row ->
                val rowStart = row * columns
                val top = row * cellHeight
                var column = 0
                while (column < columns) {
                    val index = rowStart + column
                    val background = snapshot.cells.backgrounds[index]
                    var endColumn = column + 1
                    while (
                        endColumn < columns &&
                            snapshot.cells.backgrounds[rowStart + endColumn] == background
                    ) {
                        endColumn++
                    }
                    if (background != snapshot.defaultBackground) {
                        drawRect(
                            color = Color(background),
                            topLeft = Offset(column * cellWidth, top),
                            size = Size((endColumn - column) * cellWidth, cellHeight),
                        )
                    }
                    column = endColumn
                }
            }

            val selectionColor = Color(theme.foreground.argb).copy(alpha = SelectionAlpha)
            repeat(rows) { row ->
                val rowStart = row * columns
                val top = row * cellHeight
                var column = 0
                while (column < columns) {
                    if (
                        snapshot.cells.flags[column + rowStart].toInt() and
                            TerminalCellFlagSelected == 0
                    ) {
                        column++
                        continue
                    }
                    var endColumn = column + 1
                    while (
                        endColumn < columns &&
                            snapshot.cells.flags[endColumn + rowStart].toInt() and
                                TerminalCellFlagSelected != 0
                    ) {
                        endColumn++
                    }
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(column * cellWidth, top),
                        size = Size((endColumn - column) * cellWidth, cellHeight),
                    )
                    column = endColumn
                }
            }

            repeat(rows) { row ->
                val top = row * cellHeight
                val baseline = top + baselineOffset
                repeat(columns) { column ->
                    val index = row * columns + column
                    val width = snapshot.cells.width(index)
                    if (
                        width == TerminalCellWidth.WideSpacerTail ||
                            width == TerminalCellWidth.WideSpacerHead
                    ) {
                        return@repeat
                    }
                    val grapheme = snapshot.cells.graphemes[index]
                    val flags = snapshot.cells.flags[index].toInt() and 0xff
                    val foreground = Color(snapshot.cells.foregrounds[index])
                    if (grapheme.isNotEmpty() && grapheme != " ") {
                        glyphLayouts[GlyphKey(grapheme, fontVariant(flags))]?.let { layout ->
                            drawText(
                                textLayoutResult = layout,
                                color = foreground,
                                topLeft = Offset(column * cellWidth, top),
                                alpha =
                                    if (flags and TerminalCellFlagFaint != 0) FaintAlpha else 1f,
                            )
                        }
                    }
                    val left = column * cellWidth
                    val decorationWidth =
                        if (width == TerminalCellWidth.Wide) cellWidth * 2 else cellWidth
                    if (flags and TerminalCellFlagUnderline != 0) {
                        drawLine(
                            color = foreground,
                            start = Offset(left, baseline + DecorationOffset),
                            end = Offset(left + decorationWidth, baseline + DecorationOffset),
                            alpha = if (flags and TerminalCellFlagFaint != 0) FaintAlpha else 1f,
                        )
                    }
                    if (flags and TerminalCellFlagStrikethrough != 0) {
                        val strikeY = top + cellHeight / 2
                        drawLine(
                            color = foreground,
                            start = Offset(left, strikeY),
                            end = Offset(left + decorationWidth, strikeY),
                            alpha = if (flags and TerminalCellFlagFaint != 0) FaintAlpha else 1f,
                        )
                    }
                }
            }

            if (
                snapshot.cursorVisible &&
                    snapshot.cursorColumn in 0 until columns &&
                    snapshot.cursorRow in 0 until rows
            ) {
                val cursorColumn =
                    if (snapshot.cursorWide && snapshot.cursorColumn > 0) {
                        snapshot.cursorColumn - 1
                    } else {
                        snapshot.cursorColumn
                    }
                val cursorWidth = if (snapshot.cursorWide) cellWidth * 2 else cellWidth
                val left = cursorColumn * cellWidth
                val top = snapshot.cursorRow * cellHeight
                val cursorColor = Color(theme.cursor.argb)
                when (snapshot.cursorStyle) {
                    TerminalCursorStyle.Bar ->
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left, top),
                            size = Size(CursorStrokeWidth, cellHeight),
                        )
                    TerminalCursorStyle.Underline ->
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left, top + cellHeight - CursorStrokeWidth),
                            size = Size(cursorWidth, CursorStrokeWidth),
                        )
                    TerminalCursorStyle.BlockHollow -> {
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left, top),
                            size = Size(cursorWidth, CursorStrokeWidth),
                        )
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left, top + cellHeight - CursorStrokeWidth),
                            size = Size(cursorWidth, CursorStrokeWidth),
                        )
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left, top),
                            size = Size(CursorStrokeWidth, cellHeight),
                        )
                        drawRect(
                            color = cursorColor,
                            topLeft = Offset(left + cursorWidth - CursorStrokeWidth, top),
                            size = Size(CursorStrokeWidth, cellHeight),
                        )
                    }
                    TerminalCursorStyle.Block ->
                        drawRect(
                            color = cursorColor.copy(alpha = BlockCursorAlpha),
                            topLeft = Offset(left, top),
                            size = Size(cursorWidth, cellHeight),
                        )
                }
            }
        }

        TerminalTextInput(session = session, focusRequester = focusRequester)
    }
}

private fun Offset.toTerminalCellPosition(
    snapshot: TerminalSnapshot,
    cellWidth: Float,
    cellHeight: Float,
): TerminalCellPosition =
    TerminalCellPosition(
        column = floor(x / cellWidth).toInt().coerceIn(0, snapshot.columns - 1),
        row = floor(y / cellHeight).toInt().coerceIn(0, snapshot.rows - 1),
    )

private fun measureVisibleGlyphs(
    snapshot: TerminalSnapshot,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    normalStyle: TextStyle,
): Map<GlyphKey, TextLayoutResult> = buildMap {
    snapshot.cells.graphemes.forEachIndexed { index, grapheme ->
        if (grapheme.isEmpty() || grapheme == " ") return@forEachIndexed
        val flags = snapshot.cells.flags[index].toInt() and 0xff
        val variant = fontVariant(flags)
        val key = GlyphKey(grapheme, variant)
        getOrPut(key) {
            textMeasurer.measure(
                text = grapheme,
                style =
                    normalStyle.copy(
                        fontWeight = variant.fontWeight,
                        fontStyle = variant.fontStyle,
                    ),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

private data class GlyphKey(val grapheme: String, val variant: TerminalFontVariant)

private enum class TerminalFontVariant(
    val fontWeight: FontWeight,
    val fontStyle: FontStyle,
) {
    Normal(FontWeight.Normal, FontStyle.Normal),
    Bold(FontWeight.Bold, FontStyle.Normal),
    Italic(FontWeight.Normal, FontStyle.Italic),
    BoldItalic(FontWeight.Bold, FontStyle.Italic),
}

private fun fontVariant(flags: Int): TerminalFontVariant =
    when {
        flags and TerminalCellFlagBold != 0 && flags and TerminalCellFlagItalic != 0 ->
            TerminalFontVariant.BoldItalic
        flags and TerminalCellFlagBold != 0 -> TerminalFontVariant.Bold
        flags and TerminalCellFlagItalic != 0 -> TerminalFontVariant.Italic
        else -> TerminalFontVariant.Normal
    }

internal fun Int.toUnicodeString(): String {
    if (this !in MinimumUnicodeCodepoint..MaximumUnicodeCodepoint || this in SurrogateRange) {
        return ReplacementCharacter.toString()
    }
    if (this <= Char.MAX_VALUE.code) return toChar().toString()
    val value = this - SupplementaryCodepointOffset
    val high = HighSurrogateStart + (value shr 10)
    val low = LowSurrogateStart + (value and LowSurrogateMask)
    return charArrayOf(high.toChar(), low.toChar()).concatToString()
}

internal const val TerminalCanvasTestTag = "ComposettyTerminalCanvas"

private const val GlyphCacheSize = 1024
private const val FaintAlpha = 96f / 255f
private const val SelectionAlpha = 0.28f
private const val DecorationOffset = 1f
private const val CursorStrokeWidth = 1.5f
private const val BlockCursorAlpha = 0.35f
private const val MinimumUnicodeCodepoint = 0
private const val MaximumUnicodeCodepoint = 0x10ffff
private const val SupplementaryCodepointOffset = 0x10000
private const val HighSurrogateStart = 0xd800
private const val LowSurrogateStart = 0xdc00
private const val LowSurrogateMask = 0x3ff
private val SurrogateRange = 0xd800..0xdfff
private const val ReplacementCharacter = '\ufffd'
