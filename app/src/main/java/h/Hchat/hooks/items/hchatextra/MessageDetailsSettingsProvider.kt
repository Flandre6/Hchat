package h.Hchat.hooks.items.hchatextra

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageDetailsSettingsProvider : SimpleFeatureSettingsProvider(
    FEATURE_ID,
    "消息显示时间",
    "在聊天消息旁显示时间、类型等详情",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val FEATURE_ID = "hchat_message_time"
    }
}
