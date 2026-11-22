package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.domain.data.model.radiobuilder.RadioBuilder
import com.maxrave.domain.data.model.radiobuilder.RadioChip
import com.maxrave.domain.data.model.radiobuilder.RadioChipIcon
import com.maxrave.domain.data.model.radiobuilder.RadioChipGroup
import com.maxrave.domain.data.model.radiobuilder.RadioSeed
import com.maxrave.domain.data.model.radiobuilder.RadioSeedKind
import com.maxrave.domain.data.model.radiobuilder.RadioTuningSection
import com.maxrave.domain.data.model.radiobuilder.RadioTuningStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Reads `browse FEmusic_radio_builder` from the ANDROID_MUSIC client, and builds the radio id the
 * picks stand for. The answer is one `musicRadioBuilderModel` deep inside an element renderer; it is
 * found by key rather than by path, since YouTube rewraps element renderers often.
 */
object RadioBuilderParser {
    private const val PREFIX = "RDAT"
    private val GENRE = Regex("^RDAT(G[A-Za-z0-9_-]+?E)")

    fun parse(root: JsonElement): RadioBuilder? {
        val model = root.findObject("musicRadioBuilderModel") ?: return null
        val tuning = model.obj("tuningPage")?.obj("data")
        val seeds =
            model.obj("seedGrid")?.array("items").orEmpty().mapNotNull { item ->
                val token = item.string("opaqueToken")?.takeIf { it.startsWith(PREFIX) } ?: return@mapNotNull null
                RadioSeed(
                    title = item.string("title") ?: return@mapNotNull null,
                    thumbnail =
                        item
                            .obj("musicThumbnail")
                            ?.obj("image")
                            ?.array("sources")
                            ?.lastOrNull()
                            ?.string("url"),
                    token = token,
                    // Non-artist seeds come only to a signed-in mobile app; drawn square.
                    kind = if (item.string("style") == "SELECTABLE_THUMBNAIL_ITEM_STYLE_ARTIST") RadioSeedKind.ARTIST else RadioSeedKind.SONG,
                )
            }
        if (seeds.isEmpty()) return null
        // The picks the page opens with, told apart by label too: "Medium" and "Blend" share RDATz.
        val initial =
            model
                .obj("formSchema")
                ?.obj("initialValues")
                ?.array("selectedChips")
                .orEmpty()
                .mapNotNull { chip -> chip.string("chipId")?.let { it to chip.string("chipLabel") } }
        val sections =
            tuning?.array("tuningSections").orEmpty().mapNotNull { section ->
                var chipStyle: String? = null
                val groups =
                    section.array("tuningChipGroups").orEmpty().mapNotNull { group ->
                        val chips =
                            group.array("chips").orEmpty().mapNotNull { chip ->
                                if (chipStyle == null) chipStyle = chip.string("chipStyle")
                                RadioChip(
                                    label = chip.string("chipLabel") ?: return@mapNotNull null,
                                    id = chip.string("chipId")?.takeIf { it.startsWith(PREFIX) } ?: return@mapNotNull null,
                                    icon =
                                        when (chip.string("iconVariant")) {
                                            "MUSIC_CHIP_ICON_VARIANT_FAMILIAR" -> RadioChipIcon.FAMILIAR
                                            "MUSIC_CHIP_ICON_VARIANT_BLEND" -> RadioChipIcon.BLEND
                                            "MUSIC_CHIP_ICON_VARIANT_DISCOVER" -> RadioChipIcon.DISCOVER
                                            else -> null
                                        },
                                )
                            }
                        if (chips.isEmpty()) return@mapNotNull null
                        val required = group.boolean("requiredChoice") == true
                        RadioChipGroup(
                            required = required,
                            chips = chips,
                            initialChipId =
                                chips.firstOrNull { chip -> (chip.id to chip.label) in initial }?.id
                                    ?: chips.firstOrNull()?.id?.takeIf { required },
                        )
                    }
                if (groups.isEmpty()) return@mapNotNull null
                RadioTuningSection(
                    title = section.string("sectionTitle").orEmpty(),
                    style =
                        when {
                            chipStyle?.contains("MEDIUM_ROUNDED") == true -> RadioTuningStyle.SEGMENTED
                            chipStyle?.contains("LARGE_ROUND") == true -> RadioTuningStyle.CIRCLES
                            else -> RadioTuningStyle.CHIPS
                        },
                    groups = groups,
                )
            }
        return RadioBuilder(
            title = model.string("title").orEmpty(),
            tuneLabel = model.obj("tuningPageButton")?.string("title"),
            tuningTitle = tuning?.string("title"),
            doneLabel = tuning?.obj("playbackButton")?.string("title"),
            seeds = seeds,
            sections = sections,
        )
    }

    /**
     * The artists of `browse FEmusic_tastebuilder` on WEB_REMIX ("Pick 5 artists you like", filled
     * from the signed-in account's YouTube activity), one list per `tastebuilderItemListRenderer`, in
     * the page's order: the artist the page shows first, then the related artists it reveals once that
     * one is picked. A list whose first artist cannot be read is dropped whole, since the caller tells
     * lists apart by that artist. An item's `selectionFormValue` is
     * `tastebuilder_selection=<Knowledge Graph id>` (`/g/11nvdtgzw1`, `/m/0gjdn4c`), the same id the
     * radio id spells as `a<id>E` (`/m/0gjdn4c` is The Weeknd, `RDATa0gjdn4cEz`).
     */
    fun parseTasteArtists(root: JsonElement): List<List<RadioSeed>> =
        root.findAll("tastebuilderItemListRenderer").mapNotNull { list ->
            val items = list.array("contents").orEmpty().map { it.obj("tastebuilderItemRenderer")?.toTasteArtist() }
            if (items.firstOrNull() == null) null else items.filterNotNull()
        }

