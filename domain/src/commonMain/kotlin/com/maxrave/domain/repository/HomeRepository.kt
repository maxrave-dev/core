package com.maxrave.domain.repository

import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface HomeRepository {
    /**
     * @return Pair of continueParams and HomeItem List
     */
    fun getHomeData(
        params: String? = null,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>>

    fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>>

    fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>>

    fun getChartData(countryCode: String = "KR"): Flow<Resource<Chart>>

    fun getMoodAndMomentsData(): Flow<Resource<Mood>>

    /**
     * Cover art for one browse category, or null when it cannot be resolved.
     *
     * The category list has no artwork field, so this costs a full category browse the first time
     * and is cached on disk afterwards. Call it lazily — one category at a time, as its tile
     * actually becomes visible — never for the whole list at once.
     */
    fun getMoodCategoryArtwork(params: String): Flow<String?>

    fun getGenreData(params: String): Flow<Resource<GenreObject>>

    fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>>
}