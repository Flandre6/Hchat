package h.Hchat.hooks.items.script

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.CopyOnWriteArrayList

fun main() {
    check(ScriptMessageHook.install(FeatureContext()))
    val changes = WeChatApis.message().changes()
    changes.emit(1)
    check(ScriptMessageBean.constructed.get() == 0) { "Idle plugins allocated message beans" }
    val ids = CopyOnWriteArrayList<Long>()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val done = CountDownLatch(129)
    val caller = Thread.currentThread()
    ScriptPluginRuntime.enabled = true
    ScriptPluginRuntime.consumer = { message ->
        check(Thread.currentThread() !== caller) { "Script callback ran on producer thread" }
        if (message.getMsgId() == 1L) {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        ids.add(message.getMsgId())
        done.countDown()
    }
    changes.emit(1)
    check(started.await(5, TimeUnit.SECONDS))
    for (id in 2L..400L) changes.emit(id)
    val field = ScriptMessageHook::class.java.getDeclaredField("dispatchExecutor").apply { isAccessible = true }
    val executor = field.get(null) as ThreadPoolExecutor
    check(executor.queue.size == 128) { "Message backlog not bounded" }
    check(HLog.errors.count { it.contains("消息分发队列已满") } == 1)
    release.countDown()
    check(done.await(5, TimeUnit.SECONDS))
    check(ids.toList() == (1L..129L).toList()) { "Accepted messages lost FIFO order" }

    val blocked = CountDownLatch(1)
    val unblock = CountDownLatch(1)
    val finished = CountDownLatch(1)
    ScriptPluginRuntime.consumer = {
        blocked.countDown()
        check(unblock.await(5, TimeUnit.SECONDS))
        finished.countDown()
    }
    changes.emit(1_000)
    check(blocked.await(5, TimeUnit.SECONDS))
    for (id in 1_001L..1_100L) changes.emit(id)
    ScriptPluginRuntime.enabled = false
    ScriptMessageHook.clearPendingMessages()
    check(executor.queue.isEmpty()) { "Disabling consumers retained queued events" }
    unblock.countDown()
    check(finished.await(5, TimeUnit.SECONDS))

    ScriptPluginRuntime.images = true
    changes.emit(2_000, "image")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (ScriptPluginRuntime.mediaCalls.get() == 0 && System.nanoTime() < deadline) Thread.yield()
    check(ScriptPluginRuntime.mediaCalls.get() == 1) { "Media-only plugin was skipped" }
    check(executor.queue.isEmpty())
    println("PASS: zero-consumer fast path, 128 pending messages, FIFO, no producer execution, throttle, disable cleanup, media-only delivery")
}
