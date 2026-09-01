package h.Hchat.hooks.items.homesidepanel

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Stable WeChat page routes used by the OKK-style drawer. */
internal object HomeSidePanelPageLauncher {
    fun openShortcut(activity: Activity, shortcut: HomeSidePanelSettings.Shortcut): Boolean {
        val candidates = when (shortcut) {
            HomeSidePanelSettings.Shortcut.QR_CODE -> listOf(
                "com.tencent.mm.plugin.setting.ui.setting.SelfQRCodeUI",
                "com.tencent.mm.plugin.setting.ui.setting.ColorfulSelfQRCodeUI"
            )
            HomeSidePanelSettings.Shortcut.PAY -> listOf(
                "com.tencent.mm.plugin.offline.ui.WalletOfflineEntranceUI"
            )
            HomeSidePanelSettings.Shortcut.SERVICE -> listOf(
                "com.tencent.mm.plugin.mall.ui.MallIndexUIv2",
                "com.tencent.mm.plugin.mall.ui.MallIndexUI"
            )
            HomeSidePanelSettings.Shortcut.FAVORITE -> listOf(
                "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"
            )
        }
        return candidates.any { openClass(activity, it) }
    }

    fun openStatus(activity: Activity): Boolean {
        val candidates = listOf(
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2" to true,
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivity" to false,
            "com.tencent.mm.plugin.textstatus.ui.TextStatusNewActivity" to false,
            "com.tencent.mm.plugin.textstatus.ui.TextStatusEditActivityV2" to false,
            "com.tencent.mm.plugin.textstatus.ui.TextStatusEditActivity" to false,
            "com.tencent.mm.plugin.textstatus.ui.flutter.StatusFlutterPublishActivity" to false
        )
        return candidates.any { (className, enterFlag) ->
            openClass(activity, className) { intent ->
                if (enterFlag) intent.putExtra("KEY_IS_ENTER", true)
            }
        }
    }

    fun openUrl(activity: Activity, url: String): Boolean = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    }.getOrDefault(false)

    private fun openClass(
        activity: Activity,
        className: String,
        configure: (Intent) -> Unit = {}
    ): Boolean {
        val exists = runCatching {
            Class.forName(className, false, activity.classLoader)
        }.isSuccess
        if (!exists) return false
        return runCatching {
            val intent = Intent().setClassName(activity, className)
            configure(intent)
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
