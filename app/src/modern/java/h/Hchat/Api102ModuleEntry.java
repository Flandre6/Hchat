package h.Hchat;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import h.Hchat.modern.ModernXposedRuntime;

/**
 * API 102 entry point. The existing bootstrap is invoked with a small
 * source-compatible package parameter while hooks are installed through the
 * modern XposedInterface backend.
 */
public final class Api102ModuleEntry extends XposedModule {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null || !isWeChatPackage(param.getPackageName())) return;
        ModernXposedRuntime.attach(this);

        XC_LoadPackage.LoadPackageParam legacyParam = new XC_LoadPackage.LoadPackageParam();
        legacyParam.packageName = param.getPackageName();
        legacyParam.processName = resolveProcessName(param.getPackageName());
        legacyParam.classLoader = param.getClassLoader();
        legacyParam.appInfo = param.getApplicationInfo();
        try {
            new ModuleEntry().handleLoadPackage(legacyParam);
        } catch (Throwable throwable) {
            ModernXposedRuntime.log("API 102 模块入口初始化失败", throwable);
        }
    }

    private boolean isWeChatPackage(String packageName) {
        return packageName != null && packageName.startsWith(WECHAT_PACKAGE);
    }

    private String resolveProcessName(String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String processName = Application.getProcessName();
                if (processName != null && !processName.isEmpty()) return processName;
            } catch (Throwable ignored) {
                // Fall through to the package name. The modern callback does not
                // expose processName directly, while the main process uses it.
            }
        }
        return packageName;
    }
}
