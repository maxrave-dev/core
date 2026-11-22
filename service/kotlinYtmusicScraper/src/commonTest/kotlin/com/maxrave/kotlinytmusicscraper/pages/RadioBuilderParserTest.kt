package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.domain.data.model.radiobuilder.RadioChipIcon
import com.maxrave.domain.data.model.radiobuilder.RadioSeedKind
import com.maxrave.domain.data.model.radiobuilder.RadioTuningStyle
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Shape copied from `browse FEmusic_radio_builder` on ANDROID_MUSIC 7.27.52 (2026-11-22, hl=vi),
// trimmed to what the parser reads; one seed is made up to cover the square style.
private const val PAGE = """
{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
 {"itemSectionRenderer":{"contents":[{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"musicRadioBuilderModel":{
  "title":"Chọn nhạc",
  "tuningPage":{"data":{"title":"Điều chỉnh","tuningSections":[
   {"sectionTitle":"Mức độ đa dạng nghệ sĩ","tuningChipGroups":[{"chips":[
     {"chipLabel":"Ít đa dạng","chipId":"RDATrX","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_MEDIUM_ROUNDED"},
     {"chipLabel":"Trung bình","chipId":"RDATz","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_MEDIUM_ROUNDED"},
     {"chipLabel":"Rất đa dạng","chipId":"RDATrZ","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_MEDIUM_ROUNDED"}],"requiredChoice":true}]},
   {"sectionTitle":"Khám phá âm nhạc","tuningChipGroups":[{"chips":[
     {"chipLabel":"Quen thuộc","chipId":"RDATiY","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_LARGE_ROUND","iconVariant":"MUSIC_CHIP_ICON_VARIANT_FAMILIAR"},
     {"chipLabel":"Kết hợp","chipId":"RDATz","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_LARGE_ROUND","iconVariant":"MUSIC_CHIP_ICON_VARIANT_BLEND"},
     {"chipLabel":"Khám phá","chipId":"RDATiX","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_LARGE_ROUND","iconVariant":"MUSIC_CHIP_ICON_VARIANT_DISCOVER"}],"requiredChoice":true}]},
   {"sectionTitle":"Bộ lọc","tuningChipGroups":[
     {"chips":[{"chipLabel":"Đình đám","chipId":"RDATpY","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_UNKNOWN"},{"chipLabel":"Ít phổ biến","chipId":"RDATpX","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_UNKNOWN"}],"requiredChoice":false},
     {"chips":[{"chipLabel":"Mới phát hành","chipId":"RDATdX","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_UNKNOWN"}],"requiredChoice":false},
     {"chips":[{"chipLabel":"Hứng khởi","chipId":"RDATmY","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_UNKNOWN"},{"chipLabel":"Thư giãn","chipId":"RDATmX","chipStyle":"MUSIC_ATTRIBUTE_CHIP_STYLE_UNKNOWN"}],"requiredChoice":false}]}],
   "playbackButton":{"title":"Xong"}}},
  "tuningPageButton":{"title":"Chỉnh"},
  "formSchema":{"initialValues":{"selectedChips":[
    {"chipLabel":"Trung bình","chipId":"RDATz"},{"chipLabel":"Kết hợp","chipId":"RDATz"}]}},
  "seedGrid":{"items":[
   {"title":"The Weeknd","opaqueToken":"RDATa0gjdn4cEz","style":"SELECTABLE_THUMBNAIL_ITEM_STYLE_ARTIST",
    "musicThumbnail":{"image":{"sources":[{"url":"https://example.com/w60"},{"url":"https://example.com/w180"}]}}},
   {"title":"Some Album","opaqueToken":"RDATb12345E","style":"SELECTABLE_THUMBNAIL_ITEM_STYLE_SQUARE"},
   {"title":"No token"}]}
 }}}}}}}]}}]}}}}]}}}
"""

