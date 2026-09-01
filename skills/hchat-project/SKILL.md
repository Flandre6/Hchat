---
name: hchat-project
description: Hchat 项目协作硬规则、分支同步、逆向验证、DexKit 缓存和构建约定。
---

# Hchat 项目协作 Skill

## 适用范围

用于 `/data/data/com.termux/files/home/Hchat` 项目开发，以及 `alt-entry` 分支同步。

工作目录：

```sh
cd /data/data/com.termux/files/home/Hchat
```

`alt-entry` 分支：

```sh
cd /data/data/com.termux/files/home/Hchat-alt-entry
```

## 必读

- `AGENTS.md`：项目硬规则。
- `docs/FEATURE_FRAMEWORK.md`：内部协作、工具、构建发布说明。
- `docs/WECHAT_APIS.md`：已封装公共 API。
- `docs/SCRIPT_PLUGIN_API.md`：脚本插件对外接口。

## Skill 内容边界

- 本 skill 只记录长期协作规则、注意事项、流程约束和容易踩坑的通用要求。
- 不要把已经实现的功能细节、具体 UI 行为、逆向类名/方法名/签名、版本结论、临时调试过程或单功能方案写进 skill。
- 功能说明、实现细节、逆向依据、接口行为和用户可见变化应写进对应文档，例如 `docs/FEATURE_FRAMEWORK.md`、`docs/WECHAT_APIS.md`、`docs/SCRIPT_PLUGIN_API.md`。
- 如果某条信息只服务当前任务，不要沉淀进 skill；只有抽象成长期项目规则或通用注意事项后才允许加入。

## 核心规则

- 提交信息、推送说明、Release notes、工作流说明统一使用中文。
- 处理非简单任务时，必须先识别可独立并行的子任务；子代理工具可用且并行能实质缩短耗时时，优先启用一个或多个子代理处理边界清晰、写入范围不冲突的工作，主代理保留关键路径并负责审查、整合结果及关闭子代理。简单小改、强耦合任务或协调成本高于收益时不强行拆分。
- 不要回退用户已有改动；只提交本次任务相关文件。
- 除非用户明确允许，否则不要在本地执行 Gradle。
- 只有用户明确允许“本地编译”或“本地构建”时才运行 Gradle；本地构建默认跳过 AAR metadata 检测并使用低内存参数。
- 本地项目保持 `compileSdk 34` / `targetSdk 34`；GitHub Actions 构建时临时覆盖为 `compileSdk 37` / `targetSdk 37`。
- APK native ABI 只维护 ARM 64/32：`arm64-v8a` 与 `armeabi-v7a`。新增或替换 native 库必须同时覆盖这两套 ABI，不能只放 64 位库。
- 不要提交 keystore、`local.properties`、构建缓存、APK 产物、临时文件，或只服务本地构建的 Gradle 兜底配置。
- 影响功能行为、对外接口、脚本接口、UI 入口、文件路径、构建发布流程或逆向依据的改动，只同步更新对应文档；只有长期项目规则本身发生变化时，才更新仓库内和已安装的 skill。
- 脚本插件接口保持 WA 风格：优先兼容 WA 的方法名、参数顺序、参数类型、返回值；新增脚本接口一项一提交，并同步更新 `docs/SCRIPT_PLUGIN_API.md`。
- 插件 Agent 只能把内置指南和插件接口文档明确列出的能力当作已确认能力；两者未说明且当前运行时或工具结果无法确认时，必须明确说明未知或需要实际验证，不得猜测接口、行为、限制或版本兼容性。
- 模块自身错误日志统一走 `h.Hchat.utils.HLog.e(...)`。
- Agent 的外部联网必须保留可核验来源；服务端专用的搜索工具不能直接假设可从 APK 调用，运行端应通过明确的工具调用和结果回灌提供能力，不能只依赖摘要接口并把失败说成没有资料。
- Agent 模型请求启用提示缓存时，固定 system 与工具定义必须保持稳定；用户消息、模型响应、工具调用和工具结果按发生顺序只追加，普通网络重试复用同一请求前缀，只有压缩上下文或用户主动改写历史时开启新的缓存周期。
- Agent 的 API 或网络失败不能删除已完成的插件暂存修改；继续任务必须恢复经路径、插件身份、基线、内容指纹和 revision 校验的工作区 checkpoint，并避免重放已成功工具，只有新任务、重新开始、明确取消、成功提交或恢复点过期才清理。

