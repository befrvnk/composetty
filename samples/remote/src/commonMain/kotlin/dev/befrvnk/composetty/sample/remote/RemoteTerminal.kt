package dev.befrvnk.composetty.sample.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.befrvnk.composetty.GhosttyTerminal
import dev.befrvnk.composetty.GhosttyTerminalSessionFactory
import dev.befrvnk.composetty.TerminalKeyboardAccessory
import dev.befrvnk.composetty.TerminalRgb
import dev.befrvnk.composetty.TerminalSize
import dev.befrvnk.composetty.TerminalTheme
import dev.befrvnk.composetty.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Application-owned connection boundary for a remote terminal transport. */
interface TerminalConnection {
    val output: Flow<ByteArray>

    fun enqueueInput(bytes: ByteArray)

    fun enqueueResize(size: TerminalSize)
}

/** Forwards Composetty's terminal events to an application-owned [TerminalConnection]. */
class RemoteTransport(private val connection: TerminalConnection) : TerminalTransport {
    override fun write(bytes: ByteArray) {
        connection.enqueueInput(bytes)
    }

    override fun resize(size: TerminalSize) {
        connection.enqueueResize(size)
    }
}

/**
 * In-memory asynchronous connection used by this sample.
 *
 * Its worker receives queued input and produces output separately from [RemoteTransport.write].
 */
class LoopbackTerminalConnection(scope: CoroutineScope) : TerminalConnection, AutoCloseable {
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val resize = Channel<TerminalSize>(Channel.UNLIMITED)
    private val mutableOutput = Channel<ByteArray>(Channel.UNLIMITED)

    override val output: Flow<ByteArray> = mutableOutput.receiveAsFlow()
    private val worker: Job

    init {
        mutableOutput.trySend(
            (
                "\u001b[?2027h" +
                    "Composetty remote transport sample\r\n" +
                    "Input is queued and echoed asynchronously. Replace this connection with SSH or WebSocket.\r\n\r\n$ "
            )
                .encodeToByteArray()
        )
        worker =
            scope.launch {
                for (bytes in input) {
                    mutableOutput.send(echo(bytes))
                }
            }
    }

    override fun enqueueInput(bytes: ByteArray) {
        input.trySend(bytes.copyOf())
    }

    override fun enqueueResize(size: TerminalSize) {
        resize.trySend(size)
    }

    suspend fun nextResize(): TerminalSize = resize.receive()

    override fun close() {
        worker.cancel()
        input.close()
        resize.close()
        mutableOutput.close()
    }

    private fun echo(bytes: ByteArray): ByteArray =
        buildString {
                bytes.decodeToString().forEach { character ->
                    when (character) {
                        '\b',
                        '\u007f' -> append("\b \b")
                        '\r' -> append("\r\n$ ")
                        else -> append(character)
                    }
                }
            }
            .encodeToByteArray()
}

@Composable
fun RemoteTerminal(
    connection: TerminalConnection,
    theme: TerminalTheme = SampleTheme,
    modifier: Modifier = Modifier,
) {
    val transport = remember(connection) { RemoteTransport(connection) }
    val session = remember(transport) {
        GhosttyTerminalSessionFactory().create(theme, transport)
    }

    LaunchedEffect(session, connection) {
        connection.output.collect(session::receive)
    }
    DisposableEffect(session) {
        onDispose(session::close)
    }

    Column(modifier) {
        GhosttyTerminal(
            session = session,
            theme = theme,
            modifier = Modifier.weight(1f),
        )
        TerminalKeyboardAccessory(session)
    }
}

@Composable
fun LoopbackTerminalSample(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val connection = remember { LoopbackTerminalConnection(scope) }

    DisposableEffect(connection) {
        onDispose(connection::close)
    }
    RemoteTerminal(connection = connection, modifier = modifier.fillMaxSize())
}

private val SampleTheme =
    TerminalTheme(
        foreground = TerminalRgb(230, 230, 230),
        background = TerminalRgb(25, 25, 25),
        cursor = TerminalRgb(230, 230, 230),
    )
