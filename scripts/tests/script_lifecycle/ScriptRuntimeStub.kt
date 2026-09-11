package h.Hchat.hooks.items.script

import h.Hchat.hooks.api.core.TestMessage
import java.util.concurrent.atomic.AtomicInteger
class ScriptMessageBean(private val message: TestMessage) {
    init { constructed.incrementAndGet() }
    fun isImage() = message.kind == "image"
    fun isVideo() = false
    fun isVideoNumberVideo() = false
    fun isSend() = false
    fun getMsgId() = message.id
    fun getMsgSvrId() = message.id
    fun getTalker() = "friend"
    fun getSender() = "friend"
    fun getMsgType() = message.kind
    fun getContent() = "content" + message.id
    companion object { val constructed = AtomicInteger() }
}
object ScriptPluginRuntime {
    @Volatile var enabled = false
    @Volatile var images = false
    @Volatile var consumer: (ScriptMessageBean) -> Unit = {}
    val mediaCalls = AtomicInteger()
    fun hasHandleMsgCallbacks() = enabled
    fun hasImageDownloadCallback() = images
    fun hasVideoDownloadCallback() = false
    fun hasFinderMediaDownloadCallback() = false
    fun captureHandleMsgDispatch(message: ScriptMessageBean): Runnable? {
        if (!enabled) return null
        val captured = consumer
        return Runnable { captured(message) }
    }
    fun dispatchOnImageDownload(message: ScriptMessageBean) { mediaCalls.incrementAndGet() }
    fun dispatchOnVideoDownload(message: ScriptMessageBean) {}
    fun dispatchOnFinderMediaDownload(message: ScriptMessageBean) {}
}
