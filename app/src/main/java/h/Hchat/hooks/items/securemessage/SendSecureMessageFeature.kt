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

/** Injects the WeChat secure-message marker before outgoing text messages are stored. */
class SendSecureMessageFeature : BaseFeature() {
    @Volatile private var installed = false
    private var prefs: android.content.SharedPreferences? = null

    override fun featureId(): String = SecureMessageSettings.SEND_ID
    override fun name(): String = "安全消息"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SendSecureMessageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        prefs = HchatStorage.preferences(context.hostContext(), SecureMessageSettings.SEND_PREFS)
        schedule(context)
        subscribe(Events.DexReady::class.java) { schedule(context) }
    }

    private fun schedule(context: FeatureContext) {
        DexInstallScheduler.schedule(featureId(), name()) { installHook(context) }
    }

    @Synchronized
    private fun installHook(context: FeatureContext): Boolean {
        if (installed) return true
        val insert = locateInsertMethod(context) ?: return false
        val merge = locateMergeMethod(context) ?: return false
        return runCatching {
            HookRegistry.get().hook(insert, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val args = param.args ?: return
                    val msg = args.firstOrNull() ?: return
                    if (!isOutgoingText(msg)) return
                    runCatching { KavaReflector.invoke(merge, null, msg, SecureMessageSettings.SEC_XML, false) }
                        .onFailure { logError("安全消息标记注入失败", it) }
                }
            })
            installed = true
            true
        }.getOrElse {
            logError("安全消息入库Hook安装失败", it)
            false
        }
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, true) != false

    private fun isOutgoingText(message: Any): Boolean {
        val send = readNumber(message, "field_isSend", "isSend", "getIsSend", "getSend")
        if (send != null && send.toInt() != 1) return false
        val type = readNumber(message, "field_type", "type", "getType", "getMsgType")
        return type == null || type.toInt() == 1
    }

    private fun readNumber(receiver: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) ?: KavaReflector.invokeMethod(receiver, name)
            if (value is Number) return value
        }
        return null
    }

    private fun locateInsertMethod(context: FeatureContext): Method? {
        return findMethods(context, listOf("Error insert message msg:%s talker:%s"))
            .firstOrNull { method -> method.returnType == Void.TYPE && method.parameterCount >= 1 && !KavaReflector.isStatic(method) }
            ?: findMethods(context, listOf("Error insert message msg:%s talker:%s"))
                .firstOrNull { it.returnType == Void.TYPE && it.parameterCount >= 1 }
    }

    private fun locateMergeMethod(context: FeatureContext): Method? {
        return findMethods(context, listOf("(?s)<sec_msg_node[^>]*>.*?</sec_msg_node>"))
            .firstOrNull { method ->
                KavaReflector.isStatic(method) && method.returnType == Void.TYPE && method.parameterCount == 3 &&
                    method.parameterTypes[1] == String::class.java && method.parameterTypes[2] == Boolean::class.javaPrimitiveType
            }
    }

    private fun findMethods(context: FeatureContext, strings: List<String>): List<Method> = runCatching {
        context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply { usingEqStrings(*strings.toTypedArray()) })
        }).mapNotNull { data -> runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
    }.getOrElse {
        logError("安全消息方法定位失败", it)
        emptyList()
    }
}
