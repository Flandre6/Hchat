package h.Hchat.utils
object HLog {
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun e(message: String, error: Throwable? = null) { errors.add(message) }
}