// The WEB_REMIX taste builder (signed in, 2026-11-23), trimmed: two lists of an artist and a related
// one each, the second with a /m/ id, plus a list whose head has no id.
private const val TASTE_PAGE = """
{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"tastebuilderRenderer":{"contents":[
 {"tastebuilderItemListRenderer":{"contents":[
  {"tastebuilderItemRenderer":{"title":{"runs":[{"text":"TINH HÀ \"SAY HI\" "}]},
   "thumbnailRenderer":{"musicArtistThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/w60"},{"url":"https://example.com/w544"}]}}},
   "selectionFormValue":"tastebuilder_selection=/g/11nvdtgzw1"}},
  {"tastebuilderItemRenderer":{"title":{"runs":[{"text":"Related"}]},"selectionFormValue":"tastebuilder_selection=/g/11ykk4vrxj"}}
 ],"collapsedItemCount":1}},
 {"tastebuilderItemListRenderer":{"contents":[
  {"tastebuilderItemRenderer":{"title":{"runs":[{"text":"The Weeknd"}]},"selectionFormValue":"tastebuilder_selection=/m/0gjdn4c"}}
 ]}},
 {"tastebuilderItemListRenderer":{"contents":[{"tastebuilderItemRenderer":{"title":{"runs":[{"text":"No id"}]}}}]}}
]}}}}]}}}
"""

