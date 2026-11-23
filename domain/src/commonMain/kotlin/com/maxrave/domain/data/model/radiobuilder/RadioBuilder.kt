package com.maxrave.domain.data.model.radiobuilder

/**
 * YouTube Music's radio builder: pick seeds, then tune the radio. Every label comes from YouTube in
 * the request's language, so the app adds no text of its own to either page.
 */
data class RadioBuilder(
    /** "Pick music" */
    val title: String,
    /** "Tune": the button that opens the tuning page. */
    val tuneLabel: String?,
    /** "Adjust": the tuning page's title. */
    val tuningTitle: String?,
    /** "Done" */
    val doneLabel: String?,
    val seeds: List<RadioSeed>,
    /** In the order YouTube lists them; [com.maxrave.domain.repository.HomeRepository.radioId] relies on it. */
    val sections: List<RadioTuningSection>,
    /**
     * The related artists picking a seed brings in, keyed by that seed's token: the taste builder's
     * lists, which show only their first artist until it is picked.
     */
    val related: Map<String, List<RadioSeed>> = emptyMap(),
)

/**
 * One pick on "Pick music", as a one-seed radio id: `RDATa<id>E(z)` for an artist (its Knowledge
 * Graph id), `RDATv<videoId>` for a song, `RDATG<id>E` for a genre. Every pick goes into the one
 * tuned radio Done opens.
 */
data class RadioSeed(
    val title: String,
    val thumbnail: String?,
    val token: String,
    val kind: RadioSeedKind,
    /** A song's artists; null for an artist or a genre. */
    val subtitle: String? = null,
)

/** Artists are drawn round, songs square, genres as a square tile of their own. */
enum class RadioSeedKind { ARTIST, SONG, GENRE }

data class RadioTuningSection(
    val title: String,
    val style: RadioTuningStyle,
    val groups: List<RadioChipGroup>,
)

/** How a section is drawn, read from its chips' style. */
enum class RadioTuningStyle {
    /** One row of joined buttons: artist variety. */
    SEGMENTED,

    /** Large round choices: music discovery. */
    CIRCLES,

    /** Free-standing chips: filters. */
    CHIPS,
}

/**
 * Chips of which at most one is picked. A [required] group always has one picked, starting at
 * [initialChipId]; the others may have none.
 */
data class RadioChipGroup(
    val required: Boolean,
    val chips: List<RadioChip>,
    val initialChipId: String?,
)

data class RadioChip(
    val label: String,
    /** `RDATrX`, `RDATpY`…; `RDATz` is the group's default and adds nothing to the id. */
    val id: String,
    /** The picture a music-discovery circle carries; YouTube names it, the app draws it. */
    val icon: RadioChipIcon? = null,
)

/** `MUSIC_CHIP_ICON_VARIANT_*` on the music-discovery chips. */
enum class RadioChipIcon { FAMILIAR, BLEND, DISCOVER }
