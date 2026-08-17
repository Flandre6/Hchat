package h.Hchat.hooks.items.backgroundbeauty

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.ScrollView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Member
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

class BackgroundBeautyFeature : BaseFeature() {
    private var runtime: BackgroundBeautyRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "沉浸式背景"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(BackgroundBeautySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = BackgroundBeautyRuntime(context)
        runtime?.install()
        subscribe(Events.DexReady::class.java) {
            DexInstallScheduler.schedule("$ID:chat_background", name()) {
                runtime?.installChatBackgroundComponentHook(allowDexSearch = true) == true
            }
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "background_beauty"
    }
}

private class BackgroundBeautyTransparentDrawable : Drawable() {
    override fun draw(canvas: Canvas) = Unit

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Drawable opacity is not used by the background renderer")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

private class ScreenAlignedBitmapDrawable(
    bitmap: Bitmap,
    anchor: View,
    opacity: Float
) : Drawable() {
    private val bitmapRef = WeakReference(bitmap)
    private val anchorRef = WeakReference(anchor)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        alpha = (BackgroundBeautySettings.opacity(opacity) * 255f).toInt().coerceIn(0, 255)
    }
    private val rootLocation = IntArray(2)
    private val anchorLocation = IntArray(2)
    private val destination = RectF()

    init {
        updateDestination()
    }

    private fun updateDestination() {
        val bitmap = bitmapRef.get() ?: return
        if (bitmap.isRecycled) return
        val anchor = anchorRef.get() ?: return
        val root = anchor.rootView ?: anchor
        val rootWidth = root.width.takeIf { it > 0 } ?: bounds.width()
        val rootHeight = root.height.takeIf { it > 0 } ?: bounds.height()
        if (rootWidth <= 0 || rootHeight <= 0) return
        runCatching {
            root.getLocationOnScreen(rootLocation)
            anchor.getLocationOnScreen(anchorLocation)
        }.getOrElse {
            rootLocation[0] = 0
            rootLocation[1] = 0
            anchorLocation[0] = 0
            anchorLocation[1] = 0
        }
        val scale = max(
            rootWidth.toFloat() / bitmap.width.toFloat(),
            rootHeight.toFloat() / bitmap.height.toFloat()
        )
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val offsetX = anchorLocation[0] - rootLocation[0]
        val offsetY = anchorLocation[1] - rootLocation[1]
        destination.set(
            (rootWidth - drawnWidth) / 2f - offsetX,
            (rootHeight - drawnHeight) / 2f - offsetY,
            (rootWidth + drawnWidth) / 2f - offsetX,
            (rootHeight + drawnHeight) / 2f - offsetY
        )
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        updateDestination()
    }

