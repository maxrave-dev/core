package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Search screen for the Android Auto templated surface, backed by the same
 * [com.maxrave.media3.service.callback.SimpleMediaSessionCallback] search
 * pipeline the classic voice/browse search used — MediaBrowser.search()
 * triggers onSearch, then getSearchResult() returns the collected tracks.
 * Searching starts on submit only: every query hits the YouTube scraper.
 */
@UnstableApi
internal class SearchCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    // null = nothing searched yet; empty = search finished without results
    private var results: List<MediaItem>? = null
    private var searching = false
    private var searchJob: Job? = null
    private val artwork = mutableMapOf<String, CarIcon>()

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val builder =
            SearchTemplate
                .Builder(
                    object : SearchTemplate.SearchCallback {
                        override fun onSearchTextChanged(searchText: String) {
                            // Intentionally empty — searching only on submit
                        }

                        override fun onSearchSubmitted(searchText: String) {
                            submitSearch(searchText)
                        }
                    },
                ).setHeaderAction(Action.BACK)
                .setSearchHint("Search songs")
                .setShowKeyboardByDefault(results == null && !searching)
        if (searching) {
            return builder.setLoading(true).build()
        }
        results?.let { items ->
            val nowPlayingId = handler.nowPlaying.value?.mediaId
            val itemListBuilder = ItemList.Builder().setNoItemsMessage("No results")
            items.take(carContext.listContentLimit()).forEach { item ->
                val isPlaying = item.mediaId.substringAfterLast('/') == nowPlayingId
                itemListBuilder.addItem(
                    carContext.mediaRow(item, artwork[item.mediaId], isPlaying) {
                        playViaBrowser(carContext, browserProvider(), item, TAG)
                        // Land back on the playback screen, matching the classic surface
                        screenManager.popToRoot()
                        screenManager.push(NowPlayingCarScreen(carContext))
                    },
                )
            }
            builder.setItemList(itemListBuilder.build())
        }
        return builder.build()
    }

    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        searching = true
        invalidate()
        searchJob?.cancel()
        searchJob =
            screenScope.launch {
                val items =
                    try {
                        val browser = browserProvider().await()
                        browser.search(query, null).await()
                        browser
                            .getSearchResult(query, 0, Int.MAX_VALUE, null)
                            .await()
                            .value
                            ?.toList()
                            ?: emptyList()
                    } catch (e: CancellationException) {
                        // Rethrow so a superseded search can't clobber newer results
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "search($query) failed: ${e.message}")
                        emptyList()
                    }
                searching = false
                results = items
                invalidate()
                loadArtworkInto(screenScope, bitmapLoader, items, carContext.listContentLimit(), artwork, TAG) {
                    invalidate()
                }
            }
    }

    private companion object {
        private const val TAG = "SearchCarScreen"
    }
}
