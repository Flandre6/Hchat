import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.HookRegistry

fun main() {
    val registry = HookRegistry.get()
    val method = String::class.java.getMethod("length")
    val permanent = registry.hook(method, XC_MethodHook())
    repeat(1_000) {
        val first = registry.hook(method, XC_MethodHook())
        val second = registry.hook(method, XC_MethodHook())
        check(registry.size() == 3)
        registry.unhook(first)
        registry.unhook(second)
        check(!first.active && !second.active)
        check(registry.size() == 1) { "Unloaded plugin retained by global registry" }
        check(permanent.active) { "Unrelated hook removed" }
    }
    registry.unhook(null)
    val failing = registry.hook(method, XC_MethodHook())
    failing.fail = true
    check(runCatching { registry.unhook(failing) }.isFailure)
    check(registry.size() == 2) { "Failed unhook lost the handle needed to retry" }
    failing.fail = false
    registry.unhook(failing)
    registry.unhook(failing)
    check(registry.size() == 1)
    registry.unhookAll()
    check(registry.size() == 0 && !permanent.active)
    println("PASS: 1000 reload cycles, independent hooks, null/idempotent removal, failed-unhook retry")
}
