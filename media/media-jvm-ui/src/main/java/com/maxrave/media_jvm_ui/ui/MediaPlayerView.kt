package com.maxrave.media_jvm_ui.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.swing.GstVideoComponent
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.swing.JLayeredPane
import javax.swing.JTextArea


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
            val playBin = PlayBin("canvas").apply {
                setVideoSink(gsv.element)
            }
            playBin.autoFlushBus = true
            playBin.bus.connect(
                Bus.EOS {
                    playBin.pause()
                    playBin.seek(0L, TimeUnit.MILLISECONDS)
                    playBin.play()
                }
            )
            playBin.setURI(URI(url))
            playBin.play()
        }
    }

    Box(
        modifier.graphicsLayer { clip = true }
            .border(
                width = 2.dp,
                color = androidx.compose.ui.graphics.Color.Red,
            )
    ) {
        if (gsVideoComponent != null) {
            SwingPanel(
                factory = {
                    JLayeredPane().apply {
                        gsVideoComponent?.preferredSize = java.awt.Dimension(400, 300)
                        setLayer(gsVideoComponent, 0)
                        setLayer(
                            JTextArea().apply {
                                text = "Media Player View"
                            },
                            10000
                        )
                    }
                },
                modifier = Modifier.fillMaxHeight()
                    .wrapContentWidth(),
                background = androidx.compose.ui.graphics.Color.Transparent
            )
        }
    }
}