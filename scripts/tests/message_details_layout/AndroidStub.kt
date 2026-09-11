package android.view
open class View {
    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(view: View)
        fun onViewDetachedFromWindow(view: View)
    }
    var viewTreeObserver = ViewTreeObserver()
    var isAttachedToWindow = true
    var parent: ViewGroup? = null
    var visibility = 0
    var left = 0; var top = 0; var right = 100; var bottom = 100
    var minimumHeight = 0; var alpha = 1f
    var layoutParams: ViewGroup.LayoutParams? = ViewGroup.LayoutParams()
    val attachListeners = linkedSetOf<OnAttachStateChangeListener>()
    val posts = mutableListOf<Runnable>()
    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.add(listener) }
    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.remove(listener) }
    fun post(runnable: Runnable): Boolean { posts.add(runnable); return true }
    fun removeCallbacks(runnable: Runnable): Boolean = posts.remove(runnable)
    fun flush() { val batch = posts.toList(); posts.clear(); batch.forEach { it.run() } }
    fun detach() { isAttachedToWindow = false; attachListeners.toList().forEach { it.onViewDetachedFromWindow(this) }; viewTreeObserver = ViewTreeObserver() }
    fun attach(window: ViewTreeObserver = viewTreeObserver) { viewTreeObserver = window; isAttachedToWindow = true; attachListeners.toList().forEach { it.onViewAttachedToWindow(this) } }
}
open class ViewGroup : View() {
    class LayoutParams(var height: Int = -2)
    val children = mutableListOf<View>()
    val childCount get() = children.size
    fun getChildAt(index: Int): View = children[index]
    fun addView(view: View) { children.add(view); view.parent = this }
    fun removeView(view: View) { children.remove(view); view.parent = null }
}
class ViewTreeObserver {
    fun interface OnGlobalLayoutListener { fun onGlobalLayout() }
    var isAlive = true
    val listeners = linkedSetOf<OnGlobalLayoutListener>()
    fun addOnGlobalLayoutListener(listener: OnGlobalLayoutListener) { check(isAlive); listeners.add(listener) }
    fun removeOnGlobalLayoutListener(listener: OnGlobalLayoutListener) { check(isAlive); listeners.remove(listener) }
    fun dispatch() { listeners.toList().forEach { it.onGlobalLayout() } }
    fun mergeInto(target: ViewTreeObserver) { target.listeners.addAll(listeners); listeners.clear(); isAlive = false }
}
