# Hchat 社区贡献指南

感谢参与 Hchat 维护。这个项目是微信 LSPosed/Xposed 模块，很多问题不是普通 Android 页面问题，而是微信混淆、热更新、进程、数据库、R8 和 Hook 时机共同造成的。请按本文档提交可复现、可审查的改动。

## 1. 先确认你改的是哪个仓库

本公开仓库是：

```text
ljh520134/Hchat-alt-entry
```

公开仓库的默认开发基准是：

```text
alt-entry
```

它不是主线仓库的 `main`，也不要把公开仓库误配置成私有仓库 `ljh520134/Hchat`。

## 2. Fork 和远端配置

在 GitHub 页面点击 **Fork**，然后在 Termux 中：

```sh
git clone --branch alt-entry --single-branch \
  https://github.com/你的用户名/Hchat-alt-entry.git
cd Hchat-alt-entry

git remote rename origin upstream
git remote add origin \
  https://github.com/你的用户名/Hchat-alt-entry.git

git remote -v
git status --short --branch
```

如果你已经克隆了上游仓库，也可以只修改远端：

```sh
git remote rename origin upstream
git remote add origin https://github.com/你的用户名/Hchat-alt-entry.git
```

## 3. 每次开发前同步

先确认没有未保存的修改：

```sh
git status --short
```

工作区干净时同步：

```sh
git fetch upstream alt-entry
git switch alt-entry
git pull --ff-only upstream alt-entry
```

如果有自己的未提交修改，不要直接 reset。先保存：

```sh
git stash push -u -m "开发前临时保存"
git pull --ff-only upstream alt-entry
git stash pop
```

出现冲突时，先保留自己的补丁副本，再逐个解决；不要为了“快速同步”删除别人的改动。

## 4. 创建工作分支

不要直接在 `alt-entry` 上开发：

```sh
git switch -c fix/朋友圈过滤加载
```

推荐命名：

```text
fix/xxx       修复问题
feature/xxx   新增功能
refactor/xxx  只做结构整理
docs/xxx      文档修改
build/xxx     构建或 CI 修改
```

一个分支尽量只解决一个问题。不要把“朋友圈过滤、底栏动画、视频号下载、服务器迁移”混在同一个 PR 中。

## 5. 给 AI 的问题描述

有效的问题描述至少包括：

- 微信版本和 versionCode，例如 `8.0.49 (2600)`
- Android 版本、设备 ABI 和是否微信分身
- 模块版本、R8 或无 R8 构建
- 复现步骤
- 实际结果
- 期望结果
- 完整异常堆栈或关键日志
- 已经尝试过的提交或构建版本
- 是否允许修改代码、是否允许构建

不要只发“这个功能没反应，修一下”。这会让 AI 猜路径，尤其容易在微信混淆代码上改错入口。

推荐格式：

```text
仓库：ljh520134/Hchat-alt-entry
分支：fix/xxx
目标：修复 xxx
环境：微信 8.0.49 (2600)，Android 15，arm64-v8a，R8/无 R8
复现：1. ... 2. ... 3. ...
实际：...
期望：...
日志：贴完整堆栈，不要删掉异常类型和第一处 Hchat 调用
约束：先逆向确认；不要猜类名；不要改无关功能；本轮先不要构建
完成标准：代码、文档、静态检查和指定测试通过
```

## 6. 使用 Codex 的推荐流程

在仓库根目录启动：

```sh
codex -C "$PWD" -s workspace-write -a on-request
```

首次任务先让 AI 只调查：

```text
先不要修改文件，也不要构建。读取 AGENTS.md、相关 docs、git status 和目标代码，说明真实调用链、需要逆向确认的点、拟修改文件和验证办法。若涉及微信内部结构，先使用 DexClub MCP；没有证据不要猜混淆类名或方法名。
```

确认方案后再说：

```text
按刚才的范围动手。只改与这个问题直接相关的文件，保留我已有修改。完成后执行 git diff --check、相关测试和 git diff，总结改动、未验证项和测试结果。暂时不要提交和推送。
```

完成后让 Codex 做一次独立审查：

```sh
codex review --base alt-entry
```

有未提交修改时：

```sh
codex review --uncommitted
```

## 7. 涉及 DexClub 的任务

必须遵守以下顺序：

1. 准备目标微信 APK 的绝对路径。
2. 使用 `open_target_session` 打开目标。
3. 用字符串、资源或 Manifest 锚点缩小候选。
4. 用 `inspect_method` 查看字段、调用和字符串证据。
5. 确实需要实现细节时，才导出少量 Java 或 Smali。
6. 多版本任务至少打开两个 APK，并使用相同锚点横向比较。

