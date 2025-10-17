package com.maxrave.media_jvm_ui.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.simpmusic.media_jvm.GstreamerPlayerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.swing.GstVideoComponent
import org.koin.compose.koinInject
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.swing.JPanel

@Composable
fun MediaPlayerViewWithUrl(
    url: String,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var gsVideoComponent by remember {
        mutableStateOf<GstVideoComponent?>(null)
    }
    LaunchedEffect(url) {
        scope.launch(Dispatchers.Swing) {
            val gsv = GstVideoComponent()
            gsv.background = (java.awt.Color(0, 0, 0, 0))
            gsVideoComponent = gsv
            val playBin =
                PlayBin("canvas").apply {
                    setVideoSink(gsv.element)
                }
            playBin.autoFlushBus = true
            playBin.bus.connect(
                Bus.EOS {
                    playBin.pause()
                    playBin.seek(0L, TimeUnit.MILLISECONDS)
                    playBin.play()
                },
            )
            playBin.setURI(URI(url))
            playBin.play()
        }
    }

    Box(
        modifier
            .graphicsLayer { clip = true },
    ) {
        if (gsVideoComponent != null) {
            SwingPanel(
                factory = {
                    JPanel().apply {
                        background = java.awt.Color(0, 0, 0, 0)
                        gsVideoComponent?.preferredSize = java.awt.Dimension(400, 300)
                        add(gsVideoComponent)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .wrapContentWidth(),
                background = Color.Transparent,
            )
        }
    }
}

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
) {
    val player: GstreamerPlayerAdapter = koinInject<GstreamerPlayerAdapter>()

    val scope = rememberCoroutineScope()
    var sizePx by remember {
        mutableStateOf(0 to 0)
    }
    var gsVideoComponent by remember {
        mutableStateOf<GstVideoComponent?>(null)
    }

    var showArtwork by rememberSaveable {
        mutableStateOf(false)
    }

    var artworkUri by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var currentLineIndex by rememberSaveable {
        mutableIntStateOf(-1)
    }
    var currentTranslatedLineIndex by rememberSaveable {
        mutableIntStateOf(-1)
    }

    DisposableEffect(true) {
        val listener = object: MediaPlayerListener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                gsVideoComponent = player.getCurrentPlayer()?.videoComponent
            }

            override fun onMediaItemTransition(mediaItem: GenericMediaItem?, reason: Int) {
                gsVideoComponent = null
            }
        }
        scope.launch(Dispatchers.Swing) {
            gsVideoComponent = player.getCurrentPlayer()?.videoComponent
            player.addListener(listener)
        }
        onDispose {
            scope.launch(Dispatchers.Swing) {
                player.removeListener(listener)
                gsVideoComponent = null
            }
        }
    }

    LaunchedEffect(key1 = timelineState) {
        val lines = lyricsData?.lines ?: return@LaunchedEffect
        val translatedLines = translatedLyricsData?.lines
        if (timelineState.current > 0L) {
            lines.indices.forEach { i ->
                val sentence = lines[i]
                val startTimeMs = sentence.startTimeMs.toLong()

                // estimate the end time of the current sentence based on the start time of the next sentence
                val endTimeMs =
                    if (i < lines.size - 1) {
                        lines[i + 1].startTimeMs.toLong()
                    } else {
                        // if this is the last sentence, set the end time to be some default value (e.g., 1 minute after the start time)
                        startTimeMs + 60000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentLineIndex = i
                }
            }
            translatedLines?.indices?.forEach { i ->
                val sentence = translatedLines[i]
                val startTimeMs = sentence.startTimeMs.toLong()

                // estimate the end time of the current sentence based on the start time of the next sentence
                val endTimeMs =
                    if (i < translatedLines.size - 1) {
                        translatedLines[i + 1].startTimeMs.toLong()
                    } else {
                        // if this is the last sentence, set the end time to be some default value (e.g., 1 minute after the start time)
                        startTimeMs + 60000
                    }
                if (timelineState.current in startTimeMs..endTimeMs) {
                    currentTranslatedLineIndex = i
                }
            }
            if (lines.isNotEmpty() &&
                (
                    timelineState.current in (
                        0..(
                            lines.getOrNull(0)?.startTimeMs
                                ?: "0"
                        ).toLong()
                    )
                )
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
        modifier = modifier.graphicsLayer { clip = true }
            .onGloballyPositioned {
                val width = it.size.width
                val height = it.size.height
                sizePx = width to height
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(showArtwork) {
            if (it) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(
                                artworkUri,
                            ).diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(
                                artworkUri,
                            ).crossfade(550)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.FillHeight,
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .align(Alignment.Center),
                )
            } else {
                Box(
                    Modifier
                        .graphicsLayer { clip = true }
                        .wrapContentSize()
                        .align(Alignment.Center),
                ) {
                    if (gsVideoComponent != null && sizePx.first > 0 && sizePx.second > 0) {
                        SwingPanel(
                            factory = {
                                JPanel().apply {
                                    background = java.awt.Color(0, 0, 0, 0)
                                    gsVideoComponent?.preferredSize = java.awt.Dimension(sizePx.first, sizePx.second)
                                    add(gsVideoComponent)
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .wrapContentWidth(),
                            background = Color.Transparent,
                        )
                    }
                }
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
                                    text = lines.getOrNull(currentLineIndex)?.words ?: return@Crossfade,
                                    style =
                                        mainTextStyle
                                            .let {
                                                if (shouldScaleDownSubtitle) {
                                                    it.copy(fontSize = it.fontSize * 0.8f)
                                                } else {
                                                    it
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
                                                translatedTextStyle.let {
                                                    if (shouldScaleDownSubtitle) {
                                                        it.copy(fontSize = it.fontSize * 0.8f)
                                                    } else {
                                                        it
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