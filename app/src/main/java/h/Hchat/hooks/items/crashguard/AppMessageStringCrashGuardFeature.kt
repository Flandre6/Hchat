package h.Hchat.hooks.items.crashguard

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 防护微信 AppMessage 文本生成方法中的空字符串 NPE。 */
class AppMessageStringCrashGuardFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "微信消息文本崩溃防护"

    override fun onFeatureInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.EARLY) {
            AppMessageStringCrashGuardRuntime(context).install()
        }
    }

    companion object {
        const val ID = "app_message_string_crash_guard"
    }
}

private class AppMessageStringCrashGuardRuntime(
    private val context: FeatureContext
) {
    private companion object {
        const val CACHE_PREFS = "Hchat_app_message_string_crash_guard_cache"
        const val CACHE_METHOD = "string_context_boolean_method"
    }

    @Volatile
    private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = locateMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val result = runCatching {
                        // 绕过当前 Hook 调用原方法，避免递归。
                        XposedBridge.invokeOriginalMethod(method, param.thisObject, param.args)
                    }.getOrDefault("")
                    // before 阶段设置结果，阻止原方法再次执行。
                    param.result = result as? String ?: ""
                }
            })
            installed = true
            true
        }.getOrDefault(false)
    }

    private fun locateMethod(): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, runtimeKey, context.hostClassLoader(), CACHE_METHOD)
            ?.takeIf(::isTargetMethod)
            ?.let { return it }

        val found = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        returnType("java.lang.String")
                        paramTypes("android.content.Context", "boolean")
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.asSequence()
                .filter(::isTargetMethod)
                .sortedWith(
                    compareByDescending<Method> { it.name == "l" }
                        .thenByDescending { hasCrashFields(it.declaringClass) }
                        .thenBy { it.declaringClass.name }
                )
                .firstOrNull()
        }.getOrNull()

        if (found != null) {
            DexMethodCache.save(prefs, runtimeKey, CACHE_METHOD, found)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_METHOD)
        }
        return found
    }

    private fun isTargetMethod(method: Method): Boolean {
        val params = method.parameterTypes
        val owner = method.declaringClass.name
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == String::class.java &&
            params.size == 2 &&
            params[0] == Context::class.java &&
            params[1] == java.lang.Boolean.TYPE &&
            !owner.startsWith("com.tencent.mm.")
    }

    private fun hasCrashFields(clazz: Class<*>): Boolean {
        val names = HashSet<String>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { names += it.name }
            current = current.superclass
        }
        return listOf("l2", "m2", "o2").count(names::contains) >= 2
    }
}
