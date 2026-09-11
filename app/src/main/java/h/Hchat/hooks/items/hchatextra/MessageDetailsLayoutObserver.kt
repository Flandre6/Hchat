package h.Hchat.hooks.items.hchatextra

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** UI-thread only. Actions receive current views; callers must not capture root/label in them. */
internal class MessageDetailsLayoutObserver {
    private val entries = WeakHashMap<View, Entry>()

    fun observe(root: View, label: TextView, action: (View, TextView) -> Unit) {
        val existing = entries[root]
        if (existing != null && existing.label.get() === label) {
            existing.action = action
            return
        }
        cancel(root)
        val entry = Entry(root, label, action)
        entries[root] = entry
        root.addOnAttachStateChangeListener(entry)
        if (root.isAttachedToWindow) entry.onViewAttachedToWindow(root)
    }

    fun cancel(root: View) {
        entries.remove(root)?.dispose()
    }

    fun clear() {
        val old = entries.values.toList()
        entries.clear()
        old.forEach(Entry::dispose)
    }

    private inner class Entry(
        root: View,
        label: TextView,
        var action: (View, TextView) -> Unit
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
        val root = WeakReference(root)
        val label = WeakReference(label)
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var active = true
        private var generation = 0L
        private var pending: Runnable? = null
        private var previousGeometry: List<Int>? = null

        override fun onGlobalLayout() = schedule()

        override fun onViewAttachedToWindow(view: View) {
            if (!isCurrent(view)) return
            removeObserver()
            val current = view.viewTreeObserver
            if (current.isAlive) {
                // A detached observer may already have been merged into the new window.
                current.removeOnGlobalLayoutListener(this)
                current.addOnGlobalLayoutListener(this)
                observer = WeakReference(current)
            }
            previousGeometry = null
            cancelPending()
            schedule()
        }

        override fun onViewDetachedFromWindow(view: View) {
            removeObserver()
            cancelPending()
            previousGeometry = null
        }

        private fun isCurrent(view: View): Boolean = active && entries[view] === this

        private fun schedule() {
            val view = root.get() ?: run { dispose(); return }
            if (!isCurrent(view) || !view.isAttachedToWindow || pending != null) return
            val scheduledGeneration = generation
            val callback = Runnable {
                if (generation != scheduledGeneration) return@Runnable
                pending = null
                val currentRoot = root.get() ?: run { dispose(); return@Runnable }
                val currentLabel = label.get()
                if (!isCurrent(currentRoot) || !currentRoot.isAttachedToWindow) return@Runnable
                if (currentLabel == null) {
                    cancel(currentRoot)
                    return@Runnable
                }
                val geometry = geometry(currentRoot, currentLabel)
                if (geometry == previousGeometry) return@Runnable
                previousGeometry = geometry
                action(currentRoot, currentLabel)
            }
            pending = callback
            if (!view.post(callback)) pending = null
        }

        private fun cancelPending() {
            generation++
            pending?.let { root.get()?.removeCallbacks(it) }
            pending = null
        }

        private fun removeObserver() {
            val saved = observer.get()
            if (saved?.isAlive == true) saved.removeOnGlobalLayoutListener(this)
            val current = root.get()?.viewTreeObserver
            if (current !== saved && current?.isAlive == true) current.removeOnGlobalLayoutListener(this)
            observer.clear()
        }

        fun dispose() {
            active = false
            cancelPending()
            removeObserver()
            root.get()?.removeOnAttachStateChangeListener(this)
            // Release arbitrary callback captures immediately even if a dispatch snapshot retains us.
            action = { _, _ -> }
            previousGeometry = null
        }
    }

    private fun geometry(root: View, label: TextView): List<Int> {
        val result = ArrayList<Int>()
        fun record(view: View, depth: Int) {
            result.add(System.identityHashCode(view))
            result.add(System.identityHashCode(view.parent))
            result.add(view.visibility)
            result.add(view.left)
            result.add(view.top)
            result.add(view.right)
            result.add(view.bottom)
            result.add(view.minimumHeight)
            result.add(view.layoutParams?.height ?: 0)
            result.add(java.lang.Float.floatToIntBits(view.alpha))
            if (view is ViewGroup && depth < 4) {
                result.add(view.childCount)
                for (index in 0 until view.childCount) record(view.getChildAt(index), depth + 1)
            }
        }
        record(root, 0)
        // Labels can live below the bounded subtree or be removed without changing row bounds.
        record(label, 4)
        return result
    }
}
