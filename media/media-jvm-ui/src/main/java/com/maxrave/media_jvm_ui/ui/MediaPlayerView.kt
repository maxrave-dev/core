package com.maxrave.media_jvm_ui.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.simpmusic.media_jvm.mpv.MpvPlayer
import com.simpmusic.media_jvm.mpv.MpvPlayerAdapter
import com.simpmusic.media_jvm.mpv.MpvVideoFrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun MediaPlayerViewWithUrl(
    url: String,
    modifier: Modifier,
    cropToBounds: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var frameSource by remember { mutableStateOf<MpvVideoFrameSource?>(null) }
    var mpvPlayer by remember { mutableStateOf<MpvPlayer?>(null) }

    DisposableEffect(url) {
        // Creating the handle no longer touches Swing (the render target is a plain frame
        // flow), so the blocking mpv_create/mpv_initialize pair runs on IO instead of the EDT.
        scope.launch(Dispatchers.IO) {
            // audioOnly = false attaches the software render context before the first loadfile,
            // which render.h requires. Returns null when libmpv is missing; the Box below then
            // simply renders nothing, and MpvPlayer.create() has already logged why.
            val player = MpvPlayer.create(audioOnly = false)
            if (player != null) {
                mpvPlayer = player
                // mpv loops natively, so no end-of-file listener is needed.
                player.setLooping(true)
                frameSource = player.videoFrames
                player.loadFile(url, startPaused = false)
            }
        }
        onDispose {
            mpvPlayer?.release()
            frameSource = null
            mpvPlayer = null
        }
    }

    // Kept out of the DisposableEffect above so flipping the flag re-scales the running video
    // instead of tearing down and re-creating the mpv handle.
    LaunchedEffect(mpvPlayer, cropToBounds) {
        mpvPlayer?.setPanscan(if (cropToBounds) 1.0 else 0.0)
    }

    Box(
        modifier
            .then(
                Modifier
                    .graphicsLayer { clip = true },
            ),
    ) {
        frameSource?.let { source ->
            MpvVideoFrames(
                source = source,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
            )
        }
    }
}

/**
 * Draws the frames published by an [MpvVideoFrameSource] as a plain Compose [Image].
 *
 * Replaces the previous SwingPanel embedding. SwingPanel is a heavyweight AWT overlay: it sits
 * above every Compose node regardless of z-order, repositions one frame late while scrolling
 * (the flicker that exposed the transparent window behind it), and as an AWT component could
 * only have one parent — so two screens composing the player fought over the panel. An Image
 * participates in normal Compose rendering, and any number of screens can collect the same
 * source at once.
 *
 * The box reports its size to the source, so mpv scales frames to exactly this box — letterboxing
 * them by default, or cropping them to cover it when the caller asked for that (see
 * `MpvPlayer.setPanscan`). Either way the fit is decided before Compose sees the pixels, so
 * [ContentScale.Fit] only matters in the moment after a resize while the next correctly-sized
 * frame is still being rendered.
 */
@Composable
private fun MpvVideoFrames(
    source: MpvVideoFrameSource,
    modifier: Modifier = Modifier,
) {
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        withContext(Dispatchers.Default) {
            // The conversion copies the pixels, so keep it off the UI thread. Every emission is
            // an immutable snapshot — see [MpvVideoFrameSource.frames].
            source.frames.collect { image ->
                frame = image?.toComposeImageBitmap()
            }
        }
    }
    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { source.setTargetSize(it.width, it.height) },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private val RICH_SYNC_TIMESTAMP_REGEX = Regex("""<\d{2}:\d{2}\.\d{2,3}>\s*""")

