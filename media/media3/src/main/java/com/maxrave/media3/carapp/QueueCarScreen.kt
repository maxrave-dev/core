package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Queue screen backed directly by [MediaPlayerHandler.queueData] instead of
 * the player timeline — the dual-player crossfade adapter only ever exposes a
 * single item to the session, so the host's built-in queue cannot show the
 * real queue.
 */
internal class QueueCarScreen(
    carContext: CarContext,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            handler.queueData.collect { invalidate() }
        }
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val queue = handler.queueData.value?.data
        val tracks = queue?.listTracks.orEmpty()
        val nowPlayingId = handler.nowPlaying.value?.mediaId
        val maxRows = carContext.listContentLimit()

        val currentIndex = tracks.indexOfFirst { it.videoId == nowPlayingId }.coerceAtLeast(0)
        // Window the list around the playing track when the queue exceeds the host row limit
        val start = if (tracks.size <= maxRows) 0 else minOf(currentIndex, tracks.size - maxRows)
        val visibleTracks = tracks.subList(start, minOf(tracks.size, start + maxRows))

        val itemListBuilder =
            ItemList
                .Builder()
                .setNoItemsMessage("Queue is empty")
        visibleTracks.forEachIndexed { offset, track ->
            val index = start + offset
            val isPlaying = index == currentIndex && track.videoId == nowPlayingId
            itemListBuilder.addItem(
                Row
                    .Builder()
                    .setTitle(track.title)
                    .apply {
                        val artists = track.artists?.joinToString(", ") { it.name }.orEmpty()
                        if (isPlaying) {
                            addText(carContext.nowPlayingText(artists))
                        } else if (artists.isNotBlank()) {
                            addText(artists)
                        }
                    }.setOnClickListener {
                        handler.playMediaItemInMediaSource(index)
                    }.build(),
            )
        }

        return ListTemplate
            .Builder()
            // Standard identity so the host draws the equalizer panel;
            // navigating on tap is the app's job via the listener
            .addAction(
                Action
                    .Builder(Action.MEDIA_PLAYBACK)
                    .setOnClickListener {
                        // The queue is always pushed from the playback screen
                        screenManager.pop()
                    }.build(),
            )
            .setHeader(
                Header
                    .Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(queue?.playlistName?.takeIf { it.isNotBlank() } ?: "Queue")
                    .build(),
            ).setSingleList(itemListBuilder.build())
            .build()
    }
}
