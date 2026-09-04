package h.Hchat.modern;

import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import io.github.libxposed.api.XposedInterface;

/**
 * Small bridge from the existing callback shape to the API 102 interceptor chain.
 * No legacy framework implementation is used in the modern build.
 */
public final class ModernXposedRuntime {
    private static final String TAG = "Hchat-Api102";
    private static volatile XposedInterface xposed;

    private ModernXposedRuntime() {
    }

    public static void attach(XposedInterface api) {
        xposed = api;
    }

    public static XC_MethodHook.Unhook hook(Member member, XC_MethodHook callback) {
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("API 102 仅支持可执行成员: " + member);
        }
        XposedInterface api = xposed;
        if (api == null) {
            throw new IllegalStateException("API 102 HookRuntime 尚未初始化");
        }
        XposedInterface.HookBuilder builder = api.hook((Executable) member)
                .setPriority(callback.getPriority())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
        XposedInterface.HookHandle handle = builder.intercept(chain -> {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = member;
            param.thisObject = chain.getThisObject();
            param.args = chain.getArgs().toArray();

            try {
                callback.callBeforeHookedMethod(param);
            } catch (Throwable throwable) {
                log("beforeHookedMethod 异常", throwable);
                param.clearReturnEarly();
            }

            if (!param.isReturnEarly()) {
                try {
                    // API 102 rejects a null receiver in proceedWith(). Static
                    // methods (and constructors before an instance exists) must
                    // continue through the args-only overload.
                    Object result = param.thisObject == null
                            ? chain.proceed(param.args)
                            : chain.proceedWith(param.thisObject, param.args);
                    param.setResult(result);
                    param.clearReturnEarly();
                } catch (Throwable throwable) {
                    param.setThrowable(throwable);
                    param.clearReturnEarly();
                }
            }

            try {
                callback.callAfterHookedMethod(param);
            } catch (Throwable throwable) {
                log("afterHookedMethod 异常", throwable);
            }

            return param.getResultOrThrowable();
        });
        return new XC_MethodHook.Unhook(handle::unhook);
    }

    public static void log(String message) {
        Log.i(TAG, message == null ? "null" : message);
    }

    public static void log(Throwable throwable) {
        Log.e(TAG, throwable == null ? "null" : throwable.getMessage(), throwable);
    }

    public static void log(String message, Throwable throwable) {
        Log.e(TAG, message + ": " + throwable.getMessage(), throwable);
    }
}
