package com.maxrave.media3.carapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarIconSpan
import androidx.car.app.model.Row
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private const val DEFAULT_LIST_LIMIT = 100
private const val ARTWORK_CORNER_RADIUS_FRACTION = 0.1f

/** Hosts offer no rounded-corner image API (only square/circle), so the corners are baked in. */
private fun Bitmap.withRoundedCorners(): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = minOf(width, height) * ARTWORK_CORNER_RADIUS_FRACTION
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)
    return output
}

/** Host row budget for list templates, with a sane fallback. */
internal fun CarContext.listContentLimit(): Int =
    runCatching {
        getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(DEFAULT_LIST_LIMIT)

/**
 * Secondary-text line with a static equalizer glyph in front of [suffix],
 * marking the currently playing row. Static by design: animating it requires
 * re-sending the whole template every frame (~420KB of binder traffic per
 * tick), which floods the host and starves its input handling.
 */
internal fun CarContext.nowPlayingText(suffix: String): CharSequence =
    SpannableString(if (suffix.isBlank()) " " else "  $suffix").apply {
        setSpan(
            CarIconSpan.create(
                CarIcon
                    .Builder(IconCompat.createWithResource(this@nowPlayingText, R.drawable.ic_car_now_playing))
                    .build(),
                CarIconSpan.ALIGN_CENTER,
            ),
            0,
            1,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }

/**
 * Shared browse/search row: artwork as the start image, equalizer marker on
 * the playing row, chevron for browsable nodes.
 */
internal fun CarContext.mediaRow(
    item: MediaItem,
    artwork: CarIcon?,
    isPlaying: Boolean,
    onClick: () -> Unit,
): Row {
    val metadata = item.mediaMetadata
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
    return Row
        .Builder()
        .setTitle(title)
        .apply {
            val subtitle = metadata.subtitle?.toString().orEmpty()
            if (isPlaying) {
                addText(nowPlayingText(subtitle))
            } else if (subtitle.isNotBlank()) {
                addText(subtitle)
            }
            artwork?.let { setImage(it) }
            setBrowsable(metadata.isBrowsable == true)
        }.setOnClickListener { onClick() }
        .build()
}

/**
 * Fire-and-forget play through the session's browser. Runs on the future's
 * executor, not a screen scope: navigating right after pops the screen and
 * would cancel a coroutine before the command is sent.
 */
@UnstableApi
internal fun playViaBrowser(
    carContext: CarContext,
    browserFuture: ListenableFuture<MediaBrowser>,
    item: MediaItem,
    tag: String,
) {
    browserFuture.addListener({
        runCatching {
            val browser = browserFuture.get()
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
        }.onFailure {
            Logger.e(tag, "playItem(${item.mediaId}) failed: ${it.message}")
        }
    }, ContextCompat.getMainExecutor(carContext))
}

/** Loads row artwork into [artwork], invalidating via [onLoaded] per image. */
@UnstableApi
internal fun loadArtworkInto(
    scope: CoroutineScope,
    bitmapLoader: CoilBitmapLoader,
    items: List<MediaItem>,
    limit: Int,
    artwork: MutableMap<String, CarIcon>,
    tag: String,
    onLoaded: () -> Unit,
) {
    items.take(limit).forEach { item ->
        val uri = item.mediaMetadata.artworkUri ?: return@forEach
        if (artwork.containsKey(item.mediaId)) return@forEach
        scope.launch {
            try {
                val bitmap = bitmapLoader.loadBitmap(uri).await()
                artwork[item.mediaId] =
                    CarIcon.Builder(IconCompat.createWithBitmap(bitmap.withRoundedCorners())).build()
                onLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "artwork(${item.mediaId}) failed: ${e.message}")
            }
        }
    }
}
