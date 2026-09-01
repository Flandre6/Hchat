package h.Hchat.ui.miuix

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.homesidepanel.HomeSidePanelSettings
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeSidePanelMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HomeSidePanelSettings.preferences(context) }
    var enabled by remember { mutableStateOf(sp.getBoolean(HomeSidePanelSettings.KEY_ENABLE, HomeSidePanelSettings.DEFAULT_ENABLE)) }
    var signature by remember { mutableStateOf(sp.getString(HomeSidePanelSettings.KEY_SIGNATURE, HomeSidePanelSettings.DEFAULT_SIGNATURE).orEmpty()) }
    var selected by remember { mutableStateOf(HomeSidePanelSettings.shortcuts(context)) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun save() {
        val normalizedSignature = signature.trim().ifEmpty { HomeSidePanelSettings.DEFAULT_SIGNATURE }
        signature = normalizedSignature
        sp.edit()
            .putBoolean(HomeSidePanelSettings.KEY_ENABLE, enabled)
            .putString(HomeSidePanelSettings.KEY_SIGNATURE, normalizedSignature)
            .putString(
                HomeSidePanelSettings.KEY_SHORTCUTS,
                selected.distinct().take(3).joinToString(",") { it.id }
            )
            .apply()
        Toast.makeText(context, "首页侧边栏设置已保存", Toast.LENGTH_SHORT).show()
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = ::save,
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
            item { SmallTitle(text = "功能开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用首页侧边栏",
                        summary = "在微信首页左缘右滑，或点击首页顶栏头像打开快捷面板",
                        onCheckedChange = { enabled = it }
                    )
                    InsetDivider()
                    InputRow(
                        title = "面板签名",
                        summary = "显示在快捷面板标题下方",
                        value = signature,
                        onValueChange = { signature = it }
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "快捷入口") }
            item {
                SettingsCard {
                    HomeSidePanelSettings.Shortcut.entries.forEachIndexed { index, shortcut ->
                        SwitchRow(
                            checked = shortcut in selected,
                            title = shortcut.title,
                            summary = shortcut.subtitle,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    (selected + shortcut).distinct().take(3)
                                } else {
                                    selected.filterNot { it == shortcut }
                                }
                            }
                        )
                        if (index < HomeSidePanelSettings.Shortcut.entries.lastIndex) InsetDivider()
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "说明") }
            item {
                SettingsCard {
                    Column {
                        Text(
                            text = "侧边栏只会挂载在微信首页 LauncherUI，不影响聊天页面。快捷入口名称和可用性取决于当前微信版本。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}
