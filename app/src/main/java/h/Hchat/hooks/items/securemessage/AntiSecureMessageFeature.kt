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
        if (runtimeKey.isBlank()) return false
        DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), SecureMessageSettings.CACHE_CHECK)
            ?.takeIf(::isCheckMethod)
            ?.let { return installHook(it) }
        DexMethodCache.clear(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK)
        val located = locateRawCheck(context)?.also { DexMethodCache.save(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK, it) }
        return installHook(located)
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
            true
        }.getOrElse { logError("反安全消息Hook安装失败", it); false }
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, SecureMessageSettings.DEFAULT_ENABLE) == true

    private fun isCheckMethod(method: Method): Boolean = KavaReflector.isStatic(method) && method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive

    private fun locateRawCheck(context: FeatureContext): Method? = findMethods(context, listOf(".msgsource.sec_msg_node.sfn"))
        .filter(::isCheckMethod)
        .distinctBy { it.toGenericString() }
        .singleOrNull()

    private fun methodCacheKey(context: FeatureContext): String = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        .takeIf { it.isNotBlank() }
        ?.let { "$it|${SecureMessageSettings.CACHE_SCHEMA}" }
        .orEmpty()

    private fun findMethods(context: FeatureContext, strings: List<String>): List<Method> = runCatching {
        context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply { usingEqStrings(*strings.toTypedArray()) })
        }).mapNotNull { data -> runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
    }.getOrElse {
        logError("反安全消息方法定位失败", it)
        emptyList()
    }
}
