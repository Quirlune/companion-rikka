package dev.gray.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import dev.gray.companion.core.AvatarImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** One portable thumbnail codec for both the image picker and content-package imports. */
internal object AvatarImportPreparation {
    fun encode(bytes: ByteArray): AvatarImage {
        require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) { "头像文件过大或为空" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "头像图片不可读" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmaps = mutableListOf<Bitmap>()
        fun keep(bitmap: Bitmap) = bitmap.also { bitmaps += it }
        try {
            val decoded = keep(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("头像图片不可读"))
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = keep(Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true))
            val edge = minOf(oriented.width, oriented.height)
            val cropped = keep(Bitmap.createBitmap(oriented, (oriented.width - edge) / 2,
                (oriented.height - edge) / 2, edge, edge))
            val scaled = keep(Bitmap.createScaledBitmap(cropped, 256, 256, true))
            val opaque = keep(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888))
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            for (quality in listOf(82, 65, 45)) {
                val compressed = ByteArrayOutputStream().use { output ->
                    check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    output.toByteArray()
                }
                if (compressed.size <= AvatarImage.MAX_BYTES)
                    return AvatarImage(base64 = Base64.getEncoder().encodeToString(compressed))
            }
            error("头像缩略图仍过大")
        } finally { bitmaps.distinct().forEach { it.recycle() } }
    }
}
