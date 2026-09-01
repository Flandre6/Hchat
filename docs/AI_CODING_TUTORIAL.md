# Hchat AI Coding 教程

本教程面向第一次参与 Hchat 开发的用户，覆盖 Termux、Codex、DexClub MCP、微信逆向、R8 构建和 GitHub PR。

公开仓库：

```text
https://github.com/ljh520134/Hchat-alt-entry
```

默认开发分支：`alt-entry`。

项目硬规则看 [AGENTS.md](../AGENTS.md)，Git 和 PR 看 [CONTRIBUTING.md](../CONTRIBUTING.md)，功能/API 细节按需查看 `docs/FEATURE_FRAMEWORK.md`、`docs/WECHAT_APIS.md` 和 `docs/SCRIPT_PLUGIN_API.md`。

## 0. 最短流程

只想先修一个问题时，执行：

```sh
pkg update && pkg upgrade -y
pkg install -y git nodejs-lts openssh curl wget jq unzip zip ripgrep tmux python openjdk-21 gh
termux-setup-storage
npm install -g @mmmbuto/codex-cli-termux@latest
cd "$HOME"
git clone --branch alt-entry --single-branch \
  https://github.com/ljh520134/Hchat-alt-entry.git
cd Hchat-alt-entry
codex -C "$PWD" --sandbox workspace-write --ask-for-approval on-request
```

进入 Codex 后先发：

```text
先不要修改文件、构建、提交或推送。读取 AGENTS.md、相关 docs 和 git status。
根据我提供的日志和复现步骤定位真实调用链；涉及微信内部类、方法、字段、数据库或 Intent 时，先使用 DexClub MCP 逆向确认，不要猜混淆名称。
输出根因候选、需要确认的证据、拟修改文件、兼容风险和测试方案。
```

## 1. Termux 环境

### 1.1 安装和存储权限

安装可信来源的 Termux，首次启动后执行：

```sh
pkg update && pkg upgrade -y
termux-setup-storage
```

允许系统弹出的存储权限。共享存储的真实目录、微信 APK 文件名和归档方式由用户自己决定，项目文档不指定固定路径。

### 1.2 安装工具

```sh
pkg install -y git nodejs-lts openssh curl wget jq unzip zip ripgrep tmux python openjdk-21 gh
```

检查：

```sh
git --version
node --version
npm --version
java -version
gh --version
rg --version
```

Hchat 的 Java/Kotlin 源码目标为 Java 17，GitHub Actions 使用 JDK 21。出现 JDK 不兼容时，以项目配置和 Gradle 报错为准，并记录实际版本。

### 1.3 用 tmux 保持长任务

编译、DexClub 和 Codex 都可能运行较久：

```sh
tmux new -s hchat
```

暂时离开：按 `Ctrl+b`，再按 `d`。重新进入：

```sh
tmux attach -t hchat
```

## 2. 安装和使用 Codex

### 2.1 安装

本项目按 `DioNanos/codex-termux` 的 Termux 发行版编写：

```sh
npm install -g @mmmbuto/codex-cli-termux@latest
which codex
codex --version
codex --help
```

发行版版本和参数可能变化，命令不一致时以 `codex --help` 和对应 README 为准。不要同时安装多个来源的 Codex，也不要用陌生网站的二进制覆盖 npm 安装。

### 2.2 登录和权限

```sh
codex login
codex login status
codex doctor
```

部分版本可能支持 `codex --login`。登录 Token、API key 和授权 URL 不能写入 Git、截图、Issue 或提示词。

新手推荐在仓库根目录运行：

```sh
codex -C "$PWD" --sandbox workspace-write --ask-for-approval on-request
```

部分旧版支持等价短参数：

```sh
codex -C "$PWD" -s workspace-write -a on-request
```

`workspace-write` 允许修改当前工作区，`on-request` 会在高风险命令前询问。涉及 Xposed、反射、数据库、native 和签名时，先看 diff 再批准。

## 3. 获取源码和分支

### 3.1 公开仓库

