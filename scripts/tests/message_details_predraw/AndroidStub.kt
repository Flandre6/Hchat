package android.view

class View(var viewTreeObserver: ViewTreeObserver = ViewTreeObserver()) {
    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(view: View)
        fun onViewDetachedFromWindow(view: View)
    }
    private val attachListeners = linkedSetOf<OnAttachStateChangeListener>()
    val attachListenerCount: Int get() = attachListeners.size
    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.add(listener) }
    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.remove(listener) }
    fun attachmentSnapshot(): List<OnAttachStateChangeListener> = attachListeners.toList()
    fun attached() { attachListeners.toList().forEach { it.onViewAttachedToWindow(this) } }
    fun detached() {
        attachListeners.toList().forEach { it.onViewDetachedFromWindow(this) }
        viewTreeObserver = ViewTreeObserver()
    }
}

class ViewTreeObserver {
    fun interface OnPreDrawListener {
        fun onPreDraw(): Boolean
    }

    var isAlive = true
        private set
    private val listeners = linkedSetOf<OnPreDrawListener>()
    val listenerCount: Int get() = listeners.size

    fun addOnPreDrawListener(listener: OnPreDrawListener) {
        check(isAlive)
        listeners.add(listener)
    }

    fun removeOnPreDrawListener(listener: OnPreDrawListener) {
        check(isAlive)
        listeners.remove(listener)
    }

    fun snapshot(): List<OnPreDrawListener> = listeners.toList()

    fun dispatch() = dispatch(snapshot())

    fun dispatch(snapshot: List<OnPreDrawListener>) {
        snapshot.forEach { check(it.onPreDraw()) { "Queue must not cancel drawing" } }
    }

    fun mergeInto(target: ViewTreeObserver) {
        check(isAlive && target.isAlive)
        target.listeners.addAll(listeners)
        listeners.clear()
        isAlive = false
    }
}
