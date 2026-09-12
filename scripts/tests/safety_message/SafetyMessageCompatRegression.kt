package h.Hchat.hooks.items.specialmessage

private var checks = 0

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
    checks++
}

fun main() {
    val old = requireNotNull(SafetyMessageHostProfile.forVersion("8.0.76", 3140L))
    val current = requireNotNull(SafetyMessageHostProfile.forVersion("8.0.77", 3160L))
    expect(old.textOwner == "lq1.u0", "8076 text owner")
    expect(old.textMethod == "r", "8076 text method")
    expect(old.textRequest == "a65.en4", "8076 text request")
    expect(old.sourceOwner == "lq1.d1", "8076 source owner")
    expect(old.emojiScene == "o22.y", "8076 emoji scene")
    expect(old.talkerGetter == "N0", "8076 talker getter")
    expect(current.textOwner == "ft1.u0", "8077 text owner")
    expect(current.textMethod == "p", "8077 text method")
    expect(current.textRequest == "y85.gq4", "8077 text request")
    expect(current.sourceOwner == "ft1.d1", "8077 source owner")
    expect(current.emojiScene == "k52.y", "8077 emoji scene")
    expect(current.talkerGetter == "Q0", "8077 talker getter")

    val names = listOf<String?>(null, "", "8.0.49", "8.0.58", "8.0.66", "8.0.68",
        "8.0.72", "8.0.74", "8.0.76", "8.0.77", "8.0.78", "8077", "8.0.770", " 8.0.77")
    val codes = listOf(-1L, 0L, 2600L, 2841L, 2980L, 3020L, 3100L, 3120L, 3140L, 3160L, 3161L,
        (1L shl 32) + 3140L, (1L shl 32) + 3160L)
    for (name in names) {
        for (code in codes) {
            val supported = (name == "8.0.76" && code == 3140L) || (name == "8.0.77" && code == 3160L)
            expect((SafetyMessageHostProfile.forVersion(name, code) != null) == supported,
                "Version pair must match exactly: $name ($code)")
        }
    }
    println("SafetyMessageCompatRegression: $checks checks passed")
}
