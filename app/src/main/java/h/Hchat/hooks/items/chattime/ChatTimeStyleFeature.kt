package h.Hchat.hooks.items.chattime

import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
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
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class ChatTimeStyleFeature : BaseFeature() {
    private var runtime: ChatTimeStyleRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "会话时间样式"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatTimeStyleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ChatTimeStyleRuntime(context)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.install() == true
        }
    }

    companion object {
        const val ID = "chat_time_style"
    }
}

private class ChatTimeStyleRuntime(
    private val context: FeatureContext
) {
    private data class BoundTime(
        val createTime: Long,
        val nativeText: CharSequence,
        val nativeVisibility: Int
    )

    private val prefs = HchatStorage.preferences(context.hostContext(), ChatTimeStyleSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_chat_time_style_method_cache")
    private val rootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val timeFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val unsupportedTimeHolders = ConcurrentHashMap.newKeySet<Class<*>>()
    private val bindings = Collections.synchronizedMap(WeakHashMap<TextView, BoundTime>())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ChatTimeStyleSettings.KEY_MODE || key == ChatTimeStyleSettings.KEY_TIME_FORMAT) {
            refreshAttachedTimes()
        }
    }

    @Volatile private var installed = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        val attached = synchronized(bindings) {
            bindings.entries.map { it.key to it.value }.also { bindings.clear() }
        }
        attached.forEach { (view, bound) ->
            view.post { applyStyle(view, bound, ChatTimeStyleSettings.MODE_ORIGINAL) }
        }
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = locateBindMethod() ?: run {
            HLog.e("$TAG 定位聊天时间绑定方法失败")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    restoreBoundTime(param.args)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    bindTime(param.args)
                }
            })
            installed = true
            true
        }.getOrElse {
            HLog.e("$TAG 安装聊天时间样式 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun bindTime(args: Array<Any?>?) {
        val mode = currentMode()
        val holder = messageHolder(args) ?: return
        val root = findRootView(holder) ?: return
        val timeView = findBoundTimeView(holder, root) ?: return
        val createTime = if (mode == ChatTimeStyleSettings.MODE_ORIGINAL) {
            bindings[timeView]?.createTime ?: 0L
        } else {
            resolveNativeMessage(args?.getOrNull(1))
                ?.let(::messageCreateTime)
                ?: resolveNativeMessage(args)?.let(::messageCreateTime)
                ?: 0L
        }
        val bound = BoundTime(
            createTime = createTime,
            nativeText = timeView.text ?: "",
            nativeVisibility = timeView.visibility
        )
        bindings[timeView] = bound
        if (mode == ChatTimeStyleSettings.MODE_ORIGINAL) return
        if (mode == ChatTimeStyleSettings.MODE_HIDDEN) {
            timeView.visibility = View.GONE
            return
        }
        when (mode) {
            ChatTimeStyleSettings.MODE_CUSTOM -> {
                applyStyle(timeView, bound, mode)
            }
            else -> { // MODE_EVERY：每条消息都显示，支持自定义格式
                timeView.visibility = bound.nativeVisibility
                timeView.text = if (bound.nativeVisibility == View.VISIBLE && bound.createTime > 0L) {
                    formatTime(bound.createTime)
                } else {
                    bound.nativeText
                }
            }
        }
    }

    private fun restoreBoundTime(args: Array<Any?>?) {
        val holder = messageHolder(args) ?: return
        val root = findRootView(holder) ?: return
        val timeView = findBoundTimeView(holder, root) ?: return
        val bound = bindings[timeView] ?: return
        timeView.text = bound.nativeText
        timeView.visibility = bound.nativeVisibility
    }

    private fun findBoundTimeView(holder: Any, root: View): TextView? {
        findTimeView(holder)?.let { return it }
        val taggedHolder = root.tag ?: return null
        if (taggedHolder === holder) return null
        return findTimeView(taggedHolder)
    }

    private fun applyStyle(view: TextView, bound: BoundTime, mode: String) {
        when (mode) {
            ChatTimeStyleSettings.MODE_HIDDEN -> view.visibility = View.GONE
            ChatTimeStyleSettings.MODE_EVERY, ChatTimeStyleSettings.MODE_CUSTOM -> {
                view.visibility = bound.nativeVisibility
                view.text = if (bound.nativeVisibility == View.VISIBLE && bound.createTime > 0L) {
                    formatTime(bound.createTime)
                } else {
                    bound.nativeText
                }
            }
            else -> {
                view.text = bound.nativeText
                view.visibility = bound.nativeVisibility
            }
        }
    }

    private fun refreshAttachedTimes() {
        val mode = currentMode()
        val attached = synchronized(bindings) { bindings.entries.map { it.key to it.value } }
        attached.forEach { (view, bound) ->
            view.post {
                if (view.parent != null) applyStyle(view, bound, mode)
            }
        }
    }

    private fun currentMode(): String = ChatTimeStyleSettings.normalizeMode(
        prefs.getString(ChatTimeStyleSettings.KEY_MODE, ChatTimeStyleSettings.DEFAULT_MODE)
    )

    private fun formatTime(timestamp: Long): String {
        val pattern = prefs.getString(
            ChatTimeStyleSettings.KEY_TIME_FORMAT,
            ChatTimeStyleSettings.DEFAULT_TIME_FORMAT
        ).orEmpty().ifBlank { ChatTimeStyleSettings.DEFAULT_TIME_FORMAT }
        return runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }.getOrElse {
            SimpleDateFormat(ChatTimeStyleSettings.DEFAULT_TIME_FORMAT, Locale.getDefault())
                .format(Date(timestamp))
        }
    }

    private fun locateBindMethod(): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_BIND_METHOD)
            ?.takeIf(::isBindCandidate)
            ?.let { return it }
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf("MicroMsg.MvvmChattingItem", "[onBindView]"))
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位聊天时间绑定方法异常: ${it.message}", it)
            emptyList()
        }
        val method = methods.firstOrNull(::isBindCandidate)
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_BIND_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_BIND_METHOD)
        }
        return method
    }

    private fun isBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size >= 3 &&
            types.any { it == Integer.TYPE || it == java.lang.Integer::class.java } &&
            types.any(::isLikelyViewHolderClass)
    }

    private fun isLikelyViewHolderClass(clazz: Class<*>): Boolean {
        if (findRootField(clazz) != null) return true
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (KavaReflector.declaredFields(current).any { View::class.java.isAssignableFrom(it.type) }) return true
            current = current.superclass
        }
        return false
    }

    private fun messageHolder(args: Array<Any?>?): Any? {
        args ?: return null
        return args.getOrNull(0)?.takeIf { findRootView(it) != null }
            ?: args.firstOrNull { it != null && findRootView(it) != null }
    }

    private fun findRootView(holder: Any): View? {
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        return KavaReflector.readField(findRootField(holder.javaClass), holder) as? View
    }

    private fun findRootField(clazz: Class<*>): Field? {
        rootFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "itemView" || it.type == View::class.java
            }
            if (field != null) {
                rootFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findTimeView(holder: Any): TextView? {
        timeFieldCache[holder.javaClass]?.let {
            return KavaReflector.readField(it, holder) as? TextView
        }
        if (holder.javaClass in unsupportedTimeHolders) return null
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "timeTV" && TextView::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                timeFieldCache[holder.javaClass] = field
                return KavaReflector.readField(field, holder) as? TextView
            }
            current = current.superclass
        }
        unsupportedTimeHolders += holder.javaClass
        return null
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        return resolveNativeMessage(
            source,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        if (isNativeMessage(source)) return source
        if (source is Array<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { result -> return result } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { result -> return result } }
            return null
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.") || source is View) return null
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (KavaReflector.isStatic(field) || field.type.isPrimitive || field.type.isArray) continue
                if (field.type == String::class.java || Number::class.java.isAssignableFrom(field.type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun isNativeMessage(value: Any): Boolean {
        if (!value.javaClass.name.startsWith("com.tencent.mm.storage.")) return false
        return messageCreateTime(value) > 0L && messageId(value) > 0L
    }

    private fun messageCreateTime(message: Any): Long {
        parseLong(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, "getCreateTime"), message))
            ?.let { if (it > 0L) return it }
        for (name in arrayOf("field_createTime", "createTime")) {
            parseLong(KavaReflector.readField(message, name))?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun messageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID")) {
            parseLong(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, name), message))
                ?.let { if (it > 0L) return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID")) {
            parseLong(KavaReflector.readField(message, name))?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun parseLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private companion object {
        const val TAG = "[Hchat:ChatTimeStyle]"
        const val CACHE_SCHEMA = "chat_time_style_v1"
        const val CACHE_BIND_METHOD = "chat_time_bind"
    }
}
