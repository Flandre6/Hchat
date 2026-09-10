package h.Hchat.hooks.items.virtualcamera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executor

class VirtualCameraFeature : BaseFeature() {
    private var hooker: VirtualCameraHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "虚拟摄像头（实验）"

    override fun isEnabled(context: FeatureContext): Boolean {
        val loadPackage = context.loadPackageParam()
        if (loadPackage.packageName != "com.tencent.mm" || loadPackage.processName != loadPackage.packageName) {
            return false
        }
        return HchatStorage.preferences(context.hostContext(), VirtualCameraSettings.PREFS_NAME)
            .getBoolean(VirtualCameraSettings.KEY_ENABLE, VirtualCameraSettings.DEFAULT_ENABLE)
    }

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(VirtualCameraSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = VirtualCameraHooker(context).also { it.install() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.clear()
        hooker = null
    }

    companion object {
        const val ID = "virtual_camera"
    }
}

private class VirtualCameraHooker(private val context: FeatureContext) {
    private val prefs = HchatStorage.preferences(context.hostContext(), VirtualCameraSettings.PREFS_NAME)
    private val camera2 = VirtualCamera2Runtime(::mediaPath, ::isOperational)
    private val hookedCallbacks = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val frameCache = HashMap<String, ByteArray>()
    private val jpegCache = HashMap<String, ByteArray>()
    private val camera1Outputs = WeakHashMap<Camera, VirtualCameraSurfaceOutput>()
    private val camera1Drains = WeakHashMap<Camera, VirtualTextureDrain>()
    private val internalCamera1Preview = ThreadLocal<Boolean>()
    @Volatile private var currentActivityName = ""
    @Volatile private var lastMediaFailureLogAt = 0L

    @Synchronized
    fun install() {
        installActivityGuard()
        installImageReaderTracking()
        installCamera1()
        installCamera2()
        HLog.i("$TAG Hook 已安装: Camera1 + Camera2；身份/人脸/活体页面自动旁路")
    }

