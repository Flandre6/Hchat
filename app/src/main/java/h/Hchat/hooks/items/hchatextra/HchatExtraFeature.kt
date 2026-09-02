package h.Hchat.hooks.items.hchatextra

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.hideavatar.HideChatAvatarSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class HchatExtraFeature : BaseFeature() {
    private var hooker: HchatExtraHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "分支扩展功能"

    override fun onFeatureInit(context: FeatureContext) {
        HchatExtraSettings.migrateLegacyPreferences(context.hostContext())
        registerSettingsProvider(MessageDetailsSettingsProvider())
        registerSettingsProvider(GroupMemberHistorySettingsProvider())
        registerSettingsProvider(RedPacketDetailsSettingsProvider())
        registerSettingsProvider(SkipWebRiskSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = HchatExtraHooker(context, ::logError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.destroy()
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    companion object {
        const val ID = "hchat_extra"
    }
}

private class HchatExtraHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MessageDetailsBinding(
        val root: View,
        val nativeTimeLabel: TextView?,
        val holder: Any,
        val nativeMessage: Any?,
        val details: MessageDetails
    )

    private data class MessageDetailsBindState(
        val holder: Any?
    )

    private data class AvatarDetailsAnchor(
        val parent: RelativeLayout,
        val positionView: View,
        val hidden: Boolean
    )

    private data class BottomDetailsAnchor(
        val parent: ViewGroup,
        val layoutView: View,
        val alignmentView: View
    )

    private data class AvatarDetailsSpacing(
        val originalTop: Int,
        val originalBottom: Int,
        val appliedTop: Int,
        val appliedBottom: Int,
        val originalClipToPadding: Boolean,
        val clipStates: List<AvatarDetailsClipState>
    )

    private data class AvatarDetailsClipState(
        val view: WeakReference<ViewGroup>,
        val originalClipChildren: Boolean
    )

    private data class MessageDetailsConfig(
        val enabled: Boolean,
        val position: String,
        val format: String,
        val tokens: Set<String>,
        val timeFormatter: DateTimeFormatter,
        val textSizeSp: Float,
        val avatarGapDp: Int,
        val leftMarginDp: Int,
        val rightMarginDp: Int,
        val clickShow: Boolean,
        val lightTextColor: Int,
        val darkTextColor: Int,
        val lightBgColor: Int,
        val darkBgColor: Int
    )

    private data class MessageAccessorKey(
        val type: Class<*>,
        val getter: String,
        val primaryField: String,
        val fallbackField: String
    )

    private data class MessageAccessor(
        val getter: Method?,
        val primaryField: Field?,
        val fallbackField: Field?
    )

    private data class PendingPreDraw(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener
    )

    private enum class AvatarPositionResult {
        STABLE,
        NEEDS_LAYOUT,
        UNAVAILABLE
    }

    private val prefs = HchatStorage.preferences(context.hostContext(), HchatExtraSettings.PREFS_NAME)
    private val hideAvatarPrefs = HchatStorage.preferences(
        context.hostContext(),
        HideChatAvatarSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_method_cache")
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val itemMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val itemListFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderTimeFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderAvatarFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderClickAreaFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderMainContainerMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val holderWithoutMainContainerMethod = ConcurrentHashMap.newKeySet<Class<*>>()
    private val holderContentFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val messageAccessorCache = ConcurrentHashMap<MessageAccessorKey, MessageAccessor>()
    private val nativeMessageClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val messageNestedFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val messageDetailsFailureKeys = ConcurrentHashMap.newKeySet<String>()
    private val messageDetailsLabels = Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())
    private val messageDetailsBindings = WeakHashMap<TextView, MessageDetailsBinding>()
    private val avatarDetailsSpacings = WeakHashMap<RelativeLayout, AvatarDetailsSpacing>()
    private val messageDetailsRetryListeners = WeakHashMap<View, PendingPreDraw>()
    private val messageDetailsPositionListeners = WeakHashMap<View, PendingPreDraw>()
    private val messageDetailsColorListeners = WeakHashMap<View, PendingPreDraw>()
    private val messageDetailsBindStates = ThreadLocal<ArrayDeque<MessageDetailsBindState>>()
    @Volatile private var messageDetailsConfig = readMessageDetailsConfig()
    @Volatile private var hideSelfAvatar = hideAvatarPrefs.getBoolean(
        HideChatAvatarSettings.KEY_HIDE_SELF,
        HideChatAvatarSettings.DEFAULT_HIDE_SELF
    )
    @Volatile private var hideOtherAvatar = hideAvatarPrefs.getBoolean(
        HideChatAvatarSettings.KEY_HIDE_OTHER,
        HideChatAvatarSettings.DEFAULT_HIDE_OTHER
    )
    private val messageDetailsPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in MESSAGE_DETAILS_CONFIG_KEYS) {
            messageDetailsConfig = readMessageDetailsConfig()
        }
        when {
            key in MESSAGE_DETAILS_COLOR_KEYS -> refreshAttachedMessageDetailsLabels()
            key in MESSAGE_DETAILS_REBIND_KEYS -> refreshAttachedMessageDetailsLayouts()
        }
    }
    private val hideAvatarPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HideChatAvatarSettings.KEY_HIDE_SELF || key == HideChatAvatarSettings.KEY_HIDE_OTHER) {
            hideSelfAvatar = hideAvatarPrefs.getBoolean(
                HideChatAvatarSettings.KEY_HIDE_SELF,
                HideChatAvatarSettings.DEFAULT_HIDE_SELF
            )
            hideOtherAvatar = hideAvatarPrefs.getBoolean(
                HideChatAvatarSettings.KEY_HIDE_OTHER,
                HideChatAvatarSettings.DEFAULT_HIDE_OTHER
            )
            refreshAttachedMessageDetailsLayouts()
        }
    }
    private var chattingDataAdapterClass: Class<*>? = null
    @Volatile private var injectingGroupHistory = false

    init {
        migrateMessageDetailsDefaultFormat()
        messageDetailsConfig = readMessageDetailsConfig()
        prefs.registerOnSharedPreferenceChangeListener(messageDetailsPrefsListener)
        hideAvatarPrefs.registerOnSharedPreferenceChangeListener(hideAvatarPrefsListener)
    }

    private fun migrateMessageDetailsDefaultFormat() {
        if (!prefs.contains(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT)) return
        val stored = prefs.getString(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT, null)
        if (stored == HchatExtraSettings.LEGACY_MESSAGE_DETAILS_FORMAT) {
            prefs.edit()
                .putString(
                    HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
                    HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
                )
                .apply()
        }
    }

    private fun readMessageDetailsConfig(): MessageDetailsConfig {
        val position = when (prefs.getString(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_POSITION,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_POSITION
        )) {
            HchatExtraSettings.POSITION_AVATAR_ABOVE -> HchatExtraSettings.POSITION_AVATAR_ABOVE
            HchatExtraSettings.POSITION_AVATAR_BELOW -> HchatExtraSettings.POSITION_AVATAR_BELOW
            else -> HchatExtraSettings.POSITION_MESSAGE_BOTTOM
        }
        val format = prefs.getString(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
        ).orEmpty().ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT }
        val timePattern = prefs.getString(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT
        ).orEmpty().ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT }
        val timeFormatter = runCatching { DateTimeFormatter.ofPattern(timePattern) }
            .getOrDefault(DEFAULT_MESSAGE_DETAILS_TIME_FORMATTER)
        val lightText = messageDetailsTextColor(false)
        val darkText = messageDetailsTextColor(true)
        val lightBg = messageDetailsBgColor(false)
        val darkBg = messageDetailsBgColor(true)
        return MessageDetailsConfig(
            enabled = prefs.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS
            ),
            position = position,
            format = format,
            tokens = MESSAGE_DETAILS_TOKEN_PATTERN.findAll(format)
                .map { match -> match.groups[1]?.value ?: match.groups[2]?.value.orEmpty() }
                .toSet(),
            timeFormatter = timeFormatter,
            textSizeSp = prefs.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TEXT_SIZE
            ).toFloat(),
            avatarGapDp = prefs.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_AVATAR_GAP,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_AVATAR_GAP
            ).coerceIn(0, 64),
            leftMarginDp = prefs.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LEFT_MARGIN,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LEFT_MARGIN
            ),
            rightMarginDp = prefs.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_RIGHT_MARGIN,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_RIGHT_MARGIN
            ),
            clickShow = prefs.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_CLICK_SHOW,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_CLICK_SHOW
            ),
            lightTextColor = resolveMessageDetailsColor(
                lightText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT,
                darkText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT,
                Color.RED
            ),
            darkTextColor = resolveMessageDetailsColor(
                darkText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT,
                lightText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT,
                Color.RED
            ),
            lightBgColor = resolveMessageDetailsColor(
                lightBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG,
                darkBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG,
                Color.TRANSPARENT
            ),
            darkBgColor = resolveMessageDetailsColor(
                darkBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG,
                lightBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG,
                Color.TRANSPARENT
            )
        )
    }

    private fun schedulePreDraw(
        key: View,
        observedView: View,
        registry: WeakHashMap<View, PendingPreDraw>,
        action: () -> Unit
    ): Boolean {
        cancelPendingPreDraw(key, registry)
        val observer = observedView.viewTreeObserver
        if (!observer.isAlive) return false
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        listener = ViewTreeObserver.OnPreDrawListener {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            synchronized(registry) {
                if (registry[key]?.listener === listener) registry.remove(key)
            }
            action()
            true
        }
        synchronized(registry) {
            registry[key] = PendingPreDraw(observer, listener)
        }
        observer.addOnPreDrawListener(listener)
        return true
    }

    private fun cancelPendingPreDraw(key: View, registry: WeakHashMap<View, PendingPreDraw>) {
        val pending = synchronized(registry) { registry.remove(key) } ?: return
        if (pending.observer.isAlive) {
            pending.observer.removeOnPreDrawListener(pending.listener)
        }
    }

    private fun clearPendingPreDrawListeners(registry: WeakHashMap<View, PendingPreDraw>) {
        val pending = synchronized(registry) {
            registry.values.toList().also { registry.clear() }
        }
        pending.forEach {
            if (it.observer.isAlive) it.observer.removeOnPreDrawListener(it.listener)
        }
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(messageDetailsPrefsListener)
        hideAvatarPrefs.unregisterOnSharedPreferenceChangeListener(hideAvatarPrefsListener)
        val spacings = synchronized(avatarDetailsSpacings) {
            avatarDetailsSpacings.entries.map { it.key to it.value }.also { avatarDetailsSpacings.clear() }
        }
        spacings.forEach { (parent, spacing) -> restoreAvatarDetailsSpacing(parent, spacing) }
        synchronized(messageDetailsLabels) {
            messageDetailsLabels.clear()
        }
        synchronized(messageDetailsBindings) {
            messageDetailsBindings.clear()
        }
        clearPendingPreDrawListeners(messageDetailsRetryListeners)
        clearPendingPreDrawListeners(messageDetailsPositionListeners)
        clearPendingPreDrawListeners(messageDetailsColorListeners)
    }

    fun install(): Boolean {
        var hooked = 0
        runCatching {
            if (installSkipWebRisk()) hooked++
            if (installRedPacketDetails()) hooked++
            if (installGroupMemberHistory()) hooked++
            if (installMessageDetails()) hooked++
        }.onFailure {
            logger("Hchat扩展功能安装异常", it)
        }
        return hooked > 0
    }

    private fun enabled(key: String, def: Boolean): Boolean =
        prefs.getBoolean(key, def)

    private fun installSkipWebRisk(): Boolean {
        var hooked = false
        val intercept = locateMethod(
            "web_risk_intercept_enabled",
            listOf("MicroMsg.WebViewHighRiskAdH5Interceptor", "isInterceptEnabled, expt=")
        ) ?: return false
        hooked = hookOnce(intercept) { param ->
            if (enabled(HchatExtraSettings.KEY_SKIP_WEB_RISK, HchatExtraSettings.DEFAULT_SKIP_WEB_RISK)) {
                param.result = false
            }
        } || hooked
        val safe = locateMethodsInClass(
            "web_risk_url_safe",
            intercept.declaringClass,
            listOf("http", "https")
        ).firstOrNull { it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java }
            ?: return hooked
        hooked = hookOnce(safe) { param ->
            if (enabled(HchatExtraSettings.KEY_SKIP_WEB_RISK, HchatExtraSettings.DEFAULT_SKIP_WEB_RISK)) {
                param.result = true
            }
        } || hooked
        return hooked
    }

    private fun installRedPacketDetails(): Boolean {
        var hooked = false
        locateRedPacketTimeFormatterMethod()?.let { method ->
            hooked = hookAfterOnce(method) { param ->
                if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
                val timestamp = (param.args?.getOrNull(1) as? Number)?.toLong()
                    ?.takeIf { it in RED_PACKET_TIME_MILLIS_RANGE }
                    ?: return@hookAfterOnce
                param.result = formatRedPacketRecordTime(timestamp)
            } || hooked
        }
        val bindMethod = locateMethod(
            "lucky_money_detail_bind_direct",
            listOf("MicroMsg.LuckyMoneyDetailUI", "try get user contact: %s")
        )
        if (bindMethod != null) {
            hooked = hookAfterOnce(bindMethod) { param ->
                if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
                bindRedPacketRecordTime(param.args?.getOrNull(0), param.args?.getOrNull(1))
            } || hooked
        }

        val classes = locateClasses(
            "lucky_money_scene_classes",
            listOf(
                listOf("MicroMsg.NetSceneOpenLuckyMoney", "/cgi-bin/mmpay-bin/openwxhb"),
                listOf("MicroMsg.NetSceneLuckyMoneyDetail", "/cgi-bin/mmpay-bin/qrydetailwxhb")
            )
        )
        classes.forEach { clazz ->
            KavaReflector.declaredMethods(clazz)
                .firstOrNull { method ->
                    method.name == "onGYNetEnd" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == Integer.TYPE &&
                        method.parameterTypes[1] == String::class.java &&
                        JSONObject::class.java.isAssignableFrom(method.parameterTypes[2])
                }?.let { method ->
                    hooked = hookOnce(method) { param ->
                        if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookOnce
                        val json = param.args?.getOrNull(2) as? JSONObject ?: return@hookOnce
                        processRedPacketJson(json)
                    } || hooked
                }
        }
        return hooked
    }

    private fun locateRedPacketTimeFormatterMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), "lucky_money_time_formatter")
            ?.takeIf { isRedPacketTimeFormatterMethod(it) }
            ?.let { return it }
        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    searchPackages("com.tencent.mm.plugin.luckymoney.model")
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf("HH:mm"))
                        }
                    )
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
                .firstOrNull { isRedPacketTimeFormatterMethod(it) }
        }.getOrElse {
            logger("红包详情时间格式化定位失败", it)
            null
        }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, "lucky_money_time_formatter", method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, "lucky_money_time_formatter")
        return method
    }

    private fun isRedPacketTimeFormatterMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == String::class.java &&
            types.size == 2 &&
            Context::class.java.isAssignableFrom(types[0]) &&
            types[1] == java.lang.Long.TYPE
    }

    private fun installGroupMemberHistory(): Boolean {
        var hooked = false
        val clazz = KavaReflector.loadClass("com.tencent.mm.plugin.profile.ui.ContactInfoUI", context.hostClassLoader())
            ?: return false
        val firstInstall = hookedClasses.add(clazz)
        KavaReflector.findMethodRecursive(clazz, "initView")?.let { method ->
            hooked = hookAfterOnce(method) { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfterOnce
                if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookAfterOnce
                installGroupMemberHistoryFromList(activity)
            } || hooked
        }
        methodsRecursive(clazz).firstOrNull { it.name == "onPreferenceTreeClick" }?.let { method ->
            hooked = hookOnce(method) { param ->
                val activity = param.thisObject as? Activity ?: return@hookOnce
                if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookOnce
                val pref = groupHistoryClickedPreference(param.args)
                    ?: return@hookOnce
                openGroupMemberHistory(activity)
                param.result = true
            } || hooked
        }
        if (!hooked && firstInstall) hookedClasses.remove(clazz)
        return hooked
    }

    private fun installMessageDetails(): Boolean {
        val bind = locateMessageViewBindMethod() ?: return false
        if (!hookedMethods.add(bind)) return true
        return runCatching {
            HookRegistry.get().hook(bind, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 功能关闭时不做任何 View 树/反射扫描。消息列表绑定非常高频，
                    // 这里的无条件 capture 会直接增加群聊滚动时的主线程开销。
                    if (!messageDetailsConfig.enabled) {
                        messageDetailsBindStates.remove()
                        return
                    }
                    val state = runCatching { captureMessageDetailsBindState(param.args) }
                        .getOrElse {
                            logger("消息显示时间绑定前状态读取失败", it)
                            MessageDetailsBindState(null)
                        }
                    val stack = messageDetailsBindStates.get()
                        ?: ArrayDeque<MessageDetailsBindState>().also(messageDetailsBindStates::set)
                    stack.addLast(state)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = messageDetailsBindStates.get()
                    val state = stack?.pollLast() ?: MessageDetailsBindState(null)
                    if (stack?.isEmpty() == true) messageDetailsBindStates.remove()
                    if (!messageDetailsConfig.enabled) return
                    runCatching {
                        bindBottomMessageDetails(param.thisObject, param.args, 0, state.holder)
                    }.onFailure { logger("消息显示时间绑定失败", it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(bind)
            logger("消息显示时间Hook安装失败: ${bind.name}", it)
            false
        }
    }

    private fun captureMessageDetailsBindState(args: Array<Any?>?): MessageDetailsBindState {
        val adapterHolder = messageBindHolder(args) ?: return MessageDetailsBindState(null)
        val root = findRootView(adapterHolder) ?: return MessageDetailsBindState(null)
        val holder = root.tag?.takeIf { isMessageDetailsHolder(it, root) }
        return MessageDetailsBindState(holder)
    }

    private fun hookOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure { logger("Hchat扩展Hook执行失败: ${method.name}", it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("Hchat扩展Hook安装失败: ${method.name}", it)
            false
        }
    }

    private fun hookAfterOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure { logger("Hchat扩展Hook执行失败: ${method.name}", it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("Hchat扩展Hook安装失败: ${method.name}", it)
            false
        }
    }

    private fun locateMethod(cacheName: String, strings: List<String>, searchPackage: String? = null): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), cacheName)?.let { return it }
        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    if (!searchPackage.isNullOrBlank()) searchPackages(searchPackage)
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(strings)
                        }
                    )
                }
            ).firstNotNullOfOrNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("DexKit定位失败: $cacheName", it)
            null
        }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, cacheName, method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, cacheName)
        return method
    }

    private fun locateMessageViewBindMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), "chat_message_view_bind")
            ?.takeIf { isMessageViewBindCandidate(it) }
            ?.let { return it }
        val matches = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(listOf("MicroMsg.MvvmChattingItem", "[onBindView]"))
                    })
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("消息显示时间定位失败", it)
            emptyList()
        }
        val method = matches.firstOrNull { isMessageViewBindCandidate(it) }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, "chat_message_view_bind", method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, "chat_message_view_bind")
        return method
    }

    private fun locateChattingDataAdapterClass(): Class<*>? {
        chattingDataAdapterClass?.let { return it }
        val methodCacheKey = methodCacheKey()
        val prefs = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_class_cache")
        val key = "${methodCacheKey}_chatting_data_adapter"
        prefs.getString(key, null)?.let { name ->
            KavaReflector.loadClass(name, context.hostClassLoader())?.let {
                chattingDataAdapterClass = it
                return it
            }
        }
        val clazz = runCatching {
            context.dexKitBridge().findClass(
                FindClass().apply {
                    matcher(ClassMatcher().apply {
                        usingStrings(listOf("MicroMsg.ChattingDataAdapterV3", "[handleMsgChange] isLockNotify:"))
                    })
                }
            ).firstOrNull()?.let { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
        }.getOrElse {
            logger("Hchat聊天消息Adapter定位失败", it)
            null
        }
        if (clazz != null) {
            chattingDataAdapterClass = clazz
            prefs.edit().putString(key, clazz.name).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
        return clazz
    }

    private fun isMessageViewBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size >= 3 && types.any { it == Integer.TYPE || it == java.lang.Integer::class.java } &&
            types.any { isLikelyViewHolderClass(it) }
    }

    private fun isLikelyViewHolderClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        if (findRootField(clazz) != null) return true
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (KavaReflector.declaredFields(current).any { it.type == View::class.java }) return true
            current = current.superclass
        }
        return false
    }

    private fun locateMethodsInClass(cacheName: String, clazz: Class<*>, strings: List<String>): List<Method> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.loadList(methodPrefs, methodCacheKey, context.hostClassLoader(), cacheName)
            .filter { it.declaringClass == clazz }
        if (cached.isNotEmpty()) return cached
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(clazz.name)
                            usingStrings(strings)
                        }
                    )
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("DexKit定位失败: $cacheName", it)
            emptyList()
        }
        if (methods.isNotEmpty()) DexMethodCache.saveList(methodPrefs, methodCacheKey, cacheName, methods)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, cacheName)
        return methods
    }

    private fun MethodData.toRuntimeMethodOrNull(): Method? {
        return runCatching { getMethodInstance(context.hostClassLoader()) }.getOrNull()
    }

    private fun locateClasses(cacheName: String, stringGroups: List<List<String>>): List<Class<*>> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_class_cache")
        val classes = linkedSetOf<Class<*>>()
        stringGroups.forEachIndexed { index, strings ->
            val key = "${cacheName}_$index"
            cached.getString("${methodCacheKey}_$key", null)?.let { name ->
                KavaReflector.loadClass(name, context.hostClassLoader())?.let { classes += it }
            }
            if (classes.size > index) return@forEachIndexed
            val clazz = runCatching {
                context.dexKitBridge().findClass(
                    FindClass().apply {
                        matcher(ClassMatcher().apply { usingStrings(strings) })
                    }
                ).firstOrNull()?.let { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
            }.getOrElse {
                logger("DexKit定位类失败: $key", it)
                null
            }
            if (clazz != null) {
                classes += clazz
                cached.edit().putString("${methodCacheKey}_$key", clazz.name).apply()
            } else {
                cached.edit().remove("${methodCacheKey}_$key").apply()
            }
        }
        return classes.toList()
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun bindRedPacketRecordTime(holder: Any?, record: Any?) {
        val timestamp = getTimestampFromRecord(record) ?: return
        val text = formatRedPacketRecordTime(timestamp)
        var applied = setRedPacketRecordTimeByHolder(holder, text)
        val textView = getRedPacketRecordTimeTextView(holder, timestamp)
        if (textView != null) {
            textView.text = text
            textView.post {
                if (enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) {
                    textView.text = text
                }
            }
            applied = true
        }
        if (!applied) {
            val itemView = holder?.let { findRootView(it) }
            itemView?.post {
                setRedPacketRecordTimeByHolder(holder, text)
                getRedPacketRecordTimeTextView(holder, timestamp)?.text = text
            }
        }
    }

    private fun formatRedPacketRecordTime(timestamp: Long): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        val today = LocalDate.now()
        val pattern = when {
            localDateTime.toLocalDate() == today -> "HH:mm:ss"
            localDateTime.year == today.year -> "M月d日 HH:mm:ss"
            else -> "yyyy年M月d日 HH:mm:ss"
        }
        return localDateTime.format(DateTimeFormatter.ofPattern(pattern))
    }

    private fun getRedPacketRecordTimeTextView(holder: Any?, timestamp: Long): TextView? {
        findRedPacketRecordTimeTextViewByHolder(holder)?.let { return it }
        val itemView = holder?.let { findRootView(it) } as? ViewGroup ?: return null
        findRedPacketRecordTimeTextViewById(itemView)?.let { return it }
        findRedPacketRecordTimeTextViewByText(itemView, timestamp)?.let { return it }
        return findViewByChildIndexes(itemView, 0, 1, 1, 1, 1) as? TextView
    }

    private fun setRedPacketRecordTimeByHolder(holder: Any?, text: String): Boolean {
        if (holder == null) return false
        val resId = redPacketRecordTimeResId() ?: return false
        var ok = false
        methodsRecursive(holder.javaClass).forEach { method ->
            if (method.parameterTypes.size != 2 ||
                method.parameterTypes[0] != Integer.TYPE ||
                !method.parameterTypes[1].isAssignableFrom(String::class.java)
            ) {
                return@forEach
            }
            val result = runCatching {
                method.isAccessible = true
                method.invoke(holder, resId, text)
                true
            }.getOrDefault(false)
            if (result) ok = true
        }
        return ok
    }

    private fun findRedPacketRecordTimeTextViewByHolder(holder: Any?): TextView? {
        if (holder == null) return null
        val resId = redPacketRecordTimeResId() ?: return null
        return methodsRecursive(holder.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 1 ||
                method.parameterTypes[0] != Integer.TYPE ||
                !View::class.java.isAssignableFrom(method.returnType)
            ) {
                return@firstNotNullOfOrNull null
            }
            runCatching {
                method.isAccessible = true
                method.invoke(holder, resId) as? TextView
            }.getOrNull()
        }
    }

    private fun redPacketRecordTimeResId(): Int? {
        return runCatching {
            context.hostContext().resources.getIdentifier(
                RED_PACKET_RECORD_TIME_ID_NAME,
                "id",
                context.hostContext().packageName
            ).takeIf { it != 0 }
        }.getOrNull()
    }

    private fun findRedPacketRecordTimeTextViewById(root: ViewGroup): TextView? {
        return runCatching {
            val resId = root.resources.getIdentifier(
                RED_PACKET_RECORD_TIME_ID_NAME,
                "id",
                root.context.packageName
            )
            if (resId == 0) null else root.findViewById<TextView>(resId)
        }.getOrNull()
    }

    private fun findRedPacketRecordTimeTextViewByText(root: View, timestamp: Long): TextView? {
        val candidates = redPacketRecordTimeWithoutSecondsCandidates(timestamp)
            .map { normalizeRedPacketTimeText(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (candidates.isEmpty()) return null
        val shortTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        return findTextViewRecursive(root) { textView ->
            val text = normalizeRedPacketTimeText(textView.text)
            text in candidates || (
                text.endsWith(shortTime) &&
                    text.length <= 20 &&
                    !text.contains("元") &&
                    !text.contains("/")
                )
        }
    }

    private fun redPacketRecordTimeWithoutSecondsCandidates(timestamp: Long): Set<String> {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        val today = LocalDate.now()
        val patterns = linkedSetOf("HH:mm")
        if (localDateTime.toLocalDate() != today) {
            patterns += "M月d日 HH:mm"
            if (localDateTime.year != today.year) {
                patterns += "yyyy年M月d日 HH:mm"
            }
        }
        return patterns.mapTo(linkedSetOf()) {
            localDateTime.format(DateTimeFormatter.ofPattern(it))
        }
    }

    private fun normalizeRedPacketTimeText(text: CharSequence?): String {
        return text?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    }

    private fun findTextViewRecursive(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && root.visibility == View.VISIBLE && predicate(root)) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findTextViewRecursive(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun findViewByChildIndexes(root: ViewGroup, vararg indexes: Int): View? {
        var current: View = root
        indexes.forEach { index ->
            val group = current as? ViewGroup ?: return null
            if (index < 0 || index >= group.childCount) return null
            current = group.getChildAt(index)
        }
        return current
    }

    private fun getTimestampFromRecord(record: Any?): Long? {
        return findTimestampInObject(record, 0, Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    }

    private fun findTimestampInObject(value: Any?, depth: Int, visited: MutableSet<Any>): Long? {
        if (value == null || depth > 4 || !visited.add(value)) return null
        when (value) {
            is String -> return timestampFromString(value)
            is Number -> return timestampFromNumber(value)
        }
        val clazz = value.javaClass
        if (clazz.isPrimitive ||
            clazz.isArray ||
            clazz.name.startsWith("android.") ||
            clazz.name.startsWith("java.") ||
            clazz.name.startsWith("kotlin.")
        ) {
            return null
        }
        fieldsRecursive(clazz).forEach { field ->
            val fieldValue = KavaReflector.readField(field, value) ?: return@forEach
            when (fieldValue) {
                is String -> timestampFromString(fieldValue)?.let { return it }
                is Number -> timestampFromNumber(fieldValue)?.let { return it }
                else -> findTimestampInObject(fieldValue, depth + 1, visited)?.let { return it }
            }
        }
        return null
    }

    private fun timestampFromString(value: String): Long? {
        val clean = value.trim()
        if (clean.length == 10 && clean.all { it.isDigit() }) {
            return timestampFromSeconds(clean.toLongOrNull())
        }
        if (clean.length == 13 && clean.all { it.isDigit() }) {
            return clean.toLongOrNull()?.takeIf { it in RED_PACKET_TIME_MILLIS_RANGE }
        }
        return null
    }

    private fun timestampFromNumber(value: Number): Long? {
        val raw = value.toLong()
        if (raw in RED_PACKET_TIME_SECONDS_RANGE) return raw * 1000L
        if (raw in RED_PACKET_TIME_MILLIS_RANGE) return raw
        return null
    }

    private fun timestampFromSeconds(value: Long?): Long? {
        val seconds = value ?: return null
        return seconds.takeIf { it in RED_PACKET_TIME_SECONDS_RANGE }?.times(1000L)
    }

    private fun processRedPacketJson(json: JSONObject) {
        val totalAmount = json.optInt("totalAmount", 0)
        val totalNum = json.optInt("totalNum", 0)
        val recNum = json.optInt("recNum", 0)
        val recAmount = json.optInt("recAmount", 0)
        if (totalAmount <= 0 && totalNum <= 0) return
        val remaining = (totalAmount - recAmount) / 100.0
        val title = buildString {
            append("金额:").append(recAmount / 100.0).append('/').append(totalAmount / 100.0).append("元\n")
            append("数量:").append(recNum).append('/').append(totalNum)
            if (remaining > 0.0) append("\n剩余:").append(remaining).append("元")
        }
        json.put("headTitle", title)
    }

    private fun bindBottomMessageDetails(
        bindObject: Any?,
        args: Array<Any?>?,
        attempt: Int,
        capturedHolder: Any? = null
    ): Boolean {
        val holder = messageBindHolder(args) ?: return false
        val root = findRootView(holder) ?: return false
        cancelPendingPreDraw(root, messageDetailsRetryListeners)
        val nativeMessage = resolveMessageFromBindArgs(args)
            ?: resolveMessageFromAdapter(bindObject, messageBindPosition(args))
        if (nativeMessage == null) {
            if (attempt >= MESSAGE_DETAILS_MAX_RETRY) {
                removeTaggedMessageDetailsViewsAround(root)
                logMessageDetailsFailure("message", holder, root)
            }
            scheduleMessageDetailsRetry(root, bindObject, args, attempt, capturedHolder)
            return false
        }
        val messageType = messageType(nativeMessage)
        if (WeChatMessageTypes.isSystem(messageType)) {
            removeTaggedMessageDetailsViewsAround(root)
            restoreAvatarDetailsSpacingsAround(root)
            return true
        }
        val details = messageDetails(nativeMessage, knownType = messageType)
        val viewTag = capturedHolder?.takeIf { isMessageDetailsHolder(it, root) }
            ?: root.tag?.takeIf { isMessageDetailsHolder(it, root) }
            ?: holder
        val label = findHolderTextView(viewTag, "timeTV", holderTimeFieldCache)
        val inserted = insertMessageDetailsLabel(root, label, viewTag, details, nativeMessage = nativeMessage)
        if (!inserted) {
            if (attempt >= MESSAGE_DETAILS_MAX_RETRY) {
                removeTaggedMessageDetailsViewsAround(root)
                logMessageDetailsFailure("layout", viewTag, root)
            }
            scheduleMessageDetailsRetry(root, bindObject, args, attempt, viewTag)
        }
        label?.setOnClickListener(null)
        if (label != null) label.isClickable = false
        return inserted
    }

    private fun resolveMessageFromBindArgs(args: Array<Any?>?): Any? {
        return resolveNativeMessage(args?.getOrNull(1))
    }

    private fun logMessageDetailsFailure(reason: String, holder: Any, root: View) {
        val position = messageDetailsConfig.position
        val key = "$reason|${holder.javaClass.name}|$position"
        if (!messageDetailsFailureKeys.add(key)) return
        val hasTime = findHolderTextView(holder, "timeTV", holderTimeFieldCache) != null
        val hasAvatar = findHolderAvatarView(holder) != null || findChattingAvatarView(root) != null
        val hasClickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache) != null
        logger(
            "消息详情绑定失败 reason=$reason holder=${holder.javaClass.name} " +
                "root=${root.javaClass.name} position=$position " +
                "time=$hasTime avatar=$hasAvatar clickArea=$hasClickArea",
            null
        )
    }

    private fun scheduleMessageDetailsRetry(
        root: View,
        bindObject: Any?,
        args: Array<Any?>?,
        attempt: Int,
        holder: Any?
    ) {
        if (attempt >= MESSAGE_DETAILS_MAX_RETRY) return
        val nextAttempt = attempt + 1
        schedulePreDraw(root, root, messageDetailsRetryListeners) {
            bindBottomMessageDetails(bindObject, args, nextAttempt, holder)
        }
    }

    private fun isMessageDetailsHolder(holder: Any, root: View): Boolean {
        val timeView = findHolderTextView(holder, "timeTV", holderTimeFieldCache) ?: return false
        return isViewWithinRoot(timeView, root)
    }

    private fun insertMessageDetailsLabel(
        root: View,
        nativeTimeLabel: TextView?,
        holder: Any,
        details: MessageDetails,
        preferredLabel: TextView? = null,
        nativeMessage: Any? = null
    ): Boolean {
        val config = messageDetailsConfig
        val position = config.position
        val configuredAvatarHidden = configuredAvatarHidden(details.isSelf)
        val hiddenAvatarBelowUsesBottom =
            configuredAvatarHidden && position == HchatExtraSettings.POSITION_AVATAR_BELOW
        val resolvedAvatarAnchor = if (hiddenAvatarBelowUsesBottom) {
            null
        } else {
            findAvatarDetailsAnchor(root, holder, configuredAvatarHidden)
        }
        val avatarAnchor = if (
            position == HchatExtraSettings.POSITION_MESSAGE_BOTTOM || hiddenAvatarBelowUsesBottom
        ) {
            null
        } else {
            resolvedAvatarAnchor
        }
        if (avatarAnchor == null) {
            restoreAvatarDetailsSpacingsAround(root)
        }
        val bottomAnchor = if (avatarAnchor == null) {
            messageContentAnchor(holder, nativeTimeLabel ?: return false)
        } else {
            null
        }
        val avatarContent = avatarAnchor
            ?.takeIf { it.hidden }
            ?.let { avatarDetailsContentView(holder, it.parent) }
        val parent: ViewGroup = avatarAnchor?.parent ?: bottomAnchor?.parent ?: return false
        if (parent !is RelativeLayout && parent !is LinearLayout) return false
        val label = preferredLabel?.takeIf { it.tag == TAG_MESSAGE_DETAILS_VIEW }
            ?: findDirectMessageDetailsLabel(parent)
            ?: TextView(nativeTimeLabel?.context ?: root.context).also {
                it.tag = TAG_MESSAGE_DETAILS_VIEW
            }
        if (label.parent !== parent) {
            removeTaggedMessageDetailsViewsAround(root, keep = label)
            (label.parent as? ViewGroup)?.removeView(label)
        }
        rememberMessageDetailsLabel(label)
        applyMessageDetailsLabel(label, details)
        val previousBinding = synchronized(messageDetailsBindings) { messageDetailsBindings[label] }
        if (config.clickShow) {
            val canReuseListener = label.isClickable && nativeMessage != null &&
                previousBinding?.nativeMessage === nativeMessage
            if (!canReuseListener) {
                label.setOnClickListener { view ->
                    val fullDetails = nativeMessage?.let {
                        messageDetails(it, knownType = details.type, includeContent = true)
                    } ?: details
                    showMessageDetailsDialog(view.context, fullDetails)
                }
            }
            label.isClickable = true
        } else {
            if (label.isClickable) {
                label.setOnClickListener(null)
                label.isClickable = false
            }
        }
        val inserted = if (avatarAnchor != null) {
            addAvatarDetailsView(root, avatarAnchor, avatarContent, label, position, details.isSelf)
        } else {
            addBottomDetailsView(
                parent,
                bottomAnchor?.layoutView ?: return false,
                bottomAnchor.alignmentView,
                label,
                details,
                configuredAvatarHidden || resolvedAvatarAnchor?.hidden == true
            )
        }
        if (inserted) {
            rememberMessageDetailsBinding(label, root, nativeTimeLabel, holder, nativeMessage, details)
        }
        return inserted
    }

    private fun findDirectMessageDetailsLabel(parent: ViewGroup): TextView? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is TextView && child.tag == TAG_MESSAGE_DETAILS_VIEW) return child
        }
        return null
    }

    private fun applyMessageDetailsLabel(label: TextView, details: MessageDetails) {
        val text = formatMessageDetailsLabel(details)
        if (!TextUtils.equals(label.text, text)) {
            label.text = text
        }
        applyMessageDetailsColors(label)
        if (label.alpha != 1f) label.alpha = 1f
        if (!label.includeFontPadding) label.includeFontPadding = true
        val horizontalPadding = dp(label.context, 4f)
        val verticalPadding = dp(label.context, 2f)
        if (label.paddingLeft != horizontalPadding || label.paddingTop != verticalPadding ||
            label.paddingRight != horizontalPadding || label.paddingBottom != verticalPadding
        ) {
            label.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        val textSize = messageDetailsConfig.textSizeSp
        if (kotlin.math.abs(label.textSize / label.resources.displayMetrics.scaledDensity - textSize) > 0.01f) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    private fun applyMessageDetailsColors(label: TextView) {
        val textColor = messageDetailsResolvedTextColor(label.context)
        if (label.currentTextColor != textColor) label.setTextColor(textColor)
        val bgColor = messageDetailsResolvedBgColor(label.context)
        val currentBgColor = (label.background as? ColorDrawable)?.color
        if (currentBgColor != bgColor && !(currentBgColor == null && bgColor == Color.TRANSPARENT)) {
            label.setBackgroundColor(bgColor)
        }
    }

    private fun rememberMessageDetailsLabel(label: TextView) {
        synchronized(messageDetailsLabels) {
            messageDetailsLabels.add(label)
        }
    }

    private fun rememberMessageDetailsBinding(
        label: TextView,
        root: View,
        nativeTimeLabel: TextView?,
        holder: Any,
        nativeMessage: Any?,
        details: MessageDetails
    ) {
        synchronized(messageDetailsBindings) {
            messageDetailsBindings[label] = MessageDetailsBinding(root, nativeTimeLabel, holder, nativeMessage, details)
        }
    }

    private fun refreshAttachedMessageDetailsLabels() {
        val labels = synchronized(messageDetailsLabels) {
            messageDetailsLabels.toList()
        }
        labels.forEach { label ->
            label.post {
                if (label.parent != null && label.tag == TAG_MESSAGE_DETAILS_VIEW) {
                    applyMessageDetailsColors(label)
                    label.invalidate()
                }
            }
        }
    }

    private fun refreshAttachedMessageDetailsLayouts() {
        val bindings = synchronized(messageDetailsBindings) {
            messageDetailsBindings.entries.map { it.key to it.value }
        }
        bindings.forEach { (label, binding) ->
            label.post {
                val currentBinding = synchronized(messageDetailsBindings) { messageDetailsBindings[label] }
                if (currentBinding !== binding) return@post
                if (label.parent != null && label.tag == TAG_MESSAGE_DETAILS_VIEW) {
                    val details = binding.nativeMessage?.let { messageDetails(it) } ?: binding.details
                    insertMessageDetailsLabel(
                        binding.root,
                        binding.nativeTimeLabel,
                        binding.holder,
                        details,
                        preferredLabel = label,
                        nativeMessage = binding.nativeMessage
                    )
                }
            }
        }
    }

    private fun addAvatarDetailsView(
        root: View,
        target: AvatarDetailsAnchor,
        content: View?,
        label: TextView,
        position: String,
        isSelf: Boolean
    ): Boolean {
        val parent = target.parent
        val oldParent = label.parent as? ViewGroup
        val alreadyAttached = oldParent === parent
        if (oldParent != null && !alreadyAttached) oldParent.removeView(label)
        val gap = dp(label.context, messageDetailsConfig.avatarGapDp.toFloat())
        val params = if (alreadyAttached) {
            label.layoutParams as? RelativeLayout.LayoutParams
        } else {
            null
        } ?: RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        configureAvatarDetailsLabel(label)
        if (label.translationX != 0f) label.translationX = 0f
        if (label.translationY != 0f) label.translationY = 0f
        if (label.gravity != Gravity.CENTER) label.gravity = Gravity.CENTER
        if (label.textAlignment != View.TEXT_ALIGNMENT_CENTER) {
            label.textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        if (!alreadyAttached || target.hidden) label.visibility = View.INVISIBLE
        measureAvatarDetailsLabel(label)
        val initialReservedSpace = if (position == HchatExtraSettings.POSITION_AVATAR_ABOVE) {
            label.measuredHeight + gap
        } else {
            0
        }
        applyAvatarDetailsSpacing(root, parent, position, initialReservedSpace)
        if (!alreadyAttached) {
            parent.addView(label, params)
        }
        if (!scheduleAvatarDetailsPosition(label, root, target, content, position, gap, isSelf, 0)) {
            parent.removeView(label)
            restoreAvatarDetailsSpacingsAround(root)
            return false
        }
        refreshMessageDetailsColorsAfterAttach(label, newlyAttached = !alreadyAttached)
        return true
    }

    private fun scheduleAvatarDetailsPosition(
        label: TextView,
        root: View,
        target: AvatarDetailsAnchor,
        content: View?,
        position: String,
        gap: Int,
        isSelf: Boolean,
        attempt: Int
    ): Boolean {
        val parent = target.parent
        return schedulePreDraw(label, parent, messageDetailsPositionListeners) {
            when (positionAvatarDetailsLabel(label, root, target, content, position, gap, isSelf, attempt >= 2)) {
                AvatarPositionResult.STABLE -> label.visibility = View.VISIBLE
                AvatarPositionResult.NEEDS_LAYOUT -> {
                    layoutAvatarDetailsLabelNow(label, parent)
                    label.visibility = View.VISIBLE
                    if (attempt < MESSAGE_DETAILS_POSITION_MAX_RETRY) {
                        scheduleAvatarDetailsPosition(label, root, target, content, position, gap, isSelf, attempt + 1)
                    }
                }
                AvatarPositionResult.UNAVAILABLE -> {
                    if (attempt < MESSAGE_DETAILS_POSITION_MAX_RETRY) {
                        scheduleAvatarDetailsPosition(label, root, target, content, position, gap, isSelf, attempt + 1)
                    } else {
                        removeTaggedMessageDetailsViewsAround(root)
                        restoreAvatarDetailsSpacingsAround(root)
                    }
                }
            }
        }
    }

    private fun layoutAvatarDetailsLabelNow(label: TextView, parent: RelativeLayout) {
        val params = label.layoutParams as? RelativeLayout.LayoutParams ?: return
        val width = params.width.takeIf { it > 0 } ?: label.measuredWidth
        val height = label.measuredHeight
        if (width <= 0 || height <= 0) return
        val left = parent.paddingLeft + params.leftMargin
        val top = parent.paddingTop + params.topMargin
        label.layout(left, top, left + width, top + height)
    }

    private fun applyAvatarDetailsSpacing(
        root: View,
        parent: RelativeLayout,
        position: String,
        space: Int
    ) {
        restoreAvatarDetailsSpacingsAround(root, keep = parent)
        val existing = synchronized(avatarDetailsSpacings) { avatarDetailsSpacings[parent] }
        val originalTop = existing?.originalTop ?: parent.paddingTop
        val originalBottom = existing?.originalBottom ?: parent.paddingBottom
        val reserved = space.coerceAtLeast(0)
        val appliedTop = originalTop + if (position == HchatExtraSettings.POSITION_AVATAR_ABOVE) reserved else 0
        val appliedBottom = originalBottom + if (position == HchatExtraSettings.POSITION_AVATAR_BELOW) reserved else 0
        val originalClipToPadding = existing?.originalClipToPadding ?: parent.clipToPadding
        val clipStates = existing?.clipStates ?: disableAvatarDetailsClipping(root, parent)
        clipStates.forEach { state -> state.view.get()?.clipChildren = false }
        if (parent.paddingTop != appliedTop || parent.paddingBottom != appliedBottom) {
            parent.setPadding(parent.paddingLeft, appliedTop, parent.paddingRight, appliedBottom)
        }
        parent.clipToPadding = false
        synchronized(avatarDetailsSpacings) {
            avatarDetailsSpacings[parent] = AvatarDetailsSpacing(
                originalTop,
                originalBottom,
                appliedTop,
                appliedBottom,
                originalClipToPadding,
                clipStates
            )
        }
    }

    private fun disableAvatarDetailsClipping(root: View, parent: RelativeLayout): List<AvatarDetailsClipState> {
        val states = ArrayList<AvatarDetailsClipState>()
        var current: View? = parent
        while (current != null && current !== root) {
            if (current is ViewGroup) {
                states += AvatarDetailsClipState(WeakReference(current), current.clipChildren)
                current.clipChildren = false
            }
            current = current.parent as? View
        }
        return states
    }

    private fun expandAvatarAboveSpacing(parent: RelativeLayout, extra: Int): Boolean {
        if (extra <= 0) return false
        val spacing = synchronized(avatarDetailsSpacings) { avatarDetailsSpacings[parent] } ?: return false
        val updated = spacing.copy(appliedTop = spacing.appliedTop + extra)
        parent.setPadding(parent.paddingLeft, updated.appliedTop, parent.paddingRight, updated.appliedBottom)
        synchronized(avatarDetailsSpacings) {
            avatarDetailsSpacings[parent] = updated
        }
        return true
    }

    private fun expandAvatarBelowSpacing(parent: RelativeLayout, extra: Int): Boolean {
        if (extra <= 0) return false
        val spacing = synchronized(avatarDetailsSpacings) { avatarDetailsSpacings[parent] } ?: return false
        if (spacing.appliedBottom != spacing.originalBottom) return false
        val updated = spacing.copy(appliedBottom = spacing.appliedBottom + extra)
        parent.setPadding(parent.paddingLeft, updated.appliedTop, parent.paddingRight, updated.appliedBottom)
        synchronized(avatarDetailsSpacings) {
            avatarDetailsSpacings[parent] = updated
        }
        return true
    }

    private fun restoreAvatarDetailsSpacingsAround(root: View, keep: RelativeLayout? = null) {
        val entries = synchronized(avatarDetailsSpacings) {
            avatarDetailsSpacings.entries
                .filter { (parent, _) -> parent !== keep && isViewWithinRoot(parent, root) }
                .map { it.key to it.value }
                .also { matches -> matches.forEach { (parent, _) -> avatarDetailsSpacings.remove(parent) } }
        }
        entries.forEach { (parent, spacing) -> restoreAvatarDetailsSpacing(parent, spacing) }
    }

    private fun restoreAvatarDetailsSpacing(parent: RelativeLayout, spacing: AvatarDetailsSpacing) {
        val restoredTop = if (parent.paddingTop == spacing.appliedTop) spacing.originalTop else parent.paddingTop
        val restoredBottom = if (parent.paddingBottom == spacing.appliedBottom) {
            spacing.originalBottom
        } else {
            parent.paddingBottom
        }
        if (restoredTop != parent.paddingTop || restoredBottom != parent.paddingBottom) {
            parent.setPadding(parent.paddingLeft, restoredTop, parent.paddingRight, restoredBottom)
        }
        parent.clipToPadding = spacing.originalClipToPadding
        spacing.clipStates.forEach { state ->
            val view = state.view.get() ?: return@forEach
            if (!view.clipChildren) view.clipChildren = state.originalClipChildren
        }
    }

    private fun isViewWithinRoot(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun positionAvatarDetailsLabel(
        label: TextView,
        root: View,
        target: AvatarDetailsAnchor,
        content: View?,
        position: String,
        gap: Int,
        isSelf: Boolean,
        fallbackBelow: Boolean
    ): AvatarPositionResult {
        val parent = target.parent
        val positionView = target.positionView
        if (label.parent !== parent || positionView.height <= 0 || (!target.hidden && positionView.width <= 0)) {
            return AvatarPositionResult.UNAVAILABLE
        }
        measureAvatarDetailsLabel(label)
        val horizontalBounds = avatarDetailsHorizontalBounds(root, parent)
            ?: return AvatarPositionResult.UNAVAILABLE
        val avatarBounds = if (positionView === parent) {
            Rect(parent.paddingLeft, parent.paddingTop, parent.width - parent.paddingRight, parent.height - parent.paddingBottom)
        } else {
            Rect(0, 0, positionView.width, positionView.height).also {
                parent.offsetDescendantRectToMyCoords(positionView, it)
            }
        }
        val params = label.layoutParams as? RelativeLayout.LayoutParams
            ?: return AvatarPositionResult.UNAVAILABLE
        val labelWidth = label.measuredWidth
        val minLeft = horizontalBounds.left
        val maxLeft = (horizontalBounds.right - labelWidth).coerceAtLeast(minLeft)
        val contentBounds = content
            ?.takeIf { target.hidden && it.width > 0 && it.height > 0 && isViewWithinRoot(it, parent) }
            ?.let { view ->
                Rect(0, 0, view.width, view.height).also {
                    parent.offsetDescendantRectToMyCoords(view, it)
                }
            }
        val left = if (target.hidden) {
            if (isSelf) {
                (contentBounds?.right?.minus(labelWidth) ?: maxLeft).coerceIn(minLeft, maxLeft)
            } else {
                (contentBounds?.left ?: minLeft).coerceIn(minLeft, maxLeft)
            }
        } else {
            (avatarBounds.left + (avatarBounds.width() - labelWidth) / 2).coerceIn(minLeft, maxLeft)
        }
        val top = if (position == HchatExtraSettings.POSITION_AVATAR_ABOVE) {
            avatarBounds.top - label.measuredHeight - gap
        } else {
            avatarBounds.bottom + gap
        }
        if (position == HchatExtraSettings.POSITION_AVATAR_BELOW) {
            val overflow = top + label.measuredHeight - parent.height
            if (overflow > 0 && expandAvatarBelowSpacing(parent, overflow)) {
                return AvatarPositionResult.NEEDS_LAYOUT
            }
        }
        if (top < 0 && position == HchatExtraSettings.POSITION_AVATAR_ABOVE && !fallbackBelow) {
            return if (expandAvatarAboveSpacing(parent, -top)) {
                AvatarPositionResult.NEEDS_LAYOUT
            } else {
                AvatarPositionResult.UNAVAILABLE
            }
        }
        val resolvedTop = if (top >= 0) top else avatarBounds.bottom + gap
        val resolvedLeftMargin = left - parent.paddingLeft
        val resolvedTopMargin = resolvedTop - parent.paddingTop
        if (params.width == labelWidth &&
            params.leftMargin == resolvedLeftMargin &&
            params.topMargin == resolvedTopMargin
        ) {
            return AvatarPositionResult.STABLE
        }
        params.width = labelWidth
        params.leftMargin = resolvedLeftMargin
        params.marginStart = params.leftMargin
        params.topMargin = resolvedTopMargin
        label.layoutParams = params
        return AvatarPositionResult.NEEDS_LAYOUT
    }

    private fun configureAvatarDetailsLabel(label: TextView) {
        if (label.maxLines != 1) {
            label.setSingleLine(true)
            label.maxLines = 1
            label.setHorizontallyScrolling(false)
        }
        if (label.ellipsize != null) label.ellipsize = null
        if (label.minWidth != 0) label.minWidth = 0
        if (label.maxWidth != Int.MAX_VALUE) label.maxWidth = Int.MAX_VALUE
    }

    private fun measureAvatarDetailsLabel(label: TextView) {
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    private fun avatarDetailsHorizontalBounds(root: View, parent: RelativeLayout): Rect? {
        val rootWidth = root.width.takeIf { it > 0 } ?: root.measuredWidth.takeIf { it > 0 }
        if (rootWidth != null && root is ViewGroup && isViewWithinRoot(parent, root)) {
            val bounds = Rect(0, 0, rootWidth, root.height.coerceAtLeast(1))
            if (root !== parent) {
                root.offsetRectIntoDescendantCoords(parent, bounds)
            }
            if (bounds.width() > 0) return bounds
        }
        val parentWidth = parent.width.takeIf { it > 0 } ?: parent.measuredWidth.takeIf { it > 0 }
            ?: return null
        return Rect(0, 0, parentWidth, parent.height.coerceAtLeast(1))
    }

    private fun addBottomDetailsView(
        parent: ViewGroup,
        content: View,
        alignmentView: View,
        label: TextView,
        details: MessageDetails,
        avatarHidden: Boolean
    ): Boolean {
        if (label.maxLines != Int.MAX_VALUE) {
            label.setSingleLine(false)
            label.maxLines = Int.MAX_VALUE
            label.setHorizontallyScrolling(false)
        }
        if (label.ellipsize != null) label.ellipsize = null
        if (label.maxWidth != Int.MAX_VALUE) label.maxWidth = Int.MAX_VALUE
        val oldParent = label.parent as? ViewGroup
        val alreadyAttached = oldParent === parent
        if (oldParent != null && !alreadyAttached) oldParent.removeView(label)
        val config = messageDetailsConfig
        val edge = dp(label.context, config.leftMarginDp.toFloat())
        val right = dp(label.context, config.rightMarginDp.toFloat())
        if (label.translationX != 0f) label.translationX = 0f
        if (label.translationY != 0f) label.translationY = 0f
        if (parent is RelativeLayout) {
            ensureViewId(content)
            val expectedParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            expectedParams.addRule(RelativeLayout.BELOW, content.id)
            expectedParams.topMargin = dp(label.context, 2f)
            if (details.isSelf) {
                if (avatarHidden) {
                    expectedParams.addRule(RelativeLayout.ALIGN_PARENT_END)
                    (label.layoutParams as? RelativeLayout.LayoutParams)?.let { current ->
                        expectedParams.marginEnd = current.marginEnd
                        expectedParams.rightMargin = current.rightMargin
                    }
                } else {
                    expectedParams.addRule(RelativeLayout.ALIGN_PARENT_END)
                    expectedParams.marginEnd = right
                    expectedParams.rightMargin = right
                }
                if (label.gravity != Gravity.END) label.gravity = Gravity.END
                if (label.textAlignment != View.TEXT_ALIGNMENT_TEXT_END) {
                    label.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                }
            } else {
                if (avatarHidden) {
                    expectedParams.addRule(RelativeLayout.ALIGN_PARENT_START)
                    (label.layoutParams as? RelativeLayout.LayoutParams)?.let { current ->
                        expectedParams.marginStart = current.marginStart
                        expectedParams.leftMargin = current.leftMargin
                    }
                } else {
                    expectedParams.addRule(RelativeLayout.ALIGN_PARENT_START)
                    expectedParams.marginStart = edge
                    expectedParams.leftMargin = edge
                }
                if (label.gravity != Gravity.START) label.gravity = Gravity.START
                if (label.textAlignment != View.TEXT_ALIGNMENT_TEXT_START) {
                    label.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                }
            }
            val expectedVisibility = if (avatarHidden) View.INVISIBLE else View.VISIBLE
            if (label.visibility != expectedVisibility) label.visibility = expectedVisibility
            if (!alreadyAttached) {
                parent.addView(label, expectedParams)
            } else if (!sameRelativeLayoutParams(label.layoutParams, expectedParams)) {
                label.layoutParams = expectedParams
            }
            if (avatarHidden) {
                scheduleBottomDetailsAlignment(label, parent, alignmentView, details.isSelf, 0)
            } else {
                cancelPendingPreDraw(label, messageDetailsPositionListeners)
            }
            refreshMessageDetailsColorsAfterAttach(label, newlyAttached = !alreadyAttached)
            return true
        }
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(label.context, 2f)
        if (details.isSelf) {
            if (!avatarHidden) params.marginEnd = right
            if (label.gravity != Gravity.END) label.gravity = Gravity.END
            if (label.textAlignment != View.TEXT_ALIGNMENT_TEXT_END) {
                label.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            }
        } else {
            if (!avatarHidden) params.marginStart = edge
            if (label.gravity != Gravity.START) label.gravity = Gravity.START
            if (label.textAlignment != View.TEXT_ALIGNMENT_TEXT_START) {
                label.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            }
        }
        when (parent) {
            is LinearLayout -> {
                val linearParams = LinearLayout.LayoutParams(params)
                if (avatarHidden) {
                    linearParams.gravity = if (details.isSelf) Gravity.END else Gravity.START
                    (label.layoutParams as? ViewGroup.MarginLayoutParams)?.let { current ->
                        if (details.isSelf) {
                            linearParams.marginEnd = current.marginEnd
                            linearParams.rightMargin = current.rightMargin
                        } else {
                            linearParams.marginStart = current.marginStart
                            linearParams.leftMargin = current.leftMargin
                        }
                    }
                    if (label.visibility != View.INVISIBLE) label.visibility = View.INVISIBLE
                } else {
                    if (label.visibility != View.VISIBLE) label.visibility = View.VISIBLE
                }
                val contentIndex = parent.indexOfChild(content)
                val expectedIndex = contentIndex.takeIf { it >= 0 }?.plus(1) ?: parent.childCount
                if (!alreadyAttached) {
                    parent.addView(label, expectedIndex, linearParams)
                } else {
                    val currentIndex = parent.indexOfChild(label)
                    if (currentIndex != expectedIndex) {
                        parent.removeView(label)
                        val insertAt = parent.indexOfChild(content).takeIf { it >= 0 }?.plus(1)
                            ?: parent.childCount
                        parent.addView(label, insertAt, linearParams)
                    } else if (!sameLinearLayoutParams(label.layoutParams, linearParams)) {
                        label.layoutParams = linearParams
                    }
                }
                if (avatarHidden) {
                    scheduleBottomDetailsAlignment(label, parent, alignmentView, details.isSelf, 0)
                } else {
                    cancelPendingPreDraw(label, messageDetailsPositionListeners)
                }
                refreshMessageDetailsColorsAfterAttach(label, newlyAttached = !alreadyAttached)
            }
            else -> return false
        }
        return true
    }

    private fun scheduleBottomDetailsAlignment(
        label: TextView,
        parent: ViewGroup,
        alignmentView: View,
        isSelf: Boolean,
        attempt: Int
    ) {
        if (!schedulePreDraw(label, parent, messageDetailsPositionListeners) {
            if (positionBottomDetailsLabel(label, parent, alignmentView, isSelf)) {
                label.visibility = View.VISIBLE
            } else if (attempt < MESSAGE_DETAILS_POSITION_MAX_RETRY) {
                scheduleBottomDetailsAlignment(label, parent, alignmentView, isSelf, attempt + 1)
            } else {
                label.visibility = View.VISIBLE
            }
        }) {
            label.visibility = View.VISIBLE
        }
    }

    private fun positionBottomDetailsLabel(
        label: TextView,
        parent: ViewGroup,
        alignmentView: View,
        isSelf: Boolean
    ): Boolean {
        if (label.parent !== parent || parent.width <= 0 || alignmentView.width <= 0 ||
            !isViewWithinRoot(alignmentView, parent)
        ) {
            return false
        }
        val bounds = Rect(0, 0, alignmentView.width, alignmentView.height)
        parent.offsetDescendantRectToMyCoords(alignmentView, bounds)
        val params = label.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        var changed = false
        if (isSelf) {
            val margin = (parent.width - parent.paddingRight - bounds.right).coerceAtLeast(0)
            if (params.marginEnd != margin || params.rightMargin != margin) {
                params.marginEnd = margin
                params.rightMargin = margin
                changed = true
            }
        } else {
            val margin = (bounds.left - parent.paddingLeft).coerceAtLeast(0)
            if (params.marginStart != margin || params.leftMargin != margin) {
                params.marginStart = margin
                params.leftMargin = margin
                changed = true
            }
        }
        if (changed) label.layoutParams = params
        return true
    }

    private fun refreshMessageDetailsColorsAfterAttach(label: TextView, newlyAttached: Boolean) {
        if (!newlyAttached) return
        applyMessageDetailsColors(label)
        schedulePreDraw(label, label, messageDetailsColorListeners) {
            if (label.parent != null && label.tag == TAG_MESSAGE_DETAILS_VIEW) {
                applyMessageDetailsColors(label)
            }
        }
        label.post {
            if (label.parent != null && label.tag == TAG_MESSAGE_DETAILS_VIEW) {
                applyMessageDetailsColors(label)
                label.invalidate()
            }
        }
    }

    private fun sameRelativeLayoutParams(current: ViewGroup.LayoutParams?, expected: RelativeLayout.LayoutParams): Boolean {
        val value = current as? RelativeLayout.LayoutParams ?: return false
        return value.width == expected.width &&
            value.height == expected.height &&
            value.leftMargin == expected.leftMargin &&
            value.topMargin == expected.topMargin &&
            value.rightMargin == expected.rightMargin &&
            value.bottomMargin == expected.bottomMargin &&
            value.marginStart == expected.marginStart &&
            value.marginEnd == expected.marginEnd &&
            value.rules.contentEquals(expected.rules)
    }

    private fun sameLinearLayoutParams(current: ViewGroup.LayoutParams?, expected: LinearLayout.LayoutParams): Boolean {
        val value = current as? LinearLayout.LayoutParams ?: return false
        return value.width == expected.width &&
            value.height == expected.height &&
            value.leftMargin == expected.leftMargin &&
            value.topMargin == expected.topMargin &&
            value.rightMargin == expected.rightMargin &&
            value.bottomMargin == expected.bottomMargin &&
            value.marginStart == expected.marginStart &&
            value.marginEnd == expected.marginEnd &&
            value.gravity == expected.gravity &&
            value.weight == expected.weight
    }

    private fun messageBindPosition(args: Array<Any?>?): Int? {
        args ?: return null
        return args.getOrNull(2) as? Int
            ?: args.firstOrNull { it is Int } as? Int
    }

    private fun messageBindHolder(args: Array<Any?>?): Any? {
        args ?: return null
        return args.getOrNull(0)?.takeIf { findRootView(it) != null }
            ?: args.firstOrNull { arg -> arg != null && findRootView(arg) != null }
    }

    private fun resolveMessageFromAdapter(bindObject: Any?, position: Int?): Any? {
        if (bindObject == null || position == null) return null
        val adapter = chattingAdapterFromBindObject(bindObject) ?: return null
        return resolveNativeMessage(adapterItem(adapter, position))
    }

    private fun chattingAdapterFromBindObject(source: Any): Any? {
        itemListFieldCache[source.javaClass]?.let { field ->
            KavaReflector.readField(field, source)?.let { return it }
        }
        val adapterClass = locateChattingDataAdapterClass()
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (adapterClass != null && !adapterClass.isAssignableFrom(field.type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                if (hasGetItemMethod(value.javaClass)) {
                    itemListFieldCache[source.javaClass] = field
                    return value
                }
            }
            current = current.superclass
        }
        if (adapterClass != null) return null
        current = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, source) ?: continue
                if (hasGetItemMethod(value.javaClass)) {
                    itemListFieldCache[source.javaClass] = field
                    return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun hasGetItemMethod(clazz: Class<*>): Boolean {
        return methodsRecursive(clazz).any { method ->
            method.name == "getItem" &&
                method.parameterTypes.size == 1 &&
                (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == java.lang.Integer::class.java)
        }
    }

    private fun findRootView(holder: Any): View? {
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        return KavaReflector.readField(findRootField(holder.javaClass), holder) as? View
    }

    private fun findRootField(clazz: Class<*>): Field? {
        holderRootFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "itemView" || it.type == View::class.java
            }
            if (field != null) {
                holderRootFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findHolderTextView(holder: Any, fieldName: String, cache: ConcurrentHashMap<Class<*>, Field>): TextView? {
        cache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? TextView }
        val field = fieldsRecursive(holder.javaClass).firstOrNull {
            it.name == fieldName && TextView::class.java.isAssignableFrom(it.type)
        } ?: return null
        cache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? TextView
    }

    private fun findHolderView(
        holder: Any,
        fieldName: String,
        cache: ConcurrentHashMap<Class<*>, Field>
    ): View? {
        cache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? View }
        val field = fieldsRecursive(holder.javaClass).firstOrNull {
            it.name == fieldName && View::class.java.isAssignableFrom(it.type)
        } ?: return null
        cache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? View
    }

    private fun findHolderAvatarView(holder: Any): View? {
        holderAvatarFieldCache[holder.javaClass]?.let {
            return KavaReflector.readField(it, holder) as? View
        }
        val fields = fieldsRecursive(holder.javaClass)
            .filter { View::class.java.isAssignableFrom(it.type) }
        val field = fields.firstOrNull { it.name.equals("avatarIV", ignoreCase = true) }
            ?: fields.firstOrNull { it.name.contains("avatar", ignoreCase = true) }
            ?: return null
        holderAvatarFieldCache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? View
    }

    private fun findAvatarDetailsAnchor(
        root: View,
        holder: Any,
        expectedHidden: Boolean = false
    ): AvatarDetailsAnchor? {
        // Some message holders keep several avatar fields and reuse the same holder class.
        // Prefer the avatar that is actually attached to this message row so a cached field
        // from another layout variant cannot move the label to a different horizontal anchor.
        val holderAvatar = findHolderAvatarView(holder)
            ?.takeIf { isViewWithinRoot(it, root) }
        val avatar = holderAvatar?.takeIf {
            it.javaClass.name == CHATTING_AVATAR_VIEW_CLASS &&
                (expectedHidden || isVisibleAvatar(it))
        } ?: findChattingAvatarView(root)
            ?: holderAvatar
            ?: return null
        var child = avatar
        var relativeParent: RelativeLayout? = null
        var mask: View? = null
        val directParent = avatar.parent as? View
        while (child !== root) {
            val parent = child.parent as? ViewGroup ?: return null
            if (parent.javaClass.name == MASK_LAYOUT_CLASS) {
                mask = parent
            }
            if (parent is RelativeLayout) {
                relativeParent = parent
            }
            if (parent === root) {
                val targetParent = relativeParent ?: return null
                val hidden = expectedHidden || avatar.visibility != View.VISIBLE || mask?.layoutParams?.width == 0
                val positionView = if (hidden) mask ?: directParent ?: avatar else avatar
                return AvatarDetailsAnchor(targetParent, positionView, hidden)
            }
            if (isScrollingContainer(parent)) return null
            child = parent
        }
        return null
    }

    private fun findChattingAvatarView(root: View): View? {
        findChattingAvatarView(root, visibleOnly = true)?.let { return it }
        return findChattingAvatarView(root, visibleOnly = false)
    }

    private fun findChattingAvatarView(root: View, visibleOnly: Boolean): View? {
        if (root.javaClass.name == CHATTING_AVATAR_VIEW_CLASS && (!visibleOnly || isVisibleAvatar(root))) {
            return root
        }
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findChattingAvatarView(root.getChildAt(index), visibleOnly)?.let { return it }
        }
        return null
    }

    private fun isVisibleAvatar(view: View): Boolean {
        return view.visibility == View.VISIBLE &&
            (view.width > 0 || view.measuredWidth > 0) &&
            (view.height > 0 || view.measuredHeight > 0)
    }

    private fun isScrollingContainer(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("ListView") || name.contains("ScrollView")
    }

    private fun removeTaggedMessageDetailsViews(root: View, keep: View? = null) {
        if (root !is ViewGroup) return
        var index = root.childCount - 1
        while (index >= 0) {
            val child = root.getChildAt(index)
            if (child !== keep && child is TextView && child.tag == TAG_MESSAGE_DETAILS_VIEW) {
                cancelPendingPreDraw(child, messageDetailsPositionListeners)
                cancelPendingPreDraw(child, messageDetailsColorListeners)
                synchronized(messageDetailsBindings) { messageDetailsBindings.remove(child) }
                synchronized(messageDetailsLabels) { messageDetailsLabels.remove(child) }
                root.removeViewAt(index)
            } else {
                removeTaggedMessageDetailsViews(child, keep)
            }
            index--
        }
    }

    private fun removeTaggedMessageDetailsViewsAround(root: View, keep: View? = null) {
        removeTaggedMessageDetailsViews(root, keep)
        var current = root.parent as? ViewGroup
        var depth = 0
        while (current != null && depth < 3 && !isScrollingContainer(current)) {
            removeTaggedMessageDetailsViews(current, keep)
            current = current.parent as? ViewGroup
            depth++
        }
    }

    private fun adapterItem(adapter: Any, position: Int): Any? {
        if (position < 0) return null
        itemMethodCache[adapter.javaClass]?.let { return KavaReflector.invoke(it, adapter, position) }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val method = KavaReflector.declaredMethods(current).firstOrNull {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Int::class.java) &&
                    (it.name == "J0" || it.name == "getItem" || it.name == "get")
            }
            if (method != null) {
                itemMethodCache[adapter.javaClass] = method
                KavaReflector.invoke(method, adapter, position)?.let { return it }
            }
            current = current.superclass
        }
        return adapterListItem(adapter, position)
    }

    private fun adapterListItem(adapter: Any, position: Int): Any? {
        itemListFieldCache[adapter.javaClass]?.let { field ->
            listItem(KavaReflector.readField(field, adapter), position)?.let { return it }
        }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "K" || it.name == "items" || it.name == "data" || it.name == "list"
            }
            if (field != null) {
                itemListFieldCache[adapter.javaClass] = field
                return listItem(KavaReflector.readField(field, adapter), position)
            }
            current = current.superclass
        }
        return findNestedListItem(
            adapter,
            position,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
    }

    private fun listItem(list: Any?, position: Int): Any? {
        if (list == null || position < 0) return null
        if (list is List<*> && position < list.size) return list[position]
        return KavaReflector.invoke(KavaReflector.findMethod(list.javaClass, "get", Integer.TYPE), list, position)
            ?: KavaReflector.invoke(KavaReflector.findMethod(list.javaClass, "get", Int::class.java), list, position)
    }

    private fun findNestedListItem(source: Any?, position: Int, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || position < 0 || depth > 3 || !visited.add(source)) return null
        listItem(source, position)?.takeIf { resolveNativeMessage(it) != null }?.let { return it }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        for (field in messageNestedFields(source.javaClass)) {
            val value = KavaReflector.readField(field, source) ?: continue
            findNestedListItem(value, position, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        if (source == null) return null
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        return resolveNativeMessage(
            source,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        val className = source.javaClass.name
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        if (source is Collection<*>) {
            for (item in source) resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            return null
        }
        for (field in messageNestedFields(source.javaClass)) {
            val value = KavaReflector.readField(field, source) ?: continue
            resolveNativeMessage(value, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun messageNestedFields(type: Class<*>): List<Field> {
        return messageNestedFieldCache[type] ?: fieldsRecursive(type)
            .filter { field ->
                val fieldType = field.type
                !fieldType.isPrimitive &&
                    !fieldType.isArray &&
                    fieldType != String::class.java &&
                    !Number::class.java.isAssignableFrom(fieldType)
            }
            .also { messageNestedFieldCache.putIfAbsent(type, it) }
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        val clazz = value.javaClass
        nativeMessageClassCache[clazz]?.let { return it }
        val result = KavaReflector.findFieldRecursive(clazz, "field_msgId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_msgSvrId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_type") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_content") != null
        nativeMessageClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun messageId(message: Any): Long {
        parseLong(readMessageValue(message, "getMsgId", "field_msgId", "msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "getMsgID", "msgID", "id"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "getId", "id", "field_msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "", "field_msgId", "msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "", "msgID", "id"))
            ?.takeIf { it > 0L }?.let { return it }
        return 0L
    }

    private fun installGroupMemberHistoryFromList(activity: Activity) {
        val listView = findListView(activity.window?.decorView as? ViewGroup)
        val adapter = listView?.adapter
        if (adapter != null) {
            if (injectGroupMemberHistoryPreference(activity, adapter)) return
        }
        injectGroupMemberHistoryPreference(activity, null)
    }

    private fun injectGroupMemberHistoryPreference(activity: Activity, adapter: Any?): Boolean {
        val groupId = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (!groupId.endsWith("@chatroom") && !groupId.endsWith("@im.chatroom")) return false
        if (!isGroupMemberProfile(activity, groupId)) return false
        val memberId = currentWxId(activity) ?: return false
        if (memberId.endsWith("@chatroom") || memberId == groupId) return false
        if (adapter != null && injectPreferenceByAdapter(activity, adapter, PREF_GROUP_MEMBER_HISTORY, "历史发言记录")) return true
        val screen = KavaReflector.invokeMethod(activity, "getPreferenceScreen") ?: return false
        val existing = findPreference(screen, PREF_GROUP_MEMBER_HISTORY)
        if (existing != null) {
            setGroupHistoryPreferenceClick(existing, activity)
            notifyPreferenceChanged(screen)
            return true
        }
        val prefClass = KavaReflector.loadClass("com.tencent.mm.ui.base.preference.Preference", context.hostClassLoader())
            ?: return false
        val pref = KavaReflector.newInstance(KavaReflector.findConstructor(prefClass, Context::class.java), activity)
            ?: return false
        setPreferenceKey(pref, PREF_GROUP_MEMBER_HISTORY)
        setPreferenceTitle(pref, "历史发言记录")
        setGroupHistoryPreferenceClick(pref, activity)
        val added = addPreference(screen, pref, screenInsertionIndex(screen))
        if (added) notifyPreferenceChanged(screen)
        return added
    }

    private fun groupHistoryClickedPreference(args: Array<Any?>?): Any? {
        val pref = args?.getOrNull(1)?.takeIf { isGroupHistoryPreference(it) }
        if (pref != null) return pref
        return args?.firstOrNull { isGroupHistoryPreference(it) }
    }

    private fun openGroupMemberHistory(activity: Activity) {
        val groupId = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (!groupId.endsWith("@chatroom") && !groupId.endsWith("@im.chatroom")) return
        val memberId = currentWxId(activity) ?: return
        if (memberId.endsWith("@chatroom") || memberId == groupId) return
        val clazz = KavaReflector.loadClass("com.tencent.mm.chatroom.ui.SelectedMemberChattingRecordUI", context.hostClassLoader())
            ?: return
        activity.startActivity(Intent(activity, clazz).apply {
            putExtra("RoomInfo_Id", groupId)
            putExtra("room_member", memberId)
            putExtra("title", "查看群成员消息历史")
        })
    }

    private fun currentWxId(activity: Activity): String? {
        val intent = activity.intent ?: return null
        return listOf(
            "Contact_User",
            "RoomInfo_Id",
            "room_name",
            "Contact_ChatRoomId",
            "Chat_User"
        ).firstNotNullOfOrNull { key ->
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
        }
    }

    private fun isGroupMemberProfile(activity: Activity, groupId: String): Boolean {
        val intent = activity.intent ?: return false
        if (intent.getStringExtra("Contact_ChatRoomId") == groupId) return true
        if (intent.getStringExtra("Chat_User") == groupId) return true
        if (intent.getStringExtra("Contact_User") == groupId) return false
        return intent.getStringExtra("Contact_ChatRoomId")?.endsWith("@chatroom") == true ||
            intent.getStringExtra("Chat_User")?.endsWith("@chatroom") == true
    }

    private fun messageType(message: Any): Int {
        return parseInt(readMessageValue(message, "getType", "field_type", "type"))
            ?: readInt(message, "field_type", "type")
    }

    private fun messageDetails(
        message: Any,
        knownType: Int? = null,
        includeContent: Boolean = false
    ): MessageDetails {
        val config = messageDetailsConfig
        val tokens = config.tokens
        val needsAtUsers = includeContent || tokens.any {
            it == "atUserList" || it == "rawAtUserList" || it == "mentionedUsers"
        }
        val needsContent = includeContent || needsAtUsers
        val content = if (needsContent) {
            readMessageString(message, "getContent", "field_content", "content")
        } else {
            ""
        }
        val talker = if (includeContent) {
            readMessageString(message, "getTalker", "field_talker", "talker")
        } else {
            ""
        }
        val msgSource = if (needsAtUsers) {
            readMessageString(message, "getMsgSource", "field_msgSource", "msgSource")
                .ifBlank { readMessageSourceFromLvBuffer(message) }
        } else {
            ""
        }
        val body = if (needsContent) stripGroupSenderPrefix(content) else ""
        val serverId = if (includeContent || "msgSvrId" in tokens) {
            parseLong(readMessageValue(message, "getMsgSvrId", "field_msgSvrId", "msgSvrId")) ?: 0L
        } else {
            0L
        }
        val messageId = if ("msgId" in tokens) messageId(message) else 0L
        val createTime = if ("time" in tokens || "relativeTime" in tokens) {
            parseLong(readMessageValue(message, "getCreateTime", "field_createTime", "createTime")) ?: 0L
        } else {
            0L
        }
        return MessageDetails(
            type = knownType ?: messageType(message),
            id = messageId,
            serverId = serverId,
            talker = talker,
            sender = senderOf(talker, content),
            content = content,
            body = body,
            msgSource = msgSource,
            atUserList = if (needsAtUsers) atUserList(msgSource, content) else "",
            nativeClassName = message.javaClass.simpleName,
            createTime = createTime,
            isSelf = (parseInt(readMessageValue(message, "isSend", "field_isSend", "isSend"))
                ?: parseInt(readMessageValue(message, "getIsSend", "field_isSend", "isSend"))
                ?: readInt(message, "field_isSend", "isSend")) == 1
        )
    }

    private fun showMessageDetailsDialog(context: Context, details: MessageDetails) {
        val raw = stripGroupSenderPrefix(details.content).ifBlank { details.body }.ifBlank { details.msgSource }
        val shouldFormat = prefs.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT_CONTENT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT_CONTENT
            ) || looksLikeXml(raw)
        val initialText = if (shouldFormat) {
            formatXmlLikeContent(raw)
        } else {
            raw
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(context, 8f)
            val padTop = dp(context, 4f)
            setPadding(padH, padTop, padH, 0)
        }

        val searchStatusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            text = "搜索"
        }
        val closeSearchButton = TextView(context).apply {
            text = "×"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(33, 150, 243))
            visibility = View.GONE
        }
        searchStatusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchStatusRow.addView(closeSearchButton, LinearLayout.LayoutParams(dp(context, 40f), ViewGroup.LayoutParams.WRAP_CONTENT))

        val searchPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(context, 4f), 0, 0)
        }
        searchPanel.addView(searchStatusRow)
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchInput = EditText(context).apply {
            hint = "搜索"
            setSingleLine(true)
            textSize = 12f
        }
        val replaceInput = EditText(context).apply {
            hint = "替换为"
            setSingleLine(true)
            textSize = 12f
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(replaceInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchPanel.addView(searchRow)
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun compactAction(title: String): TextView = TextView(context).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(33, 150, 243))
            val padH = dp(context, 8f)
            val padV = dp(context, 4f)
            setPadding(padH, padV, padH, padV)
        }
        val prevButton = compactAction("上一个")
        val nextButton = compactAction("下一个")
        val replaceButton = compactAction("替换")
        val replaceAllButton = compactAction("全部替换")
        actionRow.addView(prevButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(replaceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(replaceAllButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchPanel.addView(actionRow)
        root.addView(searchPanel)

        val mentionTargets = mentionTargets(details)
        if (mentionTargets.isNotEmpty()) {
            val mentionedUsersText = TextView(context).apply {
                text = buildString {
                    append("艾特对象")
                    mentionTargets.forEach { target -> append('\n').append(target) }
                }
                setTextIsSelectable(true)
                textSize = 13f
                setTextColor(Color.GRAY)
                includeFontPadding = true
                setPadding(0, dp(context, 4f), 0, dp(context, 8f))
            }
            root.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = mentionTargets.size > 4
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(
                        mentionedUsersText,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(dp(context, 120f), dp(context, 30f + mentionTargets.size * 20f))
                )
            )
        }

        val contentHeight = if (initialText.length > 600) {
            minOf(dp(context, 720f), (context.resources.displayMetrics.heightPixels * 0.78f).toInt())
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val viewText = TextView(context).apply {
            text = initialText
            setTextIsSelectable(true)
            textSize = 18f
            typeface = Typeface.DEFAULT
            includeFontPadding = true
            setPadding(0, 0, 0, 0)
        }
        val viewScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                viewText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val editor = EditText(context).apply {
            setText(initialText)
            setSelectAllOnFocus(false)
            setHorizontallyScrolling(false)
            minLines = 14
            maxLines = 24
            textSize = 18f
            typeface = Typeface.DEFAULT
            background = null
            setPadding(0, 0, 0, 0)
            includeFontPadding = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.START or Gravity.TOP
        }
        root.addView(
            viewScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                contentHeight
            )
        )

        var currentMatch = -1
        var editMode = false
        var lastSelectedEditorText = ""
        lateinit var dialog: AlertDialog
        fun updateLastSelectedEditorText() {
            val allText = editor.text?.toString().orEmpty()
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtMost(allText.length)
            if (end > start) {
                lastSelectedEditorText = allText.substring(start, end)
            }
        }
        fun editedPayload(): String {
            val displayText = editor.text?.toString().orEmpty()
            return if (displayText == initialText) raw else unformatXmlLikeContent(displayText)
        }
        fun updateButtons() {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (editMode) "搜索" else "编辑"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = if (editMode) "发送" else "关闭"
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "复制"
        }
        fun enterEditMode() {
            if (editMode) return
            editMode = true
            val previousScrollY = viewScroll.scrollY
            val visibleOffset = visibleTextOffset(viewText, previousScrollY)
            root.removeView(viewScroll)
            root.addView(
                editor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    contentHeight
                )
            )
            editor.requestFocus()
            editor.setSelection(visibleOffset.coerceIn(0, editor.text?.length ?: 0))
            editor.post {
                editor.scrollTo(0, previousScrollY)
            }
            status.text = "编辑"
            updateButtons()
        }
        fun matchPositions(): List<Int> {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) return emptyList()
            val text = editor.text?.toString().orEmpty()
            val result = ArrayList<Int>()
            var from = 0
            while (from <= text.length) {
                val index = text.indexOf(query, from)
                if (index < 0) break
                result += index
                from = index + query.length
            }
            return result
        }
        fun showSearchPanel(show: Boolean) {
            if (show && !editMode) enterEditMode()
            searchPanel.visibility = if (show) View.VISIBLE else View.GONE
            closeSearchButton.visibility = if (show) View.VISIBLE else View.GONE
            if (contentHeight > 0 && editMode) {
                editor.layoutParams = (editor.layoutParams as LinearLayout.LayoutParams).apply {
                    height = if (show) {
                        (contentHeight - dp(context, 96f)).coerceAtLeast(dp(context, 420f))
                    } else {
                        contentHeight
                    }
                }
            }
            if (show) {
                updateLastSelectedEditorText()
                val allText = editor.text?.toString().orEmpty()
                val selectedText = lastSelectedEditorText.takeIf { it.isNotEmpty() && allText.contains(it) }.orEmpty()
                if (selectedText.isNotEmpty()) {
                    searchInput.setText(selectedText)
                    searchInput.setSelection(searchInput.text?.length ?: 0)
                    currentMatch = -1
                }
                searchInput.requestFocus()
                status.text = "搜索结果: ${matchPositions().size}"
            } else {
                status.text = "搜索"
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentMatch = -1
                status.text = "搜索结果: ${matchPositions().size}"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        fun selectMatch(next: Boolean) {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return
            }
            val matches = matchPositions()
            status.text = "搜索结果: ${matches.size}"
            if (matches.isEmpty()) {
                currentMatch = -1
                status.text = "未找到: $query"
                return
            }
            currentMatch = if (currentMatch < 0) {
                if (next) 0 else matches.lastIndex
            } else if (next) {
                (currentMatch + 1) % matches.size
            } else {
                (currentMatch - 1 + matches.size) % matches.size
            }
            val start = matches[currentMatch]
            editor.setSelection(start, start + query.length)
            status.text = "${currentMatch + 1}/${matches.size}"
        }
        prevButton.setOnClickListener { selectMatch(false) }
        nextButton.setOnClickListener { selectMatch(true) }
        replaceButton.setOnClickListener {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return@setOnClickListener
            }
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
            val selected = editor.text?.subSequence(start, end)?.toString().orEmpty()
            if (selected != query) {
                selectMatch(true)
                return@setOnClickListener
            }
            val replacement = replaceInput.text?.toString().orEmpty()
            editor.text?.replace(start, end, replacement)
            currentMatch = -1
            status.text = "已替换 1 处"
            selectMatch(true)
        }
        replaceAllButton.setOnClickListener {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return@setOnClickListener
            }
            val replacement = replaceInput.text?.toString().orEmpty()
            val oldText = editor.text?.toString().orEmpty()
            val count = matchPositions().size
            status.text = "搜索结果: $count"
            if (count <= 0) {
                status.text = "未找到: $query"
                return@setOnClickListener
            }
            editor.setText(oldText.replace(query, replacement))
            editor.setSelection(0)
            currentMatch = -1
            status.text = "已替换 $count 处"
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(details.nativeClassName.ifBlank { "消息详情" })
            .setView(root)
            .setNeutralButton("编辑", null)
            .setNegativeButton("复制", null)
            .setPositiveButton("关闭", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnLongClickListener {
            if (editMode) showSearchPanel(searchPanel.visibility != View.VISIBLE) else enterEditMode()
            true
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (editMode) showSearchPanel(searchPanel.visibility != View.VISIBLE) else enterEditMode()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            if (editMode) {
                val allText = editor.text?.toString().orEmpty()
                val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtMost(allText.length)
                val hasSelection = end > start
                val selected = if (hasSelection) allText.substring(start, end) else editedPayload()
                copyMessageDetails(context, selected)
                if (hasSelection) {
                    status.text = "已复制选中内容"
                } else {
                    dialog.dismiss()
                }
            } else {
                val allText = viewText.text?.toString().orEmpty()
                val start = minOf(viewText.selectionStart, viewText.selectionEnd).coerceAtLeast(0)
                val end = maxOf(viewText.selectionEnd, viewText.selectionStart).coerceAtMost(allText.length)
                if (end > start) {
                    copyMessageDetails(context, allText.substring(start, end))
                    Toast.makeText(context, "已复制选中内容", Toast.LENGTH_SHORT).show()
                } else {
                    copyMessageDetails(context, raw)
                    dialog.dismiss()
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (!editMode) {
                dialog.dismiss()
            } else {
                val sent = sendEditedMessageDetails(context, details, editedPayload())
                if (sent) dialog.dismiss()
            }
        }
        status.setOnClickListener {
            showSearchPanel(searchPanel.visibility != View.VISIBLE)
        }
        status.setOnLongClickListener {
            showSearchPanel(searchPanel.visibility != View.VISIBLE)
            true
        }
        closeSearchButton.setOnClickListener {
            showSearchPanel(false)
        }
        closeSearchButton.setOnLongClickListener {
            showSearchPanel(false)
            true
        }
    }

    private fun copyMessageDetails(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("消息详情", text))
    }

    private fun visibleTextOffset(textView: TextView, scrollY: Int): Int {
        val textLength = textView.text?.length ?: 0
        val layout = textView.layout ?: return 0
        if (layout.lineCount <= 0) return 0
        val line = layout.getLineForVertical(scrollY.coerceAtLeast(0))
            .coerceIn(0, layout.lineCount - 1)
        return layout.getLineStart(line).coerceIn(0, textLength)
    }

    private fun sendEditedMessageDetails(context: Context, details: MessageDetails, text: String): Boolean {
        val payload = text.trim()
        val talker = details.talker.ifBlank { WeChatApis.chatPage()?.currentTalker().orEmpty() }
        if (talker.isBlank() || payload.isBlank()) {
            Toast.makeText(context, "发送失败：会话或内容为空", Toast.LENGTH_SHORT).show()
            return false
        }
        val lower = payload.lowercase()
        if (isImageXml(lower)) {
            Toast.makeText(context, "图片 XML 不能直接发送，请下载后走图片发送", Toast.LENGTH_SHORT).show()
            return false
        }
        val sender = WeChatApis.message().sender()
        val ok = when {
            isAppMsgXml(lower) -> sender?.sendXml(talker, payload) == true
            looksLikeXml(payload) -> {
                Toast.makeText(context, "当前只支持 AppMsg 卡片 XML 发送", Toast.LENGTH_SHORT).show()
                return false
            }
            else -> sender?.sendText(talker, payload) == true
        }
        Toast.makeText(context, if (ok) "已发送" else "发送失败", Toast.LENGTH_SHORT).show()
        return ok
    }

    private fun isAppMsgXml(lower: String): Boolean {
        return lower.contains("<appmsg") && lower.contains("</appmsg>")
    }

    private fun isImageXml(lower: String): Boolean {
        return lower.contains("<img") && !isAppMsgXml(lower)
    }

    private fun looksLikeXml(text: String): Boolean {
        val value = text.trim()
        return value.startsWith("<") && value.endsWith(">") && value.indexOf('>') > 1
    }

    private fun formatMessageDetailsLabel(details: MessageDetails): String {
        val config = messageDetailsConfig
        val createTime = details.createTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        var formattedTime: String? = null
        var normalizedType: Int? = null
        fun formatTime(): String {
            return formattedTime ?: LocalDateTime
                .ofInstant(Instant.ofEpochMilli(createTime), ZoneId.systemDefault())
                .format(config.timeFormatter)
                .also { formattedTime = it }
        }
        if (config.format == HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT) return formatTime()
        return replaceMessageDetailsTokens(config.format) { name ->
            when (name) {
                "time" -> formatTime()
                "relativeTime" -> relativeMessageTime(createTime)
                "type" -> messageTypeLabel(
                    normalizedType ?: WeChatMessageTypes.normalize(details.type).also { normalizedType = it }
                )
                "typeDec" -> details.type.toString()
                "typeHex" -> "0x" + Integer.toUnsignedString(details.type, 16)
                "msgId" -> details.id.toString()
                "msgSvrId" -> details.serverId.toString()
                "atUserList" -> semanticAtUserList(details)
                "rawAtUserList" -> details.atUserList
                "mentionedUsers" -> mentionedUsersLabel(details)
                else -> null
            }
        }
    }

    private fun messageTypeLabel(type: Int): String = when (type) {
        37 -> "好友申请"
        WeChatMessageTypes.TEXT -> "文字"
        WeChatMessageTypes.IMAGE -> "图片"
        WeChatMessageTypes.VOICE -> "语音"
        42, 66 -> "名片"
        WeChatMessageTypes.VIDEO -> "视频"
        62 -> "小视频"
        WeChatMessageTypes.EMOJI -> "表情"
        WeChatMessageTypes.LOCATION -> "位置"
        WeChatMessageTypes.APP -> "链接/卡片"
        50, 51, 52, 53 -> "通话消息"
        WeChatMessageTypes.SYSTEM -> "系统消息"
        WeChatMessageTypes.RECALLED -> "撤回消息"
        else -> "未知消息"
    }

    private fun relativeMessageTime(createTime: Long): String {
        val zone = ZoneId.systemDefault()
        val days = LocalDate.now(zone).toEpochDay() -
            Instant.ofEpochMilli(createTime).atZone(zone).toLocalDate().toEpochDay()
        if (days > 1L) return "${days}天前"
        if (days == 1L) return "昨天"
        val diff = System.currentTimeMillis() - createTime
        if (diff <= 0L) return "刚刚"
        val minutes = diff / 60000L
        val hours = diff / 3600000L
        return when {
            minutes < 1L -> "刚刚"
            hours < 1L -> "${minutes}分钟前"
            else -> "${hours.coerceAtLeast(1L)}小时前"
        }
    }

    private fun mentionedUsersLabel(details: MessageDetails): String {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> "@所有人"
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> "群公告"
            WeChatMessage.AtMentionType.AT_ME -> "@我"
            WeChatMessage.AtMentionType.OTHERS -> "@${parseAtUserIds(details.atUserList).size}人"
            WeChatMessage.AtMentionType.NONE -> ""
        }
    }

    private fun semanticAtUserList(details: MessageDetails): String {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> "@所有人"
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> "群公告全体"
            else -> details.atUserList
        }
    }

    private fun mentionTargets(details: MessageDetails): List<String> {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> listOf("@所有人（全体群成员）")
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> listOf("群公告全体")
            else -> parseAtUserIds(details.atUserList)
                .filterNot { it == "announcement@all" || it == "notify@all" }
        }
    }

    private fun atMentionType(details: MessageDetails): WeChatMessage.AtMentionType {
        return WeChatMessage.classifyAtMention(
            details.msgSource,
            details.body,
            WeChatApis.account()?.selfWxId().orEmpty()
        )
    }

    private fun parseAtUserIds(atUserList: String): List<String> {
        return atUserList.split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun atUserList(msgSource: String, content: String): String {
        val source = msgSource.ifBlank { content }
        val fromXml = AT_USER_LIST_PATTERN
            .find(source)
            ?.let { match -> match.groupValues.getOrNull(1).orEmpty().ifBlank { match.groupValues.getOrNull(2).orEmpty() } }
            ?.trim()
            .orEmpty()
        if (fromXml.isNotBlank()) return fromXml
        WeChatMessage.msgSourceValue(source, ".msgsource.atuserlist").takeIf { it.isNotBlank() }?.let { return it }
        WeChatMessage.msgSourceValue(source, "atuserlist").takeIf { it.isNotBlank() }?.let { return it }
        return when {
            source.contains("announcement@all") -> "announcement@all"
            source.contains("notify@all") -> "notify@all"
            else -> ""
        }
    }

    private fun replaceMessageDetailsTokens(source: String, valueFor: (String) -> String?): String {
        return MESSAGE_DETAILS_TOKEN_PATTERN.replace(source) { match ->
            val name = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
            valueFor(name) ?: match.value
        }
    }

    private fun readMessageSourceFromLvBuffer(message: Any): String {
        val buffer = readMessageValue(message, "getLvBuffer", "field_lvbuffer", "lvbuffer") as? ByteArray
            ?: return ""
        if (buffer.size < 9 || buffer.first() != '{'.code.toByte() || buffer.last() != '}'.code.toByte()) {
            return ""
        }
        return runCatching {
            val bytes = ByteBuffer.wrap(buffer).apply { position(1) }
            val firstLength = readLvBufferLength(bytes) ?: return@runCatching ""
            if (bytes.remaining() < firstLength) return@runCatching ""
            bytes.position(bytes.position() + firstLength)
            if (bytes.remaining() < 4) return@runCatching ""
            bytes.position(bytes.position() + 4)
            val sourceLength = readLvBufferLength(bytes) ?: return@runCatching ""
            if (sourceLength == 0 || bytes.remaining() < sourceLength) return@runCatching ""
            val sourceBytes = ByteArray(sourceLength)
            bytes.get(sourceBytes)
            sourceBytes.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun readLvBufferLength(buffer: ByteBuffer): Int? {
        if (buffer.remaining() < 2) return null
        val length = buffer.short.toInt() and 0xffff
        return length.takeIf { it <= LV_BUFFER_STRING_MAX_LENGTH }
    }

    private fun configuredAvatarHidden(isSelf: Boolean): Boolean {
        return if (isSelf) hideSelfAvatar else hideOtherAvatar
    }

    private fun messageContentAnchor(holder: Any, label: TextView): BottomDetailsAnchor? {
        val labelParent = label.parent as? ViewGroup ?: return null
        if (labelParent !is RelativeLayout && labelParent !is LinearLayout) return null
        holderMainContentView(holder, labelParent)?.let { mainContent ->
            val layoutView = directChildOf(labelParent, mainContent) ?: return@let
            return BottomDetailsAnchor(labelParent, layoutView, mainContent)
        }
        messageContentView(holder, labelParent, label)?.let { content ->
            val layoutView = directChildOf(labelParent, content) ?: return@let
            return BottomDetailsAnchor(labelParent, layoutView, content)
        }
        return null
    }

    private fun holderMainContentView(holder: Any, parent: ViewGroup): View? {
        val holderClass = holder.javaClass
        val method = holderMainContainerMethodCache[holderClass] ?: run {
            if (holderWithoutMainContainerMethod.contains(holderClass)) return null
            val resolved = methodsRecursive(holderClass).firstOrNull { candidate ->
                candidate.name == "getMainContainerView" &&
                    candidate.parameterTypes.isEmpty() &&
                    View::class.java.isAssignableFrom(candidate.returnType)
            }
            if (resolved == null) {
                holderWithoutMainContainerMethod += holderClass
                return null
            }
            holderMainContainerMethodCache[holderClass] = resolved
            resolved
        }
        val view = KavaReflector.invoke(method, holder) as? View ?: return null
        if (view.visibility == View.GONE || !isViewWithinRoot(view, parent)) return null
        val clickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache)
        if (view === clickArea && !hasMessageSizedBounds(view, parent)) return null
        return view
    }

    private fun hasMessageSizedBounds(view: View, parent: ViewGroup): Boolean {
        if (view.width <= 0 || parent.width <= 0 || !isViewWithinRoot(view, parent)) return false
        val bounds = Rect(0, 0, view.width, view.height)
        parent.offsetDescendantRectToMyCoords(view, bounds)
        val inset = dp(view.context, 8f)
        return bounds.width() < parent.width - parent.paddingLeft - parent.paddingRight - inset ||
            bounds.left > parent.paddingLeft + inset ||
            bounds.right < parent.width - parent.paddingRight - inset
    }

    private fun avatarDetailsContentView(holder: Any, parent: RelativeLayout): View? {
        holderMainContentView(holder, parent)?.let { return it }
        return messageContentView(holder, parent)
    }

    private fun messageContentView(holder: Any, parent: ViewGroup, excluded: View? = null): View? {
        val rootView = findRootView(holder)
        val clickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache)
        val fields = holderContentFieldCache.getOrPut(holder.javaClass) {
            fieldsRecursive(holder.javaClass)
                .filter { View::class.java.isAssignableFrom(it.type) }
                .filterNot { isAuxiliaryMessageViewField(it.name) }
        }
        return fields
            .asSequence()
            .mapNotNull { KavaReflector.readField(it, holder) as? View }
            .distinct()
            .filter {
                it !== excluded && it !== rootView && it !== clickArea &&
                    it.visibility != View.GONE && isViewWithinRoot(it, parent)
            }
            .maxByOrNull { viewScoreForBottomDetails(it) }
            ?.takeIf { viewScoreForBottomDetails(it) > 0 }
    }

    private fun isAuxiliaryMessageViewField(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "timetv" ||
            lower == "avatariv" ||
            lower == "usertv" ||
            lower == "clickarea" ||
            lower.contains("time") ||
            lower.contains("avatar") ||
            lower.contains("click") ||
            lower.contains("history") ||
            lower.contains("nomore") ||
            lower.contains("mask") ||
            lower.contains("checkbox") ||
            lower.contains("check")
    }

    private fun directChildOf(parent: ViewGroup, view: View): View? {
        if (view.parent === parent) return view
        var current: View = view
        var depth = 0
        while (depth < 8) {
            val next = current.parent as? View ?: return null
            if (next.parent === parent) return next
            current = next
            depth++
        }
        return null
    }

    private fun viewScoreForBottomDetails(view: View): Int {
        val params = view.layoutParams
        val width = view.width.takeIf { it > 0 }
            ?: view.measuredWidth.takeIf { it > 0 }
            ?: params?.width?.takeIf { it > 0 }
            ?: 0
        val height = view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
            ?: params?.height?.takeIf { it > 0 }
            ?: 0
        var score = width.coerceAtMost(dp(view.context, 420f)) + height.coerceAtMost(dp(view.context, 420f))
        if (view is TextView) score += 80
        if (view is ViewGroup) score += view.childCount.coerceAtMost(8) * 20
        if (params?.width == ViewGroup.LayoutParams.WRAP_CONTENT || params?.width == ViewGroup.LayoutParams.MATCH_PARENT) score += 40
        if (view.contentDescription?.toString().orEmpty().contains("avatar", ignoreCase = true)) score -= 600
        if (width in 1..dp(view.context, 72f) && height in 1..dp(view.context, 72f)) score -= 500
        return score
    }

    private fun ensureViewId(view: View) {
        if (view.id == View.NO_ID) {
            view.id = View.generateViewId()
        }
    }

    private fun messageDetailsResolvedTextColor(context: Context): Int {
        val config = messageDetailsConfig
        return if (isNightMode(context)) config.darkTextColor else config.lightTextColor
    }

    private fun messageDetailsResolvedBgColor(context: Context): Int {
        val config = messageDetailsConfig
        return if (isNightMode(context)) config.darkBgColor else config.lightBgColor
    }

    private fun resolveMessageDetailsColor(
        currentValue: String,
        currentDefault: String,
        alternateValue: String,
        alternateDefault: String,
        fallback: Int
    ): Int {
        val currentColor = parseColorSettingOrNull(currentValue)
        val alternateColor = parseColorSettingOrNull(alternateValue)
        val currentIsDefault = isSameColor(currentValue, currentDefault)
        val alternateIsDefault = isSameColor(alternateValue, alternateDefault)
        return when {
            currentColor != null && !currentIsDefault -> currentColor
            alternateColor != null && !alternateIsDefault -> alternateColor
            currentColor != null -> currentColor
            alternateColor != null -> alternateColor
            else -> fallback
        }
    }

    private fun isSameColor(value: String, other: String): Boolean {
        val color = parseColorSettingOrNull(value)
        val otherColor = parseColorSettingOrNull(other)
        return color != null && otherColor != null && color == otherColor
    }

    private fun messageDetailsTextColor(night: Boolean): String {
        return if (night) {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT
            ).orEmpty()
        } else {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT
            ).orEmpty()
        }
    }

    private fun messageDetailsBgColor(night: Boolean): String {
        return if (night) {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG
            ).orEmpty()
        } else {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG
            ).orEmpty()
        }
    }

    private fun isNightMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun parseColorSettingOrNull(value: String): Int? {
        return runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }

    private fun formatXmlLikeContent(raw: String): String {
        return raw.replace("><", ">\n<")
    }

    private fun unformatXmlLikeContent(text: String): String {
        return text.replace(Regex(">\\s+<"), "><")
    }

    private fun senderOf(talker: String, content: String): String {
        if (talker.endsWith("@chatroom")) {
            val idx = content.indexOf(":\n")
            if (idx > 0) return content.substring(0, idx)
        }
        return talker
    }

    private fun stripGroupSenderPrefix(content: String): String {
        val idx = content.indexOf(":\n")
        val prefix = if (idx > 0) content.substring(0, idx) else ""
        return if (prefix.isNotBlank() && !prefix.contains("<") && !prefix.contains("\n")) {
            content.substring(idx + 2)
        } else {
            content
        }
    }

    private fun readMessageString(source: Any, getter: String, fieldName: String, fallbackField: String): String {
        return readMessageValue(source, getter, fieldName, fallbackField) as? String ?: ""
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        val key = MessageAccessorKey(source.javaClass, getter, fieldName, fallbackField)
        val accessor = messageAccessorCache[key] ?: MessageAccessor(
            getter = KavaReflector.findMethod(source.javaClass, getter),
            primaryField = KavaReflector.findFieldRecursive(source.javaClass, fieldName),
            fallbackField = KavaReflector.findFieldRecursive(source.javaClass, fallbackField)
        ).also { messageAccessorCache.putIfAbsent(key, it) }
        KavaReflector.invoke(accessor.getter, source)?.let { return it }
        KavaReflector.readField(accessor.primaryField, source)?.let { return it }
        return KavaReflector.readField(accessor.fallbackField, source)
    }

    private fun readLong(receiver: Any?, vararg names: String): Long {
        if (receiver == null) return 0L
        names.forEach { name ->
            val value = KavaReflector.readField(receiver, name)
            if (value is Number) return value.toLong()
        }
        return 0L
    }

    private fun readInt(receiver: Any?, vararg names: String): Int {
        if (receiver == null) return 0
        names.forEach { name ->
            val value = KavaReflector.readField(receiver, name)
            if (value is Number) return value.toInt()
        }
        return 0
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    private fun preferenceKey(pref: Any?): String {
        if (pref == null) return ""
        for (name in arrayOf("r", "q")) {
            (KavaReflector.readField(pref, name) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        KavaReflector.declaredFields(pref.javaClass).forEach { field ->
            if (field.type == String::class.java) {
                val value = KavaReflector.readField(field, pref) as? String
                if (!value.isNullOrBlank() && value == PREF_GROUP_MEMBER_HISTORY) return value
            }
        }
        return KavaReflector.invokeMethod(pref, "getKey") as? String ?: ""
    }

    private fun findPreference(screen: Any, key: String): Any? {
        return methodsRecursive(screen.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == String::class.java &&
                method.returnType.name.contains("Preference")
            ) {
                KavaReflector.invoke(method, screen, key)?.takeIf { preferenceKey(it) == key }
            } else null
        }
    }

    private fun setPreferenceKey(pref: Any, key: String) {
        KavaReflector.invokeMethod(pref, "setKey", key)
        KavaReflector.writeField(pref, "r", key)
        KavaReflector.writeField(pref, "q", key)
        KavaReflector.declaredFields(pref.javaClass)
            .firstOrNull { it.type == String::class.java && !Modifier.isFinal(it.modifiers) }
            ?.let { KavaReflector.writeField(it, pref, key) }
    }

    private fun setPreferenceTitle(pref: Any, title: String) {
        setPreferenceText(pref, title, preferSecond = true)
    }

    private fun setPreferenceSummary(pref: Any, summary: String) {
        setPreferenceText(pref, summary, preferSecond = false)
    }

    private fun setPreferenceText(pref: Any, text: String, preferSecond: Boolean) {
        if (preferSecond) {
            KavaReflector.writeField(pref, "i", text)
            KavaReflector.writeField(pref, "h", text)
        } else {
            KavaReflector.writeField(pref, "n", text)
            KavaReflector.writeField(pref, "m", text)
        }
        val methods = methodsRecursive(pref.javaClass)
            .filter {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0].isAssignableFrom(String::class.java) ||
                        it.parameterTypes[0].isAssignableFrom(CharSequence::class.java)) &&
                    it.returnType == java.lang.Void.TYPE
            }
        val method = if (preferSecond) methods.getOrNull(1) ?: methods.firstOrNull() else methods.firstOrNull()
        KavaReflector.invoke(method, pref, text)
    }

    private fun injectPreferenceByAdapter(activity: Activity, adapter: Any, key: String, title: String): Boolean {
        findAdapterPreference(adapter, key, title)?.let { pref ->
            setPreferenceKey(pref, key)
            setPreferenceTitle(pref, title)
            setPreferenceSummary(pref, "")
            setGroupHistoryPreferenceClick(pref, activity)
            ensureAdapterPreferenceIndex(adapter, pref, adapterInsertionIndex(adapter, pref))
            return true
        }
        if (injectingGroupHistory) return false
        injectingGroupHistory = true
        val inserted = try {
            val prefClass = KavaReflector.loadClass("com.tencent.mm.ui.base.preference.Preference", context.hostClassLoader())
                ?: return false
            val pref = KavaReflector.newInstance(KavaReflector.findConstructor(prefClass, Context::class.java), activity)
                ?: return false
            setPreferenceKey(pref, key)
            setPreferenceTitle(pref, title)
            setPreferenceSummary(pref, "")
            setGroupHistoryPreferenceClick(pref, activity)
            copyLayoutResource(firstAdapterPreference(adapter), pref)
            val index = adapterInsertionIndex(adapter, null)
            methodsRecursive(adapter.javaClass).any { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[1] == Integer.TYPE &&
                    method.parameterTypes[0].isAssignableFrom(pref.javaClass) &&
                    KavaReflector.invokeSuccessfully(method, adapter, pref, index)
            }
        } finally {
            injectingGroupHistory = false
        }
        if (!inserted) return false
        notifyAdapterChanged(adapter)
        return true
    }

    private fun firstAdapterPreference(adapter: Any): Any? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            adapterPreferenceAt(adapter, i)?.let { return it }
        }
        return null
    }

    private fun findAdapterPreference(adapter: Any, key: String, title: String? = null): Any? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(adapter, i) ?: continue
            if (preferenceKey(pref) == key || (!title.isNullOrBlank() && preferenceTitle(pref) == title)) return pref
        }
        return null
    }

    private fun isGroupHistoryPreference(pref: Any?): Boolean {
        if (pref == null) return false
        return preferenceKey(pref) == PREF_GROUP_MEMBER_HISTORY ||
            preferenceTitle(pref) == "历史发言记录"
    }

    private fun setGroupHistoryPreferenceClick(pref: Any, activity: Activity?) {
        if (activity == null) return
        methodsRecursive(pref.javaClass)
            .firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                    View.OnClickListener::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == java.lang.Void.TYPE
            }
            ?.let { method ->
                KavaReflector.invoke(method, pref, View.OnClickListener {
                    openGroupMemberHistory(activity)
                })
            }
    }

    private fun preferenceTitle(pref: Any?): String {
        if (pref == null) return ""
        for (name in arrayOf("i", "h")) {
            (KavaReflector.readField(pref, name) as? CharSequence)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return methodsRecursive(pref.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.isEmpty() && CharSequence::class.java.isAssignableFrom(method.returnType)) {
                (KavaReflector.invoke(method, pref) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
            } else null
        }.orEmpty()
    }

    private fun ensureAdapterPreferenceIndex(adapter: Any, pref: Any, targetIndex: Int): Boolean {
        val currentIndex = adapterPreferenceIndex(adapter, pref) ?: return false
        val boundedTarget = targetIndex.coerceIn(0, (adapterCount(adapter) - 1).coerceAtLeast(0))
        if (currentIndex == boundedTarget) return true
        if (!removePreference(adapter, pref)) return false
        return addPreference(adapter, pref, targetIndex.coerceIn(0, adapterCount(adapter).coerceAtLeast(0)))
    }

    private fun adapterPreferenceIndex(adapter: Any, target: Any): Int? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            if (adapterPreferenceAt(adapter, i) === target) return i
        }
        return null
    }

    private fun removePreference(adapter: Any, pref: Any): Boolean {
        return methodsRecursive(adapter.javaClass).any { method ->
            if (method.parameterTypes.size != 1 ||
                !method.parameterTypes[0].isAssignableFrom(pref.javaClass) ||
                (method.returnType != java.lang.Boolean.TYPE && method.returnType != java.lang.Boolean::class.java)
            ) {
                false
            } else {
                KavaReflector.invoke(method, adapter, pref) == true &&
                    adapterPreferenceIndex(adapter, pref) == null
            }
        }
    }

    private fun adapterInsertionIndex(adapter: Any, ignored: Any?): Int {
        val count = adapterCount(adapter)
        var ignoredBefore = 0
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(adapter, i)
            if (pref === ignored) {
                ignoredBefore++
                continue
            }
            val adjustedIndex = i - ignoredBefore
            if (isProfileIdPreference(pref)) return adjustedIndex + 1
            val key = preferenceKey(pref)
            if (isProfileBusinessPreference(pref, key)) return adjustedIndex
        }
        return (count - ignoredBefore).coerceAtLeast(0)
    }

    private fun screenInsertionIndex(screen: Any): Int {
        val count = adapterCount(screen)
        for (i in 0 until count) {
            if (isProfileIdPreference(adapterPreferenceAt(screen, i))) return i + 1
        }
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(screen, i)
            if (isProfileBusinessPreference(pref, preferenceKey(pref))) return i
        }
        return count.coerceAtLeast(0)
    }

    private fun isProfileIdPreference(pref: Any?): Boolean {
        if (pref == null) return false
        if (preferenceKey(pref) == PREF_PROFILE_ID) return true
        return preferenceTitle(pref).trim().startsWith("ID:")
    }

    private fun isProfileBusinessPreference(pref: Any?, key: String): Boolean {
        if (key == "contact_info_sns" || key == "contact_info_more" || key.contains("permission")) return true
        val title = preferenceTitle(pref)
        return title.contains("设置备注") ||
            title.contains("标签") ||
            title.contains("朋友圈") ||
            title.contains("添加到通讯录")
    }

    private fun adapterPreferenceAt(adapter: Any, index: Int): Any? {
        return methodsRecursive(adapter.javaClass).firstNotNullOfOrNull { method ->
            if (method.name == "getItem" &&
                method.parameterTypes.size == 1 &&
                (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == java.lang.Integer::class.java)
            ) {
                KavaReflector.invoke(method, adapter, index)
            } else null
        }
    }

    private fun adapterCount(adapter: Any): Int {
        return methodsRecursive(adapter.javaClass).firstNotNullOfOrNull { method ->
            if (method.name == "getCount" && method.parameterTypes.isEmpty()) {
                (KavaReflector.invoke(method, adapter) as? Number)?.toInt()
            } else null
        } ?: 0
    }

    private fun copyLayoutResource(from: Any?, to: Any) {
        if (from == null) return
        val layout = KavaReflector.invokeMethod(from, "getLayoutResource") as? Number ?: return
        if (layout.toInt() == 0) return
        KavaReflector.invokeMethod(to, "setLayoutResource", layout.toInt())
    }

    private fun notifyAdapterChanged(adapter: Any) {
        injectingGroupHistory = true
        try {
            KavaReflector.invokeMethod(adapter, "notifyDataSetChanged")
            methodsRecursive(adapter.javaClass)
                .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty() }
                ?.let { KavaReflector.invoke(it, adapter) }
        } finally {
            injectingGroupHistory = false
        }
    }

    private fun notifyPreferenceChanged(screen: Any) {
        KavaReflector.invokeMethod(screen, "notifyDataSetChanged")
        methodsRecursive(screen.javaClass)
            .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty() }
            ?.let { KavaReflector.invoke(it, screen) }
    }

    private fun findListView(group: ViewGroup?): android.widget.ListView? {
        if (group == null) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.ListView) return child
            if (child is ViewGroup) findListView(child)?.let { return it }
        }
        return null
    }

    private fun addPreference(screen: Any, pref: Any, index: Int): Boolean {
        return methodsRecursive(screen.javaClass).any { method ->
            if (method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Integer.TYPE &&
                method.parameterTypes[0].isAssignableFrom(pref.javaClass)
            ) {
                KavaReflector.invokeSuccessfully(method, screen, pref, index)
            } else false
        }
    }

    private fun methodsRecursive(clazz: Class<*>?): List<Method> {
        val methods = ArrayList<Method>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            methods += KavaReflector.declaredMethods(current)
            current = current.superclass
        }
        return methods
    }

    private fun fieldsRecursive(clazz: Class<*>?): List<java.lang.reflect.Field> {
        val fields = ArrayList<java.lang.reflect.Field>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            fields += KavaReflector.declaredFields(current)
            current = current.superclass
        }
        return fields
    }

    companion object {
        private const val PREF_GROUP_MEMBER_HISTORY = "hchat_group_member_history"
        private const val TAG_MESSAGE_DETAILS_VIEW = "hchat_message_details_view"
        private const val LV_BUFFER_STRING_MAX_LENGTH = 3072
        private val MESSAGE_DETAILS_TOKEN_PATTERN = Regex("\\$\\{([A-Za-z][A-Za-z0-9]*)\\}|\\$([A-Za-z][A-Za-z0-9]*)")
        private val AT_USER_LIST_PATTERN = Regex(
            "<atuserlist><!\\[CDATA\\[(.*?)]]></atuserlist>|<atuserlist>(.*?)</atuserlist>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val DEFAULT_MESSAGE_DETAILS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT)
        private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"
        private const val CHATTING_AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"
        private const val RED_PACKET_RECORD_TIME_ID_NAME = "j6q"
        private val RED_PACKET_TIME_SECONDS_RANGE = 1_262_304_000L..4_102_444_800L
        private val RED_PACKET_TIME_MILLIS_RANGE = 1_262_304_000_000L..4_102_444_800_000L
        private const val PREF_PROFILE_ID = "hchat_profile_id"
        private const val MESSAGE_DETAILS_MAX_RETRY = 2
        private const val MESSAGE_DETAILS_POSITION_MAX_RETRY = 4
        private val MESSAGE_DETAILS_COLOR_KEYS = setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT
        )
        private val MESSAGE_DETAILS_LAYOUT_KEYS = setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_POSITION,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_AVATAR_GAP,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_LEFT_MARGIN,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_RIGHT_MARGIN
        )
        private val MESSAGE_DETAILS_REBIND_KEYS = MESSAGE_DETAILS_LAYOUT_KEYS + setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_CLICK_SHOW
        )
        private val MESSAGE_DETAILS_CONFIG_KEYS = MESSAGE_DETAILS_COLOR_KEYS + MESSAGE_DETAILS_REBIND_KEYS + setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS
        )
    }
}

private data class MessageDetails(
    val type: Int,
    val id: Long,
    val serverId: Long,
    val talker: String,
    val sender: String,
    val content: String,
    val body: String,
    val msgSource: String,
    val atUserList: String,
    val nativeClassName: String,
    val createTime: Long,
    val isSelf: Boolean
)
