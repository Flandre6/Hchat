package android.os

class Looper { companion object { fun getMainLooper() = Looper() } }
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
class Handler(looper: Looper) {
    fun postDelayed(task: Runnable, delay: Long): Boolean {
        if (reject) return false
        synchronized(pending) { pending.add(task) }
        return true
    }
    fun removeCallbacks(task: Runnable) { synchronized(pending) { pending.remove(task) } }
    companion object {
        val pending = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        var reject = false
        fun drain() {
            while (true) {
                val next = synchronized(pending) { if (pending.isEmpty()) null else pending.removeAt(0) } ?: break
                next.run()
            }
        }
    }
}