    private fun installActivityGuard() {
        KavaReflector.findDeclaredMethod(Activity::class.java, "onCreate", Bundle::class.java)?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    currentActivityName = activity.javaClass.name
                    if (isSensitiveActivity(currentActivityName)) {
                        camera2.resetSession("创建敏感页面")
                        releaseCamera1Outputs()
                    }
                }
            })
        }
        KavaReflector.findDeclaredMethod(Activity::class.java, "onResume")?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    currentActivityName = activity.javaClass.name
                    if (isSensitiveActivity(currentActivityName)) {
                        camera2.resetSession("进入敏感页面")
                        releaseCamera1Outputs()
                        HLog.i("$TAG 已旁路敏感页面: $currentActivityName")
                    }
                }
            })
        }
        KavaReflector.findDeclaredMethod(Activity::class.java, "onPause")?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (currentActivityName == activity.javaClass.name) currentActivityName = ""
                }
            })
        }
    }

    private fun installImageReaderTracking() {
        KavaReflector.declaredMethods(ImageReader::class.java)
            .filter { it.name == "newInstance" && KavaReflector.isStatic(it) }
            .forEach { method ->
                runCatching {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? ImageReader)?.let(camera2::rememberImageReader)
                        }
                    })
                }.onFailure { HLog.e("$TAG ImageReader 跟踪 Hook 失败: ${method.parameterTypes.contentToString()}", it) }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builderClass = KavaReflector.loadClass("android.media.ImageReader\$Builder", context.hostClassLoader())
            KavaReflector.findMethod(builderClass, "build")?.let { build ->
                runCatching {
                    HookRegistry.get().hook(build, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? ImageReader)?.let(camera2::rememberImageReader)
                        }
                    })
                }.onFailure { HLog.e("$TAG ImageReader.Builder 跟踪 Hook 失败", it) }
            }
        }
    }

    private fun installCamera1() {
        val cameraClass = KavaReflector.loadClass("android.hardware.Camera", context.hostClassLoader()) ?: return
        listOf("setPreviewCallback", "setPreviewCallbackWithBuffer", "setOneShotPreviewCallback").forEach { name ->
            val method = KavaReflector.findMethod(cameraClass, name, Camera.PreviewCallback::class.java) ?: return@forEach
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isOperational()) return
                        param.args.firstOrNull()?.let { hookPreviewCallback(it.javaClass) }
                    }
                })
            }.onFailure { HLog.e("$TAG 安装 Camera1 $name Hook 失败", it) }
        }

        KavaReflector.findMethod(cameraClass, "setPreviewTexture", SurfaceTexture::class.java)?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isOperational() || internalCamera1Preview.get() == true) return
                    val camera = param.thisObject as? Camera ?: return
                    val original = param.args.getOrNull(0) as? SurfaceTexture ?: return
                    runCatching {
                        val drain = camera1Drain(camera)
                        param.args[0] = drain.surfaceTexture()
                        replaceCamera1Output(camera, Surface(original), ownsTarget = true)
                    }.onFailure { HLog.e("$TAG Camera1 SurfaceTexture 接管失败", it) }
                }
            })
        }

        KavaReflector.declaredMethods(cameraClass)
            .filter { it.name == "setPreviewDisplay" && it.parameterTypes.size == 1 }
            .forEach { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isOperational() || internalCamera1Preview.get() == true) return
                        val camera = param.thisObject as? Camera ?: return
                        val holder = param.args.getOrNull(0) as? android.view.SurfaceHolder ?: return
                        val target = holder.surface ?: return
                        if (!target.isValid) return
                        runCatching {
                            val drain = camera1Drain(camera)
                            internalCamera1Preview.set(true)
                            camera.setPreviewTexture(drain.surfaceTexture())
                            replaceCamera1Output(camera, target, ownsTarget = false)
                            param.result = null
                        }.onFailure { HLog.e("$TAG Camera1 SurfaceHolder 接管失败", it) }
                            .also { internalCamera1Preview.remove() }
                    }
                })
            }

        KavaReflector.declaredMethods(cameraClass)
            .filter { it.name == "takePicture" && it.parameterTypes.lastOrNull() == Camera.PictureCallback::class.java }
            .forEach { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isOperational()) return
                        val camera = param.thisObject as? Camera ?: return
                        val callback = param.args.lastOrNull() as? Camera.PictureCallback ?: return
                        val jpeg = replacementJpeg(camera) ?: return
                        runCatching { callback.onPictureTaken(jpeg, camera) }
                            .onFailure { HLog.e("$TAG Camera1 虚拟拍照回调失败", it) }
                        param.result = null
                    }
                })
            }

        listOf("stopPreview", "release").forEach { name ->
            KavaReflector.findMethod(cameraClass, name)?.let { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        releaseCamera1(param.thisObject as? Camera)
                    }
                })
            }
        }
    }

    @Synchronized
    private fun hookPreviewCallback(callbackClass: Class<*>) {
        if (!hookedCallbacks.add(callbackClass)) return
        val method = KavaReflector.findMethodRecursive(
            callbackClass,
            "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java
        ) ?: run {
            HLog.e("$TAG 找不到 Camera1 回调: ${callbackClass.name}")
            return
        }
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isOperational()) return
                    val data = param.args.getOrNull(0) as? ByteArray ?: return
                    val camera = param.args.getOrNull(1) as? Camera ?: return
                    replacementFrame(camera, data.size)?.let { replacement ->
                        replacement.copyInto(data, endIndex = minOf(replacement.size, data.size))
                    }
                }
            })
            HLog.i("$TAG Camera1 回调已接管: ${callbackClass.name}")
        }.onFailure { HLog.e("$TAG 安装 Camera1 回调 Hook 失败", it) }
    }

    private fun installCamera2() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val managerClass = KavaReflector.loadClass("android.hardware.camera2.CameraManager", context.hostClassLoader()) ?: return
        listOfNotNull(
            KavaReflector.findMethod(managerClass, "openCamera", String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java),
            KavaReflector.findMethod(managerClass, "openCamera", String::class.java, Executor::class.java, CameraDevice.StateCallback::class.java)
        ).forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isOperational()) camera2.resetSession("openCamera(${param.args.getOrNull(0)})")
                }
            })
        }

        val builderClass = KavaReflector.loadClass("android.hardware.camera2.CaptureRequest\$Builder", context.hostClassLoader())
        KavaReflector.findMethod(builderClass, "addTarget", Surface::class.java)?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isOperational()) return
                    val original = param.args.getOrNull(0) as? Surface ?: return
                    camera2.virtualFor(original)?.let { param.args[0] = it }
                }
            })
        }
        KavaReflector.findMethod(builderClass, "removeTarget", Surface::class.java)?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args.getOrNull(0) as? Surface ?: return
                    camera2.existingVirtualFor(original)?.let { param.args[0] = it }
                }
            })
        }

        listOfNotNull(
            KavaReflector.loadClass("android.hardware.camera2.impl.CameraDeviceImpl", context.hostClassLoader()),
            KavaReflector.loadClass("android.hardware.camera2.CameraDevice", context.hostClassLoader())
        ).distinct().forEach { deviceClass ->
            KavaReflector.declaredMethods(deviceClass)
                .filter {
                    !KavaReflector.isAbstract(it) &&
                        (it.name.startsWith("createCaptureSession") ||
                            it.name.startsWith("createReprocessableCaptureSession") ||
                            it.name == "createConstrainedHighSpeedCaptureSession")
                }
                .forEach { method ->
                    runCatching {
                        HookRegistry.get().hook(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isOperational()) camera2.rewriteSessionArguments(param.args)
                            }
                        })
                    }.onFailure { HLog.e("$TAG Camera2 会话 Hook 失败: ${method.name}${method.parameterTypes.contentToString()}", it) }
                }
            KavaReflector.findDeclaredMethod(deviceClass, "close")?.takeIf { !KavaReflector.isAbstract(it) }?.let { close ->
                runCatching {
                    HookRegistry.get().hook(close, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            camera2.resetSession("CameraDevice.close")
                        }
                    })
                }.onFailure { HLog.e("$TAG Camera2 close Hook 失败: ${deviceClass.name}", it) }
            }
        }
    }

    private fun replaceCamera1Output(camera: Camera, target: Surface, ownsTarget: Boolean) {
        camera1Outputs.remove(camera)?.release()
        camera1Outputs[camera] = VirtualCameraSurfaceOutput(
            target,
            ::mediaPath,
            "$TAG Camera1",
            ownsTarget
        ).also { it.start() }
    }

    private fun camera1Drain(camera: Camera): VirtualTextureDrain {
        camera1Drains[camera]?.takeIf { it.isValid() }?.let { return it }
        val created = VirtualTextureDrain("Hchat-VirtualCamera1")
        if (!created.isValid()) {
            created.release()
            error("Camera1 virtual Surface is unavailable")
        }
        camera1Drains[camera] = created
        return created
    }

    private fun replacementFrame(camera: Camera, outputSize: Int): ByteArray? {
        val path = mediaPath() ?: return null
        val size = runCatching { camera.parameters?.previewSize }.getOrNull() ?: return null
        val width = size.width and -2
        val height = size.height and -2
        if (width <= 0 || height <= 0) return null
        val key = "$path|${File(path).lastModified()}|${width}x$height|$outputSize"
        synchronized(frameCache) {
            frameCache[key]?.let { return it }
            val bitmap = VirtualCameraMedia.decodeFrame(path, width, height) ?: return logMediaFailure(path)
            val yuv = VirtualCameraMedia.bitmapToNv21(bitmap, width, height)
            bitmap.recycle()
            if (yuv.size < outputSize) return null
            frameCache.clear()
            frameCache[key] = yuv
            return yuv
        }
    }

    private fun replacementJpeg(camera: Camera): ByteArray? {
        val path = mediaPath() ?: return null
        val size = runCatching { camera.parameters?.pictureSize ?: camera.parameters?.previewSize }.getOrNull() ?: return null
        val targetWidth = size.width.coerceAtMost(1920).coerceAtLeast(2)
        val targetHeight = size.height.coerceAtMost(1920).coerceAtLeast(2)
        val key = "$path|${File(path).lastModified()}|${targetWidth}x${targetHeight}"
        synchronized(jpegCache) {
            jpegCache[key]?.let { return it }
            val bitmap = VirtualCameraMedia.decodeFrame(path, targetWidth, targetHeight) ?: return logMediaFailure(path)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            bitmap.recycle()
            return output.toByteArray().also {
                jpegCache.clear()
                jpegCache[key] = it
            }
        }
    }

    private fun <T> logMediaFailure(path: String): T? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMediaFailureLogAt >= 10_000L) {
            lastMediaFailureLogAt = now
            HLog.e("$TAG 无法解码虚拟媒体: $path")
        }
        return null
    }

    private fun mediaPath(): String? {
        val path = prefs.getString(VirtualCameraSettings.KEY_MEDIA_PATH, VirtualCameraSettings.DEFAULT_MEDIA_PATH)
            ?.trim().orEmpty()
        return path.takeIf { it.isNotBlank() && File(it).isFile }
    }

    private fun isOperational(): Boolean {
        return prefs.getBoolean(VirtualCameraSettings.KEY_ENABLE, VirtualCameraSettings.DEFAULT_ENABLE) &&
            mediaPath() != null && !isSensitiveActivity(currentActivityName)
    }

    private fun isSensitiveActivity(className: String): Boolean {
        val value = className.lowercase(Locale.ROOT)
        return SENSITIVE_MARKERS.any(value::contains)
    }

    @Synchronized
    private fun releaseCamera1(camera: Camera?) {
        if (camera == null) return
        camera1Outputs.remove(camera)?.release()
        camera1Drains.remove(camera)?.release()
    }

    @Synchronized
    private fun releaseCamera1Outputs() {
        camera1Outputs.values.toList().forEach { it.release() }
        camera1Outputs.clear()
        camera1Drains.values.toList().forEach { it.release() }
        camera1Drains.clear()
    }

    @Synchronized
    fun clear() {
        releaseCamera1Outputs()
        camera2.release()
        synchronized(frameCache) { frameCache.clear() }
        synchronized(jpegCache) { jpegCache.clear() }
        hookedCallbacks.clear()
        HLog.i("$TAG 运行时资源已清理")
    }

    companion object {
        private const val TAG = "[Hchat:VirtualCamera]"
        private val SENSITIVE_MARKERS = listOf(
            "face", "facerecognize", "huiyan", "identity", "certification",
            "verify", "verification", "liveness", "livecheck", "realname", "soter", "biometric"
        )
    }
}
