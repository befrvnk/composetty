package dev.befrvnk.composetty.consumer

import kotlin.test.Test

class PublishedIosConsumerTest {
    @Test
    fun publishedIosArtifactLinksItsNativeLibrary() {
        assertPublishedTerminalRoundTrip()
    }
}
