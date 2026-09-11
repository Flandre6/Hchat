package h.Hchat.hooks.items.hchatextra

import android.view.View
import android.view.ViewTreeObserver

private fun checkCase(name: String, block: () -> Unit) {
    block()
    println("PASS: $name")
}

private fun attach(view: View): ViewTreeObserver {
    val window = ViewTreeObserver()
    view.viewTreeObserver.mergeInto(window)
    view.viewTreeObserver = window
    view.attached()
    return window
}

fun main() {
    checkCase("cached row reattachment revalidates once without rebinding") {
        val queue = MessageDetailsAttachQueue()
        val view = View()
        var calls = 0
        queue.observe(view) { calls++ }
        val first = attach(view)
        first.dispatch()
        first.dispatch()
        check(calls == 1)
        view.detached()
        val next = attach(view)
        next.dispatch()
        check(calls == 2)
        queue.clear()
        check(view.attachListenerCount == 0)
    }
    checkCase("detach suppresses a queued cached-row repair") {
        val queue = MessageDetailsAttachQueue()
        val view = View()
        queue.observe(view) { error("Detached row repaired") }
        val window = attach(view)
        val snapshot = window.snapshot()
        view.detached()
        window.dispatch(snapshot)
        check(window.listenerCount == 0)
        queue.clear()
        check(view.attachListenerCount == 0)
    }
    checkCase("new binding invalidates captured attachment and pre-draw snapshots") {
        val queue = MessageDetailsAttachQueue()
        val view = View()
        queue.observe(view) { error("Old binding repaired") }
        val window = attach(view)
        val draws = window.snapshot()
        val attachments = view.attachmentSnapshot()
        var calls = 0
        queue.observe(view) { calls++ }
        attachments.forEach { it.onViewAttachedToWindow(view) }
        window.dispatch(draws)
        check(calls == 0 && window.listenerCount == 0)
        view.detached()
        attach(view).dispatch()
        check(calls == 1)
        queue.clear()
    }
    checkCase("feature shutdown cancels cached-row repair and attach listener") {
        val queue = MessageDetailsAttachQueue()
        val view = View()
        queue.observe(view) { error("Destroyed feature repaired a row") }
        val window = attach(view)
        val draws = window.snapshot()
        val attachments = view.attachmentSnapshot()
        queue.clear()
        window.dispatch(draws)
        attachments.forEach { it.onViewAttachedToWindow(view) }
        check(window.listenerCount == 0 && view.attachListenerCount == 0)
    }

    checkCase("cancel suppresses an already captured attachment callback") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        queue.schedule(view, view) { error("Cancelled action executed") }
        val snapshot = view.attachmentSnapshot()
        queue.cancel(view)
        val window = attach(view)
        snapshot.forEach { it.onViewAttachedToWindow(view) }
        check(window.listenerCount == 0 && view.attachListenerCount == 0)
    }
    checkCase("cancel after attachment and recycling cleans the previous window") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = 0
        queue.schedule(view, view) { calls++ }
        val window = attach(view)
        val snapshot = window.snapshot()
        view.detached()
        queue.cancel(view)
        window.dispatch(snapshot)
        check(calls == 0 && window.listenerCount == 0 && view.attachListenerCount == 0)
    }
    checkCase("reattachment moves pending work to the new window") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = 0
        queue.schedule(view, view) { calls++ }
        val oldWindow = attach(view)
        view.detached()
        val newWindow = attach(view)
        check(oldWindow.listenerCount == 0 && newWindow.listenerCount == 1)
        newWindow.dispatch()
        check(calls == 1 && newWindow.listenerCount == 0 && view.attachListenerCount == 0)
    }
    checkCase("cancel suppresses an existing dispatch snapshot") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = 0
        check(queue.schedule(view, view) { calls++ })
        val snapshot = view.viewTreeObserver.snapshot()
        queue.cancel(view)
        view.viewTreeObserver.dispatch(snapshot)
        check(calls == 0)
        check(view.viewTreeObserver.listenerCount == 0)
    }
    checkCase("replacement ignores old snapshot and preserves new registration") {
        val queue = MessageDetailsPreDrawQueue()
        val key = View()
        val oldView = View()
        val newView = View()
        var calls = ""
        queue.schedule(key, oldView) { calls += "old" }
        val snapshot = oldView.viewTreeObserver.snapshot()
        queue.schedule(key, newView) { calls += "new" }
        oldView.viewTreeObserver.dispatch(snapshot)
        check(calls.isEmpty())
        newView.viewTreeObserver.dispatch()
        check(calls == "new")
        check(newView.viewTreeObserver.listenerCount == 0)
    }
    checkCase("migrated observer executes once and removes its listener") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = 0
        queue.schedule(view, view) { calls++ }
        val window = attach(view)
        val snapshot = window.snapshot()
        window.dispatch()
        window.dispatch(snapshot)
        window.dispatch()
        check(calls == 1)
        check(window.listenerCount == 0)
    }
    checkCase("cancel removes migrated observer listener") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = 0
        queue.schedule(view, view) { calls++ }
        val window = attach(view)
        val snapshot = window.snapshot()
        queue.cancel(view)
        check(window.listenerCount == 0)
        window.dispatch(snapshot)
        check(calls == 0)
    }
    checkCase("clear invalidates every key including migrated snapshots") {
        val queue = MessageDetailsPreDrawQueue()
        val first = View()
        val second = View()
        var calls = 0
        queue.schedule(first, first) { calls++ }
        queue.schedule(second, second) { calls++ }
        val window = attach(first)
        val firstSnapshot = window.snapshot()
        val secondSnapshot = second.viewTreeObserver.snapshot()
        queue.clear()
        check(window.listenerCount == 0 && second.viewTreeObserver.listenerCount == 0)
        window.dispatch(firstSnapshot)
        second.viewTreeObserver.dispatch(secondSnapshot)
        check(calls == 0)
    }
    checkCase("action can reschedule its key for the next frame") {
        val queue = MessageDetailsPreDrawQueue()
        val view = View()
        var calls = ""
        queue.schedule(view, view) {
            calls += "first"
            queue.schedule(view, view) { calls += ",second" }
        }
        val firstSnapshot = view.viewTreeObserver.snapshot()
        view.viewTreeObserver.dispatch()
        check(calls == "first")
        view.viewTreeObserver.dispatch(firstSnapshot)
        check(calls == "first" && view.viewTreeObserver.listenerCount == 1)
        view.viewTreeObserver.dispatch()
        check(calls == "first,second" && view.viewTreeObserver.listenerCount == 0)
    }
    checkCase("unavailable observer returns false after cancelling previous action") {
        val queue = MessageDetailsPreDrawQueue()
        val key = View()
        val deadView = View()
        var calls = 0
        queue.schedule(key, key) { calls++ }
        deadView.viewTreeObserver.mergeInto(ViewTreeObserver())
        check(!queue.schedule(key, deadView) { calls++ })
        key.viewTreeObserver.dispatch()
        check(calls == 0 && key.viewTreeObserver.listenerCount == 0)
    }
}
