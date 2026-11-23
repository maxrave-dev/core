package com.maxrave.domain.home

import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.isQuickPicksTitle
import com.maxrave.domain.data.model.home.prioritizeQuickPicksFirst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeQuickPicksOrderTest {
    @Test
    fun recognizesQuickPicksTitlesAcrossLocalizedYouTubeVariants() {
        assertTrue(isQuickPicksTitle("Quick picks"))
        assertTrue(isQuickPicksTitle("Escolhas rápidas"))
        assertTrue(isQuickPicksTitle("Escolha a dedo"))
        assertTrue(isQuickPicksTitle("Selecciones rápidas"))
        assertTrue(isQuickPicksTitle("Selección rápida"))
        assertTrue(isQuickPicksTitle("Sélection rapide"))
        assertTrue(isQuickPicksTitle("Custom Title", localizedQuickPicksTitle = "Custom Title"))
        assertFalse(isQuickPicksTitle("Ouvir novamente"))
        assertFalse(isQuickPicksTitle("Trending songs"))
    }

    @Test
    fun movesQuickPicksToFirstIndexWhilePreservingRelativeOrderOfOtherShelves() {
        val listenAgain = HomeItem(contents = emptyList(), title = "Ouvir novamente")
        val recommended = HomeItem(contents = emptyList(), title = "Álbuns recomendados")
        val quickPicks = HomeItem(contents = emptyList(), title = "Escolha a dedo")
        val trending = HomeItem(contents = emptyList(), title = "Em alta")

        val reordered = listOf(listenAgain, recommended, quickPicks, trending).prioritizeQuickPicksFirst("Escolhas rápidas")
        assertEquals(
            listOf("Escolha a dedo", "Ouvir novamente", "Álbuns recomendados", "Em alta"),
            reordered.map { it.title },
        )

        // Already first stays unchanged
        assertEquals(reordered, reordered.prioritizeQuickPicksFirst("Escolhas rápidas"))
    }
}
