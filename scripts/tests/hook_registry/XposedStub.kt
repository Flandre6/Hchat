package de.robv.android.xposed

import java.lang.reflect.Member

open class XC_MethodHook {
    inner class Unhook {
        var active = true
        var fail = false
        fun unhook() {
            if (fail) error("simulated framework failure")
            active = false
        }
    }
}

object XposedBridge {
    fun hookMethod(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook = callback.Unhook()
}
