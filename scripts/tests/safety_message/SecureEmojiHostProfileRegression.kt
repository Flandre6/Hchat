package h.Hchat.hooks.items.securemessage

fun main() {
    check(
        SecureEmojiHostProfile.forVersion("8.0.76", 3140L) == SecureEmojiHostProfile(
            sourceOwner = "lq1.d1",
            emojiScene = "o22.y"
        )
    )
    check(
        SecureEmojiHostProfile.forVersion("8.0.77", 3160L) == SecureEmojiHostProfile(
            sourceOwner = "ft1.d1",
            emojiScene = "k52.y"
        )
    )
    check(SecureEmojiHostProfile.forVersion("8.0.77", 3140L) == null)
    check(SecureEmojiHostProfile.forVersion("8.0.76", 3160L) == null)
    check(SecureEmojiHostProfile.forVersion("8.0.78", 3160L) == null)
    println("安全消息表情宿主配置回归通过")
}
