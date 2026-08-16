package dev.befrvnk.composetty.consumer

import dev.befrvnk.composetty.TerminalRgb
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedAndroidConsumerTest {
    @Test
    fun publishedAndroidArtifactExposesCommonApi() {
        assertEquals(0xff123456.toInt(), TerminalRgb(0x12, 0x34, 0x56).argb)
    }
}
