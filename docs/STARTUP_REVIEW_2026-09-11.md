# 微信启动时模块加载优化

本轮基于上一轮插件生命周期与内存优化后的源码，继续检查启动加载路径。改动前快照保存在相邻 Hchat-startup-review-before 目录。

## 已完成的优化

| 路径 | 原行为 | 当前行为 |
| --- | --- | --- |
| WeChatVersionApi.build | firstNonEmpty 参数提前执行，即使前项命中也读取 Tinker 目录 | 按原优先级逐项判空，只读取必要来源；每次仍实时计算指纹 |
| DexFinder.resolveProtobufPacketApi | 公共 API 缓存命中后仍调用 findProtobufBaseClass 做 DexKit 查询 | 基类纳入现有持久化缓存；旧缓存补齐一次，之后只在缺失时定位 |
| NativeLibraryLoader | 热更新、平板、模块初始化重复请求 DexKit 时仍打开 APK、加文件锁、清理目录 | 同一模块 ClassLoader 的同一库只对成功结果去重；失败保留重试 |
| 平板早期 Hook | 有效缓存也要等后台 DexKit 串行门 | 先做不依赖 DexKit 的缓存安装；缺失后才进入串行门并再次检查 |

未修改微信混淆入口和定位条件，没有并发使用 DexKitBridge，也没有删除公共 API 缓存命中后的缺项修复。设置入口、底栏、平板与热更新的早期安装要求继续保留。

## 证据与验证

DexClub 对用户给出的 Hchat-alt-entry-r8-debug-signed.apk 确认：Lh/Hchat/dexkit/DexFinder;->resolveProtobufPacketApi()V 开头无条件调用 findProtobufBaseClass()，与本地旧源码一致。

- 版本指纹：scripts/tests/startup_version/run.js 的 22 项 JVM 桩测试通过。完整运行时元信息场景中，旧实现调用 getFilesDir 进入热更新路径 5 次，修改后 0 次；测试覆盖各级优先级、缺少 BuildConfig、文件回退、元数据修改后下一次调用立即更新。
- 原生库去重：scripts/tests/startup/run.js 验证 100 个并发请求只执行一次成功加载动作，库之间及 ClassLoader 之间隔离，返回 false 或抛异常后仍能重试。真实 System.load 和 Android 跨进程文件锁不在该 JVM 测试范围内。
- 使用本机已有 Android 37 SDK 与已构建类/依赖，对 ModuleEntry、DexFinder、NativeLibraryLoader、NativeLoadCache 做离线 javac 检查，通过；这是 Java 改动检查，不是完整源码或 APK 构建验证。
- 使用 git diff --no-index 做空白检查，并校验导出的补丁能反向匹配当前文件。本目录仍无 .git、主线目录不存在，因此没有提交或双分支同步。

复现命令：

    node scripts/tests/startup_version/run.js
    node scripts/tests/startup/run.js

未运行 Gradle，未生成或替换 APK。未取得设备启动耗时、主线程 trace、对应微信 APK，不能给出启动快了多少毫秒或百分比，也未做多版本微信实机验证。缓存完整的后续启动更可能受益，首次安装、微信升级或缓存失效时必要定位仍会执行。

后续实机应在相同微信版本、模块配置和插件集合下比较冷启动，分别观察设置入口可用、公共 API 就绪及主界面可交互时间，避免将减少的目录访问次数当成实测启动时间。