不要：

- 把相对路径 `app.apk` 传给逆向工具
- 把一个微信版本的混淆 descriptor 直接复制到另一个版本
- 只凭类名相似就 Hook 第一个方法
- 用 shell 反编译结果替代已配置的 DexClub MCP
- 因为当前目标列表为空就认定 DexClub 不可用

更完整的安装、端口和调用示例见 `docs/AI_CODING_TUTORIAL.md`。

## 8. Hchat 代码注意事项

### Hook 和版本兼容

- Hook 具体可执行方法，不要全局替换共用抽象入口。
- 版本差异要保留并行业务入口，不能只验证一个版本。
- Activity `onCreate` 不一定是 Intent 参数真正被复制的位置，要检查 UI、ViewModel 或 UIC 的初始化顺序。
- 需要早于 UI 构建的 Hook 才允许走早期入口；普通 DexKit 任务使用统一调度器。

### DexKit

- 定位结果必须按微信版本、versionCode、clientVersion、热更新标识、APK 时间戳和 ClassLoader 区分缓存。
- 命中缓存时不要每次启动重跑 DexKit。
- 共享 `DexKitBridge` 必须串行使用。
- 定位失败不能标记为 installed；应保留限频日志和补装机会。

### UI

- RecyclerView 复用时每次绑定都要清理旧状态。
- ComposeView 不能用 `addView` 添加传统子 View；必须使用 Compose 内容或把传统 View 加到合适的普通父容器。
- 全屏遮罩、弹窗切换时先关闭旧层，再在下一帧创建新层，避免透明层吞掉触摸。
- 动态高度的悬浮控件必须按实际测量值为内容预留空间。

### R8

如果无 R8 正常、R8 失效：

1. 先固定同一提交和同一微信版本。
2. 对比最终 APK 的 DEX、Smali、方法名、描述符和保留规则。
3. 优先检查反射、脚本接口、JNI、Xposed 入口和公共 ABI。
4. 不要没有产物证据就连续修改业务逻辑。
5. 宽泛异常返回 `null` 时必须对原始 Throwable 限频记录日志。

## 9. 测试要求

至少执行：

```sh
git diff --check
```

源码改动建议执行：

```sh
./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

服务端改动执行：

```sh
python3 -m unittest discover -s server/plugin-market/tests -v
```

涉及微信运行时的 PR 说明：

```text
微信版本：
模块版本：
R8/无 R8：
设备和 Android：
是否强停微信后测试：
复现次数：
测试结果：
未测试版本：
```

没有设备或 APK 时，写“未验证”，不要写“兼容所有版本”。

## 10. 提交和推送

提交前检查：

```sh
git status --short
git diff --check
git diff --stat
git diff --cached --check
```

只暂存本次文件：

```sh
git add app/src/main/java/h/Hchat/hooks/items/xxx docs/xxx.md
git status --short
git diff --cached --stat
```

提交信息使用中文：

```sh
git commit -m "修复朋友圈过滤加载问题"
git push -u origin fix/朋友圈过滤加载
```

如果你有 `gh`：

```sh
gh pr create \
  --repo ljh520134/Hchat-alt-entry \
  --base alt-entry \
  --head 你的用户名:fix/朋友圈过滤加载 \
  --title "修复朋友圈过滤加载问题" \
  --body-file /tmp/hchat-pr.md
```

PR 标题说明问题，正文说明复现、根因、改动、测试和未验证范围。不要只写“修复 bug”。

## 11. PR 审查清单

- 是否只修改了一个相关功能？
- 是否保留了用户原有改动？
- 是否读过对应 API 和功能文档？
- 是否凭猜测使用了微信混淆类名？
- 是否处理 R8、热更新、ClassLoader 和缓存？
- 是否覆盖现代列表、旧列表、分组列表或子进程入口？
- 是否可能阻塞主线程？
- 是否清理 Hook、监听器、Handler、Executor 和临时文件？
- 是否把敏感信息、APK、签名文件或日志提交进 Git？
- 是否更新了对外 API 或开发文档？
- 是否写清楚真实测试环境和未测试版本？

## 12. 安全报告

不要在公开 Issue 中发布 API key、SSH 私钥、签名证书、服务器密码、微信数据库、个人聊天内容或可直接利用的账号信息。发现敏感信息泄露时，先撤销或轮换凭据，再通知维护者；仅删除 Git 当前文件不等于历史已经清除。
