# R8 安装包与插件生命周期检查（2026-09-11）

## 范围与结论

目标产物：Hchat-alt-entry-r8-debug-signed.apk，包名 h.Hchat，versionCode=1，versionName=1.0.0。
SHA-256：5ffaa373b90e932f18d6f54a1eecb537d49cef3bd9cb4d01a53cf307c197c570。

通过 DexClub MCP 检查该 APK，并对照本目录源码。确认插件 Hook 句柄滞留、无界普通消息队列、未跟随插件卸载取消的延迟任务三类问题，已修复源码。未获取设备堆转储、卡顿时线程栈、微信版本及启用插件清单，因此不能断定实际卡顿全由小程序或 R8 引起，也未测得帧率、PSS 或 GC 的改善幅度。

普通插件缺省只在 main 执行；process=appbrand/all 才进入小程序独立运行时。小程序不会初始化完整 FeatureManager 和主进程消息监听；它的启用状态及代码变更仍需该进程重新启动才能应用。关闭再打开小程序页面不保证系统销毁进程，验证时应彻底退出并重启微信。

## 已确认问题与改动

1. HookRegistry 的全局 CopyOnWriteArrayList 保存 Unhook；旧版 ScriptPluginBridge.unhookPlugin 只调用句柄的 unhook，未释放注册表条目。句柄关联 Hook 回调，脚本 Consumer/Function 进一步关联解释器。连续加载和卸载会保留旧回调。新增统一解除入口，单独解除、卸载和加载失败均同步删除注册表句柄；UI 清理异常也不再跳过后续 Hook 清理。
2. ScriptMessageHook 原用 Executors.newSingleThreadExecutor，慢插件或解释器锁长时间占用会使完整消息对象在无界队列累积。改为最多等待 128 条的单线程 FIFO 队列，溢出跳过新脚本事件并限频计数；无消费者快速退出，最后消费者卸载清空等待队列。入队捕获插件实例，执行和取得解释器锁后复核身份，避免重载接收旧事件。回调结束清除内部临时消息变量。
3. ScriptWaBridge.delay 原无卸载归属；主进程 task key 在各插件内从 1 计数，可能互相覆盖，小程序无 task API 时每项创建 Thread.sleep 线程。新增 ScriptDelayScope，每次加载独立持有；解释器初始化失败也会清理。主进程保留主线程执行，小程序使用共享两个调度线程；单实例/单进程待执行上限为 128/512。取消立即移除任务并释放闭包，已执行脚本不会被强制中断，已关闭实例不能继续追加 delay。
4. 平板检测保留聊天语音栈判定，直接检查栈元素类名，省去完整异常堆栈字符串格式化。小程序视频事件先筛事件名，再读取设置，避免无关事件反复查配置。

## APK 证据

- Lh/Hchat/ModuleEntry;：handleLoadPackage 在普通功能前按主进程/appbrand 分流；热更新和平板早期入口在进程分流前安装。
- Lh/Hchat/hooks/items/script/ScriptPluginBridge;->hookBefore(...)：调用混淆后的 Ldo1;->b(...) 注册 Hook。
- Ldo1;：静态单例及 CopyOnWriteArrayList 存储；注册方法将 Unhook 放入列表。
- Lh/Hchat/hooks/items/script/ScriptPluginBridge;->unhookPlugin(Ljava/lang/String;)V：循环直接调用 XC_MethodHook.Unhook.unhook，没有从全局列表移除。
- Lh/Hchat/hooks/items/script/ScriptWaBridge;->delay(JLjava/lang/Runnable;)V：task API 不存在时创建并启动独立 Thread。
- Lht3;->afterHookedMethod(...) 与 Le61;->beforeHookedMethod(...)：分别含聊天语音栈检测、小程序视频事件分支（R8 将多个回调合并在同一类中）。

## 验证

不运行 Gradle，不重新构建或替换 APK。新增 scripts/run_script_lifecycle_tests.cjs，直接使用本地 Kotlin 编译器对生产 HookRegistry、ScriptDelayScope、ScriptMessageHook 与小型 Android/Xposed/API 桩运行 JVM 回归：

- 1,000 次 Hook 注册/解除循环，注册表回归基线；保留无关 Hook；解除失败可保留句柄重试。
- 1,000 次延迟任务加载/卸载循环；跨插件互不覆盖；128/512 容量限制；提交失败释放配额；已关闭回调无法递归排队；小程序工作线程为两个。
- 阻塞首条回调后连续输入 400 条消息，待执行数量为 128、接受事件保持 FIFO、不在生产线程执行、溢出日志限频；关闭后队列清空；仅媒体消费者仍获事件。

这些测试验证了相应生产类在桩环境下的行为，不代表完整 Android 模块编译、真实 Xposed 解除效果、BeanShell 并发行为或设备流畅度验证。

复现命令（Kotlin lib 目录应包含 compiler-embeddable、stdlib 及其依赖 JAR）：

    node scripts/run_script_lifecycle_tests.cjs /path/to/kotlin/lib

本目录无 .git，且 main 工作目录不存在，无法确认源码提交或同步两条分支。改动前源码和文档保存在相邻 Hchat-alt-entry-review-before 目录；使用 git diff --no-index 检查与生成补丁。

## 仍需设备验证与后续关注

- 该 APK 的热更新/平板早期路径可能在子进程建立长期持有的 DexKitBridge，这是条件性常驻开销。本轮没有改动这些微信入口，避免未经微信版本验证改变设备形态或热更新行为。
- HTTP 异步请求、插件自建线程/定时器/直接 Xposed Hook、Native SO 不在本次 delay 清理范围内。慢或不返回的脚本仍可能占住解释器或 onUnload；线程数量/队列上限只能限制积压，不能安全终止任意脚本。
- 未提供目标微信 APK，未做 8.0.49、8.0.58、8.0.66、8.0.68、8.0.72、8.0.74、8.0.76、8.0.77 横向验证。本轮不新增混淆入口。
- 建议使用修复后构建，完整重启微信，然后在相同插件配置下重复打开关闭小程序、执行插件重载并持续收消息，对照内存、线程数、GC 和交互延迟；出现队列溢出日志时继续定位对应慢插件。
