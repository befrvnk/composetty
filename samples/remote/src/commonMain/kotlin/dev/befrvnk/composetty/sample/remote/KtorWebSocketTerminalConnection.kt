package dev.befrvnk.composetty.sample.remote

import dev.befrvnk.composetty.TerminalSize
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * A Ktor WebSocket adapter for servers that exchange terminal input and output as binary frames.
 *
 * The caller owns [client] and must close it when it is no longer shared by any connections. The
 * terminal resize protocol is server-specific, so [encodeResize] supplies its binary frame payload.
 */
class KtorWebSocketTerminalConnection private constructor(
    private val session: WebSocketSession,
    private val scope: CoroutineScope,
    private val encodeResize: (TerminalSize) -> ByteArray,
) : TerminalConnection, AutoCloseable {
    private val outbound = Channel<Outbound>(Channel.UNLIMITED)
    private val mutableOutput = Channel<ByteArray>(Channel.UNLIMITED)
    private val reader: Job
    private val writer: Job

    override val output: Flow<ByteArray> = mutableOutput.receiveAsFlow()

    init {
        reader =
            scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Binary) mutableOutput.send(frame.readBytes())
                    }
                } finally {
                    mutableOutput.close()
                }
            }
        writer =
            scope.launch {
                for (event in outbound) {
                    val bytes =
                        when (event) {
                            is Outbound.Input -> event.bytes
                            is Outbound.Resize -> encodeResize(event.size)
                        }
                    session.send(Frame.Binary(fin = true, data = bytes))
                }
            }
    }

    override fun enqueueInput(bytes: ByteArray) {
        outbound.trySend(Outbound.Input(bytes.copyOf()))
    }

    override fun enqueueResize(size: TerminalSize) {
        outbound.trySend(Outbound.Resize(size))
    }

    override fun close() {
        reader.cancel()
        writer.cancel()
        outbound.close()
        mutableOutput.close()
        scope.launch { session.close() }
    }

    public companion object {
        /** Opens a WebSocket connection and starts forwarding binary terminal frames. */
        public suspend fun connect(
            scope: CoroutineScope,
            client: HttpClient,
            url: String,
            encodeResize: (TerminalSize) -> ByteArray,
        ): KtorWebSocketTerminalConnection =
            KtorWebSocketTerminalConnection(
                session = client.webSocketSession(urlString = url),
                scope = scope,
                encodeResize = encodeResize,
            )
    }

    private sealed interface Outbound {
        class Input(val bytes: ByteArray) : Outbound

        class Resize(val size: TerminalSize) : Outbound
    }
}
