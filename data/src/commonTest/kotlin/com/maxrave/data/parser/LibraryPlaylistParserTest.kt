package com.maxrave.data.parser

import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPlaylistParserTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun parseLibraryPlaylistHandlesEmptySubtitleRunsAndWatchEndpoints() {
        val rawJson =
            """
            [
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "My Supermix" }] },
                  "subtitle": { "runs": [] },
                  "thumbnailRenderer": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [{ "url": "https://example.com/mix1.jpg", "width": 544, "height": 544 }]
                      }
                    }
                  },
                  "navigationEndpoint": {
                    "browseEndpoint": { "browseId": "VLRDTMAK5uy_supermix" }
                  }
                }
              },
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "My Mix 1" }] },
                  "subtitle": null,
                  "thumbnailRenderer": {},
                  "navigationEndpoint": {
                    "watchPlaylistEndpoint": { "playlistId": "RDTMAK5uy_mix1" }
                  }
                }
              },
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Radio Mix" }] },
                  "thumbnailRenderer": {},
                  "navigationEndpoint": {
                    "watchEndpoint": { "playlistId": "RDEM_artist_mix" }
                  }
                }
              },
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Broken Item Without Id" }] },
                  "thumbnailRenderer": {},
                  "navigationEndpoint": {}
                }
              }
            ]
            """.trimIndent()

        val items = json.decodeFromString<List<GridRenderer.Item>>(rawJson)
        val parsed = parseLibraryPlaylist(items)

        assertEquals(3, parsed.size)
        assertEquals("VLRDTMAK5uy_supermix", parsed[0].browseId)
        assertEquals("My Supermix", parsed[0].title)
        assertEquals("", parsed[0].author)
        assertEquals("RDTMAK5uy_mix1", parsed[1].browseId)
        assertEquals("RDEM_artist_mix", parsed[2].browseId)
    }
}