```sh
cd "$HOME"
git clone --branch alt-entry --single-branch \
  https://github.com/ljh520134/Hchat-alt-entry.git
cd Hchat-alt-entry
git status --short --branch
```

### 3.2 自己的 Fork

Fork 公开仓库后执行：

```sh
git clone --branch alt-entry --single-branch \
  https://github.com/你的用户名/Hchat-alt-entry.git
cd Hchat-alt-entry
git remote rename origin upstream
git remote add origin \
  https://github.com/你的用户名/Hchat-alt-entry.git
git remote -v
```

`upstream` 是公开仓库，`origin` 是自己的 Fork。开始新任务前：

```sh
git status --short
git fetch upstream alt-entry
git switch alt-entry
git pull --ff-only upstream alt-entry
git switch -c fix/功能名称
```

有未提交改动时先阅读、保存或提交，不能使用 `git reset --hard` 或 `git checkout -- .` 覆盖已有工作。

## 4. 日常 AI 开发流程

### 4.1 描述问题

至少提供：微信版本和 versionCode、Android/ABI、模块版本、R8 或无 R8、主体或分身、复现步骤、实际结果、期望结果和完整日志。

```text
仓库：ljh520134/Hchat-alt-entry
分支：fix/功能名称
环境：微信 <版本/versionCode>，Android <版本/SDK>，<ABI>，主体/分身，R8/无 R8
复现：1. ... 2. ... 3. ...
实际：...
期望：...
日志：...
```

### 4.2 先调查，再修改

先确认问题属于设置 UI、功能注册、DexKit、Hook、微信回调、解析、数据库、线程、脚本运行时还是 R8。调查阶段发送：

```text
先不要修改文件、构建、提交或推送。读取 AGENTS.md、相关 docs 和 git status。
根据我的复现步骤和日志定位真实调用链；涉及微信内部类、方法、字段、数据库或 Intent 时，先使用 DexClub MCP 逆向确认，不要猜混淆名称。
输出根因候选、逆向证据、拟修改文件、兼容风险和测试方案。
```

确认方案后发送：

```text
按已确认的调用链动手。只修改直接相关文件，保留已有改动，不做无关重构。
考虑旧版、现代列表、分组页、子进程和 R8 差异；不要阻塞主线程，不要新增全局 Hook、Thread.sleep 或无限重试。
完成后执行 git diff --check 和适合的测试，不要自动提交或推送。
```

### 4.3 审查改动

```sh
git status --short
git diff --check
git diff --stat
git diff -- app/src/main/java app/src/main/assets docs
```

重点看：是否误改构建/签名、重复安装 Hook、把状态更新当成新消息、主线程查询、吞掉原始异常、RecyclerView 复用问题、ComposeView 宿主问题和双 ABI 是否完整。

## 5. DexClub MCP 和微信 APK

### 5.1 启动 DexClub

DexClub MCP 不属于 Hchat 源码，需要使用可信渠道提供的 Android ARM64 版本。以下路径只是变量示例，由用户自己填写：

```sh
export DEXCLUB_HOME="$HOME/你自己的/dexclub-mcp-android-arm64"
cd "$DEXCLUB_HOME/bin"
chmod +x ./mcp
DEXCLUB_MCP_PORT=8787 ./mcp
```

另开一个 Termux 窗口配置 Codex：

```sh
codex mcp add dexclub --url http://127.0.0.1:8787/mcp
codex mcp list
```

端口可自行调整，但 Codex 和服务端必须一致。不要同时运行多个旧服务，也不要把 DexClub 二进制或缓存提交到 Git。

### 5.2 APK 路径由用户自定义

不要照抄项目文档中的固定微信 APK 路径。用户应自己填写：

```sh
export WECHAT_APK="/storage/emulated/0/你的目录/你的微信APK.apk"
test -f "$WECHAT_APK" && ls -lh "$WECHAT_APK"
```

比较多个版本时同样由用户填写：

