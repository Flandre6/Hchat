package h.Hchat.hooks.items.homesidepanel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.ui.WeChatLifecycleApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.miuix.EmbeddedComposeOwnerInstaller
import h.Hchat.ui.miuix.MiuixSettingsPage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.Collections
import java.util.WeakHashMap

/** Runtime host for the home side panel. The drawer is only attached to LauncherUI. */
internal class HomeSidePanelRuntime(private val context: FeatureContext) {
    private val hostClassLoader = context.hostClassLoader()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = Collections.synchronizedMap(WeakHashMap<Activity, PanelSession>())
    private val prefs = HomeSidePanelSettings.preferences(context.hostContext())
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HomeSidePanelSettings.KEY_ENABLE ||
            key == HomeSidePanelSettings.KEY_SHOW_WEATHER ||
            key == HomeSidePanelSettings.KEY_SHOW_HITOKOTO ||
            key == HomeSidePanelSettings.KEY_SHOW_SIGNATURE
        ) {
            mainHandler.post { sessions.values.toList().forEach { it.updateVisibility() } }
        }
    }
    private var launcherHook: XC_MethodHook.Unhook? = null
    private var destroyHook: XC_MethodHook.Unhook? = null

    fun install() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val launcher = KavaReflector.loadClass("com.tencent.mm.ui.LauncherUI", hostClassLoader)
        val onCreate = KavaReflector.findMethod(launcher, "onCreate", Bundle::class.java)
        val onDestroy = KavaReflector.findMethod(launcher, "onDestroy")
        if (onCreate == null) {
            HLog.e("[Hchat:HomeSidePanel] LauncherUI.onCreate 未找到")
            return
        }
        launcherHook = HookRegistry.get().hook(onCreate, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Activity)?.let { attachIfSupported(it) }
            }
        })
        if (onDestroy != null) {
            destroyHook = HookRegistry.get().hook(onDestroy, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let { detach(it) }
                }
            })
        }
    }

    fun attachIfSupported(activity: Activity) {
        if (!isLauncher(activity)) {
            HLog.e("[Hchat:HomeSidePanel] attachIfSupported 跳过，非 LauncherUI: ${activity.javaClass.name}")
            return
        }
        HLog.e("[Hchat:HomeSidePanel] attachIfSupported 触发: ${activity.javaClass.name}")
        mainHandler.post {
            if (sessions[activity] == null) {
                runCatching {
                    PanelSession(activity).also { session ->
                        sessions[activity] = session
                        session.attach()
                    }
                }.onFailure {
                    HLog.e("[Hchat:HomeSidePanel] 侧边栏挂载失败: ${it.message}", it)
                }
            }
        }
    }

    fun onActivityEvent(event: WeChatLifecycleApi.ActivityEvent) {
        if (event.isResume()) attachIfSupported(event.activity)
        if (event.isDestroy()) detach(event.activity)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mainHandler.post {
            sessions.values.toList().forEach { it.detach() }
            sessions.clear()
        }
        launcherHook?.unhook()
        destroyHook?.unhook()
        launcherHook = null
        destroyHook = null
    }

    private fun detach(activity: Activity) {
        mainHandler.post { sessions.remove(activity)?.detach() }
    }

    private fun isLauncher(activity: Activity): Boolean =
        activity.javaClass.name == "com.tencent.mm.ui.LauncherUI" ||
            activity.javaClass.name.endsWith(".LauncherUI")

    private inner class PanelSession(private val activity: Activity) {
        private val decor = activity.window.decorView as? ViewGroup
            ?: error("decorView is not ViewGroup")
        private val overlay = DrawerOverlay(activity)
        private val panel = ComposeView(activity)
        private val owner = PanelComposeOwner()
        private val data = HomeSidePanelDataRepository(context.moduleContext())
        private var attached = false
        private var drawerWidth = 1f
        private var progress = 0f
        private var dragging = false
        private var downX = 0f
        private var lastX = 0f
        private val configVersion = mutableStateOf(0)

        fun attach() {
            if (attached) return
            attached = true
            owner.install(panel)
            owner.attach()
            owner.installComposition(panel)
            panel.setBackgroundColor(Color.TRANSPARENT)
            panel.setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            panel.setContent {
                MaterialTheme {
                    configVersion.value
                    val enabled = prefs.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)
                    val showWeather = prefs.getBoolean(HomeSidePanelSettings.KEY_SHOW_WEATHER, HomeSidePanelSettings.DEFAULT_SHOW_WEATHER)
                    val showHitokoto = prefs.getBoolean(HomeSidePanelSettings.KEY_SHOW_HITOKOTO, HomeSidePanelSettings.DEFAULT_SHOW_HITOKOTO)
                    val showSignature = prefs.getBoolean(HomeSidePanelSettings.KEY_SHOW_SIGNATURE, HomeSidePanelSettings.DEFAULT_SHOW_SIGNATURE)
                    HomeSidePanelContent(
                        enabled = enabled,
                        showWeather = showWeather,
                        showHitokoto = showHitokoto,
                        showSignature = showSignature,
                        data = data,
                        onClose = { close(animated = true) },
                        onSettings = { openSettings() }
                    )
                }
            }
            overlay.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            decor.addView(overlay, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            overlay.post {
                drawerWidth = overlay.width * DRAWER_FRACTION
                applyProgress(0f)
                updateVisibility()
                // Android 10+ 手势导航会拦截屏幕左缘滑动（系统返回手势），
                // 在 overlay 布局完成后显式把左缘排除在系统手势外，确保能收到左滑触摸
                if (android.os.Build.VERSION.SDK_INT >= 29 && overlay.width > 0) {
                    runCatching {
                        overlay.setSystemGestureExclusionRects(
                            listOf(android.graphics.Rect(0, 0, EDGE_SLOP.toInt(), overlay.height))
                        )
                    }.onFailure {
                        HLog.e("[Hchat:HomeSidePanel] 手势排除设置失败: ${it.message}")
                    }
                }
                // 微信首页会频繁 bringToFront 自己的 view（列表滚动/角标更新），
                // 持续把 overlay 拉回最顶层，否则 MOVE 事件会被压在下层的微信 view 消费
                if (android.os.Build.VERSION.SDK_INT >= 21) overlay.elevation = 100f
                decor.viewTreeObserver.addOnGlobalLayoutListener {
                    runCatching {
                        if (overlay.parent === decor) overlay.bringToFront()
                    }
                }
            }
        }

        fun updateVisibility() {
            if (!attached) return
            val enabled = prefs.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)
            configVersion.value += 1
            if (!enabled && progress > CLOSED_EPSILON) close(animated = true)
            overlay.visibility = if (enabled || dragging) View.VISIBLE else View.GONE
            panel.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        fun detach() {
            if (!attached) return
            attached = false
            data.close()
            owner.clear(panel)
            owner.destroy()
            panel.disposeComposition()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }

        private fun openSettings() {
            MiuixSettingsPage.showFeature(activity, HomeSidePanelFeature.ID)
        }

        fun onTouch(event: MotionEvent): Boolean {
            if (!prefs.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    lastX = event.x
                    dragging = downX <= EDGE_SLOP || progress > CLOSED_EPSILON
                    if (!dragging) return false
                    overlay.visibility = View.VISIBLE
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = event.x - downX
                    val delta = event.x - lastX
                    lastX = event.x
                    val next = if (progress <= CLOSED_EPSILON && dx > 0f) {
                        (dx / drawerWidth).coerceIn(0f, 1f)
                    } else {
                        (progress + delta / drawerWidth).coerceIn(0f, 1f)
                    }
                    applyProgress(next)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    val shouldOpen = progress > OPEN_THRESHOLD || (event.x - downX) > drawerWidth * 0.25f
                    if (shouldOpen) open(animated = true) else close(animated = true)
                    return true
                }
            }
            return false
        }

        private fun open(animated: Boolean) {
            animateTo(1f, animated)
        }

        private fun close(animated: Boolean) {
            animateTo(0f, animated)
        }

        private fun animateTo(target: Float, animated: Boolean) {
            if (!animated) { applyProgress(target); return }
            val start = progress
            android.animation.ValueAnimator.ofFloat(start, target).apply {
                duration = 240L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { applyProgress(it.animatedValue as Float) }
                start()
            }
        }

        private fun applyProgress(value: Float) {
            progress = value.coerceIn(0f, 1f)
            if (drawerWidth <= 1f) drawerWidth = (overlay.width * DRAWER_FRACTION).coerceAtLeast(1f)
            panel.translationX = -drawerWidth * (1f - progress)
            overlay.dim.alpha = 0.5f * progress
            overlay.dim.isClickable = progress > CLOSED_EPSILON
            overlay.visibility = if (progress > CLOSED_EPSILON || dragging || prefs.getBoolean(HomeSidePanelSettings.KEY_ENABLE, false)) View.VISIBLE else View.GONE
            panel.bringToFront()
        }

        private inner class DrawerOverlay(context: Activity) : FrameLayout(context) {
            val dim = View(context).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
                setOnClickListener { close(animated = true) }
            }
            init {
                setBackgroundColor(Color.TRANSPARENT)
                addView(dim, LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (!prefs.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)) {
                    return false
                }
                val result = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val hit = progress <= CLOSED_EPSILON && event.x <= EDGE_SLOP
                        if (hit) HLog.e("[Hchat:HomeSidePanel] 左缘按下命中 x=${event.x} y=${event.y}")
                        hit
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                    else -> false
                }
                return result
            }

            override fun onTouchEvent(event: MotionEvent): Boolean = onTouch(event)

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w > 0) {
                    drawerWidth = w * DRAWER_FRACTION
                    panel.layoutParams = (panel.layoutParams as? FrameLayout.LayoutParams
                        ?: FrameLayout.LayoutParams(1, FrameLayout.LayoutParams.MATCH_PARENT)).also { params ->
                        params.width = drawerWidth.toInt().coerceAtLeast(1)
                        params.height = FrameLayout.LayoutParams.MATCH_PARENT
                    }
                    // Android 10+ 手势导航会拦截屏幕左缘滑动（返回手势），把左缘排除在系统手势外，
                    // 否则侧边栏收不到左滑事件
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        setSystemGestureExclusionRects(
                            listOf(android.graphics.Rect(0, 0, EDGE_SLOP.toInt(), h))
                        )
                    }
                }
            }
        }
    }

    private class PanelComposeOwner :
        androidx.lifecycle.LifecycleOwner,
        androidx.savedstate.SavedStateRegistryOwner,
        androidx.lifecycle.ViewModelStoreOwner,
        androidx.navigationevent.NavigationEventDispatcherOwner {
        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
        private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)
        private val store = androidx.lifecycle.ViewModelStore()
        private val navigationDispatcher = androidx.navigationevent.NavigationEventDispatcher()
        private val composeRuntime = lazy(LazyThreadSafetyMode.NONE) {
            h.Hchat.ui.miuix.EmbeddedComposeRuntime(lifecycleRegistry)
        }
        private var restored = false
        override val lifecycle get() = lifecycleRegistry
        override val savedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore get() = store
        override val navigationEventDispatcher get() = navigationDispatcher
        fun install(view: View) {
            EmbeddedComposeOwnerInstaller.install(view, this, this, this, this)
        }
        fun installComposition(view: ComposeView) {
            composeRuntime.value.install(view)
        }
        fun clear(view: View) { EmbeddedComposeOwnerInstaller.clear(view) }
        fun attach() {
            if (!restored) { savedStateController.performRestore(Bundle.EMPTY); restored = true }
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        fun destroy() {
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            if (composeRuntime.isInitialized()) composeRuntime.value.destroy()
            navigationDispatcher.dispose()
            store.clear()
        }
    }

    private companion object {
        const val DRAWER_FRACTION = 0.84f
        const val EDGE_SLOP = 48f
        const val OPEN_THRESHOLD = 0.45f
        const val CLOSED_EPSILON = 0.001f
    }
}

