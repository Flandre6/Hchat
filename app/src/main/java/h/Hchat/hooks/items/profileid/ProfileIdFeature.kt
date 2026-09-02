package h.Hchat.hooks.items.profileid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.WeChatIdRules
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector

class ProfileIdFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "资料页显示ID"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ProfileIdSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "profile_id"
        private const val TAG = "[Hchat:ProfileId]"
        private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        private const val CHATROOM_INFO_UI = "com.tencent.mm.chatroom.ui.ChatroomInfoUI"
        private const val CONTACT_USER = "Contact_User"
        private const val CONTACT_USERNAME = "Contact_Username"
        private const val ROOM_INFO_ID = "RoomInfo_Id"
        private const val INJECTED_TAG = "Hchat:ProfileId:Row"
        private const val INJECTED_VALUE_TAG = "Hchat:ProfileId:Value:"
        private const val CHATROOM_PREF_KEY = "hchat_profile_id"
        private const val CHATROOM_NAME_PREF_KEY = "room_name"
        private const val CHATROOM_BIND_RETRY_LIMIT = 8
        private val CHATROOM_ANCHOR_PREF_KEYS = arrayOf(
            "expand_room_member",
            "see_room_member",
            "room_name"
        )
        private const val LABEL = "ID"

        @Volatile
        private var installed = false

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, ProfileIdSettings.PREFS_NAME)
            return sp.getBoolean(ProfileIdSettings.KEY_ENABLE, ProfileIdSettings.DEFAULT_ENABLE)
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null || installed) return
            synchronized(this) {
                if (installed) return
                hookContactProfile(context, classLoader)
                hookChatroomProfile(context, classLoader)
                installed = true
            }
        }

        private fun hookContactProfile(context: Context, classLoader: ClassLoader) {
            val clazz = KavaReflector.loadClass(CONTACT_INFO_UI, classLoader)
            if (clazz == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到好友资料页类")
                return
            }
            hookAfter(clazz, "initView") { param ->
                injectProfileId(context, param.thisObject as? Activity, ProfileKind.CONTACT)
            }
        }

        private fun hookChatroomProfile(context: Context, classLoader: ClassLoader) {
            val clazz = KavaReflector.loadClass(CHATROOM_INFO_UI, classLoader)
            if (clazz == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到群聊资料页类")
                return
            }
            hookAfter(clazz, "initView") { param ->
                injectChatroomProfileId(context, param.thisObject as? Activity)
            }
            hookChatroomPreferenceClick(clazz)
        }

        private fun hookChatroomPreferenceClick(clazz: Class<*>) {
            methodsOf(clazz)
                .firstOrNull { it.name == "onPreferenceTreeClick" && it.parameterTypes.size >= 2 }
                ?.let { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val preference = param.args.getOrNull(1) ?: return
                            if (preferenceKey(preference) != CHATROOM_PREF_KEY) return
                            val profileId = resolveProfileId(activity, ProfileKind.CHATROOM) ?: return
                            copyProfileId(activity, profileId)
                            param.result = true
                        }
                    })
                }
                ?: h.Hchat.utils.HLog.e("$TAG 未找到群聊资料页 Preference 点击方法")
        }

        private fun injectChatroomProfileId(context: Context, activity: Activity?) {
            if (activity == null || !isEnabled(context)) return
            val profileId = resolveProfileId(activity, ProfileKind.CHATROOM) ?: return
            activity.window?.decorView?.post {
                injectChatroomPreferenceId(activity, profileId)
            }
        }

        private fun hookAfter(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: (XC_MethodHook.MethodHookParam) -> Unit
        ) {
            val method = KavaReflector.findMethodRecursive(clazz, methodName, *parameterTypes)
            if (method == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到 ${clazz.name}#$methodName")
                return
            }
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure {
                        h.Hchat.utils.HLog.e("$TAG 注入资料页 ID 失败: ${it.message}", it)
                    }
                }
            })
        }

        private fun injectProfileId(context: Context, activity: Activity?, kind: ProfileKind, post: Boolean = true) {
            if (activity == null || !isEnabled(context)) return
            val profileId = resolveProfileId(activity, kind) ?: return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            if (post) {
                decor.post {
                    injectProfileIdNow(activity, profileId, kind)
                }
            } else {
                injectProfileIdNow(activity, profileId, kind)
            }
        }

        private fun injectProfileIdNow(activity: Activity, profileId: String, kind: ProfileKind) {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val existing = findInjectedRow(decor)
            if (existing != null) {
                bindRow(existing, activity, profileId)
                return
            }
            val container = findInsertionContainer(decor) ?: return
            val row = createRow(activity, profileId)
            val index = insertionIndex(container, kind)
            container.addView(row, index.coerceIn(0, container.childCount), row.layoutParams)
        }

        private fun injectChatroomPreferenceId(activity: Activity, profileId: String) {
            val screen = KavaReflector.invokeMethod(activity, "getPreferenceScreen") ?: return
            val roomName = findPreference(screen, CHATROOM_NAME_PREF_KEY)
            val titleGetter = roomName?.let { findRoomNameTitleGetter(it) }
            val existing = findPreference(screen, CHATROOM_PREF_KEY)
            if (existing != null) {
                bindPreference(existing, profileId, titleGetter)
                bindPreferenceClick(existing, activity, profileId)
                notifyPreferenceChanged(screen)
                bindRenderedChatroomRow(activity, profileId)
                return
            }
            val preferenceClass = KavaReflector.loadClass(
                "com.tencent.mm.ui.base.preference.Preference",
                activity.classLoader
            ) ?: return
            val preference = KavaReflector.newInstance(
                KavaReflector.findConstructor(preferenceClass, Context::class.java),
                activity
            ) ?: return
            setPreferenceKey(preference, CHATROOM_PREF_KEY)
            bindPreference(preference, profileId, titleGetter)
            bindPreferenceClick(preference, activity, profileId)
            val anchor = findChatroomAnchorPreference(screen)
            if (!addPreferenceBeforeAnchor(screen, preference, anchor)) {
                h.Hchat.utils.HLog.e("$TAG 群聊 ID Preference 插入失败")
                return
            }
            notifyPreferenceChanged(screen)
            bindRenderedChatroomRow(activity, profileId)
        }

        private fun findChatroomAnchorPreference(screen: Any): Any? {
            for (key in CHATROOM_ANCHOR_PREF_KEYS) {
                findPreference(screen, key)?.let { return it }
            }
            return null
        }

        private fun findPreference(screen: Any, key: String): Any? {
            return methodsOf(screen.javaClass).firstNotNullOfOrNull { method ->
                if (method.parameterTypes.size != 1 ||
                    method.parameterTypes[0] != String::class.java ||
                    !method.returnType.name.contains("Preference")
                ) {
                    null
                } else {
                    KavaReflector.invoke(method, screen, key)?.takeIf { preferenceKey(it) == key }
                }
            }
        }

        private fun addPreferenceBeforeAnchor(screen: Any, preference: Any, anchor: Any?): Boolean {
            val index = preferenceIndex(screen, anchor)
                ?: CHATROOM_ANCHOR_PREF_KEYS.firstNotNullOfOrNull { preferenceIndexByKey(screen, it) }
            if (index != null && invokeAddPreference(screen, preference, index)) return true
            if (invokeAddPreference(screen, preference, 0)) return true
            return false
        }

        private fun invokeAddPreference(screen: Any, preference: Any, index: Int): Boolean {
            return methodsOf(screen.javaClass).any { method ->
                if (method.parameterTypes.size != 2 ||
                    !method.parameterTypes[0].isAssignableFrom(preference.javaClass) ||
                    method.parameterTypes[1] != Integer.TYPE
                ) {
                    false
                } else {
                    KavaReflector.invokeSuccessfully(method, screen, preference, index)
                }
            }
        }

        private fun preferenceIndex(screen: Any, preference: Any?): Int? {
            if (preference == null) return null
            val key = preferenceKey(preference) ?: return null
            return preferenceIndexByKey(screen, key)
        }

        private fun preferenceIndexByKey(screen: Any, key: String): Int? {
            methodsOf(screen.javaClass).forEach { method ->
                if (method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType == Integer.TYPE
                ) {
                    val index = KavaReflector.invoke(method, screen, key) as? Int
                    if (index != null && index >= 0) return index
                }
            }
            return null
        }

        private fun setPreferenceKey(preference: Any, key: String) {
            KavaReflector.writeField(preference, "q", key)
            KavaReflector.findMethod(preference.javaClass, "C", String::class.java)
                ?.let { KavaReflector.invoke(it, preference, key) }
        }

        private fun bindPreference(
            preference: Any,
            profileId: String,
            titleGetter: java.lang.reflect.Method? = null
        ) {
            val title = "$LABEL: $profileId"
            KavaReflector.writeField(preference, "h", title)
            KavaReflector.writeField(preference, "m", "")
            val titleSetters = titleGetter?.let { findTitleSetters(preference, it) }.orEmpty()
            if (titleSetters.isNotEmpty()) {
                clearCharSequenceValues(preference, titleSetters)
                titleSetters.forEach { KavaReflector.invoke(it, preference, title) }
            } else {
                KavaReflector.findMethod(preference.javaClass, "L", CharSequence::class.java)?.let {
                    KavaReflector.invoke(it, preference, title)
                }
            }
            KavaReflector.writeField(preference, "m", "")
        }

        private fun bindPreferenceClick(preference: Any, activity: Activity, profileId: String) {
            methodsOf(preference.javaClass).forEach { method ->
                if (method.parameterTypes.size == 1 &&
                    View.OnClickListener::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
                ) {
                    KavaReflector.invoke(method, preference, View.OnClickListener {
                        copyProfileId(activity, profileId)
                    })
                }
            }
        }

        private fun bindRenderedChatroomRow(activity: Activity, profileId: String, attempt: Int = 0) {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.postDelayed({
                val title = "$LABEL: $profileId"
                val titleView = findTextView(decor) { it == title } ?: findTextView(decor) {
                    it.startsWith("$LABEL: ") && it.contains(profileId)
                }
                if (titleView != null) {
                    bindRenderedClickTargets(titleView, activity, profileId)
                    return@postDelayed
                }
                if (attempt + 1 < CHATROOM_BIND_RETRY_LIMIT) {
                    bindRenderedChatroomRow(activity, profileId, attempt + 1)
                }
            }, if (attempt == 0) 0L else 120L)
        }

        private fun bindRenderedClickTargets(titleView: TextView, activity: Activity, profileId: String) {
            val listener = View.OnClickListener { copyProfileId(activity, profileId) }
            titleView.setOnClickListener(listener)
            titleView.isClickable = true
            titleView.isFocusable = false
            var current: View? = titleView
            var depth = 0
            while (current != null && depth < 5) {
                current.setOnClickListener(listener)
                current.isClickable = true
                current.isFocusable = false
                current = current.parent as? View
                depth++
            }
        }

        private fun findRoomNameTitleGetter(preference: Any): java.lang.reflect.Method? {
            return methodsOf(preference.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    CharSequence::class.java.isAssignableFrom(method.returnType) &&
                    (KavaReflector.invoke(method, preference)?.toString() == "群聊名称")
            }
        }

        private fun findTitleSetters(
            preference: Any,
            titleGetter: java.lang.reflect.Method
        ): List<java.lang.reflect.Method> {
            val setters = methodsOf(preference.javaClass).filter { method ->
                method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
            }
            val result = ArrayList<java.lang.reflect.Method>()
            setters.forEachIndexed { index, method ->
                val marker = "HchatTitleProbe$index"
                clearCharSequenceValues(preference, emptyList())
                KavaReflector.invoke(method, preference, marker)
                if (KavaReflector.invoke(titleGetter, preference)?.toString() == marker) {
                    result += method
                }
            }
            clearCharSequenceValues(preference, emptyList())
            return result
        }

        private fun clearCharSequenceValues(
            preference: Any,
            keep: List<java.lang.reflect.Method>
        ) {
            methodsOf(preference.javaClass).forEach { method ->
                if (method in keep) return@forEach
                if (method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
                ) {
                    KavaReflector.invoke(method, preference, "")
                }
            }
            KavaReflector.writeField(preference, "h", "")
            KavaReflector.writeField(preference, "m", "")
        }

        private fun preferenceKey(preference: Any?): String? {
            if (preference == null) return null
            (KavaReflector.readField(preference, "q") as? String)?.let { return it }
            return methodsOf(preference.javaClass).firstNotNullOfOrNull { method ->
                if (method.parameterTypes.isEmpty() && method.returnType == String::class.java) {
                    KavaReflector.invoke(method, preference) as? String
                } else {
                    null
                }
            }
        }

        private fun notifyPreferenceChanged(screen: Any) {
            KavaReflector.invokeMethod(screen, "notifyDataSetChanged")
            methodsOf(screen.javaClass).firstOrNull { method ->
                method.name == "notifyDataSetChanged" && method.parameterTypes.isEmpty()
            }?.let { KavaReflector.invoke(it, screen) }
        }

        private fun methodsOf(clazz: Class<*>?): List<java.lang.reflect.Method> {
            val methods = ArrayList<java.lang.reflect.Method>()
            var current = clazz
            while (current != null && current != Any::class.java) {
                methods += KavaReflector.declaredMethods(current)
                current = current.superclass
            }
            return methods
        }

        private fun resolveProfileId(activity: Activity, kind: ProfileKind): String? {
            val intent = activity.intent
            val direct = when (kind) {
                ProfileKind.CONTACT -> firstNonBlank(
                    intent.getStringExtra(CONTACT_USER),
                    intent.getStringExtra(CONTACT_USERNAME)
                )
                ProfileKind.CHATROOM -> intent.getStringExtra(ROOM_INFO_ID)
            }
            if (!direct.isNullOrBlank()) return direct.trim()
            val extras = intent.extras ?: return null
            for (key in extras.keySet()) {
                val value = runCatching { extras.get(key) as? String }.getOrNull()?.trim().orEmpty()
                if (value.isBlank()) continue
                if (kind == ProfileKind.CHATROOM && isChatroomId(value)) return value
                if (kind == ProfileKind.CONTACT && isPossibleContactId(value)) return value
            }
            return null
        }

        private fun firstNonBlank(vararg values: String?): String? {
            return values.firstOrNull { !it.isNullOrBlank() }?.trim()
        }

        private fun isChatroomId(value: String): Boolean {
            return value.endsWith("@chatroom") || value.endsWith("@im.chatroom")
        }

        private fun isPossibleContactId(value: String): Boolean {
            return WeChatIdRules.isLikelyContactId(value)
        }

        private fun findInjectedRow(root: ViewGroup): View? {
            if ((root.tag as? String)?.startsWith(INJECTED_VALUE_TAG) == true || root.tag == INJECTED_TAG) {
                return root
            }
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if ((child.tag as? String)?.startsWith(INJECTED_VALUE_TAG) == true || child.tag == INJECTED_TAG) {
                    return child
                }
                if (child is ViewGroup) {
                    val injected = findInjectedRow(child)
                    if (injected != null) return injected
                }
            }
            return null
        }

        private fun findInsertionContainer(root: ViewGroup): ViewGroup? {
            val candidates = ArrayList<ViewGroup>()
            collectLinearContainers(root, candidates)
            return candidates
                .filter { it.childCount >= 2 }
                .maxByOrNull { scoreContainer(it) }
        }

        private fun collectLinearContainers(group: ViewGroup, out: MutableList<ViewGroup>) {
            if (group is LinearLayout && group.orientation == LinearLayout.VERTICAL && isUsableContainer(group)) {
                out.add(group)
            }
            forEachChild(group) { child ->
                if (child is ViewGroup) collectLinearContainers(child, out)
            }
        }

        private fun isUsableContainer(group: ViewGroup): Boolean {
            if (group is FrameLayout || group is ScrollView) return false
            val width = group.width
            return width == 0 || width > dp(group.context, 220f)
        }

        private fun scoreContainer(group: ViewGroup): Int {
            var score = 0
            var textCount = 0
            if (containsScrollableContent(group)) score += 30
            collectText(group, 0) { text ->
                textCount++
                when {
                    text.contains("备注") -> score += 12
                    text.contains("标签") -> score += 12
                    text.contains("群聊名称") -> score += 16
                    text.contains("查找聊天记录") -> score += 10
                    text.contains("聊天信息") -> score += 8
                    text.contains("设置") -> score += 4
                }
            }
            score += group.childCount.coerceAtMost(12)
            if (textCount == 0) score -= 20
            if (group.parent is ScrollView) score += 10
            return score
        }

        private fun containsScrollableContent(group: ViewGroup): Boolean {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is AbsListView || child is ScrollView || child.javaClass.name.contains("RecyclerView")) {
                    return true
                }
            }
            return false
        }

        private fun collectText(view: View, depth: Int, block: (String) -> Unit) {
            if (depth > 4) return
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotBlank()) block(text)
            }
            if (view is ViewGroup) {
                forEachChild(view) { child -> collectText(child, depth + 1, block) }
            }
        }

        private fun insertionIndex(container: ViewGroup, kind: ProfileKind): Int {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                val text = textOf(child)
                if (kind == ProfileKind.CHATROOM && text.contains("群聊名称")) {
                    return index
                }
                if (kind == ProfileKind.CONTACT &&
                    (child is AbsListView || child is ScrollView || child.javaClass.name.contains("RecyclerView"))
                ) {
                    return index
                }
                if (
                    text.contains("备注") ||
                    text.contains("标签") ||
                    (kind != ProfileKind.CHATROOM && text.contains("群聊名称")) ||
                    text.contains("查找聊天记录")
                ) {
                    return index
                }
            }
            return if (container.childCount > 0) 1 else 0
        }

        private fun textOf(view: View): String {
            val builder = StringBuilder()
            collectText(view, 0) { builder.append(it).append('\n') }
            return builder.toString()
        }

        private fun createRow(activity: Activity, profileId: String): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 56f)
                setPadding(dp(activity, 16f), dp(activity, 12f), dp(activity, 16f), dp(activity, 12f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val text = TextView(activity).apply {
                textSize = 16f
                includeFontPadding = true
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrow = TextView(activity).apply {
                setText("›")
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(activity, 24f), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(text)
            row.addView(arrow)
            bindRow(row, activity, profileId)
            return row
        }

        private fun bindRow(row: View, activity: Activity, profileId: String) {
            row.tag = INJECTED_VALUE_TAG + profileId
            applyRowTheme(row, activity)
            val textView = if (row is ViewGroup) {
                firstTextChild(row)
            } else {
                null
            }
            textView?.text = "$LABEL: $profileId"
            row.setOnClickListener {
                copyProfileId(activity, profileId)
            }
        }

        private fun applyRowTheme(row: View, context: Context) {
            row.setBackgroundColor(themeColor(context, android.R.attr.colorBackground, fallbackBackgroundColor(context)))
            if (row is ViewGroup) {
                var textIndex = 0
                forEachTextView(row) { textView ->
                    val fallback = if (textIndex == 0) fallbackPrimaryTextColor(context) else fallbackSecondaryTextColor(context)
                    val attr = if (textIndex == 0) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary
                    textView.setTextColor(themeColor(context, attr, fallback))
                    textIndex++
                }
            }
        }

        private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val array = context.theme.obtainStyledAttributes(intArrayOf(attr))
            return try {
                array.getColor(0, fallback)
            } catch (_: Throwable) {
                fallback
            } finally {
                array.recycle()
            }
        }

        private fun fallbackBackgroundColor(context: Context): Int {
            return if (isNightMode(context)) Color.rgb(25, 25, 25) else Color.WHITE
        }

        private fun fallbackPrimaryTextColor(context: Context): Int {
            return if (isNightMode(context)) Color.rgb(235, 235, 235) else Color.rgb(32, 32, 32)
        }

        private fun fallbackSecondaryTextColor(context: Context): Int {
            return if (isNightMode(context)) Color.rgb(128, 128, 128) else Color.rgb(178, 178, 178)
        }

        private fun isNightMode(context: Context): Boolean {
            return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        private fun copyProfileId(activity: Activity, profileId: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(LABEL, profileId))
            Toast.makeText(activity, "已复制ID", Toast.LENGTH_SHORT).show()
        }

        private fun dp(context: Context, value: Float): Int {
            return (value * context.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun forEachChild(group: ViewGroup, block: (View) -> Unit) {
            for (index in 0 until group.childCount) {
                block(group.getChildAt(index))
            }
        }

        private fun firstTextChild(group: ViewGroup): TextView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView) return child
            }
            return null
        }

        private fun findTextView(group: ViewGroup, predicate: (String) -> Boolean): TextView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView && predicate(child.text?.toString().orEmpty())) {
                    return child
                }
                if (child is ViewGroup) {
                    findTextView(child, predicate)?.let { return it }
                }
            }
            return null
        }

        private fun forEachTextView(group: ViewGroup, block: (TextView) -> Unit) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView) {
                    block(child)
                } else if (child is ViewGroup) {
                    forEachTextView(child, block)
                }
            }
        }
    }

    private enum class ProfileKind {
        CONTACT,
        CHATROOM
    }
}
