package h.Hchat.hooks.items.specialmessage

/** APK evidence is recorded in docs/SAFETY_MESSAGE_8077.md. */
internal data class SafetyMessageHostProfile(
    val textOwner: String,
    val textMethod: String,
    val textRequest: String,
    val sourceOwner: String,
    val emojiScene: String,
    val talkerGetter: String
) {
    companion object {
        fun forVersion(name: String?, code: Long): SafetyMessageHostProfile? = when {
            name == "8.0.76" && code == 3140L -> SafetyMessageHostProfile(
                "lq1.u0", "r", "a65.en4", "lq1.d1", "o22.y", "N0"
            )
            name == "8.0.77" && code == 3160L -> SafetyMessageHostProfile(
                "ft1.u0", "p", "y85.gq4", "ft1.d1", "k52.y", "Q0"
            )
            else -> null
        }
    }
}
