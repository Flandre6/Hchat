package h.Hchat.hooks.items.securemessage

object SecureMessageSettings {
    const val SEND_ID = "send_secure_message"
    const val ANTI_ID = "anti_secure_message"
    const val SEND_PREFS = "Hchat_secure_message"
    const val ANTI_PREFS = "Hchat_anti_secure_message"
    const val KEY_ENABLE = "enable"
    const val DEFAULT_ENABLE = true
    const val SEC_XML = "<sec_msg_node><sfn>1</sfn><show-h5></show-h5><clip-len>0</clip-len><share-tip-url></share-tip-url><sec-ctrl-flag></sec-ctrl-flag><fold-reduce>0</fold-reduce><media-to-emoji>0</media-to-emoji><block-range>0</block-range><bubble-type>2</bubble-type><preview-type>0</preview-type><url-click-type>0</url-click-type></sec_msg_node>"
    const val CACHE_SCHEMA = "secure_message_v2"
    const val CACHE_INSERT = "insert_message"
    const val CACHE_MERGE = "merge_msg_source"
    const val CACHE_CHECK = "secure_message_check"
}