```sh
export WECHAT_APK_8049="/你的路径/微信8049.apk"
export WECHAT_APK_8058="/你的路径/微信8058.apk"
export WECHAT_APK_8077="/你的路径/微信8077.apk"
```

变量名和文件名都可以改。不要把个人目录、账号数据或 APK 复制进 Hchat 仓库。

### 5.3 打开目标

```text
使用 DexClub MCP 打开我提供的绝对路径 APK：
<把 WECHAT_APK 的实际值粘贴到这里>
先调用 open_target_session 并确认 session_id，不要修改源码。
```

然后要求：

```text
使用字符串、资源或 Manifest 锚点缩小候选，先 brief 输出，再 inspect_method。
只有确认候选后才导出少量 Java 或 Smali。输出目标 descriptor、调用者/被调用者、版本信息和证据。
```

目标会话列表为空只表示还没有打开 APK，不代表 MCP 不可用。

### 5.4 多版本横向比较

当前项目建议尽量覆盖：

```text
8.0.49
8.0.58
8.0.66
8.0.68
8.0.72
8.0.74
8.0.76
8.0.77
```

各版本的 versionCode、APK 文件名和路径以用户手里的实际 APK 为准，项目不预设任何个人目录。没有某个 APK 时写“未验证”，不能写“兼容所有版本”。同一功能要使用相同锚点比较类、方法、字段、View 层级和回调结构。

### 5.5 DexKit 缓存

定位缓存至少区分微信版本、versionCode、clientVersion、热更新标识、APK 时间戳和 ClassLoader。缓存命中时不要每次启动重跑 DexKit；微信升级、降级、热更新或 ClassLoader 变化时清理旧 descriptor 并重新定位。

共享 `DexKitBridge` 必须串行使用。定位失败不能标记为 installed；普通功能使用统一 `DexInstallScheduler`，不要在功能内新增裸线程、`Thread.sleep` 或多套重试框架。

## 6. Hchat 开发注意事项

### 6.1 Hook 和消息

- 只 Hook 逆向确认的具体可执行方法，不要全局 Hook 抽象入口。
- 一个业务事件选择一个权威来源，不要同时消费多个回调再靠时间窗口猜测去重。
- 数据库兜底只消费新增消息，不能把已读、媒体下载或状态更新当成新消息。
- 自动化功能必须区分入站/出站、当前账号、原始消息和模块生成消息。
- 反射统一走 `h.Hchat.utils.KavaReflector`，错误日志统一走 `HLog.e(...)`。

### 6.2 UI 和性能

- RecyclerView 每次绑定都清理旧状态。
- ComposeView 只能设置 Compose 内容，不能调用传统 `addView`。
- 数据库查询、DexKit 定位和网络请求不要阻塞主线程。
- 关闭功能或销毁 Activity 时释放监听器、Handler、Executor、Window 和缓存。
- 全屏遮罩和弹窗切换时先移除旧层，再在下一帧创建新层，避免透明层吞掉触摸。
- 悬浮层要处理真实测量高度、触摸穿透和 Activity 生命周期。

### 6.3 脚本插件

开发前阅读 `docs/SCRIPT_PLUGIN_API.md`、`docs/SCRIPT_PLUGIN_API_QUICK.md` 和 `app/src/main/assets/script_plugin_agent_guide.md`。保持 WA 风格的方法名、参数顺序、类型和返回值。未在文档、运行时或逆向中确认的能力只能标为未知，不能猜接口。

插件要处理加载、卸载、启用、异常、网络超时、文件写入、取消和消息去重。高权限、高耗电、批量发送功能默认关闭，新插件更新后不要自动启用。

## 7. 构建和 R8

### 7.1 编译检查

只检查源码：

