package h.Hchat.hooks.items.chattime

object ChatTimeStyleSettings {
    const val PREFS_NAME = "Hchat_chat_time_style_config"

    const val KEY_MODE = "chat_time_mode"
    const val KEY_TIME_FORMAT = "chat_time_format"

    const val MODE_ORIGINAL = "original"
    const val MODE_CUSTOM = "custom"
    const val MODE_HIDDEN = "hidden"
    const val MODE_EVERY = "every"

    const val DEFAULT_MODE = MODE_ORIGINAL
    const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

    fun normalizeMode(value: String?): String = when (value) {
        MODE_CUSTOM -> MODE_CUSTOM
        MODE_HIDDEN -> MODE_HIDDEN
        MODE_EVERY -> MODE_EVERY
        else -> MODE_ORIGINAL
    }
}