## 分支规则

- 项目长期维护 `main` 和 `alt-entry` 两个分支。
- 功能、接口、文档、构建流程默认同步到两个分支。
- `alt-entry` 保留频道差异：`消息显示时间`、`历史发言记录`、`红包显示详情`、`跳过网页风险` 与 `视频号媒体下载` 是分支专属入口，分别放在聊天、界面、红包转账和杂项分组；不能恢复为统一的“实用功能集合”，也不需要同步到 `main`。
- 普通双分支改动先推 `main`，再用 `rebase` 或 `cherry-pick` 线性同步到 `alt-entry`；禁止默认 merge。
- 只有用户明确说明“只改主线”或“只改 alt-entry”，或改动属于上述 `alt-entry` 频道差异时，才允许单分支改动。

## 逆向与验证

- 不确定微信内部逻辑时先逆向，不猜类名、方法名、字段、构造签名、数据库表、Intent 参数或网络结构。
- 用户明确确认“无 R8 正常、R8 异常”时，必须先对同一代码基线的最终 APK 做 DEX/Smali 对比，优先检查 keep 规则、反射/脚本方法名、方法描述符、类型收窄和 Android 公共 ABI 边界；没有最终产物证据前不得反复修改业务逻辑或连续触发构建。R8 敏感路径用宽泛异常返回 `null` 时必须对原始 Throwable 做限频错误日志，不能静默吞掉根因。
- 涉及微信内部结构、DexKit、反射、数据库、Intent、网络场景或版本兼容时，默认横向覆盖 `8.0.49`、`8.0.58`、`8.0.66`、`8.0.68`、`8.0.72`、`8.0.74`、`8.0.76`、`8.0.77`；如果缺 APK 或用户明确限制单版本，必须说明原因。
- 替换现有逆向 Hook 入口时，必须删除被替代的定位、缓存和运行时分支，不保留未经当前版本证据支持的旧兜底；新入口按默认版本集合重新横向确认。
- 同一功能在目标版本同时存在旧列表、现代列表、分组列表或灰度分支时，必须保留并安装所有经逆向确认仍可达的并行业务入口；不能因为优先候选已命中就提前结束定位。
- 优先复用 `WeChatApis` 已封装能力；没有公共 API 时，再用 DexKit、DexClub、APK 逆向证据确认。
- DexClub/MCP 工具可能以 deferred 形式提供；判断 `mcp__dexclub__` 不可用前必须先用 `tool_search` 搜 `dexclub`、`mcp__dexclub__` 或 `open_target_session`，只有搜索仍无结果或工具调用失败后才认定不可用；不要只看 `list_mcp_resources` 或 `list_mcp_resource_templates`。
- 读取混淆数据库或序列化结构时，必须用持久化元数据、实际结构和运行时样本交叉验证，不能只按名称模糊匹配或猜字段。
- 查询微信数据库时只选择已由目标版本表结构确认存在的字段；底层把 SQL 失败转换成空结果时，不能把失败误判为业务数据为空，也不能依赖上层异常重试。
- 多个功能依赖同一微信能力或数据语义时，提取到公共 API、解析器或内部服务，避免单功能特判和多套不一致实现。
- 当前流程已经持有可信原生业务对象时优先复用；只有逆向证据证明必要时才查库或重建对象。
- 微信页面把 Intent 参数复制到 UIC、ViewModel 或其它内部状态时，必须确认真实初始化时序并在复制前改写；不能只 Hook Activity `onCreate` 后假设后续状态仍会读取同一个 Intent。
- Hook 必须限定到逆向确认的具体业务范围并只 Hook 可执行的具体方法；不能全局关闭共用保护，也不能直接 Hook 抽象方法。
- 反射或 Hook 签名发生变化时，日志应包含微信版本、目标描述符和失败阶段等必要定位信息。
- 新增反射代码统一走 `h.Hchat.utils.KavaReflector`。

