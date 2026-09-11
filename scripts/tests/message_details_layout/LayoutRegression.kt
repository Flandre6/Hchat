package h.Hchat.hooks.items.hchatextra
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
fun main() {
    var passed = 0
    fun verify(name: String, body: () -> Unit) { body(); passed++; println("PASS " + name) }
    verify("same geometry and repeated global layouts coalesce without looping") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); val label = TextView()
        root.addView(label); var calls = 0
        observer.observe(root, label) { _, _ -> calls++ }
        repeat(5) { root.viewTreeObserver.dispatch() }; check(root.posts.size == 1)
        root.flush(); check(calls == 1)
        repeat(5) { root.viewTreeObserver.dispatch(); root.flush() }
        check(calls == 1 && root.posts.isEmpty()); observer.clear()
    }
    verify("asynchronous child removal is repaired while row bounds stay the same") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); val label = TextView()
        root.addView(label); var calls = 0
        observer.observe(root, label) { row, text -> calls++; if (text.parent == null) (row as ViewGroup).addView(text) }
        root.flush(); root.removeView(label)
        root.viewTreeObserver.dispatch(); root.flush()
        check(calls == 2 && label.parent === root)
        root.viewTreeObserver.dispatch(); root.flush()
        val stable = calls
        repeat(4) { root.viewTreeObserver.dispatch(); root.flush() }
        check(calls == stable); observer.clear()
    }
    verify("recycled row invalidates old runnable and listener dispatch snapshots") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup()
        var oldCalls = 0; var newCalls = 0
        observer.observe(root, TextView()) { _, _ -> oldCalls++ }
        val oldPost = root.posts.single(); val oldListener = root.viewTreeObserver.listeners.single()
        observer.observe(root, TextView()) { _, _ -> newCalls++ }
        oldPost.run(); oldListener.onGlobalLayout(); root.flush()
        check(oldCalls == 0 && newCalls == 1 && root.viewTreeObserver.listeners.size == 1)
        observer.clear(); check(root.attachListeners.isEmpty() && root.viewTreeObserver.listeners.isEmpty())
    }
    verify("detach cancels pending and attach migrates observer with a fresh refresh") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); val label = TextView(); var calls = 0
        observer.observe(root, label) { _, _ -> calls++ }; val oldPost = root.posts.single(); val oldWindow = root.viewTreeObserver
        root.detach(); check(oldWindow.listeners.isEmpty())
        val newWindow = ViewTreeObserver(); root.viewTreeObserver.mergeInto(newWindow); root.attach(newWindow)
        oldPost.run(); root.flush()
        check(calls == 1 && newWindow.listeners.size == 1)
        root.detach(); root.attach(ViewTreeObserver()); root.flush(); check(calls == 2)
        observer.clear()
    }
    verify("label deeper than depth four tracks visibility and removal") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); var parent = root
        repeat(6) { val next = ViewGroup(); parent.addView(next); parent = next }
        val label = TextView(); parent.addView(label); var calls = 0
        observer.observe(root, label) { _, _ -> calls++ }; root.flush()
        label.alpha = 0f; root.viewTreeObserver.dispatch(); root.flush(); check(calls == 2)
        parent.removeView(label); root.viewTreeObserver.dispatch(); root.flush(); check(calls == 3)
        observer.clear()
    }
    verify("re-observing same binding updates callback without recreating observation") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); val label = TextView()
        var oldCalls = 0; var newCalls = 0
        observer.observe(root, label) { _, _ -> oldCalls++ }
        observer.observe(root, label) { _, _ -> newCalls++ }; root.flush()
        check(oldCalls == 0 && newCalls == 1 && root.attachListeners.size == 1)
        observer.observe(root, label) { _, _ -> newCalls++ }; check(root.posts.isEmpty())
        observer.clear()
    }
    verify("observe detached waits for attach and cancellation releases all listeners") {
        val observer = MessageDetailsLayoutObserver(); val root = ViewGroup(); root.isAttachedToWindow = false
        var calls = 0; observer.observe(root, TextView()) { _, _ -> calls++ }
        check(root.posts.isEmpty() && root.viewTreeObserver.listeners.isEmpty())
        root.attach(); root.flush(); check(calls == 1)
        root.right++; root.viewTreeObserver.dispatch(); val stale = root.posts.single()
        observer.cancel(root); stale.run(); check(calls == 1 && root.attachListeners.isEmpty())
        check(root.viewTreeObserver.listeners.isEmpty() && root.posts.isEmpty())
    }
    println("Passed " + passed + " layout observer regression cases")
}
