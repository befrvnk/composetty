package dev.befrvnk.composetty.sample.remote

import dev.befrvnk.composetty.TerminalSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal class KtorWebSocketTerminalConnectionTest {
    @Test
    fun forwardsBinaryOutputAndSerializesInputWithResize() = runBlocking {
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val server =
            embeddedServer(ServerCIO, port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/terminal") {
                        send(Frame.Text("ignored"))
                        send(Frame.Binary(fin = true, data = "output".encodeToByteArray()))
                        repeat(2) {
                            received.send((incoming.receive() as Frame.Binary).readBytes())
                        }
                    }
                }
            }
        server.start()
        val client = HttpClient(ClientCIO) { install(ClientWebSockets) }
        try {
            val connection =
                KtorWebSocketTerminalConnection.connect(
                    scope = this,
                    client = client,
                    url = "ws://127.0.0.1:${server.engine.resolvedConnectors().single().port}/terminal",
                    encodeResize = { size -> "resize:${size.columns}x${size.rows}".encodeToByteArray() },
                )
            try {
                val output = async { connection.output.toList() }
                connection.enqueueInput("input".encodeToByteArray())
                connection.enqueueResize(TerminalSize(100, 30, 8, 16))

                assertContentEquals("input".encodeToByteArray(), received.receive())
                assertEquals("resize:100x30", received.receive().decodeToString())
                val outputFrames = output.await()
                assertEquals(1, outputFrames.size)
                assertContentEquals("output".encodeToByteArray(), outputFrames.single())
            } finally {
                connection.close()
            }
        } finally {
            client.close()
            server.stop()
        }
    }
}
