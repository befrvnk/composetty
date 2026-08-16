package dev.befrvnk.composetty.consumer

import kotlin.test.Test

class PublishedJvmConsumerTest {
    @Test
    fun publishedJvmArtifactLoadsItsNativeLibrary() {
        assertPublishedTerminalRoundTrip()
    }
}
