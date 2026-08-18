package dev.befrvnk.composetty.sample.remote

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.befrvnk.composetty.TerminalSize

internal class LoopbackTerminalConnectionTest {
    @Test
    fun forwardsInputAsynchronouslyAndReportsResizes() = runBlocking {
        val connection = LoopbackTerminalConnection(this)
        val transport = RemoteTransport(connection)
        try {
            transport.write("hello".encodeToByteArray())
            val output = async { connection.output.first() }
            val size = TerminalSize(columns = 100, rows = 30, cellWidth = 8, cellHeight = 16)
            transport.resize(size)

            assertContentEquals("hello".encodeToByteArray(), output.await())
            assertEquals(size, connection.nextResize())
        } finally {
            connection.close()
        }
    }
}
