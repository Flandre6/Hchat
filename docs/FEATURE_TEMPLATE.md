# Feature Template

新增功能时复制这个结构，不要把业务逻辑写到 `hooks/api` 或 `loader`。

代码语言原则：能用 Kotlin 写就尽量写 Kotlin；如果某段代码因为 Xposed、反射、DexKit、R8 或 Java 互调原因更适合 Java，就用 Java，不要硬转。

## Directory

简单功能使用单层目录：

```text
hooks/items/example/
  ExampleFeature.kt
  ExampleSettingsProvider.kt
  ExampleSettings.kt
```

复杂功能按职责拆子包，入口类保持很薄：

```text
hooks/items/example/core/
  ExampleFeature.kt            # 只注册设置、订阅、组装组件
  ExampleSettings.kt           # 只读配置
  ExampleState.kt              # 只放内存状态
  ExampleCoordinator.kt        # 串联业务流程
hooks/items/example/detect/    # 识别、解析、过滤
hooks/items/example/action/    # 执行动作，例如发送消息、网络请求
hooks/items/example/notify/    # Toast、系统通知、模板变量
```

不要把功能私有逻辑放进 `hooks/api`。只有多个功能都能复用、并且已经确认兼容的微信能力才放 API 层。

设置 UI 不放在功能包里。新增功能的设置页面统一写到 `ui/miuix/MiuixSettingsPage.kt`，并从 `FeatureSettingsPage(...)` 按 `featureId()` 分发。

主设置页已经内置 KSU 风格 Miuix 液态玻璃底部导航：实用、娱乐、插件、设置。不要为单个功能再做独立底部导航；功能详情页需要底部操作时使用统一的 `BottomActionBar`。

当前页签归类：

- `实用`: 红包、转账等日常工具，当前放 `AutoRedPacketFeature` 和 `AutoTransferFeature`。
- `娱乐`: 消息/玩法类功能，当前暂无默认功能。
- `插件`: 插件相关功能，例如脚本插件。
- `设置`: 全局 UI 设置和关于信息。

支付相关功能不要在业务包里重复找微信混淆类。普通转账领取/退回使用 `WeChatApis.payment().transfers()`，消息识别和规则判断可以放在功能包内。

## Feature

```kotlin
package h.Hchat.hooks.items.example

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext

class ExampleFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "示例功能"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ExampleSettingsProvider())
        subscribe(Events.MessageReceived::class.java) {
            // lightweight event handling
        }
    }

    @Throws(Throwable::class)
    override fun onFeatureInstall(context: FeatureContext) {
        if (!featureBoolean("enabled", true)) return
        if (WeChatApis.message().observe() != null && WeChatApis.message().hasObserve()) {
            trackSubscription(WeChatApis.message().observe().subscribe {
                // API subscription, auto cleanup by BaseFeature
            })
        }
    }

    companion object {
        const val ID = "example"
    }
}
```

## Settings Provider

Provider 只提供入口元信息，不打开页面。

```kotlin
package h.Hchat.hooks.items.example

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ExampleSettingsProvider : SimpleFeatureSettingsProvider(
    ExampleFeature.ID,
    "示例功能",
    "一句话说明",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
```

不要实现或恢复 `showDetail(Context)`。不要在 Provider 中创建 `Dialog`、`AlertDialog`、`Activity` 或自定义悬浮 View。Provider 只保留 `featureId/title/subtitle/category`，不要重新加图标、颜色或旧 `UIEntry`。

分类规则：

- `FeatureSettingsProvider.CATEGORY_PRACTICAL`: 放到 `实用`
- `FeatureSettingsProvider.CATEGORY_ENTERTAINMENT`: 放到 `娱乐`
- `FeatureSettingsProvider.CATEGORY_ENHANCE`: 放到 `插件`

三参数 `SimpleFeatureSettingsProvider(featureId, title, subtitle)` 默认放到 `插件`。不要在 `MiuixSettingsPage` 主页面里按功能 ID 写分组判断。

## Miuix Settings Page

在 `app/src/main/java/h/Hchat/ui/miuix/MiuixSettingsPage.kt` 的 `FeatureSettingsPage(...)` 添加分支：

```kotlin
@Composable
private fun FeatureSettingsPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    when (provider.featureId()) {
        ExampleFeature.ID -> ExampleMiuixPage(context, provider, onBack)
        else -> UnsupportedFeaturePage(provider, onBack)
    }
}
```

