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
    @Volatile private var checkInstalled = false
    @Volatile private var stripInstalled = false
    private var prefs: android.content.SharedPreferences? = null
    private lateinit var methodPrefs: android.content.SharedPreferences
    @Volatile private var interceptedLogged = false
    @Volatile private var disabledHitLogged = false
    @Volatile private var strippedLogged = false

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
        if (checkInstalled && stripInstalled) return true
        val runtimeKey = methodCacheKey(context)
        if (runtimeKey.isBlank()) {
            logError("反安全消息安装跳过：微信运行时版本信息未就绪", null)
            return false
        }
        val checksReady = if (checkInstalled) true else installCheckHooks(context, runtimeKey)
        val stripReady = if (stripInstalled) true else installIncomingMarkerStrip(context)
        return checksReady || stripReady
    }

    private fun installCheckHooks(context: FeatureContext, runtimeKey: String): Boolean {
        val candidates = linkedSetOf<Method>()
        DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), SecureMessageSettings.CACHE_CHECK)
            ?.takeIf(::isCheckMethod)
            ?.let(candidates::add)
        candidates += locateRawChecks(context)
        if (candidates.isEmpty()) {
            DexMethodCache.clear(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK)
            logError("反安全消息检查方法未定位到，微信版本可能不匹配", null)
            return false
        }
        if (candidates.size == 1) {
            DexMethodCache.save(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK, candidates.first())
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, SecureMessageSettings.CACHE_CHECK)
            logInfo("反安全消息定位到 ${candidates.size} 个检查入口，已全部安装")
        }
        return installCheckHooks(candidates.toList())
    }

    private fun installCheckHook(method: Method?): Boolean {
        method ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) {
                        if (!disabledHitLogged) {
                            disabledHitLogged = true
                            logInfo("反安全消息检查已触发，但功能开关已关闭")
                        }
                        return
                    }
                    param.result = false
                    if (!interceptedLogged) {
                        interceptedLogged = true
                        logInfo("反安全消息已拦截安全标记检查: ${method.name}")
                    }
                }
            })
            checkInstalled = true
            logInfo("反安全消息 Hook 已安装: ${method.toGenericString()}")
            true
        }.getOrElse { logError("反安全消息Hook安装失败", it); false }
    }

    private fun installCheckHooks(methods: List<Method>): Boolean {
        var hooked = false
        methods.take(MAX_CHECK_HOOKS).forEach { method ->
            if (installCheckHook(method)) hooked = true
        }
        return hooked
    }

    /**
     * The recognised check is used for messages already stored before Hchat starts.  For
     * newly received messages, remove only the secure node at the verified local insert
     * boundary, so later UI paths cannot reapply the menu restriction.
     */
    private fun installIncomingMarkerStrip(context: FeatureContext): Boolean {
        val insert = context.dexFinder().localMessageInsertMethod?.takeIf(::isInsertMethod)
            ?: return false
        return runCatching {
            HookRegistry.get().hook(insert, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val message = param.args?.firstOrNull { it != null && isMessageLike(it) } ?: return
                    if (isOutgoing(message)) return
                    val current = readMessageSource(message)
                    if (!SECURE_NODE.containsMatchIn(current)) return
                    if (!setMessageSource(message, SECURE_NODE.replace(current, ""))) return
                    if (!strippedLogged) {
                        strippedLogged = true
                        logInfo("反安全消息已清除收到消息的安全标记")
                    }
                }
            })
            stripInstalled = true
            logInfo("反安全消息入库清除 Hook 已安装: ${insert.toGenericString()}")
            true
        }.getOrElse {
            logError("反安全消息入库清除 Hook 安装失败", it)
            false
        }
    }

    private fun enabled(): Boolean = prefs?.getBoolean(SecureMessageSettings.KEY_ENABLE, SecureMessageSettings.DEFAULT_ENABLE) == true

    private fun isMessageLike(value: Any): Boolean =
        KavaReflector.readField(value, "field_type") != null ||
            KavaReflector.readField(value, "field_content") != null ||
            KavaReflector.readField(value, "field_msgSource") != null

    private fun isOutgoing(message: Any): Boolean = readNumber(message, "field_isSend", "isSend", "getIsSend", "getSend")?.toInt() == 1

    private fun readNumber(receiver: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) ?: KavaReflector.invokeMethod(receiver, name)
            if (value is Number) return value
        }
        return null
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
        return SOURCE_FIELDS.any { fieldName -> KavaReflector.writeField(message, fieldName, value) }
    }

    private fun isCheckMethod(method: Method): Boolean = KavaReflector.isStatic(method) && method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 1 && !method.parameterTypes[0].isPrimitive

    private fun isInsertMethod(method: Method): Boolean {
        if (method.parameterCount !in 1..2) return false
        if (method.returnType != Void.TYPE && method.returnType != Long::class.javaPrimitiveType) return false
        return method.parameterTypes.any { !it.isPrimitive }
    }

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
        val SOURCE_SETTERS = arrayOf("setMsgSource", "setMsgsource", "setSource")
        // 8.0.77 (e9) stores MsgInfo.msgSource in the obfuscated G field.
        val SOURCE_FIELDS = arrayOf("field_msgSource", "msgSource", "G", "g")
        val SECURE_NODE = Regex("<sec_msg_node\\b[^>]*>.*?</sec_msg_node>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
