package com.maxrave.domain.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DecodeHtmlEntitiesTest {
    @Test
    fun returnsSameStringInstanceWhenNoAmpersandPresent() {
        val plain = "Hello world from SimpMusic"
        assertSame(plain, decodeHtmlEntities(plain))
    }

    @Test
    fun decodesNamedHexAndDecimalEntitiesCaseInsensitively() {
        val input = "Don&apos;t &QUOT;stop&quot; &AMP; &lt;sing&gt; &#39;now&#39; &#x27;yeah&#X22 &#33"
        assertEquals("Don't \"stop\" & <sing> 'now' 'yeah\" !", decodeHtmlEntities(input))
    }

    @Test
    fun decodesSupplementaryCodePointsSafely() {
        assertEquals("🎵 😀", decodeHtmlEntities("&#x1F3B5; &#128512;"))
    }

    @Test
    fun preservesInvalidOutOfRangeSurrogateAndPlainAmpersand() {
        val input = "Rock & Roll &#9999999999; &#x110000; &#xD800; &#0; &unknown;"
        assertEquals(input, decodeHtmlEntities(input))
    }
}
