package android.graphics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.os.Looper
class Bitmap(val width: Int, val height: Int, val source: String) {
    val allocationByteCount: Int get() = width * height * 4
    var recycled = false
    fun recycle() { recycled = true }
    companion object {
        fun createScaledBitmap(bitmap: Bitmap, width: Int, height: Int, filter: Boolean) = Bitmap(width, height, bitmap.source)
    }
}
object BitmapFactory {
    class Options { var inJustDecodeBounds = false; var outWidth = 0; var outHeight = 0; var inSampleSize = 1 }
    class Gate { val started = CountDownLatch(1); val release = CountDownLatch(1) }
    val gates = ConcurrentHashMap<String, Gate>()
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val versions = ConcurrentHashMap<String, String>()
    fun decodeFile(path: String, options: Options): Bitmap? {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "Decode ran on UI thread" }
        if (options.inJustDecodeBounds) {
            options.outWidth = if (path == "large") 4096 else 64
            options.outHeight = if (path == "large") 2048 else 64
            return null
        }
        calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        val source = versions[path] ?: path
        gates.remove(path)?.let { it.started.countDown(); check(it.release.await(3, TimeUnit.SECONDS)) }
        if (path == "missing") return null
        return Bitmap((if (path == "large") 4096 else 64) / options.inSampleSize,
            (if (path == "large") 2048 else 64) / options.inSampleSize, source)
    }
}