@Composable
fun MediaPlayerViewWithSubtitleJvm(
    modifier: Modifier,
    playerName: String,
    shouldShowSubtitle: Boolean,
    shouldScaleDownSubtitle: Boolean,
    timelineState: TimeLine,
    lyricsData: Lyrics?,
    translatedLyricsData: Lyrics?,
    mainTextStyle: TextStyle,
    translatedTextStyle: TextStyle,
    mediaPlayerHandler: MediaPlayerHandler = koinInject(),
) {
    val player: MpvPlayerAdapter = koinInject<MpvPlayerAdapter>()

    val state by mediaPlayerHandler.nowPlayingState.collectAsState()
    val videoFrames by player.currentVideoFrames.collectAsState()

    val showArtwork = videoFrames == null

    val artworkUri = state.songEntity?.thumbnails

    var currentLineIndex by rememberSaveable { mutableIntStateOf(-1) }
    var currentTranslatedLineIndex by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(key1 = timelineState) {
        val lines = lyricsData?.lines ?: return@LaunchedEffect
        val translatedLines = translatedLyricsData?.lines
        if (timelineState.current > 0L) {
            lines.indices.forEach { i ->
                val sentence = lines[i]
                val startTimeMs = sentence.startTimeMs.toLong()
                val endTimeMs =
                    if (i < lines.size - 1) {
                        lines[i + 1].startTimeMs.toLong()
                    } else {
                        startTimeMs + 60000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentLineIndex = i
                }
            }
            translatedLines?.indices?.forEach { i ->
                val sentence = translatedLines[i]
                val startTimeMs = sentence.startTimeMs.toLong()
                val endTimeMs =
                    if (i < translatedLines.size - 1) {
                        translatedLines[i + 1].startTimeMs.toLong()
                    } else {
                        startTimeMs + 60000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentTranslatedLineIndex = i
                }
            }
            if (lines.isNotEmpty() &&
                (timelineState.current in (0..(lines.getOrNull(0)?.startTimeMs ?: "0").toLong()))
            ) {
                currentLineIndex = -1
                currentTranslatedLineIndex = -1
            }
        } else {
            currentLineIndex = -1
            currentTranslatedLineIndex = -1
        }
    }

    Box(
        modifier =
            modifier
                .graphicsLayer { clip = true },
        contentAlignment = Alignment.Center,
    ) {
        if (showArtwork) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(artworkUri)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(artworkUri)
                        .crossfade(550)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.FillHeight,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .align(Alignment.Center),
            )
        } else {
            videoFrames?.let { source ->
                MpvVideoFrames(
                    source = source,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (lyricsData != null && shouldShowSubtitle) {
            Crossfade(
                currentLineIndex != -1,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize(),
            ) {
                val lines = lyricsData.lines ?: return@Crossfade
                if (it) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(bottom = if (shouldScaleDownSubtitle) 10.dp else 40.dp)
                            .align(Alignment.BottomCenter),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(Modifier.fillMaxWidth(0.7f)) {
                            Column(
                                Modifier.align(Alignment.BottomCenter),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text =
                                        lines
                                            .getOrNull(currentLineIndex)
                                            ?.words
                                            ?.replace(RICH_SYNC_TIMESTAMP_REGEX, "")
                                            ?.trim() ?: return@Crossfade,
                                    style =
                                        mainTextStyle.let { style ->
                                            if (shouldScaleDownSubtitle) {
                                                style.copy(fontSize = style.fontSize * 0.8f)
                                            } else {
                                                style
                                            }
                                        },
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier =
                                        Modifier
                                            .padding(4.dp)
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .wrapContentWidth(),
                                )
                                Crossfade(translatedLyricsData?.lines != null, label = "") { translate ->
                                    val translateLines = translatedLyricsData?.lines ?: return@Crossfade
                                    if (translate) {
                                        Text(
                                            text = translateLines.getOrNull(currentTranslatedLineIndex)?.words ?: return@Crossfade,
                                            style =
                                                translatedTextStyle.let { style ->
                                                    if (shouldScaleDownSubtitle) {
                                                        style.copy(fontSize = style.fontSize * 0.8f)
                                                    } else {
                                                        style
                                                    }
                                                },
                                            color = Color.Yellow,
                                            textAlign = TextAlign.Center,
                                            modifier =
                                                Modifier
                                                    .background(Color.Black.copy(alpha = 0.5f))
                                                    .wrapContentWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}