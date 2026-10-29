package dev.zunehd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/** Album art from a media session: an inline bitmap, or a content/https URI to fetch. */
object ArtLoader {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun bitmap(m: MediaMetadata): Bitmap? =
        m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    fun uri(m: MediaMetadata): String? = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    ).firstNotNullOfOrNull { m.getString(it)?.takeIf { s -> s.isNotEmpty() } }

    fun load(ctx: Context, uri: String, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val b = runCatching { decode(app, uri) }.getOrNull()
            main.post { done(b) }
        }
    }

    private fun decode(ctx: Context, uri: String): Bitmap? {
        val bytes = open(ctx, uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun open(ctx: Context, uri: String): InputStream =
        if (uri.startsWith("http")) {
            URL(uri).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }.getInputStream()
        } else {
            ctx.contentResolver.openInputStream(Uri.parse(uri)) ?: error("no stream")
        }

    /** A soft, cheap blur: shrink to a few pixels and let bilinear scaling smear it back up. */
    fun blur(src: Bitmap): Bitmap {
        val w = 12
        val h = max(1, w * src.height / max(1, src.width))
        val tiny = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(tiny, w * 6, h * 6, true)
    }

    fun thumbnail(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }
}