APK 路径由用户自行填写，不在项目规则中预设个人设备目录或文件名。传给 DexClub 时使用绝对路径，例如：

```sh
export WECHAT_APK="/你的目录/你的微信APK.apk"
test -f "$WECHAT_APK" && ls -lh "$WECHAT_APK"
```

## DexKit 规则

- 新增或修改 DexKit 定位必须缓存。
- 缓存 key 至少覆盖微信版本、`versionCode`、`clientVersion`、热更新标识、APK 时间戳、ClassLoader 指纹。
- 缓存有效时不要在每次启动重跑 DexKit；读取缓存发现已存 key 和当前 runtime key 不一致时要立即清空旧 descriptor 并写入当前 key，只有缓存缺失、缓存解析失败、微信升级降级或热更新变化时才重新定位。
- 能放后台线程的 DexKit 定位和 hook 尽量放后台，避免拖慢微信启动；必须早期同步生效的场景优先走缓存命中路径。
- 依赖 DexKit 的功能要支持补装，不能只在启动时尝试一次。
- 功能同时运行在主进程与微信子进程、且 DexKit 只应在主进程执行时，主进程负责定位并缓存描述符，子进程通过跨进程缓存解析当前 ClassLoader 下的成员，并使用统一调度器有限补装，不能在子进程另建 DexKit。
- 依赖 DexKit 的普通功能统一走 `h.Hchat.hooks.core.DexInstallScheduler`；安装函数返回 `Boolean`，成功才标记 installed/hooked。
- 必须抢在微信 UI 构建前安装的设置入口类 Hook 不要等待普通 DexKit 调度队列；确认需要例外时，应在初始化后台阶段通过统一 DexKit 串行门直接安装并在文档说明原因。
- 不要在功能内新增 `installWithRetry`、`Thread.sleep` 重试或裸线程抢跑 DexKit。
- 使用 `DexMethodCache` 的功能每次定位前实时读取 `DexMethodCache.runtimeKey(context, classLoader)`；不要在 hooker 构造时保存固定 runtime key。
- 启动期共享 `DexKitBridge` 的创建、查询和关联 Hook 安装必须通过统一串行门执行；不要并发使用 bridge，也不要使用 `DexKitBridge.create(classLoader, true)`。
- DexKit 的嵌套 `addInvoke` 匹配条件作用于被调用方法；调用者持有的字符串不能附加到被调用方法 matcher，除非逆向已确认字符串确实属于被调用方法。

## 代码分层

- `hooks/api/**`：公共微信 API。
- `hooks/items/<feature>/**`：单个功能实现。
- `dexkit/**`：DexKit 定位与缓存。
- `hooks/core/**`：生命周期、hook 注册、清理。
- `ui/**`：设置 UI、Provider、页面。
- `event/**`：跨功能事件。

