package h.Hchat.hooks.items.securemessage

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Injects the WeChat secure-message marker before outgoing supported messages are stored. */
class SendSecureMessageFeature : BaseFeature() {
    @Volatile private var installed = false
    @Volatile private var mergeInstalled = false
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var methodPrefs: android.content.SharedPreferences
    @Volatile private var markerLogged = false
    @Volatile private var markerFailureLogged = false
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
        if (installed && mergeInstalled) return true
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
        val insertReady = if (installed) true else runCatching {
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
                    if (!isSupportedOutgoingMessage(msg)) return
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
        val mergeReady = if (mergeInstalled) true else installSourceMergeHooks(insert)
        return insertReady && mergeReady
    }

    /**
     * Media senders assign msgSource before the final local insert. Hook the setter layer
     * as well so the marker reaches the actual image/video/emoji/AppMsg send request.
     * Candidates are derived from the already verified insert argument class; no
     * obfuscated method name is guessed.
     */
    private fun installSourceMergeHooks(insert: Method): Boolean {
        val messageClass = insert.parameterTypes.firstOrNull {
            !it.isPrimitive && it != String::class.java && it.name.startsWith(MESSAGE_PACKAGE)
        } ?: return false
        val candidates = generateSequence<Class<*>>(messageClass) { current -> current.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { KavaReflector.declaredMethods(it).asSequence() }
            .filter(::isStringSetter)
            .distinctBy { it.toGenericString() }
            .toList()
        if (candidates.isEmpty()) {
            logError("安全消息 msgSource 合并入口未找到: ${messageClass.name}", null)
            return false
        }
        var count = 0
        candidates.forEach { method ->
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled()) return
                        val source = param.args?.getOrNull(0) as? String ?: return
                        if (!looksLikeMsgSource(source)) return
                        val message = param.thisObject ?: return
                        if (!isSupportedOutgoingMessage(message)) return
                        param.args[0] = SecureMessageSource.addMarker(source)
                    }
                })
                count++
            }.onFailure {
                logError("安全消息 msgSource 候选 Hook 安装失败: ${method.toGenericString()}", it)
            }
        }
        mergeInstalled = count > 0
        if (mergeInstalled) logInfo("安全消息 msgSource 合并 Hook 已安装: $count 个候选")
        return mergeInstalled
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, SecureMessageSettings.DEFAULT_ENABLE) == true

    private fun isMessageLike(value: Any): Boolean =
        readNumber(value, "field_type", "type", "getType", "getMsgType") != null ||
            SOURCE_FIELDS.any { KavaReflector.readField(value, it) != null }

    private fun addSecureMarker(message: Any) {
        val current = readMessageSource(message)
        if (SecureMessageSource.containsMarker(current)) return
        val updated = SecureMessageSource.addMarker(current)
        if (!setMessageSource(message, updated)) {
            if (!markerFailureLogged) {
                markerFailureLogged = true
                logError("安全消息标记注入失败: msgSource 不可写，类型=${message.javaClass.name}", null)
            }
        } else if (!markerLogged) {
            markerLogged = true
            logInfo("安全消息标记已写入消息")
        }
    }

    private fun readMessageSource(message: Any): String {
        for (fieldName in SOURCE_FIELDS) {
            (KavaReflector.readField(message, fieldName) as? String)?.let { return it }
        }
        return (KavaReflector.invokeMethod(message, "getMsgSource") as? String).orEmpty()
    }

    private fun setMessageSource(message: Any, value: String): Boolean {
        for (name in SOURCE_SETTERS) {
            val method = KavaReflector.findCompatibleMethod(message.javaClass, name, value)
            if (KavaReflector.invokeSuccessfully(method, message, value)) return true
        }
        return SOURCE_FIELDS.any { fieldName ->
            KavaReflector.writeField(message, fieldName, value)
        }
    }

    private fun isSupportedOutgoingMessage(message: Any): Boolean {
        val send = readNumber(message, "field_isSend", "isSend", "getIsSend", "getSend")
        if (send?.toInt() != 1) return false
        val type = readNumber(message, "field_type", "type", "getType", "getMsgType")
        if (type == null) return true
        val normalized = WeChatMessageTypes.normalize(type.toInt())
        return normalized == WeChatMessageTypes.TEXT ||
            normalized == WeChatMessageTypes.IMAGE ||
            normalized == WeChatMessageTypes.VIDEO ||
            normalized == VIDEO_COMPAT ||
            normalized == WeChatMessageTypes.EMOJI ||
            normalized == WeChatMessageTypes.APP
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

    private fun isStringSetter(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java))
    }

    private fun looksLikeMsgSource(value: String): Boolean {
        val trimmed = value.trimStart()
        return trimmed.startsWith("<msgsource", ignoreCase = true) &&
            trimmed.contains("</msgsource>", ignoreCase = true)
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
        // 8.0.77 (e9) stores MsgInfo.msgSource in the obfuscated G field.
        val SOURCE_FIELDS = arrayOf("field_msgSource", "msgSource", "G", "g")
        const val VIDEO_COMPAT = 62
        const val MESSAGE_PACKAGE = "com.tencent.mm.storage."
    }
}