class RadioBuilderParserTest {
    @Test
    fun tasteBuilderGivesEveryArtistOfEachList() {
        val lists = RadioBuilderParser.parseTasteArtists(json.parseToJsonElement(TASTE_PAGE))
        // The list whose first artist has no id is dropped whole.
        assertEquals(listOf(listOf("TINH HÀ \"SAY HI\"", "Related"), listOf("The Weeknd")), lists.map { list -> list.map { it.title } })
        assertEquals(listOf("RDATa11nvdtgzw1E", "RDATa11ykk4vrxjE"), lists[0].map { it.token })
        assertEquals("RDATa0gjdn4cEz", lists[1][0].token)
        assertEquals("https://example.com/w544", lists[0][0].thumbnail)
        assertEquals(RadioSeedKind.ARTIST, lists[0][0].kind)
        // The id reads back into a radio id like any other artist token.
        assertEquals("RDATa11nvdtgzw1I0gjdn4cE", RadioBuilderParser.radioId(listOf(lists[0][0].token, lists[1][0].token), emptyList()))
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesSeedsSectionsAndInitialPicks() {
        val page = assertNotNull(RadioBuilderParser.parse(json.parseToJsonElement(PAGE)))
        assertEquals("Chọn nhạc", page.title)
        assertEquals("Chỉnh", page.tuneLabel)
        assertEquals("Điều chỉnh", page.tuningTitle)
        assertEquals("Xong", page.doneLabel)

        assertEquals(listOf("The Weeknd", "Some Album"), page.seeds.map { it.title })
        assertEquals(RadioSeedKind.ARTIST, page.seeds[0].kind)
        assertEquals(RadioSeedKind.SONG, page.seeds[1].kind)
        assertEquals("https://example.com/w180", page.seeds[0].thumbnail)

        assertEquals(
            listOf(RadioTuningStyle.SEGMENTED, RadioTuningStyle.CIRCLES, RadioTuningStyle.CHIPS),
            page.sections.map { it.style },
        )
        // "Medium" and "Blend" share RDATz; each group still opens on its own default.
        assertEquals("RDATz", page.sections[0].groups[0].initialChipId)
        assertEquals("RDATz", page.sections[1].groups[0].initialChipId)
        assertTrue(page.sections[2].groups.none { it.required || it.initialChipId != null })
        assertEquals(3, page.sections[2].groups.size)
        assertEquals(
            listOf(RadioChipIcon.FAMILIAR, RadioChipIcon.BLEND, RadioChipIcon.DISCOVER),
            page.sections[1].groups[0].chips.map { it.icon },
        )
        assertTrue(page.sections[0].groups[0].chips.all { it.icon == null })
    }

    @Test
    fun noModelIsNull() {
        assertNull(RadioBuilderParser.parse(json.parseToJsonElement("""{"contents":{}}""")))
    }

    // Every id below played through `next` on 2026-11-22.
    @Test
    fun aLoneArtistGetsAZOnlyWhenTheIdIs13Long() {
        // RDATa0gjdn4cE (13) played an unrelated radio; with the z, The Weeknd.
        assertEquals("RDATa0gjdn4cEz", RadioBuilderParser.radioId(listOf("RDATa0gjdn4cEz"), listOf("RDATz", "RDATz")))
        // RDATa07c0jEz played nothing; without the z, The Beatles.
        assertEquals("RDATa07c0jE", RadioBuilderParser.radioId(listOf("RDATa07c0jEz"), emptyList()))
    }

    @Test
    fun artistsAfterTheFirstSwapTheirLeadingA() {
        assertEquals(
            "RDATa0gjdn4cI020jvtI11stpl5jvqE",
            RadioBuilderParser.radioId(listOf("RDATa0gjdn4cEz", "RDATa020jvtE", "RDATa11stpl5jvqE"), emptyList()),
        )
    }

    @Test
    fun chipCodesGoFirstInReverseOfThePageOrder() {
        // Page order: variety, discovery, popularity, new releases, mood.
        assertEquals(
            "RDATmXa0gjdn4cI11hfhf66_dE",
            RadioBuilderParser.radioId(listOf("RDATa0gjdn4cEz", "RDATa11hfhf66_dE"), listOf("RDATz", "RDATz", "RDATmX")),
        )
        assertEquals(
            "RDATpYiXa0gjdn4cE",
            RadioBuilderParser.radioId(listOf("RDATa0gjdn4cEz"), listOf("RDATz", "RDATiX", "RDATpY")),
        )
        assertEquals(
            "RDATmXdXpYa0gjdn4cE",
            RadioBuilderParser.radioId(listOf("RDATa0gjdn4cEz"), listOf("RDATpY", "RDATdX", "RDATmX")),
        )
    }

    @Test
    fun songsComeAfterTheArtistsAndGenresBeforeEverything() {
        assertEquals("RDATv4NRXx6U8ABQ", RadioBuilderParser.radioId(listOf("RDATv4NRXx6U8ABQ"), emptyList()))
        assertEquals(
            "RDATv4NRXx6U8ABQvfHI8X4OXluQ",
            RadioBuilderParser.radioId(listOf("RDATv4NRXx6U8ABQ", "RDATvfHI8X4OXluQ"), emptyList()),
        )
        // Picked song first, artist second: artists still go before songs.
        assertEquals("RDATa07c0jEv4NRXx6U8ABQ", RadioBuilderParser.radioId(listOf("RDATv4NRXx6U8ABQ", "RDATa07c0jEz"), emptyList()))
        assertEquals(
            "RDATG11rql717l6EmXa07c0jEvkZnpzY7A1u4",
            RadioBuilderParser.radioId(
                listOf("RDATvkZnpzY7A1u4", "RDATa07c0jEz", "RDATG11rql717l6E"),
                listOf("RDATz", "RDATz", "RDATmX"),
            ),
        )
        assertEquals("RDATG11rql717l6EG11rqkmdrbyE", RadioBuilderParser.radioId(listOf("RDATG11rql717l6E", "RDATG11rqkmdrbyE"), emptyList()))
    }

    @Test
    fun genreChipsBecomeGenreSeeds() {
        val next =
            """{"contents":{"chips":[
              {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Tất cả"}]},"navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDAMVMkZnpzY7A1u4"}}}}}},
              {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Thư giãn"}]},"navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDATmXvkZnpzY7A1u4"}}}}}},
              {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Vietnamese R&B"}]},"navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDATG11rql717l6EvkZnpzY7A1u4"}}}}}}
            ]}}"""
        val genres = RadioBuilderParser.parseGenreChips(json.parseToJsonElement(next))
        assertEquals(listOf("Vietnamese R&B"), genres.map { it.title })
        assertEquals("RDATG11rql717l6E", genres.single().token)
        assertEquals(RadioSeedKind.GENRE, genres.single().kind)
    }

    @Test
    fun noSeedsIsNull() {
        assertNull(RadioBuilderParser.radioId(emptyList(), listOf("RDATmX")))
    }
}