- 新增功能优先用 Kotlin；涉及 Xposed、反射、R8 或 Java 互调时以稳定为准。
- 同一底层入口被多个功能使用时，统一 Hook、统一解析和统一调度，不要重复安装 Hook 或维护彼此竞争的临时状态。
- 功能专属事件使用内部事件通道；不要把已拦截事件重新派发给无关的公共观察者。
- 状态比较、缓存和持久化必须保持数据语义一致；未知值不能覆盖已有有效值，首次初始化或批量同步不能误报变化。
- 批量操作执行前验证全部目标，执行中限制节奏并允许取消；批量转发不能在主线程同步遍历完整收件人列表，也不能把数百个收件人一次交给微信重发 Activity，必须按单个目标或有界小批次串行推进；任一目标身份不明确时不得继续破坏性操作。
- 批量发送同时存在全局间隔和任务级间隔时，运行时取两者较大值并保留内部最短安全间隔；最后一条内容或最后一个目标完成后不得再等待下一项间隔，取消状态和等待超时必须把实际配置延迟纳入计算。
- RecyclerView 等复用 UI 注入必须在每次绑定时清理旧状态，以稳定的当前行 View 为锚点，并避免阻塞主线程或反复触发布局；目标 Adapter 同时存在完整绑定和 payload 局部绑定时必须横向确认并覆盖两条入口，不能只处理首次或完整绑定后假设局部刷新会自动复用样式。
- 悬浮底栏、输入区等动态高度控件覆盖滚动内容时，必须按实际测量高度保留滚动区域和悬浮按钮的底部空间，不能依赖固定高度常量。
- 悬浮入口跨 Activity 挂载时必须在 `addView` 前恢复保存位置；窗口尺寸尚未可用时先隐藏，完成定位后再显示，不能用下一帧从左上角移动到目标位置。自定义悬浮图标和菜单表面必须适配深色模式，并为未配置的深色图标提供稳定回退。已展开的悬浮菜单在拖动入口时必须保持展开并随入口移动，不能把拖动误判为收起；单击入口或菜单外区域可以收起。限定“仅主页”显示时不能只按 Activity 类名判断，必须同时识别同一 Activity 内嵌的聊天页或其它业务页。
- 同一业务事件选择一个权威来源，不能同时消费多个回调后再依赖时间窗口去重。
- 新消息观察的数据库入站兜底只能消费新增记录，不能把已读、媒体下载或状态变化产生的消息表更新当作新消息；宿主可能用 `replace/upsert` 更新旧记录时，还必须结合有效创建时间和稳定消息 ID 去重。出站更新只允许用于补偿自己手动发送的消息，消费方仍须校验发送方向和当前账号，账号身份未就绪时会产生发送副作用的自动化功能必须失败关闭。

## 设置与脚本文档

- 设置入口统一走 `SettingsUI.show(context)`。
- 微信设置页入口必须保留原生设置项并与其它模块共存；具体插入和兼容方案写入功能文档，不沉淀到 skill。
- 普通功能设置页统一收敛到现有 Miuix 设置体系，不要随意扩散到独立 Activity 或临时 Dialog 流程。
- 主设置页首页只展示大分组入口，功能项放在对应二级菜单；新增功能优先归入既有分组，搜索必须覆盖模块功能和本地脚本插件。
- 新增功能设置页必须遵守统一交互：二级/三级菜单切换要有过渡动画；三级菜单返回二级菜单要保留原滚动位置；涉及好友、群聊、公众号、标签好友或群成员名单必须使用现有选择器，不要额外暴露 wxid、群号或成员 ID 手动填写框；涉及文件、媒体或路径选择必须使用 Android 系统文件管理器或系统选择器；二级选择菜单选中条目后不要自动返回，必须允许用户手动点返回。
- 全屏 Compose 弹窗、加载层或遮罩切换到下一层界面时，必须先完整关闭并移除旧层，再通过 Activity decor 的 `postOnAnimation` 下一帧创建新层；不得在关闭旧 `WindowDialog` 的同一主线程消息循环里立即创建联系人、时间、进度或其它弹窗。后台加载必须捕获异常，并在成功、失败、取消和 Activity 销毁路径都关闭旧遮罩，不能遗留透明全屏根 View 吞掉触摸。
- 选择器里的筛选分组、标签筛选、搜索筛选属于同层状态切换，不要做左右横向页面进出动画；只有进入标签成员、群成员、编辑页这类真实下一层页面时才做横向过渡。
- 好友、群聊、群成员、标签好友等名单选择器进入页面时，必须在当前筛选和搜索结果内优先置顶已保存的已选项；本次进入后新增勾选或取消勾选不要立刻触发列表重新排序。通用好友选择器只包含微信好友和企业微信联系人，不包含单向联系人或群成员；需要群成员的功能只能在自己的候选范围内显式追加。多选页应提供作用于当前筛选和搜索结果的全选/取消全选入口。如果弹窗或页内弹出菜单承载的是名单/多选，也按同样规则置顶。普通少量单选枚举弹窗保持业务顺序即可。
- 名单随机选择必须从当前有效候选项中无重复抽取并限制数量上限，随机结果只在用户触发时生成和确认后持久化，列表刷新或重新绑定时不能再次随机改变结果。
- 选择器的虚拟分组或标签必须在提交前解析为真实业务 ID，不能把模块私有标识写进功能配置。
- 设置项存在多个互斥选项时，优先使用类似系统设置的页内弹出选择菜单（项目里的 `PopupChoiceRow` / `PopupChoice`）；只有选项很多、需要多选、需要搜索或需要复杂配置时，才使用 `OptionPickerPage` 或单独二/三级页面。不要用点击一行循环切换值代替明确选项菜单。
- 新增功能里，只要输入框由开关、选项或模式控制，就必须等对应开关开启、选项选中或模式切换到位后再显示；关闭时不要提前露出输入框，也不要保留无效占位。
- 模板、绑定和其它引用型配置必须保持引用完整性；删除、停用、批量套用和迁移时同步处理绑定关系，并保留用户自定义内容。
- 配置升级必须向后兼容；新增默认值或迁移规则不能覆盖用户已有设置，废弃别名只保留运行时兼容，不继续暴露给新配置。
- 高耗电、高权限或常驻功能默认关闭，并在关闭或销毁时释放系统资源。
- 脚本接口保持 WA 兼容；具体回调、字段、方法语义和完整 API 只写入 `docs/SCRIPT_PLUGIN_API.md`。

