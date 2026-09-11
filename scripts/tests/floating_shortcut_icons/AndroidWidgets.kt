package android.widget
import android.graphics.drawable.Drawable
class ImageView {
    val resources = Any()
    var isAttachedToWindow = true
    var drawable: Drawable? = null
    fun setImageDrawable(value: Drawable) { drawable = value }
}
