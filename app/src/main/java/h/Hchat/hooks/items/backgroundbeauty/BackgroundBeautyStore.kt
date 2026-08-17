package h.Hchat.hooks.items.backgroundbeauty

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap

object BackgroundBeautyStore {
    private const val TAG = "[Hchat:沉浸式背景]"
    private const val DIRECTORY = "background_beauty"
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 32L * 1024L * 1024L
    private const val MAX_DECODE_DIMENSION = 4096
    private const val MAX_DECODE_PIXELS = 6L * 1024L * 1024L
    private const val MAX_CACHE_ENTRIES = 2

    private data class CacheEntry(
        val modified: Long,
        val size: Long,
        val bitmap: Bitmap
    )

    private val cache = object : LinkedHashMap<BackgroundBeautySettings.Slot, CacheEntry>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<BackgroundBeautySettings.Slot, CacheEntry>?
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    fun hasImage(context: Context, slot: BackgroundBeautySettings.Slot): Boolean {
        val file = imageFile(context, slot)
        return file.isFile && file.length() > 0L
    }

    @Synchronized
    fun loadBitmap(context: Context, slot: BackgroundBeautySettings.Slot): Bitmap? {
        val file = imageFile(context, slot)
        if (!file.isFile || file.length() <= 0L) {
            cache.remove(slot)
            return null
        }
        cache[slot]?.takeIf {
            it.modified == file.lastModified() &&
                it.size == file.length() &&
                !it.bitmap.isRecycled
        }?.let { return it.bitmap }
        val bitmap = decode(file) ?: return null
        cache[slot] = CacheEntry(file.lastModified(), file.length(), bitmap)
        return bitmap
    }

    @Synchronized
    fun saveFromUri(context: Context, slot: BackgroundBeautySettings.Slot, uri: Uri): Boolean {
        val target = imageFile(context, slot)
        val temporary = File(target.parentFile, target.name + ".tmp")
        return runCatching {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) error("背景图片不能超过 32 MB")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("无法读取背景图片")
            if (!temporary.isFile || temporary.length() <= 0L) error("背景图片为空")
            decode(temporary)?.let { bitmap ->
                if (bitmap.isRecycled) error("背景图片无效")
                bitmap.recycle()
            } ?: error("无法解析背景图片")
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                if (target.exists() && !target.delete()) error("旧背景图片删除失败")
                if (!temporary.renameTo(target)) error("背景图片替换失败")
            }
            cache.remove(slot)
            true
        }.getOrElse {
            temporary.delete()
            HLog.e("$TAG 保存${slot.title}失败: ${it.message}", it)
            false
        }
    }

    @Synchronized
    fun remove(context: Context, slot: BackgroundBeautySettings.Slot): Boolean {
        cache.remove(slot)
        val file = imageFile(context, slot)
        return !file.exists() || file.delete()
    }

    private fun imageFile(context: Context, slot: BackgroundBeautySettings.Slot): File {
        return File(File(HchatStorage.storageDir(context), DIRECTORY), slot.fileName)
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS
        ) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() /
            (sampleSize.toLong() * sampleSize.toLong()) > MAX_DECODE_PIXELS
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }
        )?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }
}
