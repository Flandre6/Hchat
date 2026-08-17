package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一保存 Xposed hook 句柄，便于防御性卸载。
 */
class HookRegistry private constructor() {
    private val unhooks = CopyOnWriteArrayList<XC_MethodHook.Unhook>()

    fun hook(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val unhook = XposedBridge.hookMethod(method, callback)
        if (unhook != null) {
            unhooks.add(unhook)
        }
        return unhook
    }

    fun add(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) {
            unhooks.add(unhook)
        }
    }

    fun size(): Int = unhooks.size

    fun unhookAll() {
        for (unhook in unhooks) {
            try {
                unhook.unhook()
            } catch (_: Throwable) {
            }
        }
        unhooks.clear()
    }

    companion object {
        private val INSTANCE = HookRegistry()

        @JvmStatic
        fun get(): HookRegistry = INSTANCE
    }
}