    private fun JsonObject.toTasteArtist(): RadioSeed? {
        val id =
            string("selectionFormValue")
                ?.substringAfter('=')
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotEmpty() } ?: return null
        val token = "${PREFIX}a${id}E"
        return RadioSeed(
            title =
                obj("title")
                    ?.array("runs")
                    ?.firstOrNull()
                    ?.string("text")
                    ?.trim() ?: return null,
            thumbnail =
                obj("thumbnailRenderer")
                    ?.obj("musicArtistThumbnailRenderer")
                    ?.obj("thumbnail")
                    ?.array("thumbnails")
                    ?.lastOrNull()
                    ?.string("url"),
            // The one-seed form the ANDROID_MUSIC page gives, `z` included when 13 long.
            token = if (token.length == 13) token + "z" else token,
            kind = RadioSeedKind.ARTIST,
        )
    }

    /**
     * The genres of a song radio's `/next` answer. The chips above its queue are tuned versions of it,
     * and a genre chip's playlist id is `RDATG<id>Ev<videoId>` ("Vietnamese R&B": `RDATG11rql717l6E…`),
     * labelled in the request's language. Each comes back as a one-genre seed, `RDATG<id>E`.
     */
    fun parseGenreChips(root: JsonElement): List<RadioSeed> =
        root.findAll("chipCloudChipRenderer").mapNotNull { chip ->
            val genre = GENRE.find(chip.findString("playlistId") ?: return@mapNotNull null)?.groupValues?.get(1) ?: return@mapNotNull null
            RadioSeed(
                title =
                    chip
                        .obj("text")
                        ?.array("runs")
                        ?.joinToString("") { it.string("text").orEmpty() }
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                thumbnail = null,
                token = PREFIX + genre,
                kind = RadioSeedKind.GENRE,
            )
        }

    /**
     * The tuned radio id for [seedTokens] and [selectedChipIds], the latter listed in the order the page
     * lists their groups. The format is the Android app's, as yMusic documents it (docs/radio.md,
     * TonybynMp4/yMusic, checked against `/next` on 2026-11-17) and as checked again here on 2026-11-22:
     * `RDAT`, then every genre as `G<id>E` (before the moods, as yMusic notes: `RDATmXG…` played nothing
     * where `RDATG…EmX…` played), then the chip codes in the REVERSE of the page's order (mood, new
     * releases, popularity, discovery, variety; any other order plays nothing), then the artists as
     * `a<id>` for the first and `I<id>` for each after, closed by one `E`, then every song as `v<videoId>`. An id exactly 13
     * characters long gets a trailing `z`, since YouTube reads `RD` + 11 characters as a video's radio
     * (`RDATa0gjdn4cE` played an unrelated radio, `RDATa0gjdn4cEz` The Weeknd); any other length takes
     * none (`RDATa07c0jEz` played nothing). Null without seeds.
     */
    fun radioId(
        seedTokens: List<String>,
        selectedChipIds: List<String>,
    ): String? {
        val parts = seedTokens.map { it.removePrefix(PREFIX) }
        // An artist token is `a<id>E`, with a `z` when the one-seed id was 13 characters long.
        val artists = parts.filter { it.startsWith('a') }.map { it.drop(1).substringBeforeLast('E') }
        val songs = parts.filter { it.startsWith('v') }
        val genres = parts.filter { it.startsWith('G') }
        if (artists.isEmpty() && songs.isEmpty() && genres.isEmpty()) return null
        val codes =
            selectedChipIds
                .reversed()
                .map { it.removePrefix(PREFIX) }
                .filter { it.isNotEmpty() && it != "z" }
        val id =
            buildString {
                append(PREFIX)
                genres.forEach(::append)
                codes.forEach(::append)
                artists.forEachIndexed { index, artist -> append(if (index == 0) 'a' else 'I').append(artist) }
                if (artists.isNotEmpty()) append('E')
                songs.forEach(::append)
            }
        return if (id.length == 13) id + "z" else id
    }

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonElement?.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    private fun JsonElement?.string(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.boolean(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement?.findAll(
        key: String,
        out: MutableList<JsonObject> = mutableListOf(),
    ): List<JsonObject> {
        when (this) {
            is JsonObject -> {
                (get(key) as? JsonObject)?.let(out::add)
                values.forEach { it.findAll(key, out) }
            }
            is JsonArray -> forEach { it.findAll(key, out) }
            else -> Unit
        }
        return out
    }

    private fun JsonElement?.findString(key: String): String? =
        when (this) {
            is JsonObject -> ((get(key) as? JsonPrimitive)?.contentOrNull) ?: values.firstNotNullOfOrNull { it.findString(key) }
            is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
            else -> null
        }

    private fun JsonElement?.findObject(key: String): JsonObject? =
        when (this) {
            is JsonObject -> (get(key) as? JsonObject) ?: values.firstNotNullOfOrNull { it.findObject(key) }
            is JsonArray -> firstNotNullOfOrNull { it.findObject(key) }
            else -> null
        }
}
