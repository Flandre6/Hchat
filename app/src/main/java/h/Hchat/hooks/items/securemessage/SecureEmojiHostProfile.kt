package h.Hchat.hooks.items.securemessage

/** Exact host entries verified for the native sendemoji request chain. */
internal data class SecureEmojiHostProfile(
    val sourceOwner: String,
    val emojiScene: String
) {
    companion object {
        fun forVersion(name: String?, code: Long): SecureEmojiHostProfile? = when {
            name == "8.0.76" && code == 3140L -> SecureEmojiHostProfile(
                sourceOwner = "lq1.d1",
                emojiScene = "o22.y"
            )
            name == "8.0.77" && code == 3160L -> SecureEmojiHostProfile(
                sourceOwner = "ft1.d1",
                emojiScene = "k52.y"
            )
            else -> null
        }
    }
}
