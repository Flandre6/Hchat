package h.Hchat.hooks.items.homesidepanel

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.ui.WeChatChatPageApi
import h.Hchat.hooks.api.ui.WeChatLifecycleApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal class HomeSidePanelRuntime(private val context: FeatureContext) {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = Collections.synchronizedMap(WeakHashMap<Activity, Session>())
    private val hooks = ArrayList<XC_MethodHook.Unhook>()
    private val prefs = HomeSidePanelSettings.preferences(context.hostContext())
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HomeSidePanelSettings.KEY_ENABLE || key == HomeSidePanelSettings.KEY_SIGNATURE || key == HomeSidePanelSettings.KEY_SHORTCUTS) {
            handler.post { sessions.values.toList().forEach { it.refresh() } }
        }
    }

    fun install() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val launcher = KavaReflector.loadClass("com.tencent.mm.ui.LauncherUI", context.hostClassLoader()) ?: return
        hook(launcher, "onCreate", arrayOf(Bundle::class.java)) { (it.thisObject as? Activity)?.let(::attach) }
        hook(launcher, "onResume", emptyArray()) { (it.thisObject as? Activity)?.let(::attach) }
        hook(launcher, "onDestroy", emptyArray()) { (it.thisObject as? Activity)?.let(::detach) }
    }

    private fun hook(owner: Class<*>, name: String, params: Array<Class<*>>, action: (XC_MethodHook.MethodHookParam) -> Unit) {
        KavaReflector.findMethod(owner, name, *params)?.let { method ->
            runCatching {
                hooks += HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) = action(param)
                })
            }.onFailure { HLog.e("[Hchat:HomeSidePanel] hook $name failed", it) }
        }
    }

    fun onActivityEvent(event: WeChatLifecycleApi.ActivityEvent) {
        if (event.isResume()) attach(event.activity)
        if (event.isDestroy()) detach(event.activity)
    }

    fun onChatPageChanged(event: WeChatChatPageApi.ChatPageEvent) {
        val activity = WeChatApis.currentActivity()?.currentActivity() ?: return
        handler.post { sessions[activity]?.setChatVisible(event.isEnter()) }
    }

    fun attach(activity: Activity) {
        if (!isLauncher(activity)) return
        handler.post {
            if (sessions[activity] == null) {
                runCatching { Session(activity).also { sessions[activity] = it; it.attach() } }
                    .onFailure { HLog.e("[Hchat:HomeSidePanel] attach failed", it) }
            }
        }
    }

    private fun detach(activity: Activity) { handler.post { sessions.remove(activity)?.detach() } }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        handler.post { sessions.values.toList().forEach { it.detach() }; sessions.clear() }
        hooks.forEach { runCatching { it.unhook() } }
        hooks.clear()
    }

    private fun isLauncher(activity: Activity): Boolean = activity.javaClass.name.endsWith(".LauncherUI") || activity.javaClass.name == "com.tencent.mm.ui.LauncherUI"

    private inner class Session(private val activity: Activity) {
        private val decor = activity.window.decorView as ViewGroup
        private val drawer = HomeSidePanelDrawer(activity) { close() }
        private var header: ViewGroup? = null
        private var avatar: ImageView? = null
        private var name: TextView? = null
        private var status: TextView? = null
        private var attached = false
        private var chatVisible = false
        private var headerAttempts = 0
        private val avatarLoading = AtomicBoolean(false)
        private var avatarUrl: String? = null
        private var lastHomeVisible: Boolean? = null
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (attached) refresh()
        }

        fun attach() {
            if (attached) return
            attached = true
            decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            drawer.attachTo(decor)
            refresh()
        }

        fun refresh() {
            if (!attached) return
            val enabled = HomeSidePanelSettings.enabled(activity)
            val homeVisible = enabled && !chatVisible && isHomeTab()
            val stateChanged = lastHomeVisible != homeVisible
            if (stateChanged) drawer.setEnabled(homeVisible)
            if (homeVisible && header == null) ensureHeader()
            val targetVisibility = if (homeVisible) View.VISIBLE else View.GONE
            if (header?.visibility != targetVisibility) header?.visibility = targetVisibility
            val currentName = accountName()
            if (name?.text?.toString() != currentName) name?.text = currentName
            if (status?.text?.toString() != "在线") status?.text = "在线"
            if (homeVisible) loadAvatar()
            if (Build.VERSION.SDK_INT >= 29 && stateChanged) {
                decor.setSystemGestureExclusionRects(
                    if (homeVisible) listOf(Rect(0, 0, dp(64), decor.height.coerceAtLeast(1))) else emptyList()
                )
            }
            lastHomeVisible = homeVisible
        }

        fun setChatVisible(visible: Boolean) {
            chatVisible = visible
            refresh()
            if (visible) drawer.close(false)
        }

        fun detach() {
            attached = false
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
            drawer.detach()
            (header?.parent as? ViewGroup)?.removeView(header)
            header = null
            lastHomeVisible = null
        }

        private fun close() { drawer.close(true) }

        private fun ensureHeader() {
            val host = findActionBarContent(decor)
            if (host == null || !isHomeTab(host)) {
                if (headerAttempts++ < 8) handler.postDelayed({ if (attached) ensureHeader() }, 300L)
                return
            }
            headerAttempts = 0
            val old = host.findViewWithTag<View>("hchat_home_avatar_header")
            if (old is ViewGroup) { header = old; collect(old); return }
            val root = LinearLayout(activity).apply {
                tag = "hchat_home_avatar_header"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { drawer.toggle() }
            }
            val iv = ImageView(activity).apply {
                tag = "hchat_home_avatar"
                setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(210, 210, 210)) })
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            root.addView(iv, LinearLayout.LayoutParams(dp(38), dp(38)))
            val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            val nick = TextView(activity).apply {
                tag = "hchat_home_avatar_name"
                setTextColor(if (night()) Color.WHITE else Color.rgb(30, 30, 30))
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            }
            val st = TextView(activity).apply {
                tag = "hchat_home_avatar_status"
                setTextColor(if (night()) Color.LTGRAY else Color.GRAY)
                textSize = 11.5f
                maxLines = 1
            }
            text.addView(nick); text.addView(st)
            text.setOnClickListener {
                if (!HomeSidePanelPageLauncher.openStatus(activity)) drawer.toggle()
            }
            root.addView(text, LinearLayout.LayoutParams(-2, -2))
            val lp: ViewGroup.LayoutParams = if (host is RelativeLayout) {
                RelativeLayout.LayoutParams(-2, -2).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    leftMargin = dp(14)
                    marginStart = dp(14)
                }
            } else {
                LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(14)
                }
            }
            host.addView(root, lp)
            header = root; avatar = iv; name = nick; status = st
        }

        private fun collect(root: View) {
            avatar = root.findViewWithTag("hchat_home_avatar")
            name = root.findViewWithTag("hchat_home_avatar_name")
            status = root.findViewWithTag("hchat_home_avatar_status")
        }

        private fun accountName(): String = WeChatApis.account()?.selfName()?.takeIf { it.isNotBlank() } ?: "我"

        private fun loadAvatar() {
            if (!avatarLoading.compareAndSet(false, true)) return
            val wxid = WeChatApis.account()?.selfWxId().orEmpty()
            val url = runCatching { WeChatApis.contacts()?.getContact(wxid)?.avatarUrl.orEmpty() }.getOrDefault("")
            if (url.isBlank()) { avatarLoading.set(false); return }
            if (avatarUrl == url) { avatarLoading.set(false); return }
            avatarUrl = url
            Thread {
                val bitmap = runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                handler.post {
                    avatarLoading.set(false)
                    if (bitmap != null && !bitmap.isRecycled) {
                        avatar?.setImageBitmap(bitmap)
                        drawer.setAvatar(bitmap)
                    }
                }
            }.start()
        }

        private fun findActionBarContent(root: ViewGroup): ViewGroup? {
            val pkg = root.context.packageName
            val id = root.context.resources.getIdentifier("ih", "id", pkg)
            if (id != 0) root.findViewById<ViewGroup>(id)?.let { return it }
            val toolbarId = root.context.resources.getIdentifier("ez", "id", pkg)
            if (toolbarId != 0) root.findViewById<ViewGroup>(toolbarId)?.let { tb ->
                for (i in 0 until tb.childCount) if (tb.getChildAt(i) is ViewGroup) return tb.getChildAt(i) as ViewGroup
                return tb
            }
            var found: ViewGroup? = null
            fun walk(v: View, depth: Int) {
                if (found != null || depth > 10) return
                if (v is ViewGroup && v.javaClass.name.contains("Toolbar")) { found = v; return }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
            walk(root, 0); return found
        }

        private fun isHomeTab(): Boolean = findActionBarContent(decor)?.let(::isHomeTab) == true

        private fun isHomeTab(host: ViewGroup): Boolean {
            fun walk(view: View): Boolean {
                if (view is TextView && view.visibility == View.VISIBLE && view.text?.toString()?.trim() == "微信") {
                    return true
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        if (walk(view.getChildAt(index))) return true
                    }
                }
                return false
            }
            return walk(host)
        }

        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + .5f).toInt()
        private fun night(): Boolean = (activity.resources.configuration.uiMode and 0x30) == 0x20
    }
}
