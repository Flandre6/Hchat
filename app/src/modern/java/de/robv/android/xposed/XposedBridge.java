package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import h.Hchat.modern.ModernXposedRuntime;

public final class XposedBridge {
    private XposedBridge() {
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        return ModernXposedRuntime.hook(method, callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> clazz, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    result.add(hookMethod(method, callback));
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> clazz, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new LinkedHashSet<>();
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            result.add(hookMethod(constructor, callback));
        }
        return result;
    }

    public static void log(String message) {
        ModernXposedRuntime.log(message);
    }

    public static void log(Throwable throwable) {
        ModernXposedRuntime.log(throwable);
    }
}
