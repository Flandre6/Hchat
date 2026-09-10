package h.Hchat.hooks.items.virtualcamera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.InputConfiguration
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Build
import android.view.Surface
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

internal class VirtualCamera2Runtime(
    private val mediaPath: () -> String?,
    private val operational: () -> Boolean
) {
    private val readersBySurface = ConcurrentHashMap<Surface, ReaderInfo>()
    private val virtualByOriginal = IdentityHashMap<Surface, VirtualTextureDrain>()
    private val outputsByOriginal = IdentityHashMap<Surface, VirtualCameraSurfaceOutput>()
    private val yuvWritersByOriginal = IdentityHashMap<Surface, VirtualYuvWriter>()

    fun rememberImageReader(reader: ImageReader) {
        runCatching {
            readersBySurface[reader.surface] = ReaderInfo(reader.width, reader.height, reader.imageFormat)
        }.onFailure { HLog.e("$TAG 记录 ImageReader 失败", it) }
    }

    @Synchronized
    fun virtualFor(original: Surface): Surface? {
        if (!operational() || !original.isValid) return null
        val reader = readersBySurface[original]
        if (reader?.format == ImageFormat.JPEG || reader?.format == 1768253795) return null
        virtualByOriginal[original]?.takeIf { it.isValid() }?.let { return it.surface() }
        val drain = runCatching {
            VirtualTextureDrain("Hchat-VirtualCamera2-${virtualByOriginal.size}")
        }.onFailure {
            HLog.e("$TAG 创建虚拟 Surface 失败，保留真实相机输出", it)
        }.getOrNull() ?: return null
        if (!drain.isValid()) {
            drain.release()
            return null
        }
        virtualByOriginal[original] = drain
        if (reader != null && isYuv(reader.format)) {
            yuvWritersByOriginal[original] = VirtualYuvWriter(original, reader, mediaPath)
                .also { it.start() }
            HLog.i("$TAG 接管 Camera2 YUV 输出: ${reader.width}x${reader.height}, format=${reader.format}")
        } else {
            outputsByOriginal[original] = VirtualCameraSurfaceOutput(original, mediaPath, TAG)
                .also { it.start() }
            HLog.i("$TAG 接管 Camera2 预览/录像 Surface")
        }
        return drain.surface()
    }

    @Synchronized
    fun existingVirtualFor(original: Surface): Surface? = virtualByOriginal[original]?.surface()

    fun rewriteSessionArguments(args: Array<Any?>) {
        for (index in args.indices) {
            val value = args[index] ?: continue
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    value.javaClass.name == "android.hardware.camera2.params.SessionConfiguration" -> {
                    rewriteSessionConfiguration(value)?.let { args[index] = it }
                }
                value is List<*> -> {
                    args[index] = rewriteList(value)
                }
            }
        }
    }

    private fun rewriteList(input: List<*>): List<*> {
        if (input.isEmpty()) return input
        return when {
            input.all { it is Surface } -> input.map { original ->
                val surface = original as Surface
                virtualFor(surface) ?: surface
            }
            input.all { it is OutputConfiguration } -> input.map { output ->
                rewriteOutputConfiguration(output as OutputConfiguration)
            }
            else -> input
        }
    }

    private fun rewriteOutputConfiguration(original: OutputConfiguration): OutputConfiguration {
        val originalSurface = original.surface ?: return original
        val replacement = virtualFor(originalSurface) ?: return original
        val rewritten = runCatching {
            OutputConfiguration(original.surfaceGroupId, replacement)
        }.getOrElse { OutputConfiguration(replacement) }
        copyOutputMetadata(original, rewritten)
        copySharedSurfaces(original, rewritten, originalSurface)
        return rewritten
    }

    private fun copySharedSurfaces(
        source: OutputConfiguration,
        target: OutputConfiguration,
        sourcePrimary: Surface
    ) {
        val extraSurfaces = runCatching { source.surfaces }
            .getOrNull()
            .orEmpty()
            .filter { it != sourcePrimary }
        if (extraSurfaces.isEmpty()) return
        runCatching { target.enableSurfaceSharing() }
        extraSurfaces.forEach { original ->
            val rewritten = virtualFor(original) ?: original
            runCatching { target.addSurface(rewritten) }
                .onFailure { HLog.e("$TAG 重建共享 Surface 失败", it) }
        }
    }

    private fun rewriteSessionConfiguration(original: Any): Any? {
        val type = original.javaClass
        val outputs = KavaReflector.invokeMethod(original, "getOutputConfigurations") as? List<*> ?: return null
        val rewrittenOutputs = outputs.filterIsInstance<OutputConfiguration>().map(::rewriteOutputConfiguration)
        if (rewrittenOutputs.size != outputs.size) return null
        val sessionType = KavaReflector.invokeMethod(original, "getSessionType") as? Int ?: return null
        val executor = KavaReflector.invokeMethod(original, "getExecutor") as? java.util.concurrent.Executor ?: return null
        val callback = KavaReflector.invokeMethod(original, "getStateCallback") as? CameraCaptureSession.StateCallback ?: return null
        val constructor = KavaReflector.findConstructor(
            type,
            Int::class.javaPrimitiveType!!,
            List::class.java,
            java.util.concurrent.Executor::class.java,
            CameraCaptureSession.StateCallback::class.java
        ) ?: return null
        val rewritten = KavaReflector.newInstance(constructor, sessionType, rewrittenOutputs, executor, callback) ?: return null
        (KavaReflector.invokeMethod(original, "getInputConfiguration") as? InputConfiguration)?.let {
            KavaReflector.invokeMethod(rewritten, "setInputConfiguration", it)
        }
        (KavaReflector.invokeMethod(original, "getSessionParameters") as? CaptureRequest)?.let {
            KavaReflector.invokeMethod(rewritten, "setSessionParameters", it)
        }
        return rewritten
    }

    private fun copyOutputMetadata(source: OutputConfiguration, target: OutputConfiguration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val getter = KavaReflector.findMethod(source.javaClass, "getPhysicalCameraId")
                val physicalId = KavaReflector.invoke(getter, source) as? String
                physicalId?.let(target::setPhysicalCameraId)
            }
        }
    }

    @Synchronized
    fun resetSession(reason: String) {
        releaseSessionResources()
        HLog.i("$TAG 已重置 Camera2 会话: $reason")
    }

    @Synchronized
    fun release() {
        releaseSessionResources()
        readersBySurface.clear()
    }

    private fun releaseSessionResources() {
        outputsByOriginal.values.toList().forEach { it.release() }
        outputsByOriginal.clear()
        yuvWritersByOriginal.values.toList().forEach { it.release() }
        yuvWritersByOriginal.clear()
        virtualByOriginal.values.toList().forEach { it.release() }
        virtualByOriginal.clear()
    }

    private fun isYuv(format: Int): Boolean {
        return format == ImageFormat.YUV_420_888 || format == ImageFormat.NV21 || format == ImageFormat.YV12
    }

    data class ReaderInfo(val width: Int, val height: Int, val format: Int)

    companion object {
        private const val TAG = "[Hchat:VirtualCamera2]"
    }
}

