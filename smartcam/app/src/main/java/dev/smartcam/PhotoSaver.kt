package dev.smartcam

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns a captured frame upright, optionally enhances it, and saves it to Pictures/SmartCam. */
object PhotoSaver {

    fun process(captured: Bitmap, rotationDegrees: Int, scene: SceneType, enhance: Boolean): Bitmap {
        var bitmap = captured
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmap = Bitmap.createBitmap(captured, 0, 0, captured.width, captured.height, matrix, true)
            captured.recycle()
        }
        if (!enhance) return bitmap
        if (!bitmap.isMutable) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }

        val sample = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 8).coerceAtLeast(1), (bitmap.height / 8).coerceAtLeast(1), true)
        val samplePixels = IntArray(sample.width * sample.height)
        sample.getPixels(samplePixels, 0, sample.width, 0, 0, sample.width, sample.height)
        sample.recycle()
        val plan = PhotoEnhancer.plan(samplePixels, scene)

        // Work in bands to avoid holding a second full-size copy of a 12+ MP photo.
        val band = 256
        val row = IntArray(bitmap.width * band)
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(band, bitmap.height - y)
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, rows)
            PhotoEnhancer.apply(row, plan)
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, rows)
            y += rows
        }
        return bitmap
    }

    fun save(resolver: ContentResolver, bitmap: Bitmap): Uri? {
        val now = System.currentTimeMillis()
        val name = "SmartCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartCam")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: throw IllegalStateException("No output stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
