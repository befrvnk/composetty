package dev.befrvnk.composetty

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

internal fun ComposeKeyEvent.toTerminalKeyEvent(): TerminalKeyEvent? {
    val terminalKey = terminalKey(key) ?: return null
    val action =
        when (type) {
            KeyEventType.KeyDown -> TerminalKeyAction.Press
            KeyEventType.KeyUp -> TerminalKeyAction.Release
            else -> return null
        }
    val text =
        utf16CodePoint
            .takeIf { codepoint ->
                codepoint in MinimumUnicodeCodepoint..MaximumUnicodeCodepoint &&
                    !codepoint.isIsoControl()
            }
            ?.toUnicodeString()
            .orEmpty()
    return TerminalKeyEvent(
        key = terminalKey,
        action = action,
        modifiers = terminalModifiers(),
        unshiftedCodepoint = unshiftedCodepoint(key),
        text = text,
    )
}

private fun ComposeKeyEvent.terminalModifiers(): Set<TerminalKeyModifier> = buildSet {
    if (isShiftPressed) add(TerminalKeyModifier.Shift)
    if (isCtrlPressed) add(TerminalKeyModifier.Control)
    if (isAltPressed) add(TerminalKeyModifier.Alt)
    if (isMetaPressed) add(TerminalKeyModifier.Super)
}

private fun terminalKey(key: Key): TerminalKey? =
    when (key) {
        Key.Grave -> TerminalKey.Backquote
        Key.Backslash -> TerminalKey.Backslash
        Key.LeftBracket -> TerminalKey.BracketLeft
        Key.RightBracket -> TerminalKey.BracketRight
        Key.Comma -> TerminalKey.Comma
        Key.Zero -> TerminalKey.Digit0
        Key.One -> TerminalKey.Digit1
        Key.Two -> TerminalKey.Digit2
        Key.Three -> TerminalKey.Digit3
        Key.Four -> TerminalKey.Digit4
        Key.Five -> TerminalKey.Digit5
        Key.Six -> TerminalKey.Digit6
        Key.Seven -> TerminalKey.Digit7
        Key.Eight -> TerminalKey.Digit8
        Key.Nine -> TerminalKey.Digit9
        Key.Equals -> TerminalKey.Equal
        Key.A -> TerminalKey.A
        Key.B -> TerminalKey.B
        Key.C -> TerminalKey.C
        Key.D -> TerminalKey.D
        Key.E -> TerminalKey.E
        Key.F -> TerminalKey.F
        Key.G -> TerminalKey.G
        Key.H -> TerminalKey.H
        Key.I -> TerminalKey.I
        Key.J -> TerminalKey.J
        Key.K -> TerminalKey.K
        Key.L -> TerminalKey.L
        Key.M -> TerminalKey.M
        Key.N -> TerminalKey.N
        Key.O -> TerminalKey.O
        Key.P -> TerminalKey.P
        Key.Q -> TerminalKey.Q
        Key.R -> TerminalKey.R
        Key.S -> TerminalKey.S
        Key.T -> TerminalKey.T
        Key.U -> TerminalKey.U
        Key.V -> TerminalKey.V
        Key.W -> TerminalKey.W
        Key.X -> TerminalKey.X
        Key.Y -> TerminalKey.Y
        Key.Z -> TerminalKey.Z
        Key.Minus -> TerminalKey.Minus
        Key.Period -> TerminalKey.Period
        Key.Apostrophe -> TerminalKey.Quote
        Key.Semicolon -> TerminalKey.Semicolon
        Key.Slash -> TerminalKey.Slash
        Key.Backspace -> TerminalKey.Backspace
        Key.Enter -> TerminalKey.Enter
        Key.Spacebar -> TerminalKey.Space
        Key.Tab -> TerminalKey.Tab
        Key.Delete -> TerminalKey.Delete
        Key.MoveEnd -> TerminalKey.End
        Key.MoveHome -> TerminalKey.Home
        Key.Insert -> TerminalKey.Insert
        Key.PageDown -> TerminalKey.PageDown
        Key.PageUp -> TerminalKey.PageUp
        Key.DirectionDown -> TerminalKey.ArrowDown
        Key.DirectionLeft -> TerminalKey.ArrowLeft
        Key.DirectionRight -> TerminalKey.ArrowRight
        Key.DirectionUp -> TerminalKey.ArrowUp
        Key.Escape -> TerminalKey.Escape
        Key.F1 -> TerminalKey.F1
        Key.F2 -> TerminalKey.F2
        Key.F3 -> TerminalKey.F3
        Key.F4 -> TerminalKey.F4
        Key.F5 -> TerminalKey.F5
        Key.F6 -> TerminalKey.F6
        Key.F7 -> TerminalKey.F7
        Key.F8 -> TerminalKey.F8
        Key.F9 -> TerminalKey.F9
        Key.F10 -> TerminalKey.F10
        Key.F11 -> TerminalKey.F11
        Key.F12 -> TerminalKey.F12
        else -> null
    }

private fun Int.isIsoControl(): Boolean = this in C0ControlRange || this in C1ControlRange

private fun unshiftedCodepoint(key: Key): Int =
    when (key) {
        Key.A -> 'a'.code
        Key.B -> 'b'.code
        Key.C -> 'c'.code
        Key.D -> 'd'.code
        Key.E -> 'e'.code
        Key.F -> 'f'.code
        Key.G -> 'g'.code
        Key.H -> 'h'.code
        Key.I -> 'i'.code
        Key.J -> 'j'.code
        Key.K -> 'k'.code
        Key.L -> 'l'.code
        Key.M -> 'm'.code
        Key.N -> 'n'.code
        Key.O -> 'o'.code
        Key.P -> 'p'.code
        Key.Q -> 'q'.code
        Key.R -> 'r'.code
        Key.S -> 's'.code
        Key.T -> 't'.code
        Key.U -> 'u'.code
        Key.V -> 'v'.code
        Key.W -> 'w'.code
        Key.X -> 'x'.code
        Key.Y -> 'y'.code
        Key.Z -> 'z'.code
        Key.Zero -> '0'.code
        Key.One -> '1'.code
        Key.Two -> '2'.code
        Key.Three -> '3'.code
        Key.Four -> '4'.code
        Key.Five -> '5'.code
        Key.Six -> '6'.code
        Key.Seven -> '7'.code
        Key.Eight -> '8'.code
        Key.Nine -> '9'.code
        Key.Spacebar -> ' '.code
        Key.Grave -> '`'.code
        Key.Minus -> '-'.code
        Key.Equals -> '='.code
        Key.LeftBracket -> '['.code
        Key.RightBracket -> ']'.code
        Key.Backslash -> '\\'.code
        Key.Semicolon -> ';'.code
        Key.Apostrophe -> '\''.code
        Key.Comma -> ','.code
        Key.Period -> '.'.code
        Key.Slash -> '/'.code
        else -> 0
    }

private const val MinimumUnicodeCodepoint = 0
private const val MaximumUnicodeCodepoint = 0x10ffff
private val C0ControlRange = 0x00..0x1f
private val C1ControlRange = 0x7f..0x9f
