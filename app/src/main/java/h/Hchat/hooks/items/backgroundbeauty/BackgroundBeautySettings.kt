package h.Hchat.hooks.items.backgroundbeauty

object BackgroundBeautySettings {
    const val PREFS_NAME = "Hchat_background_beauty_config"

    const val KEY_ENABLE = "enable"
    const val KEY_REVISION = "revision"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_OPACITY = 0.72f

    enum class Slot(
        val key: String,
        val fileName: String,
        val title: String,
        val summary: String
    ) {
        CHAT("chat", "chat.image", "聊天背景", "聊天页、状态栏和输入区使用同一张背景图"),
        WECHAT("wechat", "wechat.image", "微信页背景", "微信首页会话列表"),
        CONTACTS("contacts", "contacts.image", "通讯录背景", "微信通讯录页面"),
        DISCOVER("discover", "discover.image", "发现页背景", "微信发现页面"),
        ME("me", "me.image", "我页面背景", "微信我的页面")
    }

    fun opacityKey(slot: Slot): String = "${slot.key}_opacity"

    fun opacity(value: Float): Float = value.coerceIn(0.10f, 1.0f)
}
