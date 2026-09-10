package h.Hchat.hooks.items.virtualcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever

internal object VirtualCameraMedia {
    fun decodeFrame(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val targetWidth = requestedWidth.coerceAtLeast(2)
        val targetHeight = requestedHeight.coerceAtLeast(2)
        val raw = decodeSampledImage(path, targetWidth, targetHeight) ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
        raw ?: return null
        if (raw.width == targetWidth && raw.height == targetHeight) return raw
        return Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true).also {
            if (it !== raw) raw.recycle()
        }
    }

    private fun decodeSampledImage(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth &&
            bounds.outHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    fun bitmapToNv21(bitmap: Bitmap, requestedWidth: Int, requestedHeight: Int): ByteArray {
        val width = requestedWidth.coerceAtMost(bitmap.width) and -2
        val height = requestedHeight.coerceAtMost(bitmap.height) and -2
        val out = ByteArray(width * height * 3 / 2)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var yIndex = 0
        var uvIndex = width * height
        for (yPos in 0 until height) {
            for (xPos in 0 until width) {
                val color = pixels[yPos * width + xPos]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val yValue = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                out[yIndex++] = yValue.coerceIn(0, 255).toByte()
                if ((yPos and 1) == 0 && (xPos and 1) == 0) {
                    val uValue = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val vValue = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    out[uvIndex++] = vValue.coerceIn(0, 255).toByte()
                    out[uvIndex++] = uValue.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }
}
