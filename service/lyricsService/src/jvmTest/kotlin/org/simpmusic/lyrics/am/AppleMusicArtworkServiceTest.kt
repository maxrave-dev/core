package org.simpmusic.lyrics.am

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test

class AppleMusicArtworkServiceTest {
    @Test
    fun testAmArtworkResolution() = runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
        
        val service = AppleMusicArtworkService(client)
        
        val response = client.get("https://music.apple.com/us/album/after-hours/1499378108").bodyAsText()
        
        val keys = listOf("motionDetailSquare", "motionSquare", "motionDetailTall", "motionTall")
        for (key in keys) {
            val idx = response.indexOf("\"$key\"")
            if (idx != -1) {
                val chunk = response.substring(idx, minOf(response.length, idx + 8000))
                println("Found key: $key")
                val regex = Regex(""""video"\s*:\s*"(https:[^"\\]+?\.m3u8[^"\\]*)"""")
                val match = regex.find(chunk)
                if (match != null) {
                    println("Video for $key: ${match.groupValues[1]}")
                } else {
                    println("No video match found in chunk for $key")
                }
            } else {
                println("Key $key not found in HTML")
            }
        }
        
        client.close()
    }
}
