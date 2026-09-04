package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;

/**
 * Source-compatible callback surface used by the modern API 102 adapter.
 * The implementation is backed by io.github.libxposed.api.XposedInterface.
 */
public abstract class XC_MethodHook {
    private final int priority;

    public XC_MethodHook() {
        this(50);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        private final Map<String, Object> extras = new HashMap<>();

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        public void clearReturnEarly() {
            this.returnEarly = false;
        }

        public void setObjectExtra(String key, Object value) {
            if (key == null) return;
            extras.put(key, value);
        }

        public Object getObjectExtra(String key) {
            return key == null ? null : extras.get(key);
        }
    }

    public static class Unhook {
        private final Runnable unhookAction;

        public Unhook(Runnable unhookAction) {
            this.unhookAction = unhookAction;
        }

        public void unhook() {
            unhookAction.run();
        }
    }

    public final void callBeforeHookedMethod(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfterHookedMethod(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }
}
