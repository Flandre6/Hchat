package h.Hchat.hooks.items.hchatextra

import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/** One pending action per view. Like ViewTreeObserver itself, callers must use the UI thread. */
internal class MessageDetailsPreDrawQueue {
    private class Pending(
        val observedView: View,
        var observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener
    ) : View.OnAttachStateChangeListener {
        var active = true

        override fun onViewAttachedToWindow(view: View) {
            if (!active) return
            val current = view.viewTreeObserver
            if (observer !== current) {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                // Attachment may already have migrated this listener to the window.
                current.removeOnPreDrawListener(listener)
                current.addOnPreDrawListener(listener)
                observer = current
            }
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    private val pending = WeakHashMap<View, Pending>()

    fun schedule(key: View, observedView: View, action: () -> Unit): Boolean {
        cancel(key)
        val observer = observedView.viewTreeObserver
        if (!observer.isAlive) return false
        lateinit var entry: Pending
        val listener = ViewTreeObserver.OnPreDrawListener {
            // Removal cannot retract a callback already in a dispatch snapshot.
            val current = pending[key] === entry
            if (current) pending.remove(key)
            removeListener(entry)
            if (current) action()
            true
        }
        entry = Pending(observedView, observer, listener)
        pending[key] = entry
        observedView.addOnAttachStateChangeListener(entry)
        observer.addOnPreDrawListener(listener)
        return true
    }

    fun cancel(key: View) {
        pending.remove(key)?.let(::removeListener)
    }

    fun clear() {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach(::removeListener)
    }

    private fun removeListener(entry: Pending) {
        entry.active = false
        entry.observedView.removeOnAttachStateChangeListener(entry)
        if (entry.observer.isAlive) entry.observer.removeOnPreDrawListener(entry.listener)
        // Attachment merges the detached view's observer into the window observer,
        // invalidating the original observer while retaining its listeners.
        val currentObserver = entry.observedView.viewTreeObserver
        if (currentObserver !== entry.observer && currentObserver.isAlive) {
            currentObserver.removeOnPreDrawListener(entry.listener)
        }
    }
}

/** Revalidate cached rows once on reattachment, including rows attached without a new bind. */
internal class MessageDetailsAttachQueue {
    private val entries = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val pending = MessageDetailsPreDrawQueue()

    // The action must not strongly capture the row or its children.
    fun observe(view: View, action: (View) -> Unit) {
        cancel(view)
        val entry = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (entries[view] !== this) return
                pending.schedule(view, view) {
                    if (entries[view] === this) action(view)
                }
            }
            override fun onViewDetachedFromWindow(view: View) {
                if (entries[view] === this) pending.cancel(view)
            }
        }
        entries[view] = entry
        view.addOnAttachStateChangeListener(entry)
    }

    fun cancel(view: View) {
        entries.remove(view)?.let(view::removeOnAttachStateChangeListener)
        pending.cancel(view)
    }

    fun clear() {
        val previous = entries.entries.map { it.key to it.value }
        entries.clear()
        previous.forEach { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
        pending.clear()
    }
}
