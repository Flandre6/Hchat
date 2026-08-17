package h.Hchat.hooks.items.payment.core

import android.text.TextUtils
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 红包功能运行时状态。
 */
class RedPacketState {
    @JvmField
    val processedRedBags: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val processedRedBagIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val recordedAmountKeys: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val notifiedRedBags: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val failedNotifiedRedBags: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val senderMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val contentMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val talkerMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val ruleMap: MutableMap<String, RedPacketEffectiveRule> = ConcurrentHashMap()

    @JvmField
    val recentContents: java.util.Deque<String> = ConcurrentLinkedDeque()

    @JvmField
    val silentRedPacketMap: MutableMap<String, MutableMap<String, Any>> = ConcurrentHashMap()

    @JvmField
    val silentReceiveRequestInfoMap: MutableMap<Any, MutableMap<String, Any>> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmField
    val silentReceiveRetryMap: MutableMap<String, Int> = ConcurrentHashMap()

    @JvmField
    val silentOpenRetryMap: MutableMap<String, Int> = ConcurrentHashMap()

    @JvmField
    val silentReceivingSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val silentOpeningSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val silentFinishedSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun markDetected(nativeUrl: String?, sender: String?, content: String?, talker: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        val safeContent = content ?: ""
        val oldContent = contentMap.putIfAbsent(key, safeContent)
        if (oldContent != null) {
            if (TextUtils.isEmpty(oldContent) && !TextUtils.isEmpty(safeContent)) {
                contentMap[key] = safeContent
            }
            fillIfEmpty(senderMap, key, sender)
            fillIfEmpty(talkerMap, key, talker)
            return false
        }
        senderMap[key] = sender ?: ""
        talkerMap[key] = talker ?: ""
        recentContents.addFirst(safeContent)
        while (recentContents.size > MAX_RECENT_CONTENT) recentContents.removeLast()
        return true
    }

    fun markProcessed(nativeUrl: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        val id = redPacketId(key)
        if (!TextUtils.isEmpty(id) && !processedRedBagIds.add(id)) return false
        return processedRedBags.add(key)
    }

    fun hasProcessed(nativeUrl: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        if (processedRedBags.contains(key)) return true
        val id = redPacketId(key)
        return !TextUtils.isEmpty(id) && processedRedBagIds.contains(id)
    }

    fun markAmountRecorded(nativeUrl: String?): Boolean {
        val key = amountKey(nativeUrl)
        return !TextUtils.isEmpty(key) && recordedAmountKeys.add(key)
    }

    fun hasAmountRecorded(nativeUrl: String?): Boolean {
        val key = amountKey(nativeUrl)
        return !TextUtils.isEmpty(key) && recordedAmountKeys.contains(key)
    }

    fun markNotified(key: String?): Boolean {
        return !TextUtils.isEmpty(key) && notifiedRedBags.add(key ?: "")
    }

    fun markFailedNotified(key: String?): Boolean {
        return !TextUtils.isEmpty(key) && failedNotifiedRedBags.add(key ?: "")
    }

    fun cleanupSilentPacket(sendId: String?) {
        if (TextUtils.isEmpty(sendId)) return
        val key = sendId ?: return
        val info = silentRedPacketMap[key]
        if (info != null) {
            val nativeUrl = info["nativeurl"] as? String
            if (!TextUtils.isEmpty(nativeUrl)) {
                senderMap.remove(nativeUrl)
                contentMap.remove(nativeUrl)
                talkerMap.remove(nativeUrl)
                ruleMap.remove(nativeUrl)
            }
        }
        silentReceivingSet.remove(key)
        silentOpeningSet.remove(key)
        silentReceiveRetryMap.remove(key)
        silentOpenRetryMap.remove(key)
        silentRedPacketMap.remove(key)
    }

    private fun fillIfEmpty(map: MutableMap<String, String>, key: String, value: String?) {
        if (TextUtils.isEmpty(value)) return
        val old = map[key]
        if (TextUtils.isEmpty(old)) map[key] = value ?: return
    }

    private fun amountKey(nativeUrl: String?): String {
        val id = redPacketId(nativeUrl)
        return if (!TextUtils.isEmpty(id)) "sendid:$id" else nativeUrl ?: ""
    }

    companion object {
        private const val MAX_RECENT_CONTENT = 30

        @JvmStatic
        fun redPacketId(nativeUrl: String?): String {
            if (TextUtils.isEmpty(nativeUrl)) return ""
            var sendId = nativeUrlParam(nativeUrl, "sendid")
            if (TextUtils.isEmpty(sendId)) sendId = nativeUrlParam(nativeUrl, "sendId")
            return sendId
        }

        private fun nativeUrlParam(url: String?, key: String?): String {
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return ""
            try {
                val actualUrl = url ?: return ""
                val prefix = "$key="
                var start = actualUrl.indexOf('?')
                start = if (start >= 0) start + 1 else 0
                while (start < actualUrl.length) {
                    var end = actualUrl.indexOf('&', start)
                    if (end < 0) end = actualUrl.length
                    if (actualUrl.startsWith(prefix, start)) {
                        return actualUrl.substring(start + prefix.length, end)
                    }
                    start = end + 1
                }
            } catch (_: Throwable) {
            }
            return ""
        }
    }
}
