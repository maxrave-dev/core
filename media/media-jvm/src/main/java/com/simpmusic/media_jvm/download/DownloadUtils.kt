package com.simpmusic.media_jvm.download

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.notification.DesktopNotificationManager
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "DownloadUtils"

// Not localised: this module sits below the UI layer and has no access to compose resources. If the
// notification text needs translating, the label has to be passed down from the caller instead.
private const val DOWNLOAD_STARTED_TITLE = "Downloading"
private const val DOWNLOAD_FINISHED_TITLE = "Download complete"
private const val DOWNLOAD_FAILED_TITLE = "Download failed"
private const val ACTIVE_DOWNLOAD_NOTIFICATION_ID = "downloads.active"
private const val DOWNLOAD_RESULT_NOTIFICATION_ID = "downloads.result"

internal class DownloadUtils(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val songRepository: SongRepository,
    private val desktopNotificationManager: DesktopNotificationManager,
) : DownloadHandler {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())

    // Audio / Video
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>
        get() = _downloads
    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> get() = _downloadTask

    val downloadingVideoIds = MutableStateFlow<MutableSet<String>>(mutableSetOf())

    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) {
        val song = songRepository.getSongById(videoId).lastOrNull()
        if (song != null) {
            songRepository.updateDownloadState(
                videoId,
                DownloadState.STATE_DOWNLOADING,
            )
            onDownloadStarted(videoId, title)
            if (!File(getDownloadPath()).exists()) {
                File(getDownloadPath()).mkdirs()
            }
            songRepository
                .downloadToFile(
                    song.toTrack(),
                    path = getDownloadPath() + File.separator + videoId,
                    videoId = videoId,
                    isVideo = false,
                ).collect { state ->
                    if (state.isError) {
                        songRepository.updateDownloadState(
                            videoId,
                            DownloadState.STATE_NOT_DOWNLOADED,
                        )
                        onDownloadFinished(videoId, title, succeeded = false)
                    } else if (state.isDone) {
                        songRepository.updateDownloadState(
                            videoId,
                            DownloadState.STATE_DOWNLOADED,
                        )
                        onDownloadFinished(videoId, title, succeeded = true)
                    }
                }
        }
    }

    // ===== Desktop download notifications =====
    //
    // Grouped per batch, the same way Android collapses every queued download into the single
    // foreground-service notification rather than one per track. One notification goes out when the
    // queue goes from idle to busy, and one summary replaces it when the queue drains.
    //
    // Progress cannot be tracked the way Android does: NotificationHandle only exposes dismiss(),
    // there is no update, and desktop notifications carry no progress bar. So the opening
    // notification says work started, and the closing one says how it went.

    private val batchLock = Any()
    private val activeDownloads = mutableSetOf<String>()
    private var batchCompleted = 0
    private var batchFailed = 0
    private var batchTotal = 0
    private var lastSongTitle = ""

    private fun onDownloadStarted(
        videoId: String,
        songTitle: String,
    ) = synchronized(batchLock) {
        val wasIdle = activeDownloads.isEmpty()
        activeDownloads += videoId
        Logger.d(TAG, "Download started: $videoId (active=${activeDownloads.size}, batchOpening=$wasIdle)")
        if (wasIdle) {
            batchCompleted = 0
            batchFailed = 0
            batchTotal = 0
            lastSongTitle = songTitle
            // Only one track is known at this point, so name it. If more join the batch, the
            // closing notification switches to a count.
            desktopNotificationManager.post(
                id = ACTIVE_DOWNLOAD_NOTIFICATION_ID,
                title = DOWNLOAD_STARTED_TITLE,
                message = songTitle,
            )
        }
        batchTotal++
    }

    private fun onDownloadFinished(
        videoId: String,
        songTitle: String,
        succeeded: Boolean,
    ) = synchronized(batchLock) {
        activeDownloads -= videoId
        lastSongTitle = songTitle
        if (succeeded) batchCompleted++ else batchFailed++
        if (activeDownloads.isEmpty()) {
            // Clear the "started" notification so the tray shows one line about this batch, not two.
            desktopNotificationManager.dismiss(ACTIVE_DOWNLOAD_NOTIFICATION_ID)
            // A single-track batch says which track — that is what the user was waiting on. Several
            // tracks can only be summarised, since one notification cannot list them all.
            val summary =
                when {
                    batchTotal == 1 -> lastSongTitle
                    batchCompleted == 0 -> "$batchFailed failed"
                    batchFailed == 0 -> "$batchCompleted songs downloaded"
                    else -> "$batchCompleted downloaded, $batchFailed failed"
                }
            desktopNotificationManager.post(
                id = DOWNLOAD_RESULT_NOTIFICATION_ID,
                title = if (batchCompleted == 0) DOWNLOAD_FAILED_TITLE else DOWNLOAD_FINISHED_TITLE,
                message = summary,
            )
        }
    }

    override fun removeDownload(videoId: String) {
        File(getDownloadPath())
            .listFiles()
            .filter {
                it.name.contains(videoId)
            }.forEach {
                it.delete()
                coroutineScope.launch {
                    songRepository.updateDownloadState(
                        videoId,
                        DownloadState.STATE_NOT_DOWNLOADED,
                    )
                }
            }
    }

    override fun removeAllDownloads() {
        File(getDownloadPath()).listFiles().forEach {
            it.delete()
            coroutineScope.launch {
                songRepository.updateDownloadState(
                    it.name.split(".").first().removePrefix(
                        MERGING_DATA_TYPE.VIDEO,
                    ),
                    DownloadState.STATE_NOT_DOWNLOADED,
                )
            }
        }
    }
}

fun getDownloadPath(): String = System.getProperty("user.home") + File.separator + ".simpmusic" + File.separator + "downloads"
