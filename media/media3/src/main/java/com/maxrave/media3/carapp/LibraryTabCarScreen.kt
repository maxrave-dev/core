package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Library root recreating the classic Android Auto tab bar (Home / Downloaded /
 * Favorites / Playlists). The Home tab renders each classic shelf as a titled
 * [GridSection] inside a [SectionedItemTemplate]; the other tabs stay as flat
 * lists. All content comes from the same classic browse tree served through
 * [SimpleMediaSessionCallback]; browsable items push [MediaListCarScreen].
 */
@UnstableApi
internal class LibraryTabCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    private var activeTabId: String = SimpleMediaSessionCallback.HOME

    // List tabs — key absent = not requested yet; null value = loading; list = loaded
    private val tabChildren = mutableMapOf<String, List<MediaItem>?>()

    // Home tab — shelf node paired with its children; null = not loaded yet
    private var homeShelves: List<Pair<MediaItem, List<MediaItem>>>? = null
    private var homeLoading = false

    // This screen lives for the whole car session — a transient failure must
    // not leave a tab empty forever, so re-selecting a failed tab retries
    private val failedTabs = mutableSetOf<String>()
    private val artwork = mutableMapOf<String, CarIcon>()
    private var invalidatePending = false

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        loadHomeShelves()
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    /**
     * The Home grid can carry ~100 artworks; refreshing per image would flood
     * the host with full-template payloads, so arrivals coalesce into one
     * invalidate per batch window.
     */
    private fun requestInvalidate() {
        if (invalidatePending) return
        invalidatePending = true
        screenScope.launch {
            delay(ARTWORK_INVALIDATE_BATCH_MS)
            invalidatePending = false
            invalidate()
        }
    }

    private fun loadHomeShelves() {
        if (homeShelves != null || homeLoading) return
        homeLoading = true
        screenScope.launch {
            val shelves =
                try {
                    val browser = browserProvider().await()
                    browser
                        .getChildren(SimpleMediaSessionCallback.HOME, 0, Int.MAX_VALUE, null)
                        .await()
                        .value
                        ?.toList()
                        .orEmpty()
                        .filter { it.mediaMetadata.isBrowsable == true }
                        .map { shelf ->
                            // Second level reads the callback's in-memory home cache — no extra network
                            val children =
                                browser
                                    .getChildren(shelf.mediaId, 0, Int.MAX_VALUE, null)
                                    .await()
                                    .value
                                    ?.toList()
                                    .orEmpty()
                            shelf to children.take(MAX_GRID_ITEMS_PER_SECTION)
                        }.filter { it.second.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "loadHomeShelves failed: ${e.message}")
                    failedTabs.add(SimpleMediaSessionCallback.HOME)
                    emptyList()
                }
            homeShelves = shelves
            homeLoading = false
            invalidate()
            shelves.forEach { (_, items) ->
                loadArtworkInto(screenScope, bitmapLoader, items, MAX_GRID_ITEMS_PER_SECTION, artwork, TAG) {
                    requestInvalidate()
                }
            }
        }
    }

    private fun loadTab(tabId: String) {
        if (tabChildren.containsKey(tabId)) return
        tabChildren[tabId] = null
        screenScope.launch {
            val items =
                try {
                    browserProvider()
                        .await()
                        // The library callback returns whole lists and media3 rejects
                        // results larger than the requested pageSize
                        .getChildren(tabId, 0, Int.MAX_VALUE, null)
                        .await()
                        .value
                        ?.toList()
                        ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "getChildren($tabId) failed: ${e.message}")
                    failedTabs.add(tabId)
                    emptyList()
                }
            tabChildren[tabId] = items
            invalidate()
            loadArtworkInto(screenScope, bitmapLoader, items, carContext.listContentLimit(), artwork, TAG) {
                requestInvalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val templateBuilder =
            TabTemplate
                .Builder(
                    object : TabTemplate.TabCallback {
                        override fun onTabSelected(tabContentId: String) {
                            if (failedTabs.remove(tabContentId)) {
                                tabChildren.remove(tabContentId)
                                if (tabContentId == SimpleMediaSessionCallback.HOME) {
                                    homeShelves = null
                                }
                            }
                            activeTabId = tabContentId
                            if (tabContentId == SimpleMediaSessionCallback.HOME) {
                                loadHomeShelves()
                            } else {
                                loadTab(tabContentId)
                            }
                            invalidate()
                        }
                    },
                ).setHeaderAction(Action.APP_ICON)
        TABS.forEach { tab ->
            templateBuilder.addTab(
                Tab
                    .Builder()
                    .setContentId(tab.contentId)
                    .setTitle(carContext.getString(tab.titleRes))
                    .setIcon(
                        CarIcon
                            .Builder(IconCompat.createWithResource(carContext, tab.iconRes))
                            .build(),
                    ).build(),
            )
        }
        val content =
            if (activeTabId == SimpleMediaSessionCallback.HOME) {
                buildHomeSections()
            } else {
                buildActiveTabList()
            }
        return templateBuilder
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(activeTabId)
            .build()
    }

    /** Home: each classic shelf becomes a titled grid section. */
    private fun buildHomeSections(): Template {
        val shelves = homeShelves
        if (shelves == null) {
            return SectionedItemTemplate
                .Builder()
                .addAction(searchFab())
                .addAction(nowPlayingFab())
                .setLoading(true)
                .build()
        }
        if (shelves.isEmpty()) {
            // SectionedItemTemplate has no empty-state; fall back to a plain list message
            return ListTemplate
                .Builder()
                .addAction(searchFab())
                .addAction(nowPlayingFab())
                .setSingleList(ItemList.Builder().setNoItemsMessage("Nothing to show").build())
                .build()
        }
        val builder =
            SectionedItemTemplate
                .Builder()
                .addAction(searchFab())
                .addAction(nowPlayingFab())
        shelves.forEach { (shelf, items) ->
            val section =
                GridSection
                    .Builder()
                    // Spotify-sized tiles; anything smaller renders as a dense mosaic
                    .setItemSize(GridSection.ITEM_SIZE_EXTRA_LARGE)
                    .setTitle(shelf.mediaMetadata.title?.toString().orEmpty().ifBlank { " " })
            items.forEach { item -> section.addItem(buildGridItem(item)) }
            builder.addSection(section.build())
        }
        return builder.build()
    }

    private fun buildGridItem(item: MediaItem): GridItem {
        val metadata = item.mediaMetadata
        val browsable = metadata.isBrowsable == true
        val title = metadata.title?.toString().orEmpty().ifBlank { item.mediaId }
        // GridItem requires an image at build time — placeholder until artwork lands
        val image =
            artwork[item.mediaId]
                ?: CarIcon
                    .Builder(IconCompat.createWithResource(carContext, R.drawable.baseline_album_24))
                    .build()
        return GridItem
            .Builder()
            .setTitle(title)
            .apply {
                (metadata.subtitle ?: metadata.artist)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setText(it) }
            }.setImage(image, GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener {
                if (browsable) {
                    screenManager.push(MediaListCarScreen(carContext, browserProvider, item.mediaId, title))
                } else {
                    playViaBrowser(carContext, browserProvider(), item, TAG)
                    // This screen is the stack root, so the playback screen goes on top
                    screenManager.push(NowPlayingCarScreen(carContext))
                }
            }.build()
    }

    private fun buildActiveTabList(): ListTemplate {
        val listBuilder =
            ListTemplate
                .Builder()
                .addAction(searchFab())
                .addAction(nowPlayingFab())
        val children = tabChildren[activeTabId] ?: return listBuilder.setLoading(true).build()
        val nowPlayingId = handler.nowPlaying.value?.mediaId
        val itemListBuilder = ItemList.Builder().setNoItemsMessage("Nothing to show")
        children.take(carContext.listContentLimit()).forEach { item ->
            val browsable = item.mediaMetadata.isBrowsable == true
            // Browse media ids are paths ("song/{videoId}"); nowPlaying uses the bare videoId
            val isPlaying = !browsable && item.mediaId.substringAfterLast('/') == nowPlayingId
            itemListBuilder.addItem(
                carContext.mediaRow(item, artwork[item.mediaId], isPlaying) {
                    if (browsable) {
                        val title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { item.mediaId }
                        screenManager.push(MediaListCarScreen(carContext, browserProvider, item.mediaId, title))
                    } else {
                        playViaBrowser(carContext, browserProvider(), item, TAG)
                        // This screen is the stack root, so the playback screen goes on top
                        screenManager.push(NowPlayingCarScreen(carContext))
                    }
                },
            )
        }
        return listBuilder.setSingleList(itemListBuilder.build()).build()
    }

    // List templates allow up to 2 floating actions: search on top of the
    // equalizer panel (MFT-1)
    private fun searchFab(): Action =
        Action
            .Builder()
            .setIcon(
                CarIcon
                    .Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_search))
                    .build(),
            )
            // FAB constraint: custom actions must declare a background color
            .setBackgroundColor(CarColor.PRIMARY)
            .setOnClickListener {
                screenManager.push(SearchCarScreen(carContext, browserProvider))
            }.build()

    // Standard identity so the host draws the equalizer panel; navigating on
    // tap is the app's job via the listener
    private fun nowPlayingFab(): Action =
        Action
            .Builder(Action.MEDIA_PLAYBACK)
            .setOnClickListener {
                screenManager.push(NowPlayingCarScreen(carContext))
            }.build()

    private data class LibraryTab(
        val contentId: String,
        val titleRes: Int,
        val iconRes: Int,
    )

    private companion object {
        private const val TAG = "LibraryTabCarScreen"
        private const val MAX_GRID_ITEMS_PER_SECTION = 10
        private const val ARTWORK_INVALIDATE_BATCH_MS = 300L
        private val TABS =
            listOf(
                LibraryTab(SimpleMediaSessionCallback.HOME, R.string.home, R.drawable.home_android_auto),
                LibraryTab(SimpleMediaSessionCallback.DOWNLOADED, R.string.downloaded, R.drawable.baseline_downloaded),
                LibraryTab(SimpleMediaSessionCallback.FAVORITE, R.string.favorites, R.drawable.baseline_favorite_24),
                LibraryTab(SimpleMediaSessionCallback.PLAYLIST, R.string.playlists, R.drawable.baseline_playlist_add_24),
            )
    }
}
