package h.Hchat.hooks.items.homesidepanel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.ui.WeChatLifecycleApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.miuix.MiuixSettingsPage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/** Lightweight Hchat adaptation of the supplied home-sidebar implementation. */
internal class HomeSidePanelRuntime(private val context: FeatureContext) {
    private val hostClassLoader = context.hostClassLoader()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = Collections.synchronizedMap(WeakHashMap<Activity, PanelSession>())
    private val prefs = HomeSidePanelSettings.preferences(context.hostContext())
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HomeSidePanelSettings.KEY_ENABLE ||
            key == HomeSidePanelSettings.KEY_SIGNATURE ||
            key == HomeSidePanelSettings.KEY_SHORTCUTS
        ) {
            mainHandler.post { sessions.values.toList().forEach { it.refresh() } }
        }
    }
    private val hooks = ArrayList<XC_MethodHook.Unhook>()

    fun install() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val launcher = KavaReflector.loadClass("com.tencent.mm.ui.LauncherUI", hostClassLoader)
            ?: run {
                HLog.e("[Hchat:HomeSidePanel] LauncherUI 未找到")
                return
            }
        hook(launcher, "onCreate", arrayOf(Bundle::class.java)) { param ->
            (param.thisObject as? Activity)?.let(::attachIfSupported)
        }
        hook(launcher, "onResume", emptyArray()) { param ->
            (param.thisObject as? Activity)?.let(::attachIfSupported)
        }
        hook(launcher, "onDestroy", emptyArray()) { param ->
            (param.thisObject as? Activity)?.let(::detach)
        }
    }

    private fun hook(owner: Class<*>, name: String, params: Array<Class<*>>, after: (XC_MethodHook.MethodHookParam) -> Unit) {
        val method = KavaReflector.findMethod(owner, name, *params) ?: return
        runCatching {
            hooks += HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = after(param)
            })
        }.onFailure { HLog.e("[Hchat:HomeSidePanel] Hook ${owner.name}.$name 失败", it) }
    }

    fun onActivityEvent(event: WeChatLifecycleApi.ActivityEvent) {
        if (event.isResume()) attachIfSupported(event.activity)
        if (event.isDestroy()) detach(event.activity)
    }

    fun attachIfSupported(activity: Activity) {
        if (!isLauncher(activity)) return
        mainHandler.post {
            if (sessions[activity] == null) {
                runCatching { PanelSession(activity).also { sessions[activity] = it; it.attach() } }
                    .onFailure { HLog.e("[Hchat:HomeSidePanel] 挂载失败", it) }
            }
        }
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mainHandler.post { sessions.values.toList().forEach { it.detach() }; sessions.clear() }
        hooks.forEach { runCatching { it.unhook() } }
        hooks.clear()
    }

    private fun detach(activity: Activity) {
        mainHandler.post { sessions.remove(activity)?.detach() }
    }

    private fun isLauncher(activity: Activity): Boolean =
        activity.javaClass.name == "com.tencent.mm.ui.LauncherUI" ||
            activity.javaClass.name.endsWith(".LauncherUI")

    private inner class PanelSession(private val activity: Activity) {
        private val decor = activity.window.decorView as? ViewGroup
            ?: error("微信窗口不是 ViewGroup")
        private val root = FrameLayout(activity)
        private val edge = View(activity)
        private val scrim = View(activity)
        private val drawer = LinearLayout(activity)
        private val avatarButton = TextView(activity)
        private var attached = false
        private var opened = false
        private var dragging = false
        private var downX = 0f
        private var lastX = 0f
        private var drawerWidth = 0

        fun attach() {
            if (attached) return
            attached = true
            root.setBackgroundColor(Color.TRANSPARENT)
            root.clipChildren = false
            buildDrawer()
            val edgeWidth = dp(28)
            root.addView(edge, FrameLayout.LayoutParams(edgeWidth, -1, Gravity.START))
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            root.addView(drawer, FrameLayout.LayoutParams(dp(304), -1, Gravity.START))
            val avatarParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START)
            avatarParams.leftMargin = dp(12)
            avatarParams.topMargin = dp(52)
            root.addView(avatarButton, avatarParams)
            edge.setOnTouchListener { _, event -> onEdgeTouch(event) }
            scrim.setOnClickListener { dismiss(true) }
            avatarButton.setOnClickListener { toggle() }
            scrim.visibility = View.GONE
            drawer.translationX = -dp(304).toFloat()
            decor.addView(root, ViewGroup.LayoutParams(-1, -1))
            refresh()
            root.post { drawerWidth = drawer.width.coerceAtLeast(dp(304)); refresh() }
        }

        fun refresh() {
            if (!attached) return
            val enabled = HomeSidePanelSettings.enabled(activity)
            // Preferences can change while the drawer is already mounted. Rebuild
            // its rows so the signature and shortcut order take effect immediately.
            buildDrawer()
            edge.visibility = if (enabled || opened) View.VISIBLE else View.GONE
            avatarButton.visibility = if (enabled) View.VISIBLE else View.GONE
            root.visibility = if (enabled || opened) View.VISIBLE else View.GONE
            if (!enabled && opened) dismiss(true)
        }

        fun detach() {
            if (!attached) return
            attached = false
            (root.parent as? ViewGroup)?.removeView(root)
        }

        private fun buildDrawer() {
            drawer.removeAllViews()
            drawer.orientation = LinearLayout.VERTICAL
            drawer.setPadding(dp(20), dp(54), dp(16), dp(18))
            drawer.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(0f, 0f, dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f)
            }
            drawer.elevation = dp(12).toFloat()
            addText("Hchat 快捷面板", 22, Color.BLACK, true, 0)
            val signature = addText(HomeSidePanelSettings.signature(activity), 13, Color.GRAY, false, dp(6))
            signature.setOnClickListener { MiuixSettingsPage.showFeature(activity, HomeSidePanelFeature.ID) }
            addText("左缘右滑打开，也可点击左上角按钮", 11, Color.GRAY, false, dp(14))
            HomeSidePanelSettings.shortcuts(activity).forEach { shortcut ->
                val row = addRow(shortcut.title, shortcut.subtitle)
                row.setOnClickListener { openShortcut(shortcut) }
            }
            addText("Hchat", 12, Color.GRAY, false, dp(18))
            val settings = addRow("Hchat 设置", "打开模块功能设置")
            settings.setOnClickListener {
                dismiss(true)
                MiuixSettingsPage.show(activity)
            }
        }

        private fun addText(text: String, size: Int, color: Int, bold: Boolean, top: Int): TextView {
            return TextView(activity).apply {
                this.text = text
                setTextColor(color)
                textSize = size.toFloat()
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, top, 0, 0)
                drawer.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }

        private fun addRow(title: String, subtitle: String): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(247, 247, 249))
                    cornerRadius = dp(14).toFloat()
                }
            }
            val titleView = TextView(activity).apply { text = title; setTextColor(Color.BLACK); textSize = 15f }
            val subView = TextView(activity).apply { text = subtitle; setTextColor(Color.GRAY); textSize = 11f }
            row.addView(titleView)
            row.addView(subView)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(8)
            drawer.addView(row, lp)
            return row
        }

        private fun openShortcut(shortcut: HomeSidePanelSettings.Shortcut) {
            val classNames = when (shortcut) {
                HomeSidePanelSettings.Shortcut.QR_CODE -> arrayOf("com.tencent.mm.plugin.setting.ui.setting.SelfQRCodeUI")
                HomeSidePanelSettings.Shortcut.PAY -> arrayOf("com.tencent.mm.plugin.offline.ui.WalletOfflineEntranceUI")
                HomeSidePanelSettings.Shortcut.FAVORITE -> arrayOf("com.tencent.mm.plugin.fav.ui.FavoriteIndexUI")
            }
            dismiss(true)
            classNames.firstOrNull { className ->
                runCatching {
                    val clazz = Class.forName(className, false, activity.classLoader)
                    activity.startActivity(Intent(activity, clazz))
                    true
                }.getOrDefault(false)
            } ?: HLog.e("[Hchat:HomeSidePanel] 无法打开快捷入口: ${shortcut.id}")
        }

        private fun toggle() { if (opened) dismiss(true) else show(true) }

        private fun show(animated: Boolean) {
            if (!HomeSidePanelSettings.enabled(activity)) return
            opened = true
            root.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            scrim.setBackgroundColor(Color.argb(108, 0, 0, 0))
            drawer.visibility = View.VISIBLE
            if (animated) drawer.animate().translationX(0f).setDuration(260).start() else drawer.translationX = 0f
        }

        private fun dismiss(animated: Boolean) {
            opened = false
            if (!animated) {
                drawer.translationX = -drawerWidth.toFloat()
                scrim.visibility = View.GONE
                root.visibility = if (HomeSidePanelSettings.enabled(activity)) View.VISIBLE else View.GONE
                return
            }
            drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(220).withEndAction {
                if (!opened) {
                    scrim.visibility = View.GONE
                    if (!HomeSidePanelSettings.enabled(activity)) root.visibility = View.GONE
                }
            }.start()
        }

        private fun onEdgeTouch(event: MotionEvent): Boolean {
            if (!HomeSidePanelSettings.enabled(activity) && !opened) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    lastX = event.rawX
                    dragging = true
                    if (opened) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = event.rawX - downX
                    if (!opened && dx > dp(8)) show(false)
                    if (opened) {
                        val next = (drawer.translationX + event.rawX - lastX).coerceIn(-drawerWidth.toFloat(), 0f)
                        drawer.translationX = next
                    }
                    lastX = event.rawX
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    if (opened) {
                        if (drawer.translationX > -drawerWidth * 0.55f) show(true) else dismiss(true)
                    }
                    return true
                }
            }
            return true
        }

        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }
}
