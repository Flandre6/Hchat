package h.Hchat.hooks.items.hchatextra

import android.content.Context
import h.Hchat.preferences.HchatStorage

object HchatExtraSettings {
    const val PREFS_NAME = "Hchat_extra_config"
    private const val LEGACY_PREFS_NAME = "Hchat_wekit_port_config"

    const val KEY_GROUP_MEMBER_HISTORY = "group_member_history_enable"
    const val KEY_MESSAGE_DETAILS = "message_details_enable"
    const val KEY_MESSAGE_DETAILS_LIGHT_BG = "message_details_light_bg"
    const val KEY_MESSAGE_DETAILS_LIGHT_TEXT = "message_details_light_text"
    const val KEY_MESSAGE_DETAILS_DARK_BG = "message_details_dark_bg"
    const val KEY_MESSAGE_DETAILS_DARK_TEXT = "message_details_dark_text"
    const val KEY_MESSAGE_DETAILS_FORMAT = "message_details_format"
    const val KEY_MESSAGE_DETAILS_TIME_FORMAT = "message_details_time_format"
    const val KEY_MESSAGE_DETAILS_POSITION = "message_details_position"
    const val KEY_MESSAGE_DETAILS_AVATAR_GAP = "message_details_avatar_gap"
    const val KEY_MESSAGE_DETAILS_LEFT_MARGIN = "message_details_left_margin"
    const val KEY_MESSAGE_DETAILS_RIGHT_MARGIN = "message_details_right_margin"
    const val KEY_MESSAGE_DETAILS_TEXT_SIZE = "message_details_text_size"
    const val KEY_MESSAGE_DETAILS_CLICK_SHOW = "message_details_click_show"
    const val KEY_MESSAGE_DETAILS_FORMAT_CONTENT = "message_details_format_content"
    const val KEY_RED_PACKET_DETAILS = "red_packet_details_enable"
    const val KEY_SKIP_WEB_RISK = "skip_web_risk_enable"

    const val DEFAULT_GROUP_MEMBER_HISTORY = false
    const val DEFAULT_MESSAGE_DETAILS = false
    const val DEFAULT_MESSAGE_DETAILS_LIGHT_BG = "#00000000"
    const val DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT = "#FFFF0000"
    const val DEFAULT_MESSAGE_DETAILS_DARK_BG = "#00000000"
    const val DEFAULT_MESSAGE_DETAILS_DARK_TEXT = "#FFFF0000"
    const val DEFAULT_MESSAGE_DETAILS_FORMAT = "\${time}"
    const val LEGACY_MESSAGE_DETAILS_FORMAT = "\${time} | \${type}"
    const val DEFAULT_MESSAGE_DETAILS_TIME_FORMAT = "HH:mm:ss"
    const val POSITION_MESSAGE_BOTTOM = "message_bottom"
    const val POSITION_AVATAR_ABOVE = "avatar_above"
    const val POSITION_AVATAR_BELOW = "avatar_below"
    const val DEFAULT_MESSAGE_DETAILS_POSITION = POSITION_MESSAGE_BOTTOM
    const val DEFAULT_MESSAGE_DETAILS_AVATAR_GAP = 2
    const val DEFAULT_MESSAGE_DETAILS_LEFT_MARGIN = 64
    const val DEFAULT_MESSAGE_DETAILS_RIGHT_MARGIN = 64
    const val DEFAULT_MESSAGE_DETAILS_TEXT_SIZE = 10
    const val DEFAULT_MESSAGE_DETAILS_CLICK_SHOW = true
    const val DEFAULT_MESSAGE_DETAILS_FORMAT_CONTENT = false
    const val DEFAULT_RED_PACKET_DETAILS = false
    const val DEFAULT_SKIP_WEB_RISK = false

    @JvmStatic
    @Synchronized
    fun migrateLegacyPreferences(context: Context) {
        val legacy = HchatStorage.preferences(context, LEGACY_PREFS_NAME)
        val legacyValues = legacy.all
        if (legacyValues.isEmpty()) return
        val current = HchatStorage.preferences(context, PREFS_NAME)
        if (current.all.isEmpty()) {
            val editor = current.edit()
            legacyValues.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.apply()
        }
        legacy.edit().clear().apply()
    }
}