## 构建与发布

- 默认 APK 走 GitHub Actions 构建。
- 主线与 `alt-entry` 产物文件名固定为 `Hchat-release-signed.apk` 和 `Hchat-alt-entry-release-signed.apk`。
- GitHub Actions 使用 `gradle/actions/setup-gradle`、Gradle build cache 和并行任务；两个受信任分支均允许写缓存，setup action 不校验仓库现有 wrapper JAR；双频道工作流并行构建两个工作区后统一发布，不要恢复为串行构建或关闭缓存。
- 日常安装验证可使用 `.github/workflows/android-test.yml` 构建当前频道的无 R8 测试包；该工作流临时关闭 minify 和资源压缩、跳过 release lint，只上传 Actions Artifact，不得创建 tag、Release 或 Telegram 消息。正式发布仍使用保留 R8 的单频道或双频道工作流。
- 双频道正式工作流与无 R8 测试工作流直接使用各自的 GitHub Actions 构建编号作为 `versionCode`；单频道正式工作流按文档记录的本地版本与历史运行号锚点换算，保持后续单频道版本连续递增。
- GitHub Actions 生成 `versionName` 时三段都按 0-9 进位，`minor` 超过 9 必须进到 `major`，不要生成 `1.10.x` 这类未归一化版本。
- 常用命令：

```sh
git status --short
git diff --check
git add <file>
git commit -m "中文提交信息"
git push
gh workflow run 295394193 --repo ljh520134/Hchat --ref main
sh scripts/download_release_apk.sh
```

- 本地编译检查：

```sh
cd /data/data/com.termux/files/home/Hchat
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac

cd /data/data/com.termux/files/home/Hchat-alt-entry
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

- 只有用户明确要求本地构建时，才执行 release 构建命令：

```sh
cd /data/data/com.termux/files/home/Hchat
git checkout main
git pull --ff-only origin main
sh ./gradlew :app:assembleRelease --no-daemon --no-watch-fs -x lintVitalAnalyzeRelease

cd /data/data/com.termux/files/home/Hchat-alt-entry
git checkout alt-entry
git pull --ff-only origin alt-entry
sh ./gradlew :app:assembleRelease --no-daemon --no-watch-fs -x lintVitalAnalyzeRelease
```
