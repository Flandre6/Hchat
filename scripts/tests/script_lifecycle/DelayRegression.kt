package h.Hchat.hooks.items.script

import android.os.Handler
import h.Hchat.utils.HLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val calls = AtomicInteger()
    repeat(1_000) {
        val old = ScriptDelayScope(true) { "old" }
        old.delay(60_000, Runnable { calls.incrementAndGet() })
        old.dispose()
        old.dispose()
        old.delay(0, Runnable { calls.incrementAndGet() })
        check(Handler.pending.isEmpty()) { "Reload retained a delayed callback" }
    }
    Handler.drain()
    check(calls.get() == 0)
    val first = ScriptDelayScope(true) { "first" }
    val second = ScriptDelayScope(true) { "second" }
    first.delay(0, Runnable { calls.incrementAndGet() })
    second.delay(0, Runnable { calls.incrementAndGet() })
    Handler.drain()
    check(calls.get() == 2) { "Plugin timers replaced one another" }
    first.dispose(); second.dispose()

    val limited = ScriptDelayScope(true) { "limited" }
    repeat(1_000) { limited.delay(60_000, Runnable { calls.incrementAndGet() }) }
    check(Handler.pending.size == 128)
    check(HLog.errors.size == 1) { "Overflow logs not throttled" }
    limited.dispose()
    check(Handler.pending.isEmpty())

    Handler.reject = true
    val rejected = ScriptDelayScope(true) { "rejected" }
    repeat(600) { rejected.delay(0, Runnable {}) }
    rejected.dispose()
    Handler.reject = false
    val scopes = List(5) { ScriptDelayScope(true) { "global-limit" } }
    scopes.forEach { scope -> repeat(128) { scope.delay(60_000, Runnable {}) } }
    check(Handler.pending.size == 512) { "Global capacity or failure cleanup broken" }
    scopes.forEach { it.dispose() }
    check(Handler.pending.isEmpty())

    val recursive = ScriptDelayScope(true) { "self-disposing" }
    recursive.delay(0, Runnable {
        recursive.dispose()
        recursive.delay(0, Runnable { error("Disposed callback rescheduled itself") })
    })
    Handler.drain()
    check(Handler.pending.isEmpty())

    val started = CountDownLatch(2)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(2)
    val background = ScriptDelayScope(false) { "appbrand" }
    repeat(2) {
        background.delay(0, Runnable {
            started.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() }
        })
    }
    check(started.await(5, TimeUnit.SECONDS))
    repeat(128) { background.delay(60_000, Runnable { calls.incrementAndGet() }) }
    check(Thread.getAllStackTraces().keys.count { it.name == "Hchat-Script-Delay" } == 2)
    background.dispose()
    release.countDown()
    check(finished.await(5, TimeUnit.SECONDS))
    val fresh = ScriptDelayScope(false) { "fresh" }
    val freshRan = CountDownLatch(1)
    fresh.delay(0, Runnable { freshRan.countDown() })
    check(freshRan.await(5, TimeUnit.SECONDS))
    fresh.dispose()
    check(calls.get() == 2)
    println("PASS: delay reload cleanup, isolated plugins, capacity, rejection cleanup, disposed recursion, two appbrand workers")
}
