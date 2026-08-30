package h.Hchat.hooks.items.homesidepanel

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage

/** Settings for the Hchat-native home side drawer. */
object HomeSidePanelSettings {
    const val PREFS_NAME = "Hchat_home_side_panel"
    const val KEY_ENABLE = "enable"
    const val KEY_SIGNATURE = "signature"
    const val KEY_SHORTCUTS = "shortcuts"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SIGNATURE = "Hchat 快捷面板"

    enum class Shortcut(val id: String, val title: String, val subtitle: String) {
        QR_CODE("qrcode", "我的二维码", "展示个人二维码"),
        PAY("pay", "收付款", "付款码 / 收款"),
        FAVORITE("favorite", "收藏", "我的收藏");

        companion object {
            val defaultOrder = listOf(QR_CODE, PAY, FAVORITE)
            fun fromId(value: String): Shortcut? = entries.firstOrNull { it.id == value.trim() }
        }
    }

    fun preferences(context: Context): SharedPreferences =
        HchatStorage.preferences(context, PREFS_NAME)

    fun enabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun signature(context: Context): String =
        preferences(context).getString(KEY_SIGNATURE, DEFAULT_SIGNATURE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SIGNATURE

    fun shortcuts(context: Context): List<Shortcut> {
        val raw = preferences(context).getString(KEY_SHORTCUTS, null).orEmpty()
        val parsed = raw.split(',').mapNotNull(Shortcut::fromId).distinct().take(3)
        return parsed.ifEmpty { Shortcut.defaultOrder }
    }

    fun saveShortcuts(context: Context, values: List<Shortcut>) {
        preferences(context).edit()
            .putString(KEY_SHORTCUTS, values.distinct().take(3).joinToString(",") { it.id })
            .apply()
    }
}