private class VirtualYuvWriter(
    private val target: Surface,
    private val info: VirtualCamera2Runtime.ReaderInfo,
    private val mediaPath: () -> String?
) {
    private val thread = android.os.HandlerThread("Hchat-VirtualYuv").apply { start() }
    private val handler = android.os.Handler(thread.looper)
    private var writer: ImageWriter? = null
    private var running = false
    private var cachedFrameKey = ""
    private var cachedFrame: ByteArray? = null
    private var lastFailureLogAt = 0L

    fun start() {
        handler.post {
            runCatching {
                writer = ImageWriter.newInstance(target, 3)
                running = true
                writeNext()
            }.onFailure { HLog.e("[Hchat:VirtualCamera2] 创建 YUV 写入器失败", it) }
        }
    }

    private fun writeNext() {
        if (!running || !target.isValid) return
        runCatching {
            val path = mediaPath() ?: return@runCatching
            val frameKey = "$path|${java.io.File(path).lastModified()}|${info.width}x${info.height}"
            val nv21 = (if (frameKey == cachedFrameKey) cachedFrame else null)
                ?: VirtualCameraMedia.decodeFrame(path, info.width, info.height)?.let { bitmap ->
                    VirtualCameraMedia.bitmapToNv21(bitmap, info.width, info.height).also {
                        bitmap.recycle()
                        cachedFrameKey = frameKey
                        cachedFrame = it
                    }
                } ?: return@runCatching
            val image = writer?.dequeueInputImage() ?: return@runCatching
            try {
                fillYuv420(image, nv21, info.width and -2, info.height and -2)
                writer?.queueInputImage(image)
            } catch (throwable: Throwable) {
                image.close()
                throw throwable
            }
        }.onFailure {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFailureLogAt >= 10_000L) {
                lastFailureLogAt = now
                HLog.e("[Hchat:VirtualCamera2] 写入 YUV 帧失败", it)
            }
        }
        if (running) handler.postDelayed(::writeNext, 66L)
    }

    private fun fillYuv420(image: android.media.Image, nv21: ByteArray, width: Int, height: Int) {
        val planes = image.planes
        require(planes.size >= 3) { "YUV image has ${planes.size} planes" }
        val ySize = width * height
        writePlane(planes[0], width, height) { x, y -> nv21[y * width + x] }
        writePlane(planes[1], width / 2, height / 2) { x, y -> nv21[ySize + y * width + x * 2 + 1] }
        writePlane(planes[2], width / 2, height / 2) { x, y -> nv21[ySize + y * width + x * 2] }
    }

    private inline fun writePlane(
        plane: android.media.Image.Plane,
        width: Int,
        height: Int,
        valueAt: (Int, Int) -> Byte
    ) {
        val buffer = plane.buffer
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset < buffer.capacity()) buffer.put(offset, valueAt(x, y))
            }
        }
    }

    fun release() {
        running = false
        cachedFrame = null
        cachedFrameKey = ""
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { writer?.close() }
            writer = null
            thread.quitSafely()
        }
    }
}
