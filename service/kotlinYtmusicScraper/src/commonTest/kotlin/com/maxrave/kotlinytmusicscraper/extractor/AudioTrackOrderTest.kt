package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.test.Test
import kotlin.test.assertEquals

// xtags values copied from a live player response for 0e3GPea1Tyg (25 audio tracks per itag).
private fun url(
    xtags: String?,
    clen: Long = 1,
) = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&itag=251" +
    (xtags?.let { "&xtags=$it" } ?: "") + "&clen=$clen&mime=audio%2Fwebm"

private val ARABIC = url("acont%3Ddubbed%3Alang%3Dar", clen = 25099982)
private val VIETNAMESE_AUTO = url("acont%3Ddubbed-auto%3Alang%3Dvi")
private val ENGLISH_DESCRIPTIVE = url("acont%3Ddescriptive%3Alang%3Den")
private val ORIGINAL = url("acont%3Doriginal%3Alang%3Den-US", clen = 24140597)
private val SINGLE_TRACK = url(null)

class AudioTrackOrderTest {
    private val multiTrack = listOf(ARABIC, ENGLISH_DESCRIPTIVE, ORIGINAL, VIETNAMESE_AUTO).map { 251 to it }

    @Test
    fun noPreferencePicksOriginal() {
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("").first { it.first == 251 }.second)
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack(null).first { it.first == 251 }.second)
    }

    @Test
    fun preferredLanguageWinsWhenPresent() {
        assertEquals(VIETNAMESE_AUTO, multiTrack.orderByAudioTrack("vi").first().second)
        assertEquals(VIETNAMESE_AUTO, multiTrack.orderByAudioTrack("VI").first().second)
    }

    @Test
    fun missingPreferredLanguageFallsBackToOriginal() {
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("ja").first().second)
    }

    @Test
    fun descriptiveTrackIsNeverThePreferredOne() {
        // "en" matches both the descriptive track and the en-US original; only the original counts.
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("en").first().second)
    }

    @Test
    fun singleTrackVideoKeepsItsOrder() {
        val streams = listOf(140 to SINGLE_TRACK, 251 to SINGLE_TRACK)
        assertEquals(streams, streams.orderByAudioTrack("vi"))
    }

    @Test
    fun contentLengthComesFromTheChosenUrl() {
        assertEquals(24140597L, contentLengthOf(ORIGINAL))
        assertEquals(25099982L, contentLengthOf(ARABIC))
    }
}