```sh
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

默认优先使用 GitHub Actions，低内存设备不要频繁本地运行 Gradle。

### 7.2 正式 R8 构建

正式 release 默认开启：

```text
isMinifyEnabled = true
isShrinkResources = true
```

签名通过环境变量提供：

```text
HCAT_STORE_PASSWORD
HCAT_KEY_ALIAS
HCAT_KEY_PASSWORD
```

keystore 不得提交。没有维护者证书时使用自己的测试签名，不能保证覆盖安装维护者发布的 APK。

如果无 R8 正常、R8 失效，先固定同一提交、同一微信版本和同一测试步骤，再对比最终 APK 的 DEX、反射名称、方法描述符、脚本 API、JNI、序列化类和 keep 规则。只增加有证据支持的最小 keep 规则，不要直接全包 keep 或改业务逻辑。

### 7.3 无 R8 测试

无 R8 是诊断包，不是永久修改。测试 workflow 会临时执行：

```sh
sed -i 's/isMinifyEnabled = true/isMinifyEnabled = false/' app/build.gradle.kts
sed -i 's/isShrinkResources = true/isShrinkResources = false/' app/build.gradle.kts
```

本地建议使用独立 worktree，避免临时配置进入正常分支：

```sh
git worktree add ../Hchat-alt-entry-no-r8 alt-entry
cd ../Hchat-alt-entry-no-r8
sed -i 's/isMinifyEnabled = true/isMinifyEnabled = false/' app/build.gradle.kts
sed -i 's/isShrinkResources = true/isShrinkResources = false/' app/build.gradle.kts
sh ./gradlew :app:assembleRelease --no-daemon --no-watch-fs -x lintVitalRelease
```

测试完检查状态，再移除临时 worktree：

```sh
cd ../Hchat-alt-entry
git worktree remove ../Hchat-alt-entry-no-r8
```

### 7.4 2 GB 服务器

2 GB 内存运行 R8 可能长时间交换或被系统杀掉。当前项目 `gradle.properties` 的 JVM 上限为 2048 MB，会和系统、JDK、Gradle、Android 工具竞争内存。

优先使用 GitHub Actions。必须本地构建时增加交换空间或内存，关闭并行和 daemon：

```sh
sh ./gradlew :app:assembleRelease --no-daemon --no-parallel --no-watch-fs \
  -x lintVitalAnalyzeRelease
```

## 8. GitHub Actions 构建

### 8.1 登录 GitHub CLI

```sh
gh auth login
gh auth status
```

### 8.2 无 R8 测试包

```sh
gh workflow run android-test.yml \
  --repo ljh520134/Hchat-alt-entry \
  --ref alt-entry
gh run list --repo ljh520134/Hchat-alt-entry --workflow android-test.yml --limit 5
gh run watch <运行编号> --repo ljh520134/Hchat-alt-entry
gh run download <运行编号> \
  --repo ljh520134/Hchat-alt-entry \
  --name Hchat-alt-entry-test \
  --dir ./dist
```

无 R8 workflow 只上传 Artifact，不创建 Release。下载的 APK 不要提交。

### 8.3 R8 单频道包

确认代码、测试和 Secrets 后再运行：

```sh
gh workflow run android-single.yml \
  --repo ljh520134/Hchat-alt-entry \
  --ref alt-entry
```

`alt-entry` 正式资产名是 `Hchat-alt-entry-release-signed.apk`。正式 workflow 会创建 Release，不要用它反复测试每一个小改动。

需要的 Secrets 名称：

```text
HCAT_KEYSTORE_BASE64
HCAT_STORE_PASSWORD
HCAT_KEY_ALIAS
HCAT_KEY_PASSWORD
```

可选 Telegram Secrets：`TG_BOT_TOKEN`、`TG_CHAT_ID`。真实值只配置在 GitHub Secrets，不写入代码、日志或提示词。

## 9. 提交和 PR

提交前：

```sh
git status --short
git diff --check
git diff --stat
git diff --cached --check
```

只添加本次文件：

```sh
git add <相关文件>
git commit -m "修复某某功能"
git push -u origin fix/功能名称
```

创建 PR，目标分支是 `alt-entry`：

```sh
gh pr create \
  --repo ljh520134/Hchat-alt-entry \
  --base alt-entry \
  --head 你的用户名:fix/功能名称 \
  --title "修复某某功能"
