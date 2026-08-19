package dev.befrvnk.composetty.sample.remote

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

public fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Composetty remote transport sample",
        ) {
            LoopbackTerminalSample(showKeyboardAccessory = false)
        }
    }
}
