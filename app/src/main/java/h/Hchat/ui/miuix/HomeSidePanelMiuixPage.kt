package h.Hchat.ui.miuix

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import h.Hchat.hooks.items.homesidepanel.HomeSidePanelSettings
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeSidePanelMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HomeSidePanelSettings.preferences(context) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    var enabled by remember { mutableStateOf(sp.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)) }
    var showWeather by remember { mutableStateOf(sp.getBoolean(HomeSidePanelSettings.KEY_SHOW_WEATHER, HomeSidePanelSettings.DEFAULT_SHOW_WEATHER)) }
    var showHitokoto by remember { mutableStateOf(sp.getBoolean(HomeSidePanelSettings.KEY_SHOW_HITOKOTO, HomeSidePanelSettings.DEFAULT_SHOW_HITOKOTO)) }
    var showSignature by remember { mutableStateOf(sp.getBoolean(HomeSidePanelSettings.KEY_SHOW_SIGNATURE, HomeSidePanelSettings.DEFAULT_SHOW_SIGNATURE)) }

    fun save(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "侧边栏") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用首页侧边栏",
                        summary = "在微信首页从左缘向右滑打开负一屏",
                        onCheckedChange = {
                            enabled = it
                            save(HomeSidePanelSettings.KEY_ENABLE, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = showWeather,
                        title = "显示天气卡片",
                        summary = "使用微信资料地区匹配天气，失败时保留缓存",
                        enabled = enabled,
                        onCheckedChange = {
                            showWeather = it
                            save(HomeSidePanelSettings.KEY_SHOW_WEATHER, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = showHitokoto,
                        title = "显示一言卡片",
                        summary = "从一言服务获取每日短句并缓存",
                        enabled = enabled,
                        onCheckedChange = {
                            showHitokoto = it
                            save(HomeSidePanelSettings.KEY_SHOW_HITOKOTO, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = showSignature,
                        title = "显示个性签名",
                        summary = "在个人资料卡中显示微信签名",
                        enabled = enabled,
                        onCheckedChange = {
                            showSignature = it
                            save(HomeSidePanelSettings.KEY_SHOW_SIGNATURE, it)
                        }
                    )
                }
            }
            item {
                androidx.compose.material3.Text(
                    text = "天气和一言请求均在后台线程执行，并使用 30 分钟缓存；网络失败不会阻塞微信首页。",
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
