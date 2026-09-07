package h.Hchat.hooks.items.transparentavatar

import android.graphics.Bitmap
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.io.OutputStream

class UploadTransparentAvatarFeature : BaseFeature() {
    private var runtime: UploadTransparentAvatarRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "上传透明头像"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(UploadTransparentAvatarSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = UploadTransparentAvatarRuntime(context).also { it.installCompressHook() }
        scheduleMediaTailorLocate()
        subscribe(Events.DexReady::class.java) { scheduleMediaTailorLocate() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleMediaTailorLocate() {
        DexInstallScheduler.schedule(
            MEDIA_TAILOR_TASK_ID,
            "上传透明头像裁剪入口",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.locateMediaTailorClass() == true
        }
    }

    companion object {
        const val ID = "upload_transparent_avatar"
        private const val MEDIA_TAILOR_TASK_ID = "${ID}_media_tailor"
    }
}

private class UploadTransparentAvatarRuntime(
    private val context: FeatureContext
) {
    private val settingsPrefs = UploadTransparentAvatarSettings.preferences(context.hostContext())
    private val cachePrefs = HchatStorage.preferences(context.hostContext(), CACHE_PREFS_NAME)

    @Volatile
    private var mediaTailorClassName: String? = loadCachedMediaTailorClass()

    @Volatile
    private var compressHookInstalled = false

    @Synchronized
    fun installCompressHook(): Boolean {
        if (compressHookInstalled) return true
        val method = KavaReflector.findMethod(
            Bitmap::class.java,
            "compress",
            Bitmap.CompressFormat::class.java,
            Integer.TYPE,
            OutputStream::class.java
        ) ?: run {
            HLog.e("$TAG 未找到 Bitmap.compress Hook 入口")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    if (param.args.getOrNull(0) == Bitmap.CompressFormat.PNG) return
                    if (isAvatarUploadCall(Thread.currentThread().stackTrace)) {
                        param.args[0] = Bitmap.CompressFormat.PNG
                    }
                }
            })
            compressHookInstalled = true
            HLog.i("$TAG Bitmap.compress Hook 已安装")
            true
        }.getOrElse {
            HLog.e("$TAG 安装 Bitmap.compress Hook 失败: ${it.message}", it)
            false
        }
    }

    @Synchronized
    fun locateMediaTailorClass(): Boolean {
        loadCachedMediaTailorClass()?.let {
            mediaTailorClassName = it
            return true
        }
        val candidates = runCatching {
            context.dexKitBridge().findClass(
                FindClass().apply {
                    matcher(
                        ClassMatcher().apply {
                            usingEqStrings(MEDIA_TAILOR_ANCHOR)
                        }
                    )
                }
            ).map { it.name }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrElse {
            HLog.e("$TAG 定位微信媒体裁剪类失败: ${it.message}", it)
            emptyList()
        }
        val className = candidates.singleOrNull()
        if (className == null) {
            HLog.e("$TAG 未找到唯一媒体裁剪类: count=${candidates.size}")
            return false
        }
        if (KavaReflector.loadClass(className, context.hostClassLoader()) == null) {
            HLog.e("$TAG 媒体裁剪类无法加载: $className")
            return false
        }
        mediaTailorClassName = className
        saveCachedMediaTailorClass(className)
        HLog.i("$TAG 媒体裁剪类已定位: $className")
        return true
    }

    private fun isAvatarUploadCall(frames: Array<StackTraceElement>): Boolean {
        val locatedTailor = mediaTailorClassName
        return frames.any { frame ->
            val className = frame.className
            className.startsWith(MODEL_AVATAR_PREFIX) ||
                className.contains(PHOTO_CROP_ACTIVITY) ||
                (locatedTailor != null &&
                    (className == locatedTailor || className.startsWith(locatedTailor + '$')))
        }
    }

    private fun isEnabled(): Boolean {
        return settingsPrefs.getBoolean(
            UploadTransparentAvatarSettings.KEY_ENABLE,
            UploadTransparentAvatarSettings.DEFAULT_ENABLE
        )
    }

    private fun loadCachedMediaTailorClass(): String? {
        val cacheKey = runtimeCacheKey()
        if (cacheKey.isBlank()) return null
        if (cachePrefs.getString(CACHE_RUNTIME_KEY, "") != cacheKey) {
            cachePrefs.edit().clear().putString(CACHE_RUNTIME_KEY, cacheKey).apply()
            return null
        }
        val className = cachePrefs.getString(CACHE_MEDIA_TAILOR_CLASS, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return className.takeIf {
            KavaReflector.loadClass(it, context.hostClassLoader()) != null
        }
    }

    private fun saveCachedMediaTailorClass(className: String) {
        val cacheKey = runtimeCacheKey()
        if (cacheKey.isBlank()) return
        cachePrefs.edit()
            .putString(CACHE_RUNTIME_KEY, cacheKey)
            .putString(CACHE_MEDIA_TAILOR_CLASS, className)
            .apply()
    }

    private fun runtimeCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    companion object {
        private const val TAG = "[Hchat:UploadTransparentAvatar]"
        private const val MODEL_AVATAR_PREFIX = "com.tencent.mm.modelavatar"
        private const val PHOTO_CROP_ACTIVITY = "PhotoCropActivity"
        private const val MEDIA_TAILOR_ANCHOR = "Rect width or height contains zero. contentRect: "
        private const val CACHE_PREFS_NAME = "Hchat_transparent_avatar_class_cache"
        private const val CACHE_RUNTIME_KEY = "cache.key"
        private const val CACHE_MEDIA_TAILOR_CLASS = "media_tailor_class"
        private const val CACHE_SCHEMA = "transparent_avatar_v1"
    }
}
