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

/** Injects the WeChat secure-message marker before outgoing text messages are stored. */
class SendSecureMessageFeature : BaseFeature() {
    @Volatile private var installed = false
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var methodPrefs: android.content.SharedPreferences
    @Volatile private var markerLogged = false
    @Volatile private var hookMissLogged = false

    override fun featureId(): String = SecureMessageSettings.SEND_ID
    override fun name(): String = "安全消息"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SendSecureMessageSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        prefs = HchatStorage.preferences(context.hostContext(), SecureMessageSettings.SEND_PREFS)
        methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_secure_message_method_cache")
        logInfo("安全消息功能已初始化，等待 DexKit")
        schedule(context)
        subscribe(Events.DexReady::class.java) { schedule(context) }
    }

    private fun schedule(context: FeatureContext) {
        DexInstallScheduler.schedule(featureId(), name()) { installHook(context) }
    }

    @Synchronized
    private fun installHook(context: FeatureContext): Boolean {
        if (installed) return true
        val runtimeKey = methodCacheKey(context)
        if (runtimeKey.isBlank()) {
            logError("安全消息安装跳过：微信运行时版本信息未就绪", null)
            return false
        }
        val direct = context.dexFinder().localMessageInsertMethod?.takeIf(::isInsertMethod)
        if (direct != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_INSERT, direct)
            logInfo("安全消息使用微信本地消息插入 API: ${direct.toGenericString()}")
        }
        val insert = direct ?: cachedOrLocate(context, runtimeKey, SecureMessageSettings.CACHE_INSERT, INSERT_ANCHOR, ::isInsertMethod)
            ?: run {
                logError("安全消息入库方法未定位到，微信版本可能不匹配", null)
                return false
            }
        return runCatching {
            HookRegistry.get().hook(insert, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val args = param.args ?: return
                    val msg = args.firstOrNull { it != null && isMessageLike(it) } ?: run {
                        if (!hookMissLogged) {
                            hookMissLogged = true
                            logInfo("安全消息 Hook 已触发，但未找到消息参数")
                        }
                        return
                    }
                    if (!isOutgoingText(msg)) return
                    addSecureMarker(msg)
                }
            })
            installed = true
            logInfo("安全消息 Hook 已安装: ${insert.toGenericString()}")
            true
        }.getOrElse {
            logError("安全消息入库Hook安装失败", it)
            false
        }
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, SecureMessageSettings.DEFAULT_ENABLE) == true

    private fun isMessageLike(value: Any): Boolean =
        KavaReflector.readField(value, "field_type") != null ||
            KavaReflector.readField(value, "field_content") != null ||
            KavaReflector.readField(value, "field_msgSource") != null

    private fun addSecureMarker(message: Any) {
        val current = readMessageSource(message)
        if (current.contains("<sec_msg_node", ignoreCase = true)) return
        val updated = when {
            current.contains("</msgsource>", ignoreCase = true) ->
                current.replaceFirst(Regex("</msgsource>", RegexOption.IGNORE_CASE), SecureMessageSettings.SEC_XML + "</msgsource>")
            current.contains("<msgsource", ignoreCase = true) ->
                current.replaceFirst(
                    Regex("(<msgsource\\b[^>]*>)", RegexOption.IGNORE_CASE),
                    "${'$'}1${SecureMessageSettings.SEC_XML}"
                )
            else -> "<msgsource>${SecureMessageSettings.SEC_XML}</msgsource>"
        }
        if (!setMessageSource(message, updated)) {
            logError("安全消息标记注入失败: msgSource 不可写", null)
        } else if (!markerLogged) {
            markerLogged = true
            logInfo("安全消息标记已写入文本消息")
        }
    }

    private fun readMessageSource(message: Any): String =
        (KavaReflector.readField(message, "field_msgSource") as? String)
            ?: (KavaReflector.readField(message, "msgSource") as? String)
            ?: (KavaReflector.invokeMethod(message, "getMsgSource") as? String)
            ?: ""

    private fun setMessageSource(message: Any, value: String): Boolean {
        for (name in SOURCE_SETTERS) {
            val method = KavaReflector.findCompatibleMethod(message.javaClass, name, value)
            if (KavaReflector.invokeSuccessfully(method, message, value)) return true
        }
        return KavaReflector.writeField(message, "field_msgSource", value) ||
            KavaReflector.writeField(message, "msgSource", value)
    }

    private fun isOutgoingText(message: Any): Boolean {
        val send = readNumber(message, "field_isSend", "isSend", "getIsSend", "getSend")
        if (send?.toInt() != 1) return false
        val type = readNumber(message, "field_type", "type", "getType", "getMsgType")
        return type == null || type.toInt() == 1 || type.toInt() == 47
    }

    private fun readNumber(receiver: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) ?: KavaReflector.invokeMethod(receiver, name)
            if (value is Number) return value
        }
        return null
    }

    private fun isInsertMethod(method: Method): Boolean {
        if (method.parameterCount !in 1..2) return false
        if (method.returnType != Void.TYPE && method.returnType != Long::class.javaPrimitiveType) return false
        return method.parameterTypes.any { !it.isPrimitive }
    }

    private fun cachedOrLocate(context: FeatureContext, runtimeKey: String, name: String, anchor: String, predicate: (Method) -> Boolean): Method? {
        DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), name)?.takeIf(predicate)?.let { return it }
        val candidates = findMethods(context, anchor)
            .filter(predicate).distinctBy { it.toGenericString() }
        val method = candidates.maxByOrNull { candidate ->
            val params = candidate.parameterTypes
            var score = 0
            if (params.size == 2 && params[1] == Boolean::class.javaPrimitiveType) score += 8
            if (params.any { it.name.startsWith("com.tencent.mm.storage.") }) score += 6
            if (params.any { it.name.contains("Msg", true) || it.simpleName.equals("k9", true) }) score += 2
            score
        }
        if (method != null) DexMethodCache.save(methodPrefs, runtimeKey, name, method) else DexMethodCache.clear(methodPrefs, runtimeKey, name)
        return method
    }

    private fun methodCacheKey(context: FeatureContext): String = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        .takeIf { it.isNotBlank() }
        ?.let { "$it|${SecureMessageSettings.CACHE_SCHEMA}" }
        .orEmpty()

    private fun findMethods(context: FeatureContext, anchor: String): List<Method> = runCatching {
        val exact = context.dexKitBridge().findMethod(FindMethod().apply {
            matcher(MethodMatcher().apply { usingEqStrings(anchor) })
        })
        val candidates = if (exact.isNotEmpty()) exact else {
            runCatching {
                context.dexKitBridge().findMethod(FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(anchor) })
                })
            }.getOrDefault(emptyList())
        }
        candidates.mapNotNull { data -> runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
            .distinctBy { it.toGenericString() }
    }.getOrElse {
        logError("安全消息方法定位失败", it)
        emptyList()
    }

    private companion object {
        const val INSERT_ANCHOR = "Error insert message msg:%s talker:%s"
        val SOURCE_SETTERS = arrayOf("setMsgSource", "setMsgsource", "setSource")
    }
}