@Composable
private fun HomeSidePanelContent(
    enabled: Boolean,
    showWeather: Boolean,
    showHitokoto: Boolean,
    showSignature: Boolean,
    data: HomeSidePanelDataRepository,
    onClose: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    ) {
        var profile by remember { mutableStateOf<HomeSidePanelProfile?>(null) }
        var weather by remember { mutableStateOf<HomeSidePanelWeatherSnapshot?>(null) }
        var hitokoto by remember { mutableStateOf<HomeSidePanelHitokotoSnapshot?>(null) }
        var loading by remember { mutableStateOf(false) }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        fun reload(force: Boolean) {
            loading = true
            data.loadProfile { value ->
                mainHandler.post {
                    profile = value
                    if (showWeather) {
                        data.refreshWeather(value, force) { result ->
                            mainHandler.post {
                                weather = result.getOrNull()
                                loading = false
                            }
                        }
                    } else {
                        loading = false
                    }
                }
            }
            if (showHitokoto) {
                data.refreshHitokoto(force) { result ->
                    mainHandler.post { hitokoto = result.getOrNull() }
                }
            }
        }
        LaunchedEffect(enabled) {
            if (!enabled) return@LaunchedEffect
            reload(force = false)
        }
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("负一屏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Hchat 首页侧边栏", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                TextButton(onClick = { reload(force = true) }) { Text("刷新") }
                TextButton(onClick = onSettings) { Text("设置") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile?.name ?: "微信用户", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (profile?.wxId.orEmpty().isNotBlank()) Text(profile?.wxId.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    if (showSignature && profile?.signature.orEmpty().isNotBlank()) Text(profile?.signature.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile?.city.orEmpty().isNotBlank()) Text(
                        listOf(profile?.province, profile?.city)
                            .filter { !it.isNullOrBlank() }
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
            if (showWeather) DataCard("天气", weather?.displayText() ?: if (loading) "正在加载天气…" else "暂无天气数据")
            if (showHitokoto) DataCard("一言", hitokoto?.displayText() ?: if (loading) "正在加载一言…" else "暂无内容")
            Spacer(Modifier.weight(1f))
            Text("从屏幕左缘向右滑即可打开", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DataCard(title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text(text, lineHeight = 21.sp)
        }
    }
}