页面模板：

```kotlin
@Composable
private fun ExampleMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, "example_settings") }
    var optionPicker by remember { mutableStateOf<OptionPickerRequest?>(null) }
    var picker by remember { mutableStateOf<ContactPickerRequest?>(null) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    optionPicker?.let { request ->
        OptionPickerPage(
            request = request,
            onBack = { optionPicker = null },
            onSelected = { selected ->
                request.onSelected(selected)
                optionPicker = null
            }
        )
        return
    }

    picker?.let { request ->
        ContactPickerPage(
            context = context,
            request = request,
            onBack = { picker = null },
            onConfirm = { selected ->
                request.onValue(selected.joinToString("|") { it.id })
                picker = null
            }
        )
        return
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    // write full form values here
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "功能") }
            item {
                SettingsCard {
                    SwitchRow(sp, "enabled", "启用", "开启示例功能", true)
                    InsetDivider()
                    OptionRow(
                        sp = sp,
                        key = "mode",
                        title = "模式",
                        options = optionItems("模式一" to 0, "模式二" to 1),
                        defaultValue = 0,
                        openPicker = { optionPicker = it }
                    )
                    InsetDivider()
                    ActionRow("选择联系人", "从好友或群聊列表选择") {
                        picker = ContactPickerRequest(
                            title = "选择联系人",
                            mode = ContactPickerMode.BOTH,
                            multiSelect = true,
                            existingValue = "",
                            onValue = { value ->
                                sp.edit().putString("contacts", value).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}
```

UI 规则：

- 使用 `PageScaffold` + Compose `LazyColumn` + Miuix `SettingsCard` / rows。`PageScaffold` 负责 Miuix top app bar、backdrop 和 bottom bar。
- 需要二级选择的配置用 `OptionRow`，不要点击一行就循环切换值。
- 好友/群聊选择用 `ContactPickerPage`，它已经处理头像、头像进程内缓存、右侧选中框和完整列表点击。
- 会跳转或选择的行使用现有 `ActionRow` / `OptionRow` / `ContactRow`，不要直接写普通 `Modifier.clickable`。
- 所有页面都要给 `LazyColumn` 传 `rememberLazyListState()`，返回二级菜单时才能保持滚动位置。
- `LazyColumn` 要加 `.nestedScroll(scrollBehavior.nestedScrollConnection)`，保证 Miuix 顶栏滚动行为一致。
- 主设置页的 设置 tab 已有 `悬浮底栏` 和 `液态玻璃` 开关，值存在 `Hchat_miuix_ui`。不要在功能页重复做这些全局 UI 设置。
- 新增功能配置统一使用 `HchatStorage.preferences(context, name)` 或 `ConfigStore`，文件位于微信私有目录 `Hchat/`，日常读写由 FastKV 负责。不要直接 `context.getSharedPreferences(...)` 或自行实例化 FastKV 写模块配置。
- 主底栏实现位于 `ui/miuix/FloatingBottomBar.kt` 及其 `animation/`、`liquid/` 辅助文件。它使用 KSU 风格的多层 backdrop：普通内容层、隐藏主色 tint 层、移动选中液态胶囊。新增功能页不要改这套底栏状态或颜色机制。
- 底栏图标是本地 `ImageVector`，路径来自 AndroidX Material Rounded。不要在微信内嵌 Compose UI 中使用 `vectorResource(R.drawable...)`。
- 主功能列表不显示 Provider 图标，保持普通设置列表样式。图标只保留在底部导航。
- 液态玻璃使用 compose-miuix-ui 的 `miuix-blur`。不要在功能页单独创建 blur/backdrop；统一复用 `PageScaffold` 的结构。
- 液态玻璃只支持 Android 13 及以上；低版本必须回退普通底栏，不能构造 `RuntimeShader` 相关效果。
- `layerBackdrop(...)` 只能包住页面内容层，底栏必须作为 overlay 放在内容层外。底栏玻璃由 `FloatingBottomBar` 内部的 `drawBackdrop(...)` / `lens(...)` 处理。不要把底栏放进被记录的 backdrop 层里。
- 不要新增 Dialog，不要启动模块 Activity，不要加右上角关闭按钮。

