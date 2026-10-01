package com.smartscan.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {
    /** ~9 MP: enough for A4 at ~250 dpi without running out of memory. */
    const val MAX_SIDE = 3000

    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap =
        decode({ context.contentResolver.openInputStream(uri) }, maxSide)

    fun load(file: File, maxSide: Int = MAX_SIDE): Bitmap =
        decode({ FileInputStream(file) }, maxSide)

    /** Decodes downsampled, applies EXIF orientation, returns ARGB_8888 no larger than maxSide. */
    private fun decode(open: () -> InputStream?, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val largest = max(bounds.outWidth, bounds.outHeight)
        require(largest > 0) { "Unsupported or unreadable image" }

        var sample = 1
        while (largest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not decode image")
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            bmp = copy
        }
        val orientation = runCatching {
            open()?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return scaleDown(applyOrientation(bmp, orientation), maxSide)
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return transform(src, m)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        return transform(src, Matrix().apply { postRotate(degrees.toFloat()) })
    }

    private fun transform(src: Bitmap, m: Matrix): Bitmap {
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val largest = max(src.width, src.height)
        if (largest <= maxSide) return src
        val s = maxSide.toFloat() / largest
        val out = Bitmap.createScaledBitmap(
            src,
            (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1),
            true,
        )
        if (out !== src) src.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)
            it.toByteArray()
        }
}
