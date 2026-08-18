package dev.befrvnk.composetty.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import dev.befrvnk.composetty.sample.remote.LoopbackTerminalSample

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LoopbackTerminalSample(modifier = Modifier.safeDrawingPadding())
        }
    }
}