## Register

Add one line in `FeatureRegistry`:

```kotlin
.register(ExampleFeature())
```

## Common Patterns

订阅消息：

```kotlin
trackSubscription(WeChatApis.message().observe().subscribe { message ->
    if (message.isText()) {
        val talker = message.talker
        val sender = message.sender
        val content = message.content
    }
})
```

延迟、限频、防重复：

```kotlin
WeChatApis.runtime().tasks().runOnMainDelayed("example:$id", 1000) {
    // delayed work
}

WeChatApis.runtime().tasks().runOnce("example_once:$id") {
    // only once per key
}

WeChatApis.runtime().tasks().runThrottled("example_rate:$talker", 3000) {
    // rate limited per talker
}
```

安装 Xposed Hook：

```kotlin
HookRegistry.get().hook(method, object : XC_MethodHook() {
    override fun afterHookedMethod(param: MethodHookParam) {
        // hook body
    }
})
```

需要微信内部类：

```text
1. 先用 DexKit/DexClub 在目标版本确认类、方法、构造器。
2. 把解析结果放进 DexFinder，并加版本/热更新缓存。
3. 业务功能只消费 DexFinder 或 WeChatApis，不散落 findClass。
4. 反射查找、类加载、实例创建和方法调用必须走 h.Hchat.utils.KavaReflector。
5. 未确认的能力只写 supports/isAvailable，不写猜测实现。
```

KavaRef 约定：

```kotlin
val ctor = KavaReflector.findConstructor(clazz, String::class.java)
val instance = KavaReflector.newInstance(ctor, value)
val field = KavaReflector.readField(instance, "fieldName")
```

不要在新功能里直接散落 `getDeclared*`、`setAccessible`、`Constructor.newInstance`、`Class.forName` 或 `Method.invoke`。如果必须保留原始 `Method` / `Field` / `Constructor` 给 Xposed 或 DexKit，也必须由 `KavaReflector` 获取。

`h/Hchat/compat/kavaref/**` 是 KavaRef core 的本地兼容层。目录在 Hchat 下，文件内部包名仍是 `com.highcapable.kavaref...`，不要改包名。

运行时设置实时生效：

```kotlin
// Good: read current value when handling the event.
if (!settings.getBoolean(ExampleSettings.KEY_ENABLE, true)) return

// Good: if cached, refresh before event processing.
settingsSnapshot.refresh()
if (settingsSnapshot.skipCurrentEvent()) return

// Good: settings page writes immediately when later runtime code depends on it.
sp.edit().putBoolean(ExampleSettings.KEY_ENABLE, enabled).commit()
```

不要这样写：

```kotlin
// Bad: this value becomes stale after user saves settings.
private var enabled = false

fun hookAll() {
    enabled = settings.getBoolean(ExampleSettings.KEY_ENABLE, true)
}

fun onEvent() {
    if (!enabled) return
}
```

## Rules

- Use `BaseFeature` for lifecycle.
- Use `registerSettingsProvider`, `subscribe`, and `trackSubscription` for cleanup.
- Use `WeChatApis` before adding new DexKit logic.
- Put new DexKit results into `DexFinder` only after verifying target WeChat versions by reverse engineering.
- 新增反射代码使用 `KavaReflector`，不要直接散落原始 Java 反射。
- Runtime logs should be quiet by default. Log failures, missing classes, or explicit debug output only.
- Keep feature entry classes small. Put parsing, action, notification, and state into separate classes.
- Do not use WA/WAuxiliary API.
- Do not use Dialog/AlertDialog/module Activity for settings. All settings pages must be embedded Miuix pages under `MiuixSettingsPage.kt`.
- Settings providers are metadata only. Do not add `showDetail(Context)`.
- Use shared Miuix rows/pickers so tap handling, scroll-position retention, contact avatars, and right-side selection marks stay consistent.
- All switches and editable settings must take effect immediately after saving. Do not require restarting WeChat for normal settings.
- If settings are cached, refresh the cache before processing events or when the settings page saves.
- If a WeChat internal detail is uncertain, reverse engineer first. Do not guess class names, method names, fields, database tables, intent extras, or request parameters.
- Put every new file in the correct layer. Feature-specific code belongs in `hooks/items/<feature>/**`; only reusable verified WeChat APIs belong in `hooks/api/**`.
