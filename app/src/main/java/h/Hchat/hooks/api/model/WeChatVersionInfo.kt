package h.Hchat.hooks.api.model

class WeChatVersionInfo(
    packageName: String?,
    versionName: String?,
    @JvmField val versionCode: Long,
    clientVersion: String?,
    tinkerId: String?,
    patchId: String?,
    @JvmField val sourceLastModified: Long,
    classLoaderHash: String?,
    cacheKey: String?
) {
    @JvmField val packageName: String = packageName.orEmpty()
    @JvmField val versionName: String = versionName.orEmpty()
    @JvmField val clientVersion: String = clientVersion.orEmpty()
    @JvmField val tinkerId: String = tinkerId.orEmpty()
    @JvmField val patchId: String = patchId.orEmpty()
    @JvmField val classLoaderHash: String = classLoaderHash.orEmpty()
    @JvmField val cacheKey: String = cacheKey.orEmpty()

    fun hasTinkerPatch(): Boolean = tinkerId.isNotEmpty() || patchId.isNotEmpty()

    /**
     * Compares the host WeChat version name without depending on its version code.
     * Version codes are channel-dependent, while the semantic version is stable
     * across the arm64 APK used for compatibility checks.
     */
    fun isAtLeast(major: Int, minor: Int, patch: Int): Boolean {
        val parts = versionName.substringBefore('-').split('.')
        if (parts.size < 3) return false
        val current = IntArray(3)
        for (index in current.indices) {
            current[index] = parts[index].toIntOrNull() ?: return false
        }
        val target = intArrayOf(major, minor, patch)
        for (index in target.indices) {
            if (current[index] != target[index]) return current[index] > target[index]
        }
        return true
    }

    fun displayVersion(): String {
        val version = versionName.ifBlank { "未知" }
        val codePart = if (versionCode > 0L) ".$versionCode" else ""
        val clientPart = normalizeClientVersion()
        return buildString {
            append(version)
            append(codePart)
            if (clientPart.isNotEmpty()) {
                append('(')
                append(clientPart)
                append(')')
            }
        }
    }

    private fun normalizeClientVersion(): String {
        if (clientVersion.isBlank()) return ""
        val raw = clientVersion.trim()
        if (raw.startsWith("0x", ignoreCase = true)) {
            return "0x" + raw.removePrefix("0x").removePrefix("0X").uppercase()
        }
        return raw.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { "0x" + it.toString(16).uppercase() }
            ?: raw
    }
}
