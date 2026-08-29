package h.Hchat.hooks.items.homesidepanel

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage

/** Persistent options for the Hchat-native port of WeKit's home side panel. */
object HomeSidePanelSettings {
    const val PREFS_NAME = "Hchat_home_side_panel"

    const val KEY_ENABLE = "enable"
    const val KEY_SHOW_WEATHER = "show_weather"
    const val KEY_SHOW_HITOKOTO = "show_hitokoto"
    const val KEY_SHOW_SIGNATURE = "show_signature"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SHOW_WEATHER = true
    const val DEFAULT_SHOW_HITOKOTO = true
    const val DEFAULT_SHOW_SIGNATURE = true

    internal const val KEY_WEATHER_CACHE = "weather_cache"
    internal const val KEY_WEATHER_CACHE_AT = "weather_cache_at"
    internal const val KEY_HITOKOTO_CACHE = "hitokoto_cache"
    internal const val KEY_HITOKOTO_CACHE_AT = "hitokoto_cache_at"

    @JvmStatic
    fun preferences(context: Context): SharedPreferences =
        HchatStorage.preferences(context, PREFS_NAME)
}
