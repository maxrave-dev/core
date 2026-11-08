package com.maxrave.media3.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.TypedValue
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SizeLimitedBitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.util.concurrent.ExecutionException
import kotlin.math.roundToInt

@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        coroutineScope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: error("Could not decode image data")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        coroutineScope.future(Dispatchers.IO) {
            val result =
                (
                    context.imageLoader.execute(
                        ImageRequest
                            .Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .build(),
                    )
                )
            if (result is ErrorResult) {
                throw ExecutionException(result.throwable)
            }
            try {
                result.image?.toBitmap() ?: throw ExecutionException(NullPointerException())
            } catch (e: Exception) {
                throw ExecutionException(e)
            }
        }
}

/**
 * The dp the platform sizes metadata artwork by: `config_mediaMetadataBitmapMaxSize`, 320dp in
 * AOSP's `core/res/res/values/config.xml`.
 */
private const val ARTWORK_LIMIT_DP = 320f

/**
 * [loader] capped at the artwork size the platform's own `MediaSession` will accept.
 *
 * The framework resolves that limit against the APP's resources (AOSP `MediaSession.java`:
 * `mMaxBitmapSize = context.getResources().getDimensionPixelSize(config_mediaMetadataBitmapMaxSize)`,
 * identical on android14/15/16), while Media3 resolves the same dp against `Resources.getSystem()`.
 * On a ROM that gives the app a density of its own the two differ, `setMetadata` then rescales the
 * shared bitmap itself, and some ROMs recycle the source in that path — the next metadata update
 * dies with "cannot use a recycled source in createBitmap" (#2500, and #1248 / #1276 before it).
 *
 * Capping here with the framework's own number means the framework never has to touch the bitmap.
 * The number comes from the ROM itself ([platformArtworkLimitPx]) rather than from a hardcoded dp,
 * so a ROM carrying a different value is covered too.
 *
 * Any failure returns [loader] untouched, i.e. exactly today's behaviour.
 */
@UnstableApi
fun sizeLimitedForSession(
    context: Context,
    loader: BitmapLoader,
): BitmapLoader =
    try {
        val limit = platformArtworkLimitPx(context)
        // Media3 wraps this again with makeShared = true, so sharing here would copy the pixels twice.
        if (limit > 0) SizeLimitedBitmapLoader(loader, limit, /* makeShared = */ false) else loader
    } catch (e: Throwable) {
        Logger.w("CoilBitmapLoader", "Artwork size cap unavailable, using the plain loader: ${e.message}")
        loader
    }

/**
 * The value the ROM itself carries for `config_mediaMetadataBitmapMaxSize`, resolved against the
 * APP's resources — the same number `MediaSession.setMetadata` compares artwork against.
 *
 * It is an internal platform resource with no public constant, so a name lookup is the only way to
 * read it; media3-session's own `MediaSessionImpl.getMediaMetadataBitmapMaxSize()` does exactly
 * this, under the same lint suppression. A ROM that renamed or dropped it falls back to AOSP's own
 * [ARTWORK_LIMIT_DP].
 */
@SuppressLint("DiscouragedApi")
private fun platformArtworkLimitPx(context: Context): Int {
    val resources = context.resources
    val id = resources.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
    if (id != 0) {
        val fromRom = resources.getDimensionPixelSize(id)
        if (fromRom > 0) return fromRom
    }
    return TypedValue
        .applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            ARTWORK_LIMIT_DP,
            resources.displayMetrics,
        ).roundToInt()
}