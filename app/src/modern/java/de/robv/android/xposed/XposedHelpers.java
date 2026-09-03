package de.robv.android.xposed;

import java.lang.reflect.Method;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        try {
            return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("缺少 API 102 Hook 回调");
        }
        Object callbackObject = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(callbackObject instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("最后一个参数必须是 XC_MethodHook");
        }
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object parameterType = parameterTypesAndCallback[i];
            if (!(parameterType instanceof Class<?>)) {
                throw new IllegalArgumentException("方法参数类型必须是 Class");
            }
            parameterTypes[i] = (Class<?>) parameterType;
        }
        Method method = findMethod(clazz, methodName, parameterTypes);
        return XposedBridge.hookMethod(method, (XC_MethodHook) callbackObject);
    }

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    public static Object callMethod(Object object, String methodName, Object... args) {
        if (object == null) throw new NullPointerException("object");
        Class<?>[] types = new Class<?>[args == null ? 0 : args.length];
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? null : args[i].getClass();
            }
        }
        try {
            Method method = findCompatibleMethod(object.getClass(), methodName, types);
            method.setAccessible(true);
            return method.invoke(object, args == null ? new Object[0] : args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] parameterTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException(new NoSuchMethodException(clazz.getName() + "." + name));
    }

    private static Method findCompatibleMethod(Class<?> clazz, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] actual = method.getParameterTypes();
                if (!method.getName().equals(name) || actual.length != parameterTypes.length) continue;
                boolean compatible = true;
                for (int i = 0; i < actual.length; i++) {
                    if (parameterTypes[i] != null && !wrap(actual[i]).isAssignableFrom(wrap(parameterTypes[i]))) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(clazz.getName() + "." + name);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
