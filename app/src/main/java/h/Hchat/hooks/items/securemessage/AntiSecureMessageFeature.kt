package h.Hchat.hooks.items.securemessage

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/** Forces WeChat's secure-message checks to false so the normal message menu is shown. */
class AntiSecureMessageFeature : BaseFeature() {
    @Volatile private var installed = false
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var methodPrefs: android.content.SharedPreferences

    override fun featureId(): String = SecureMessageSettings.ANTI_ID
    override fun name(): String = "反安全消息"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AntiSecureMessageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        prefs = HchatStorage.preferences(context.hostContext(), SecureMessageSettings.ANTI_PREFS)
        methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_anti_secure_message_method_cache")
        logInfo("反安全消息功能已初始化，等待 DexKit")
        schedule(context)
        subscribe(Events.DexReady::class.java) { schedule(context) }
    }

    private fun schedule(context: FeatureContext) {
        DexInstallScheduler.schedule(featureId(), name()) { installHooks(context) }
    }

    @Synchronized
    private fun installHooks(context: FeatureContext): Boolean {
        if (installed) return true
        val runtimeKey = methodCacheKey(context)
        if (runtimeKey.isBlank()) {
            logError("反安全消息安装跳过：微信运行时版本信息未就绪", null)
            return false
        }
        val cached = DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), SecureMessageSettings.CACHE_CHECK)
            ?.takeIf(::isCheckMethod)
        if (cached != null) {
            if (installHook(cached)) return true
            logError("反安全消息缓存方法 Hook 失败，重新定位", null)
        }
        DexMethodCache.clear(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK)
        val located = locateRawChecks(context)
        if (located.isEmpty()) {
            logError("反安全消息检查方法未定位到，微信版本可能不匹配", null)
        }
        if (located.size == 1) DexMethodCache.save(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK, located.first())
        return installHooks(located)
    }

    private fun installHook(method: Method?): Boolean {
        method ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) param.result = false
                }
            })
            installed = true
            logInfo("反安全消息 Hook 已安装: ${method.toGenericString()}")
            true
        }.getOrElse { logError("反安全消息Hook安装失败", it); false }
    }

    private fun installHooks(methods: List<Method>): Boolean {
        var hooked = false
        methods.take(MAX_CHECK_HOOKS).forEach { method ->
            if (installHook(method)) hooked = true
        }
        return hooked
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, SecureMessageSettings.DEFAULT_ENABLE) == true

    private fun isCheckMethod(method: Method): Boolean = KavaReflector.isStatic(method) && method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive

    private fun locateRawChecks(context: FeatureContext): List<Method> = findMethods(context, listOf(".msgsource.sec_msg_node.sfn"))
        .filter(::isCheckMethod)
        .distinctBy { it.toGenericString() }

    private fun methodCacheKey(context: FeatureContext): String = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        .takeIf { it.isNotBlank() }
        ?.let { "$it|${SecureMessageSettings.CACHE_SCHEMA}" }
        .orEmpty()

    private fun findMethods(context: FeatureContext, strings: List<String>): List<Method> = runCatching {
        val exact = context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply { usingEqStrings(*strings.toTypedArray()) })
        })
        val candidates = if (exact.isNotEmpty()) exact else strings.asSequence()
            .flatMap { anchor ->
                runCatching {
                    context.dexKitBridge().findMethod(FindMethod().apply {
                        matcher(MethodMatcher().apply { usingStrings(anchor) })
                    }).asSequence()
                }.getOrDefault(emptySequence())
            }.toList()
        candidates.mapNotNull { data -> runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
            .distinctBy { it.toGenericString() }
    }.getOrElse {
        logError("反安全消息方法定位失败", it)
        emptyList()
    }

    private companion object {
        const val MAX_CHECK_HOOKS = 6
    }
}
