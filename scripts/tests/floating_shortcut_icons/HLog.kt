package h.Hchat.utils
object HLog { fun e(message: String, error: Throwable) { throw AssertionError(message, error) } }
