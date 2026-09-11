package h.Hchat.hooks.items.floatingshortcut
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.TimeUnit
private fun ImageView.source() = (drawable as? BitmapDrawable)?.bitmap?.source
fun main() {
    Looper.getMainLooper()
    var passed = 0
    fun verify(name: String, body: () -> Unit) {
        FloatingShortcutIconLoader.clear()
        Handler.drain()
        body()
        passed++
        println("PASS " + name)
    }
    verify("cold binding returns fallback while background decode is blocked") {
        val gate = BitmapFactory.Gate(); BitmapFactory.gates["cold"] = gate
        val fallback = Drawable(); val view = ImageView()
        FloatingShortcutIconLoader.bind(view, "cold", fallback)
        check(view.drawable === fallback)
        check(gate.started.await(3, TimeUnit.SECONDS))
        gate.release.countDown(); Handler.runNext()
        check(view.source() == "cold")
    }
    verify("shared path decodes once and cache reopening performs no decode") {
        val first = ImageView(); val second = ImageView()
        FloatingShortcutIconLoader.bind(first, "shared", Drawable())
        FloatingShortcutIconLoader.bind(second, "shared", Drawable())
        Handler.runNext()
        val third = ImageView(); FloatingShortcutIconLoader.bind(third, "shared", Drawable())
        check(listOf(first, second, third).all { it.source() == "shared" })
        check(BitmapFactory.calls["shared"]!!.get() == 1)
    }
    verify("prefetch is reused by the first visible binding") {
        FloatingShortcutIconLoader.prefetch(listOf("warm", "warm", ""))
        Handler.runNext()
        val view = ImageView(); FloatingShortcutIconLoader.bind(view, "warm", Drawable())
        check(view.source() == "warm" && BitmapFactory.calls["warm"]!!.get() == 1)
    }
    verify("late light icon never overwrites a rebound dark icon") {
        val view = ImageView()
        FloatingShortcutIconLoader.bind(view, "light", Drawable())
        FloatingShortcutIconLoader.bind(view, "dark", Drawable())
        Handler.runNext(); Handler.runNext()
        check(view.source() == "dark")
    }
    verify("detached Activity view is not updated") {
        val view = ImageView(); val fallback = Drawable()
        FloatingShortcutIconLoader.bind(view, "detached", fallback)
        view.isAttachedToWindow = false
        Handler.runNext(); check(view.drawable === fallback)
    }
    verify("replacement invalidates in-flight old file data") {
        val gate = BitmapFactory.Gate(); BitmapFactory.gates["replace"] = gate
        BitmapFactory.versions["replace"] = "old"
        val view = ImageView(); FloatingShortcutIconLoader.bind(view, "replace", Drawable())
        check(gate.started.await(3, TimeUnit.SECONDS))
        FloatingShortcutIconLoader.invalidate("replace")
        BitmapFactory.versions["replace"] = "new"
        FloatingShortcutIconLoader.bind(view, "replace", Drawable())
        gate.release.countDown(); Handler.runNext(); Handler.runNext()
        check(view.source() == "new")
        val reopened = ImageView(); FloatingShortcutIconLoader.bind(reopened, "replace", Drawable())
        check(reopened.source() == "new")
    }
    verify("clear discards late callbacks and permits a fresh decode") {
        val gate = BitmapFactory.Gate(); BitmapFactory.gates["clear"] = gate
        val oldView = ImageView(); val fallback = Drawable()
        FloatingShortcutIconLoader.bind(oldView, "clear", fallback)
        check(gate.started.await(3, TimeUnit.SECONDS))
        FloatingShortcutIconLoader.clear()
        gate.release.countDown(); Handler.runNext(); check(oldView.drawable === fallback)
        val newView = ImageView(); FloatingShortcutIconLoader.bind(newView, "clear", Drawable())
        Handler.runNext(); check(newView.source() == "clear")
    }
    verify("large source is downsampled and capped at 256 pixels") {
        val view = ImageView(); FloatingShortcutIconLoader.bind(view, "large", Drawable())
        Handler.runNext()
        val bitmap = (view.drawable as BitmapDrawable).bitmap
        check(bitmap.width == 256 && bitmap.height == 128)
    }
    verify("missing image is negatively cached and keeps fallback") {
        val fallback = Drawable(); val first = ImageView()
        FloatingShortcutIconLoader.bind(first, "missing", fallback); Handler.runNext()
        val second = ImageView(); FloatingShortcutIconLoader.bind(second, "missing", fallback)
        check(first.drawable === fallback && second.drawable === fallback)
        check(BitmapFactory.calls["missing"]!!.get() == 1)
    }
    println("Passed " + passed + " floating icon regression cases")
}