    override fun draw(canvas: Canvas) {
        val bitmap = bitmapRef.get() ?: return
        if (bitmap.isRecycled || destination.isEmpty) return
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity is not used by the background renderer")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class BackgroundBeautyRuntime(
    private val context: FeatureContext
) {
    private companion object {
        const val TAG = "[Hchat:沉浸式背景]"
        const val CHAT_ACTIVITY = "com.tencent.mm.ui.chatting.ChattingUI"
        const val LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI"
        const val MAIN_TAB_CLASS = "com.tencent.mm.ui.MainTabUI"
        const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
        const val CHATTING_IMAGE_BG_VIEW = "com.tencent.mm.ui.chatting.ChattingImageBGView"
        const val BOUNCE_VIEW = "com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2"
        const val APP_BRAND_DESKTOP_CONTAINER =
            "com.tencent.mm.plugin.appbrand.widget.desktop.AppBrandDesktopContainerView"
        const val CHAT_FOOTER_CUSTOM = "com.tencent.mm.ui.chatting.ChatFooterCustom"
        const val MAIN_FRAGMENT = "com.tencent.mm.ui.conversation.MainUI"
        const val CONTACTS_FRAGMENT = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"
        const val DISCOVER_FRAGMENT = "com.tencent.mm.ui.FindMoreFriendsUI"
        const val ME_FRAGMENT = "com.tencent.mm.ui.MoreTabUI"
        const val METHOD_CACHE_PREFS = "Hchat_background_beauty_method_cache"
        const val CACHE_CHAT_BACKGROUND_COMPONENT = "chat_background_component_init"
        const val CHAT_BACKGROUND_COMPONENT_TAG = "MicroMsg.ChattingUI.ChattingBackgroundComponent"
        const val CHAT_BACKGROUND_COMPONENT_INIT = "initBackground:"
        const val MAX_INITIAL_REFRESHES = 2
        const val MAX_LAUNCHER_BITMAPS = 2
        const val MAX_SCAN_NODES = 1200
        const val MAX_MAJOR_SURFACE_DEPTH = 3
        const val TAB_WECHAT = 0
        const val TAB_CONTACTS = 1
        const val TAB_DISCOVER = 2
        const val TAB_ME = 3
        const val IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private data class LoadRequest(
        val slot: BackgroundBeautySettings.Slot,
        val revision: Int
    )

    private data class LoadedBitmap(
        val revision: Int,
        val bitmap: Bitmap
    )

    private class SurfaceSnapshot(view: View) {
        var originalBackground: Drawable? = view.background
        var originalTintList: ColorStateList? = view.backgroundTintList
        var originalTintMode: PorterDuff.Mode? = view.backgroundTintMode
        var originalForeground: Drawable? = view.foreground
        var originalPaddingLeft: Int = view.paddingLeft
        var originalPaddingTop: Int = view.paddingTop
        var originalPaddingRight: Int = view.paddingRight
        var originalPaddingBottom: Int = view.paddingBottom
        var originalMinimumWidth: Int = view.minimumWidth
        var originalMinimumHeight: Int = view.minimumHeight
        var appliedBackground: Drawable? = null
        var appliedForeground: Drawable? = null
        var appliedBitmap: Bitmap? = null
        var appliedOpacity: Float? = null
        var slot: BackgroundBeautySettings.Slot? = null
    }

    private class NativeImageSnapshot(view: ImageView) {
        var originalDrawable: Drawable? = view.drawable
        val originalAlpha: Float = view.alpha
        val originalScaleType: ImageView.ScaleType = view.scaleType
        var originalTintList: ColorStateList? = view.imageTintList
        var appliedDrawable: Drawable? = null
        var appliedAlpha: Float? = null
        var appliedScaleType: ImageView.ScaleType? = null
        var appliedBitmap: Bitmap? = null
        var appliedOpacity: Float? = null
        var slot: BackgroundBeautySettings.Slot? = null
    }

    private class HostState(val activity: Activity) {
        val surfaces = IdentityHashMap<View, SurfaceSnapshot>()
        val nativeImages = IdentityHashMap<ImageView, NativeImageSnapshot>()
        val loadedBitmaps = LinkedHashMap<BackgroundBeautySettings.Slot, LoadedBitmap>(4, 0.75f, true)
        val originalSystemUiVisibility = activity.window.decorView.systemUiVisibility
        val originalStatusBarColor = activity.window.statusBarColor
        val originalNavigationBarColor = activity.window.navigationBarColor
        val originalNavigationBarDividerColor = if (android.os.Build.VERSION.SDK_INT >= 28) {
            activity.window.navigationBarDividerColor
        } else null
        val originalNavigationBarContrast = if (android.os.Build.VERSION.SDK_INT >= 29) {
            activity.window.isNavigationBarContrastEnforced
        } else null
        var systemBarsApplied = false
    }

    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        BackgroundBeautySettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), METHOD_CACHE_PREFS)
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-BackgroundBeauty").apply { isDaemon = true }
    }
    private val observedActivities = Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())
    private val states = Collections.synchronizedMap(WeakHashMap<Activity, HostState>())
    private val pendingLoads = Collections.synchronizedMap(WeakHashMap<Activity, LoadRequest>())
    private val pendingRefreshes = Collections.synchronizedMap(WeakHashMap<Activity, Runnable>())
    private val fragmentRoots = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )
    private val nativeChatImages = Collections.synchronizedMap(
        WeakHashMap<Activity, WeakReference<ImageView>>()
    )
    private val launcherTabIndices = Collections.synchronizedMap(WeakHashMap<Activity, Int>())
    private val layoutListeners = Collections.synchronizedMap(
        WeakHashMap<View, View.OnLayoutChangeListener>()
    )
    private val hookedMembers = ConcurrentHashMap.newKeySet<Member>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == BackgroundBeautySettings.KEY_ENABLE ||
            key == BackgroundBeautySettings.KEY_REVISION ||
            BackgroundBeautySettings.Slot.values().any { slot ->
                key == BackgroundBeautySettings.opacityKey(slot)
            }
        ) {
            main.post(::refreshAll)
        }
    }

    @Volatile
    private var destroyed = false

    fun install(): Boolean {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val installed = installActivityHooks() or
            installTabHooks() or
            installFragmentHooks() or
            installSurfaceHooks()
        installChatBackgroundComponentHook(allowDexSearch = false)
        if (!installed) HLog.e("$TAG 未安装任何页面 Hook")
        main.post(::refreshAll)
        return installed
    }

    fun destroy() {
        destroyed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        val activeStates = synchronized(states) { states.values.toList().also { states.clear() } }
        activeStates.forEach(::restoreState)
        synchronized(layoutListeners) {
            layoutListeners.forEach { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
            layoutListeners.clear()
        }
        observedActivities.clear()
        pendingLoads.clear()
        pendingRefreshes.clear()
        fragmentRoots.clear()
        nativeChatImages.clear()
        launcherTabIndices.clear()
    }

    private fun installActivityHooks(): Boolean {
        var installed = false
        listOf(CHAT_ACTIVITY, LAUNCHER_ACTIVITY).forEach { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader())
                ?: return@forEach
            KavaReflector.findMethodRecursive(clazz, "onCreate", Bundle::class.java)?.let { method ->
                installed = hookOnce(method, after = { param ->
                    (param.thisObject as? Activity)?.let { scheduleRefresh(it, MAX_INITIAL_REFRESHES) }
                }) || installed
            }
            KavaReflector.findMethodRecursive(clazz, "onResume")?.let { method ->
                installed = hookOnce(method, after = { param ->
                    (param.thisObject as? Activity)?.let { scheduleRefresh(it, MAX_INITIAL_REFRESHES) }
                }) || installed
            }
            KavaReflector.findMethodRecursive(
                clazz,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType!!
            )?.let { method ->
                installed = hookOnce(method, after = { param ->
                    if (param.args.firstOrNull() == true) {
                        (param.thisObject as? Activity)?.let { scheduleRefresh(it, 1) }
                    }
                }) || installed
            }
            KavaReflector.findMethodRecursive(clazz, "onDestroy")?.let { method ->
                installed = hookOnce(method, before = { param ->
                    (param.thisObject as? Activity)?.let(::forgetActivity)
                }) || installed
            }
        }
        return installed
    }

    private fun installTabHooks(): Boolean {
        var installed = false
        val bottomTab = KavaReflector.loadClass(BOTTOM_TAB_CLASS, context.hostClassLoader())
        KavaReflector.findMethodRecursive(
            bottomTab,
            "setTo",
            Int::class.javaPrimitiveType!!
        )?.let { method ->
            installed = hookOnce(method, after = { param ->
                val index = param.args.firstOrNull() as? Int ?: return@hookOnce
                findActivity(param.thisObject)?.let { activity ->
                    synchronized(launcherTabIndices) { launcherTabIndices[activity] = index }
                    scheduleRefresh(activity, MAX_INITIAL_REFRESHES)
                }
            }) || installed
        }
        val mainTab = KavaReflector.loadClass(MAIN_TAB_CLASS, context.hostClassLoader())
        KavaReflector.declaredMethods(mainTab)
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                    method.name in setOf("a", "c")
            }
            .forEach { method ->
                installed = hookOnce(method, after = { param ->
                    val index = param.args.firstOrNull() as? Int ?: return@hookOnce
                    findActivity(param.thisObject)?.let { activity ->
                        synchronized(launcherTabIndices) { launcherTabIndices[activity] = index }
                        scheduleRefresh(activity, MAX_INITIAL_REFRESHES)
                    }
                }) || installed
            }
        val tabsAdapter = KavaReflector.loadClass(
            "${MAIN_TAB_CLASS}\$TabsAdapter",
            context.hostClassLoader()
        )
        KavaReflector.findMethodRecursive(
            tabsAdapter,
            "onPageSelected",
            Int::class.javaPrimitiveType!!
        )?.let { method ->
            installed = hookOnce(method, after = { param ->
                val index = param.args.firstOrNull() as? Int ?: return@hookOnce
                findActivity(param.thisObject)?.let { activity ->
                    synchronized(launcherTabIndices) { launcherTabIndices[activity] = index }
                    scheduleRefresh(activity, MAX_INITIAL_REFRESHES)
                }
            }) || installed
        }
        return installed
    }

    private fun installFragmentHooks(): Boolean {
        var installed = false
        listOf(MAIN_FRAGMENT, CONTACTS_FRAGMENT, DISCOVER_FRAGMENT, ME_FRAGMENT).forEach { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader())
                ?: return@forEach
            KavaReflector.findMethodRecursive(clazz, "getLayoutView")?.let { method ->
                installed = hookOnce(method, after = { param ->
                    rememberFragmentRoot(param.thisObject, param.result as? View)
                }) || installed
            }
            KavaReflector.declaredMethods(clazz).firstOrNull { method ->
                method.name == "onViewCreated" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(View::class.java, Bundle::class.java)
                    )
            }?.let { method ->
                installed = hookOnce(method, after = { param ->
                    rememberFragmentRoot(param.thisObject, param.args.firstOrNull() as? View)
                }) || installed
            }
            KavaReflector.findMethodRecursive(clazz, "onResume")?.let { method ->
                installed = hookOnce(method, after = { param ->
                    rememberFragmentRoot(param.thisObject, null)
                }) || installed
            }
        }
        return installed
    }

    private fun installSurfaceHooks(): Boolean {
        var installed = false
        val chatImageClass = KavaReflector.loadClass(CHATTING_IMAGE_BG_VIEW, context.hostClassLoader())
        KavaReflector.declaredConstructors(chatImageClass).forEach { constructor ->
            installed = hookOnce(constructor, after = { param ->
                (param.thisObject as? ImageView)?.let(::rememberNativeChatImage)
            }) || installed
        }
        listOf(APP_BRAND_DESKTOP_CONTAINER, CHAT_FOOTER_CUSTOM).forEach { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader())
            KavaReflector.declaredConstructors(clazz).forEach { constructor ->
                installed = hookOnce(constructor, after = { param ->
                    (param.thisObject as? View)?.let { view ->
                        view.post {
                            findActivity(view)?.let {
                                scheduleRefresh(it, MAX_INITIAL_REFRESHES)
                            }
                        }
                    }
                }) || installed
            }
        }
        val bounceClass = KavaReflector.loadClass(BOUNCE_VIEW, context.hostClassLoader())
        listOf(
            "setStart2EndBgColorByActionBar",
            "setEnd2StartBgColorByNavigationBar",
            "setStart2EndBgColor",
            "setEnd2StartBgColor",
            "setBgColor"
        ).forEach { name ->
            KavaReflector.findMethodRecursive(
                bounceClass,
                name,
                Int::class.javaPrimitiveType!!
            )?.let { method ->
                installed = hookOnce(method, before = { param ->
                    val view = param.thisObject as? View ?: return@hookOnce
                    val activity = findActivity(view) ?: return@hookOnce
                    if (hasConfiguredBackground(activity)) param.args[0] = Color.TRANSPARENT
                }) || installed
            }
        }
        return installed
    }

    @Synchronized
    fun installChatBackgroundComponentHook(allowDexSearch: Boolean): Boolean {
        if (destroyed) return false
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_CHAT_BACKGROUND_COMPONENT
        )?.takeIf { it.returnType == Void.TYPE && it.parameterTypes.isEmpty() }
        val method = cached ?: run {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_CHAT_BACKGROUND_COMPONENT)
            if (!allowDexSearch) return false
            val candidates = runCatching {
                context.dexKitBridge().findMethod(FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                CHAT_BACKGROUND_COMPONENT_TAG,
                                CHAT_BACKGROUND_COMPONENT_INIT
                            )
                        )
                    })
                }).asSequence()
                    .mapNotNull { data ->
                        runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    }
                    .filter { candidate ->
                        candidate.returnType == Void.TYPE && candidate.parameterTypes.isEmpty()
                    }
                    .distinctBy { candidate -> candidate.toGenericString() }
                    .toList()
            }.getOrElse {
                HLog.e("$TAG 定位聊天原生背景初始化失败: ${it.message}", it)
                emptyList()
            }
            val located = candidates.singleOrNull()
            if (located == null) {
                HLog.e("$TAG 未找到唯一聊天原生背景初始化入口: count=${candidates.size}")
                return false
            }
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_CHAT_BACKGROUND_COMPONENT, located)
            located
        }
        return hookOnce(method, after = { param -> bindNativeChatBackground(param.thisObject) })
    }

    private fun bindNativeChatBackground(component: Any?) {
        component ?: return
        val backgroundId = resourceId("bka")
        val imageView = resolveComponentChatImage(component, backgroundId) ?: return
        rememberNativeChatImage(imageView)
    }

    private fun resolveComponentChatImage(component: Any, backgroundId: Int): ImageView? {
        if (backgroundId != 0) {
            val viewOwner = KavaReflector.readField(component, "d")
            val lookup = viewOwner?.let { owner ->
                KavaReflector.findMethodRecursive(owner.javaClass, "c", Integer.TYPE)
                    ?.takeIf { View::class.java.isAssignableFrom(it.returnType) }
            }
            val resolved = if (viewOwner != null && lookup != null) {
                KavaReflector.invoke(lookup, viewOwner, backgroundId) as? View
            } else null
            if (resolved is ImageView) return resolved
            resolved?.findViewById<ImageView>(backgroundId)?.let { return it }
        }
        val nativeBackground = KavaReflector.readField(component, "f") as? ImageView
        if (nativeBackground != null &&
            ((backgroundId != 0 && nativeBackground.id == backgroundId) ||
                hasClassName(nativeBackground, CHATTING_IMAGE_BG_VIEW))
        ) {
            return nativeBackground
        }
        return null
    }

    private fun rememberNativeChatImage(imageView: ImageView) {
        val activity = findActivity(imageView) ?: return
        synchronized(nativeChatImages) { nativeChatImages[activity] = WeakReference(imageView) }
        imageView.post { scheduleRefresh(activity, MAX_INITIAL_REFRESHES) }
    }

    private fun rememberFragmentRoot(fragment: Any?, supplied: View?) {
        if (fragment == null || slotForFragment(fragment) == null) return
        val root = supplied ?: fragmentView(fragment) ?: return
        synchronized(fragmentRoots) { fragmentRoots[fragment] = WeakReference(root) }
        observeLayout(root)
        val activity = findActivity(fragment) ?: findActivity(root) ?: return
        scheduleRefresh(activity, MAX_INITIAL_REFRESHES)
    }

    private fun observeLayout(view: View) {
        if (view.isLaidOut && view.width > 0 && view.height > 0) return
        synchronized(layoutListeners) {
            if (layoutListeners.containsKey(view)) return
            val listener = View.OnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
                synchronized(layoutListeners) {
                    layoutListeners.remove(target)?.let(target::removeOnLayoutChangeListener)
                }
                if (!destroyed) findActivity(target)?.let { scheduleRefresh(it) }
            }
            layoutListeners[view] = listener
            view.addOnLayoutChangeListener(listener)
        }
    }

    private fun hookOnce(
        member: Member,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    ): Boolean {
        if (!hookedMembers.add(member)) return true
        return runCatching {
            HookRegistry.get().hook(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    before?.invoke(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    after?.invoke(param)
                }
            })
            true
        }.getOrElse {
            hookedMembers.remove(member)
            HLog.e("$TAG Hook 安装失败: $member", it)
            false
        }
    }

    private fun scheduleRefresh(activity: Activity, retries: Int = 0) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        synchronized(observedActivities) { observedActivities[activity] = true }
        val task = synchronized(pendingRefreshes) {
            if (pendingRefreshes.containsKey(activity)) return@synchronized null
            Runnable {
                synchronized(pendingRefreshes) { pendingRefreshes.remove(activity) }
                applyForActivity(activity)
                if (retries > 0 && !destroyed) {
                    main.postDelayed({ scheduleRefresh(activity, retries - 1) }, 120L)
                }
            }.also { pendingRefreshes[activity] = it }
        } ?: return
        activity.window.decorView.postOnAnimation(task)
    }

    private fun refreshAll() {
        if (destroyed) return
        runCatching { WeChatApis.currentActivity()?.currentActivity() }
            .getOrNull()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?.let { activity -> synchronized(observedActivities) { observedActivities[activity] = true } }
        val activities = synchronized(observedActivities) { observedActivities.keys.toList() }
        activities.forEach { activity ->
            if (activity.isFinishing || activity.isDestroyed) forgetActivity(activity)
            else scheduleRefresh(activity, MAX_INITIAL_REFRESHES)
        }
    }

    private fun applyForActivity(activity: Activity) {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        if (!prefs.getBoolean(BackgroundBeautySettings.KEY_ENABLE, BackgroundBeautySettings.DEFAULT_ENABLE)) {
            clearActivity(activity)
            return
        }
        val slot = resolveTarget(activity) ?: run {
            clearActivity(activity)
            return
        }
        if (!BackgroundBeautyStore.hasImage(context.hostContext(), slot)) {
            synchronized(states) { states[activity] }?.let { state ->
                restoreSlot(state, slot)
                restoreSharedSurfaces(state)
                restoreSystemBars(state)
            }
            return
        }
        val revision = prefs.getInt(BackgroundBeautySettings.KEY_REVISION, 0)
        val state = ensureState(activity)
        state.loadedBitmaps[slot]?.takeIf { it.revision == revision }?.let { loaded ->
            applyBitmap(state, slot, loaded.bitmap)
            return
        }
        val request = LoadRequest(slot, revision)
        if (synchronized(pendingLoads) { pendingLoads[activity] } == request) return
        synchronized(pendingLoads) { pendingLoads[activity] = request }
        loader.execute {
            val bitmap = BackgroundBeautyStore.loadBitmap(context.hostContext(), slot)
            main.post {
                if (synchronized(pendingLoads) { pendingLoads[activity] } != request) return@post
                synchronized(pendingLoads) { pendingLoads.remove(activity) }
                if (bitmap == null || destroyed || activity.isFinishing || activity.isDestroyed) return@post
                if (!prefs.getBoolean(
                        BackgroundBeautySettings.KEY_ENABLE,
                        BackgroundBeautySettings.DEFAULT_ENABLE
                    ) ||
                    resolveTarget(activity) != slot ||
                    !BackgroundBeautyStore.hasImage(context.hostContext(), slot)
                ) return@post
                val currentState = ensureState(activity)
                currentState.loadedBitmaps[slot] = LoadedBitmap(revision, bitmap)
                trimLauncherBitmaps(currentState, slot)
                applyBitmap(currentState, slot, bitmap)
            }
        }
    }

    private fun ensureState(activity: Activity): HostState = synchronized(states) {
        states[activity] ?: HostState(activity).also { states[activity] = it }
    }

    private fun trimLauncherBitmaps(
        state: HostState,
        current: BackgroundBeautySettings.Slot
    ) {
        if (current == BackgroundBeautySettings.Slot.CHAT) return
        while (state.loadedBitmaps.keys.count { it != BackgroundBeautySettings.Slot.CHAT } >
            MAX_LAUNCHER_BITMAPS
        ) {
            val eldest = state.loadedBitmaps.keys.firstOrNull {
                it != current && it != BackgroundBeautySettings.Slot.CHAT
            } ?: return
            state.loadedBitmaps.remove(eldest)
            restoreSlot(state, eldest)
        }
    }

    private fun applyBitmap(
        state: HostState,
        slot: BackgroundBeautySettings.Slot,
        bitmap: Bitmap
    ) {
        if (bitmap.isRecycled) return
        val activity = state.activity
        val opacity = BackgroundBeautySettings.opacity(
            prefs.getFloat(
                BackgroundBeautySettings.opacityKey(slot),
                BackgroundBeautySettings.DEFAULT_OPACITY
            )
        )
        val host = resolveBackgroundHost(activity) ?: return
        setImageSurface(state, host, slot, bitmap, opacity)
        val content = activity.findViewById<View>(android.R.id.content)
        if (content != null && content !== host) setTransparentSurface(state, content, slot)
        if (slot == BackgroundBeautySettings.Slot.CHAT) {
            applyChatBackground(state, bitmap, opacity)
        } else {
            applyLauncherBackground(state, slot, bitmap, opacity)
        }
        applyChromeTransparency(state, slot)
        applySystemBars(state)
    }

    private fun applyLauncherBackground(
        state: HostState,
        slot: BackgroundBeautySettings.Slot,
        bitmap: Bitmap,
        opacity: Float
    ) {
        val roots = LinkedHashSet<View>()
        currentFragment(state.activity)
            ?.takeIf { fragment -> slotForFragment(fragment) == slot }
            ?.let(::fragmentView)
            ?.takeIf { root -> findActivity(root) === state.activity }
            ?.let(roots::add)
        synchronized(fragmentRoots) {
            fragmentRoots.forEach { (fragment, reference) ->
                if (slotForFragment(fragment) == slot) {
                    reference.get()
                        ?.takeIf { root -> findActivity(root) === state.activity }
                        ?.let(roots::add)
                }
            }
        }
        roots.filter { it.isAttachedToWindow }.forEach { root ->
            observeLayout(root)
            setImageSurface(state, root, slot, bitmap, opacity)
            applyWhitelistedSurfaces(state, root, slot)
        }
    }

    private fun applyChatBackground(
        state: HostState,
        bitmap: Bitmap,
        opacity: Float
    ) {
        val activity = state.activity
        val image = synchronized(nativeChatImages) {
            nativeChatImages[activity]?.get()?.takeIf { it.isAttachedToWindow }
        } ?: findDescendant(
            activity.findViewById(android.R.id.content),
            CHATTING_IMAGE_BG_VIEW
        ) as? ImageView
        if (image != null) setNativeImage(state, image, bitmap, opacity)
        activity.findViewById<View>(android.R.id.content)?.let { root ->
            applyWhitelistedSurfaces(state, root, BackgroundBeautySettings.Slot.CHAT)
        }
    }

    private fun applyWhitelistedSurfaces(
        state: HostState,
        root: View,
        slot: BackgroundBeautySettings.Slot
    ) {
        val targetIds = targetResourceIds(slot)
        val queue = ArrayDeque<View>()
        val depths = ArrayDeque<Int>()
        queue.add(root)
        depths.add(0)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_SCAN_NODES) {
            val view = queue.removeFirst()
            val depth = depths.removeFirst()
            val name = view.javaClass.name
            if (view !== root && isBottomBar(view)) continue
            if (view !== root && isComposeSurface(view)) continue
            if (name == APP_BRAND_DESKTOP_CONTAINER ||
                name == BOUNCE_VIEW ||
                name == CHAT_FOOTER_CUSTOM ||
                (view !== root && depth <= MAX_MAJOR_SURFACE_DEPTH && isMajorSurface(view))
            ) {
                if (!hasClassName(view, CHATTING_IMAGE_BG_VIEW)) {
                    setTransparentSurface(state, view, slot)
                }
            }
            if (view.id in targetIds) {
                setTransparentSurface(state, view, slot, clearForeground = true)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue.addLast(view.getChildAt(index))
                    depths.addLast(depth + 1)
                }
            }
        }
    }

    private fun applyChromeTransparency(
        state: HostState,
        slot: BackgroundBeautySettings.Slot
    ) {
        val host = resolveBackgroundHost(state.activity)
        val cached = state.surfaces.keys.filter { view ->
            view !== host && view.isAttachedToWindow && isChromeSurface(view)
        }
        if (cached.isNotEmpty()) {
            cached.forEach { view -> setTransparentSurface(state, view, slot) }
            return
        }
        val decor = state.activity.window.decorView
        val queue = ArrayDeque<View>()
        queue.add(decor)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_SCAN_NODES) {
            val view = queue.removeFirst()
            if (view !== decor && isBottomBar(view)) continue
            if (view !== decor && isComposeSurface(view)) continue
            if (view !== host && isChromeSurface(view)) {
                setTransparentSurface(state, view, slot)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.addLast(view.getChildAt(index))
            }
        }
    }

    private fun targetResourceIds(slot: BackgroundBeautySettings.Slot): Set<Int> {
        val names = when (slot) {
            BackgroundBeautySettings.Slot.WECHAT -> listOf("cj1")
            BackgroundBeautySettings.Slot.ME -> listOf("gxp", "gxn", "hxi")
            else -> emptyList()
        }
        return names.mapNotNull { name -> resourceId(name).takeIf { it != 0 } }.toSet()
    }

    private fun isMajorSurface(view: View): Boolean {
        val name = view.javaClass.name
        return view is AbsListView ||
            view is ScrollView ||
            name.contains("RecyclerView") ||
            name.contains("NestedScrollView")
    }

    private fun isChromeSurface(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("ActionBar", ignoreCase = true) ||
            name.contains("Toolbar", ignoreCase = true)
    }

    private fun isComposeSurface(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("ComposeView") || name.startsWith("androidx.compose.")
    }

    private fun setImageSurface(
        state: HostState,
        view: View,
        slot: BackgroundBeautySettings.Slot,
        bitmap: Bitmap,
        opacity: Float
    ) {
        val snapshot = surfaceSnapshot(state, view)
        synchronizeExternalBackground(snapshot, view)
        if (snapshot.slot == slot &&
            snapshot.appliedBitmap === bitmap &&
            snapshot.appliedOpacity == opacity &&
            view.background === snapshot.appliedBackground
        ) return
        val image = ScreenAlignedBitmapDrawable(bitmap, view, opacity)
        val applied = if (opacity < 0.999f) {
            LayerDrawable(
                arrayOf(
                    cloneDrawable(snapshot.originalBackground, view.context)
                        ?: ColorDrawable(resolveThemeBackground(view.context)),
                    image
                )
            )
        } else {
            image
        }
        view.backgroundTintList = null
        view.backgroundTintMode = null
        view.background = applied
        restoreSurfaceGeometry(view, snapshot)
        snapshot.appliedBackground = applied
        snapshot.appliedBitmap = bitmap
        snapshot.appliedOpacity = opacity
        snapshot.slot = slot
    }

    private fun setTransparentSurface(
        state: HostState,
        view: View,
        slot: BackgroundBeautySettings.Slot,
        clearForeground: Boolean = false
    ) {
        val snapshot = surfaceSnapshot(state, view)
        synchronizeExternalBackground(snapshot, view)
        if (snapshot.slot == slot &&
            snapshot.appliedBitmap == null &&
            snapshot.appliedBackground != null &&
            view.background === snapshot.appliedBackground &&
            (!clearForeground || view.foreground === snapshot.appliedForeground)
        ) return
        val marker = BackgroundBeautyTransparentDrawable()
        view.backgroundTintList = null
        view.backgroundTintMode = null
        view.background = marker
        restoreSurfaceGeometry(view, snapshot)
        snapshot.appliedBackground = marker
        snapshot.appliedBitmap = null
        snapshot.appliedOpacity = null
        if (clearForeground) {
            val foregroundMarker = BackgroundBeautyTransparentDrawable()
            view.foreground = foregroundMarker
            snapshot.appliedForeground = foregroundMarker
        }
        snapshot.slot = slot
    }

    private fun surfaceSnapshot(state: HostState, view: View): SurfaceSnapshot {
        return state.surfaces[view] ?: SurfaceSnapshot(view).also { state.surfaces[view] = it }
    }

    private fun synchronizeExternalBackground(snapshot: SurfaceSnapshot, view: View) {
        val applied = snapshot.appliedBackground
        if (applied != null && view.background !== applied) {
            snapshot.originalBackground = view.background
            snapshot.originalTintList = view.backgroundTintList
            snapshot.originalTintMode = view.backgroundTintMode
            snapshot.originalPaddingLeft = view.paddingLeft
            snapshot.originalPaddingTop = view.paddingTop
            snapshot.originalPaddingRight = view.paddingRight
            snapshot.originalPaddingBottom = view.paddingBottom
            snapshot.originalMinimumWidth = view.minimumWidth
            snapshot.originalMinimumHeight = view.minimumHeight
            snapshot.appliedBackground = null
            snapshot.appliedBitmap = null
            snapshot.appliedOpacity = null
        }
        val foreground = snapshot.appliedForeground
        if (foreground != null && view.foreground !== foreground) {
            snapshot.originalForeground = view.foreground
            snapshot.appliedForeground = null
        }
    }

    private fun setNativeImage(
        state: HostState,
        view: ImageView,
        bitmap: Bitmap,
        opacity: Float
    ) {
        val snapshot = state.nativeImages[view] ?: NativeImageSnapshot(view).also {
            state.nativeImages[view] = it
        }
        if (snapshot.slot == BackgroundBeautySettings.Slot.CHAT &&
            snapshot.appliedBitmap === bitmap &&
            snapshot.appliedOpacity == opacity &&
            view.drawable === snapshot.appliedDrawable
        ) return
        if (snapshot.appliedDrawable != null && view.drawable !== snapshot.appliedDrawable) {
            snapshot.originalDrawable = view.drawable
            snapshot.originalTintList = view.imageTintList
        }
        view.imageTintList = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bitmap)
        view.alpha = opacity
        snapshot.appliedDrawable = view.drawable
        snapshot.appliedAlpha = opacity
        snapshot.appliedScaleType = ImageView.ScaleType.CENTER_CROP
        snapshot.appliedBitmap = bitmap
        snapshot.appliedOpacity = opacity
        snapshot.slot = BackgroundBeautySettings.Slot.CHAT
    }

    private fun restoreSlot(state: HostState, slot: BackgroundBeautySettings.Slot) {
        state.loadedBitmaps.remove(slot)
        state.surfaces.entries.toList().forEach { (view, snapshot) ->
            if (snapshot.slot == slot) {
                restoreSurface(view, snapshot)
                state.surfaces.remove(view)
            }
        }
        state.nativeImages.entries.toList().forEach { (view, snapshot) ->
            if (snapshot.slot == slot) {
                restoreNativeImage(view, snapshot)
                state.nativeImages.remove(view)
            }
        }
    }

    private fun restoreSharedSurfaces(state: HostState) {
        val host = resolveBackgroundHost(state.activity)
        val content = state.activity.findViewById<View>(android.R.id.content)
        state.surfaces.entries.toList().forEach { (view, snapshot) ->
            if (view === host || view === content || isChromeSurface(view)) {
                restoreSurface(view, snapshot)
                state.surfaces.remove(view)
            }
        }
    }

    private fun restoreSurface(view: View, snapshot: SurfaceSnapshot) {
        val appliedBackground = snapshot.appliedBackground
        if (appliedBackground != null && view.background === appliedBackground) {
            view.background = snapshot.originalBackground
            view.backgroundTintList = snapshot.originalTintList
            view.backgroundTintMode = snapshot.originalTintMode
            restoreSurfaceGeometry(view, snapshot)
        }
        snapshot.appliedForeground?.let { appliedForeground ->
            if (view.foreground === appliedForeground) {
                view.foreground = snapshot.originalForeground
            }
        }
    }

    private fun restoreNativeImage(view: ImageView, snapshot: NativeImageSnapshot) {
        if (view.drawable === snapshot.appliedDrawable) {
            view.setImageDrawable(snapshot.originalDrawable)
            view.imageTintList = snapshot.originalTintList
        }
        snapshot.appliedAlpha?.let { applied ->
            if (view.alpha == applied) view.alpha = snapshot.originalAlpha
        }
        snapshot.appliedScaleType?.let { applied ->
            if (view.scaleType == applied) view.scaleType = snapshot.originalScaleType
        }
    }

    private fun restoreSurfaceGeometry(view: View, snapshot: SurfaceSnapshot) {
        view.setPadding(
            snapshot.originalPaddingLeft,
            snapshot.originalPaddingTop,
            snapshot.originalPaddingRight,
            snapshot.originalPaddingBottom
        )
        view.minimumWidth = snapshot.originalMinimumWidth
        view.minimumHeight = snapshot.originalMinimumHeight
    }

    private fun applySystemBars(state: HostState) {
        val window = state.activity.window
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or IMMERSIVE_FLAGS
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        state.systemBarsApplied = true
    }

    private fun restoreSystemBars(state: HostState) {
        if (!state.systemBarsApplied) return
        val window = state.activity.window
        window.decorView.systemUiVisibility =
            (window.decorView.systemUiVisibility and IMMERSIVE_FLAGS.inv()) or
            (state.originalSystemUiVisibility and IMMERSIVE_FLAGS)
        if (window.statusBarColor == Color.TRANSPARENT) {
            window.statusBarColor = state.originalStatusBarColor
        }
        if (window.navigationBarColor == Color.TRANSPARENT) {
            window.navigationBarColor = state.originalNavigationBarColor
        }
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
            state.originalNavigationBarDividerColor != null &&
            window.navigationBarDividerColor == Color.TRANSPARENT
        ) {
            window.navigationBarDividerColor = state.originalNavigationBarDividerColor
        }
        if (android.os.Build.VERSION.SDK_INT >= 29 && state.originalNavigationBarContrast != null) {
            window.isNavigationBarContrastEnforced = state.originalNavigationBarContrast
        }
        state.systemBarsApplied = false
    }

    private fun clearActivity(activity: Activity) {
        synchronized(pendingLoads) { pendingLoads.remove(activity) }
        synchronized(pendingRefreshes) { pendingRefreshes.remove(activity) }
        removeLayoutListeners(activity)
        val state = synchronized(states) { states.remove(activity) } ?: return
        restoreState(state)
    }

    private fun removeLayoutListeners(activity: Activity) {
        synchronized(layoutListeners) {
            val targets = layoutListeners.keys.filter { view -> findActivity(view) === activity }
            targets.forEach { view ->
                layoutListeners.remove(view)?.let(view::removeOnLayoutChangeListener)
            }
        }
    }

    private fun forgetActivity(activity: Activity) {
        synchronized(observedActivities) { observedActivities.remove(activity) }
        synchronized(nativeChatImages) { nativeChatImages.remove(activity) }
        synchronized(launcherTabIndices) { launcherTabIndices.remove(activity) }
        clearActivity(activity)
    }

    private fun restoreState(state: HostState) {
        state.surfaces.entries.toList().forEach { (view, snapshot) ->
            restoreSurface(view, snapshot)
        }
        state.nativeImages.entries.toList().forEach { (view, snapshot) ->
            restoreNativeImage(view, snapshot)
        }
        state.surfaces.clear()
        state.nativeImages.clear()
        state.loadedBitmaps.clear()
        restoreSystemBars(state)
    }

    private fun resolveBackgroundHost(activity: Activity): View? {
        val content = activity.findViewById<View>(android.R.id.content) ?: return null
        var current: View? = content
        while (current != null && current !== activity.window.decorView) {
            if (current.javaClass.name.contains("ActionBarOverlayLayout")) return current
            current = current.parent as? View
        }
        return content
    }

    private fun resolveTarget(activity: Activity): BackgroundBeautySettings.Slot? {
        val name = activity.javaClass.name
        if (name == CHAT_ACTIVITY || name.contains("ChattingUI")) {
            return BackgroundBeautySettings.Slot.CHAT
        }
        if (name != LAUNCHER_ACTIVITY && !name.endsWith("LauncherUI")) return null
        val fragment = currentFragment(activity)
        if (fragment?.javaClass?.name?.contains("chatting", ignoreCase = true) == true) {
            return BackgroundBeautySettings.Slot.CHAT
        }
        return currentLauncherSlot(activity)
    }

    private fun currentLauncherSlot(activity: Activity): BackgroundBeautySettings.Slot? {
        val index = KavaReflector.findMethodRecursive(activity.javaClass, "getCurrentTabIndex")
            ?.takeIf { it.parameterTypes.isEmpty() }
            ?.let { method -> KavaReflector.invoke(method, activity) as? Int }
            ?: synchronized(launcherTabIndices) { launcherTabIndices[activity] }
        return when (index) {
            TAB_WECHAT -> BackgroundBeautySettings.Slot.WECHAT
            TAB_CONTACTS -> BackgroundBeautySettings.Slot.CONTACTS
            TAB_DISCOVER -> BackgroundBeautySettings.Slot.DISCOVER
            TAB_ME -> BackgroundBeautySettings.Slot.ME
            else -> slotForFragment(currentFragment(activity))
        }
    }

    private fun currentFragment(activity: Activity): Any? {
        KavaReflector.findMethodRecursive(activity.javaClass, "getCurrentFragmet")
            ?.takeIf { it.parameterTypes.isEmpty() }
            ?.let { method -> KavaReflector.invoke(method, activity) }
            ?.let { return it }
        val mainTab = findFieldValueByClassName(activity, MAIN_TAB_CLASS) ?: return null
        return KavaReflector.findMethodRecursive(mainTab.javaClass, "g")
            ?.takeIf { it.parameterTypes.isEmpty() }
            ?.let { method -> KavaReflector.invoke(method, mainTab) }
    }

    private fun fragmentView(fragment: Any): View? {
        synchronized(fragmentRoots) {
            fragmentRoots[fragment]?.get()?.let { return it }
        }
        return KavaReflector.findMethodRecursive(fragment.javaClass, "getView")
            ?.takeIf { it.parameterTypes.isEmpty() }
            ?.let { method -> KavaReflector.invoke(method, fragment) as? View }
    }

    private fun slotForFragment(fragment: Any?): BackgroundBeautySettings.Slot? {
        fragment ?: return null
        return when {
            hasClassName(fragment, MAIN_FRAGMENT) -> BackgroundBeautySettings.Slot.WECHAT
            hasClassName(fragment, CONTACTS_FRAGMENT) -> BackgroundBeautySettings.Slot.CONTACTS
            hasClassName(fragment, DISCOVER_FRAGMENT) -> BackgroundBeautySettings.Slot.DISCOVER
            hasClassName(fragment, ME_FRAGMENT) -> BackgroundBeautySettings.Slot.ME
            else -> null
        }
    }

    private fun resourceId(name: String): Int {
        val host = context.hostContext()
        return host.resources.getIdentifier(name, "id", host.packageName)
    }

    private fun cloneDrawable(drawable: Drawable?, context: Context): Drawable? {
        return drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }

    private fun resolveThemeBackground(context: Context): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
            value.data
        } else {
            Color.TRANSPARENT
        }
    }

    private fun hasConfiguredBackground(activity: Activity): Boolean {
        if (!prefs.getBoolean(BackgroundBeautySettings.KEY_ENABLE, BackgroundBeautySettings.DEFAULT_ENABLE)) {
            return false
        }
        val slot = resolveTarget(activity) ?: return false
        return BackgroundBeautyStore.hasImage(context.hostContext(), slot)
    }

    private fun isBottomBar(view: View): Boolean {
        val name = view.javaClass.name
        return name == BOTTOM_TAB_CLASS ||
            name == "com.tencent.mm.ui.LauncherUITabView" ||
            view.tag == "Hchat:FloatingBottomBar"
    }

    private fun findDescendant(root: View?, className: String): View? {
        root ?: return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_SCAN_NODES) {
            val view = queue.removeFirst()
            if (hasClassName(view, className)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.addLast(view.getChildAt(index))
            }
        }
        return null
    }

    private fun findFieldValueByClassName(owner: Any, className: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java) {
            KavaReflector.declaredFields(type).forEach { field ->
                if (field.type.name == className) {
                    KavaReflector.readField(field, owner)?.let { return it }
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun hasClassName(value: Any, className: String): Boolean {
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            if (type.name == className) return true
            type = type.superclass
        }
        return false
    }

    private fun findActivity(owner: Any?): Activity? = findActivity(
        owner,
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    )

    private fun findActivity(owner: Any?, visited: MutableSet<Any>): Activity? {
        if (owner == null || !visited.add(owner)) return null
        if (owner is Activity) return owner
        var current: Context? = when (owner) {
            is View -> owner.context
            is Context -> owner
            else -> null
        }
        while (current != null) {
            if (current is Activity) return current
            val base = (current as? ContextWrapper)?.baseContext ?: break
            if (base === current) break
            current = base
        }
        KavaReflector.findMethodRecursive(owner.javaClass, "getActivity")
            ?.takeIf {
                it.parameterTypes.isEmpty() && Activity::class.java.isAssignableFrom(it.returnType)
            }
            ?.let { method -> KavaReflector.invoke(method, owner) as? Activity }
            ?.let { return it }
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java) {
            KavaReflector.declaredFields(type).forEach { field ->
                if (KavaReflector.isStatic(field)) return@forEach
                val value = KavaReflector.readField(field, owner) ?: return@forEach
                if (value is Activity) return value
                if (value is View || value is Context) {
                    findActivity(value, visited)?.let { return it }
                }
            }
            type = type.superclass
        }
        return null
    }
}