```

PR 说明写清问题、根因、改动文件、DexClub 依据、微信版本、Android/ABI、主体/分身、R8/无 R8、测试结果、未验证版本和已知风险。提交信息、PR 标题和说明使用中文。

提交前不要把 keystore、APK、日志、聊天数据库、个人媒体、密码、Token 或服务器信息加入 Git。发现凭据泄露时先撤销或轮换，再通知维护者；只删除当前文件不能清理 Git 历史。

## 10. 测试清单

源码和服务端：

```sh
git diff --check
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
python3 -m unittest discover -s server/plugin-market/tests -v
```

没有相应环境时写“未验证”，不要伪造通过结果。

微信运行时至少记录：微信版本/versionCode、Android/ABI、模块版本、R8/无 R8、主体/分身、是否冷启动、复现次数和结果。

涉及微信内部结构时尽量横向测试：

```text
8.0.49、8.0.58、8.0.66、8.0.68、8.0.72、8.0.74、8.0.76、8.0.77
```

缺少某个版本的 APK 或设备时明确写未验证。UI 功能要测试冷启动、热切换、页面进退、触摸、复用和 Activity 销毁；消息功能要测试私聊、群聊、公众号、自己/别人发送、引用、重复回调、免打扰和媒体；R8 功能要用同一提交和同一微信版本对比两种包。

## 11. 常见问题

### Codex 找不到

检查 `node --version`、`npm prefix -g`、`which codex` 和 `PATH`，重新打开 Termux 后再运行 `codex --help`。

### DexClub 没有工具或目标

确认 `./mcp` 正在运行、端口一致、`codex mcp list` 能看到 `dexclub`，并且已经使用自己填写的绝对 APK 路径调用 `open_target_session`。目标列表为空只表示还没有打开 APK。

### 无 R8 正常而 R8 没反应

固定同一提交、同一微信版本、同一设置和同一操作。对比最终 APK 的 DEX、反射名称、方法描述符、脚本 API、JNI、序列化类和 keep 规则。只增加有证据支持的最小 keep 规则，不要直接全包 keep 或改业务逻辑。

### `ViewTreeLifecycleOwner not found` 或 `Cannot add views to ComposeView`

复用已有 Compose 宿主；传统 View 挂到确认过的普通 `ViewGroup`，不能把 ComposeView 当普通 ViewGroup 使用 `addView`，并处理 Activity 生命周期。

### 页面卡顿、一直加载、消息重复

优先检查主线程查询、重复 Hook、无限重试、旧记录更新被误判为新消息、透明层挡触摸和未释放监听器。先用限频日志确认调用频率和线程，再做缓存、批处理和生命周期释放。

## 12. 四个常用提示词

### 调查

```text
先不要修改和构建。读取 AGENTS.md、相关 docs 和 git status。
根据日志定位 Hchat 的第一处调用，使用 DexClub 调查我提供的 APK 绝对路径。
先输出候选、调用链、证据、版本差异和测试方案，不要猜混淆名称。
```

### 修复

```text
按已确认的根因修复 <功能>。只改直接相关文件，保留已有修改。
考虑微信版本差异、主体/分身、冷启动、R8/无 R8、线程和资源释放。
完成后运行 git diff --check 和相关测试，不要自动提交或推送。
```

### 新功能

```text
为 Hchat 增加 <功能>。先调查现有设置分组、公共 API、事件通道、生命周期和相近功能。
说明入口、默认值、关闭时释放、DexKit 依据、版本兼容、R8 风险、失败回退和测试矩阵。
确认后再实现，复用已有调度器、配置迁移、日志和 UI。
```

### R8

```text
同一提交和同一微信版本下，无 R8 正常、R8 失效。
先比较两个最终 APK 的 DEX、反射类名/方法名/字段名、脚本 API、JNI、序列化类和 keep 规则。
定位失败阶段后只添加最小必要 keep 规则，说明每条规则的证据，并同时复测无 R8 和 R8。
```
