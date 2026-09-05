package h.Hchat.hooks.items.securemessage

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SendSecureMessageSettingsProvider : SimpleFeatureSettingsProvider(
    SecureMessageSettings.SEND_ID,
    "安全消息",
    "给自己发送的文本和引用回复添加 sec_msg_node 标记",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)

class AntiSecureMessageSettingsProvider : SimpleFeatureSettingsProvider(
    SecureMessageSettings.ANTI_ID,
    "反安全消息",
    "忽略 sec_msg_node 标记，恢复完整长按菜单",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
