package h.Hchat.hooks.api.core

class TestMessage(val id: Long, val kind: String = "text")
class Change(val message: TestMessage?)
class Changes {
    var isInstalled = false
    var callback: ((Change) -> Unit)? = null
    fun isAvailable() = true
    fun install() { isInstalled = true }
    fun subscribe(listener: (Change) -> Unit) { callback = listener }
    fun emit(id: Long, kind: String = "text") { callback?.invoke(Change(TestMessage(id, kind))) }
}
class MessageStore {
    fun getMessageById(id: Long): TestMessage? = null
    fun getMessageBySvrId(talker: String, id: Long): TestMessage? = null
    fun getMessageBySvrId(id: Long): TestMessage? = null
}
class MessageApi {
    val changes = Changes()
    fun changes() = changes
    fun store(): MessageStore? = null
}
object WeChatApis {
    private val api = MessageApi()
    fun message() = api
}
