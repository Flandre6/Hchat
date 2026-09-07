package h.Hchat.hooks.items.securemessage

/** XML helpers shared by secure-message sending and forwarding restrictions. */
object SecureMessageSource {
    private val secureNode = Regex(
        "<sec_msg_node\\b[^>]*>.*?</sec_msg_node>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val msgSourceOpen = Regex("<msgsource\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val msgSourceClose = Regex("</msgsource>", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun containsMarker(value: String?): Boolean = secureNode.containsMatchIn(value.orEmpty())

    @JvmStatic
    fun addMarker(value: String?): String {
        val current = value.orEmpty()
        if (containsMarker(current)) return current
        val close = msgSourceClose.find(current)
        if (close != null) {
            return current.substring(0, close.range.first) +
                SecureMessageSettings.SEC_XML +
                current.substring(close.range.first)
        }
        val open = msgSourceOpen.find(current)
        if (open != null) {
            val insertAt = open.range.last + 1
            return current.substring(0, insertAt) +
                SecureMessageSettings.SEC_XML +
                current.substring(insertAt)
        }
        return "<msgsource>${SecureMessageSettings.SEC_XML}</msgsource>"
    }

}
