package h.Hchat.hooks.items.securemessage

import de.robv.android.xposed.XC_MethodHook
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

    override fun featureId(): String = SecureMessageSettings.ANTI_ID
    override fun name(): String = "反安全消息"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AntiSecureMessageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        prefs = HchatStorage.preferences(context.hostContext(), SecureMessageSettings.ANTI_PREFS)
        schedule(context)
        subscribe(Events.DexReady::class.java) { schedule(context) }
    }

    private fun schedule(context: FeatureContext) {
        DexInstallScheduler.schedule(featureId(), name()) { installHooks(context) }
    }

    @Synchronized
    private fun installHooks(context: FeatureContext): Boolean {
        if (installed) return true
        val raw = locateRawCheck(context) ?: return false
        val related = KavaReflector.declaredMethods(raw.declaringClass).filter { method ->
            method != raw && KavaReflector.isStatic(method) &&
                method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 1
        }.take(3)
        var hooked = false
        (listOf(raw) + related).distinctBy { it.toGenericString() }.forEach { method ->
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (enabled()) param.result = false
                    }
                })
                hooked = true
            }.onFailure { logError("反安全消息Hook安装失败", it) }
        }
        installed = hooked
        return hooked
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, true) != false

    private fun locateRawCheck(context: FeatureContext): Method? = findMethods(context, listOf(".msgsource.sec_msg_node.sfn"))
        .firstOrNull { KavaReflector.isStatic(it) && it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 1 }

    private fun findMethods(context: FeatureContext, strings: List<String>): List<Method> = runCatching {
        context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply { usingEqStrings(*strings.toTypedArray()) })
        }).mapNotNull { data -> runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
    }.getOrElse {
        logError("反安全消息方法定位失败", it)
        emptyList()
    }
}
