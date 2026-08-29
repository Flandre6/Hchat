package h.Hchat.hooks.items.antireadreceipts

import android.net.Uri
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocks Finder thumbnail tracking-pixel requests used for read-receipt telemetry.
 */
class AntiReadReceiptsFeature : BaseFeature() {
    private var hooker: AntiReadReceiptsHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "反已读追踪"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AntiReadReceiptsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = AntiReadReceiptsHooker(context, ::logError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            hooker?.install() == true
        }
    }

    companion object {
        const val ID = "anti_read_receipts"
        private const val CACHE_PREFS_NAME = "Hchat_anti_read_receipts_method_cache"
        private const val CACHE_SCHEMA = "finder_loader_image_v1"
        private const val CACHE_METHOD = "finder_load_image"
    }

    private class AntiReadReceiptsHooker(
        private val context: FeatureContext,
        private val logger: (String, Throwable?) -> Unit
    ) {
        private val cache = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS_NAME)
        private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
        @Volatile private var installed = false

        @Synchronized
        fun install(): Boolean {
            if (installed) return true
            val methods = locateMethods()
            if (methods.isEmpty()) return false
            var success = true
            methods.forEach { method ->
                if (!hookedMethods.add(method)) return@forEach
                runCatching {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val url = param.args?.getOrNull(0) as? String ?: return
                            val path = runCatching { Uri.parse(url).path }.getOrNull()
                            if (path?.endsWith("/pixel") == true) {
                                param.result = null
                            }
                        }
                    })
                }.onFailure {
                    hookedMethods.remove(method)
                    success = false
                    logger("反已读追踪 Hook 安装失败: ${method.toGenericString()}", it)
                }
            }
            installed = success && methods.all(hookedMethods::contains)
            if (!installed) logger("反已读追踪方法未完全安装", null)
            return installed
        }

        private fun locateMethods(): List<Method> {
            val runtimeKey = methodCacheKey()
            DexMethodCache.loadList(cache, runtimeKey, context.hostClassLoader(), CACHE_METHOD)
                .filter(::isFinderLoadImage)
                .takeIf { it.isNotEmpty() }
                ?.let { return it }

            val candidates = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(
                            MethodMatcher().apply {
                                usingEqStrings("FinderLoaderApi", "#loadImage url=")
                            }
                        )
                    }
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }.filter(::isFinderLoadImage)
                    .distinctBy { it.toGenericString() }
            }.getOrElse {
                logger("定位视频号图片加载方法失败", it)
                emptyList()
            }

            if (candidates.isEmpty()) {
                DexMethodCache.clear(cache, runtimeKey, CACHE_METHOD)
                logger("未找到视频号图片加载方法", null)
            } else {
                DexMethodCache.saveList(cache, runtimeKey, CACHE_METHOD, candidates)
            }
            return candidates
        }

        private fun methodCacheKey(): String {
            val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            return if (runtimeKey.isBlank()) "" else "$runtimeKey|$CACHE_SCHEMA"
        }

        private fun isFinderLoadImage(method: Method): Boolean {
            val types = method.parameterTypes
            return method.returnType == Void.TYPE &&
                types.size == 3 &&
                types[0] == String::class.java &&
                ImageView::class.java.isAssignableFrom(types[1]) &&
                !Modifier.isAbstract(method.modifiers)
        }
    }
}
