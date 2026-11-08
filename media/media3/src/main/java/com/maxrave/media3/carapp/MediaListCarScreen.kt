package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Generic browse screen recreating the classic Android Auto media tree on the
 * templated surface: it renders the children of [parentId] served by
 * [com.maxrave.media3.service.callback.SimpleMediaSessionCallback] through the
 * session's MediaBrowser, so browse content and click-to-play behavior stay
 * identical to the pre-template surface. Browsable children push another
 * instance of this screen; playable ones hand the item back to the session
 * and land on the playback screen.
 */
@UnstableApi
internal class MediaListCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
    private val parentId: String,
    private val screenTitle: String,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    // null = still loading (template shows spinner); empty = loaded, nothing to show
    private var children: List<MediaItem>? = null
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
            val items =
                try {
                    browserProvider()
                        .await()
                        // The library callback returns whole lists (e.g. 1000 songs) and
                        // media3 rejects results larger than the requested pageSize
                        .getChildren(parentId, 0, Int.MAX_VALUE, null)
                        .await()
                        .value
                        ?.toList()
                        ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "getChildren($parentId) failed: ${e.message}")
                    emptyList()
                }
            children = items
            invalidate()
            loadArtworkInto(screenScope, bitmapLoader, items, carContext.listContentLimit(), artwork, TAG) {
                invalidate()
            }
        }
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val loaded = children
        val templateBuilder =
            ListTemplate
                .Builder()
                // Standard identity so the host draws the equalizer panel;
                // navigating on tap is the app's job via the listener
                .addAction(
                    Action
                        .Builder(Action.MEDIA_PLAYBACK)
                        .setOnClickListener {
                            screenManager.popToRoot()
                            screenManager.push(NowPlayingCarScreen(carContext))
                        }.build(),
                ).setHeader(
                    Header
                        .Builder()
                        .setStartHeaderAction(Action.BACK)
                        .setTitle(screenTitle)
                        .build(),
                )
        if (loaded == null) {
            return templateBuilder.setLoading(true).build()
        }
        val nowPlayingId = handler.nowPlaying.value?.mediaId
        val itemListBuilder =
            ItemList
                .Builder()
                .setNoItemsMessage("Nothing to show")
        loaded.take(carContext.listContentLimit()).forEach { item ->
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
                        // Land back on the playback screen, matching the classic surface
                        screenManager.popToRoot()
                        screenManager.push(NowPlayingCarScreen(carContext))
                    }
                },
            )
        }
        return templateBuilder.setSingleList(itemListBuilder.build()).build()
    }

    private companion object {
        private const val TAG = "MediaListCarScreen"
    }
}
