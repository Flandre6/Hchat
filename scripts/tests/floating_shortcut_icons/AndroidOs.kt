package android.os
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
class Looper private constructor() {
    companion object {
        private val owner = Thread.currentThread()
        private val main = Looper()
        fun getMainLooper() = main
        fun myLooper(): Looper? = if (Thread.currentThread() === owner) main else null
    }
}
class Handler(looper: Looper) {
    fun post(runnable: Runnable): Boolean = tasks.offer(runnable)
    companion object {
        private val tasks = LinkedBlockingQueue<Runnable>()
        fun runNext() { checkNotNull(tasks.poll(3, TimeUnit.SECONDS)) { "Worker callback timed out" }.run() }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
}
