# Hchat AI Coding 完整教程

这是一份给新贡献者的实践教程，目标是让没有 Android、Xposed、DexKit 或 GitHub 经验的人，也能用手机 Termux、Codex 和 DexClub MCP 参与 Hchat 的功能修复与开发。

本教程对应的公开仓库是：

```text
https://github.com/ljh520134/Hchat-alt-entry
```

公开仓库的开发基准分支是 `alt-entry`。开始之前先记住三条原则：

1. 不确定微信内部类、方法、字段或数据库结构时，先逆向确认，不要凭记忆猜混淆名。
2. “无 R8 正常、R8 失效”首先按最终 APK 的 DEX、反射、脚本接口、JNI 和 keep 规则排查，不要连续修改业务逻辑碰运气。
3. 每次改动都要能说明复现步骤、实际结果、期望结果、验证版本和未验证范围。

## 目录

- [一、准备工作](#一准备工作)
- [二、Termux 安装环境](#二termux-安装环境)
- [三、安装手机 Codex](#三安装手机-codex)
- [四、获取 Hchat 源码](#四获取-hchat-源码)
- [五、第一次使用 Codex](#五第一次使用-codex)
- [六、配置 DexClub MCP](#六配置-dexclub-mcp)
- [七、用 DexClub 逆向微信](#七用-dexclub-逆向微信)
- [八、修复 Bug 和新增功能](#八修复-bug-和新增功能)
- [九、开发脚本插件](#九开发脚本插件)
- [十、编译与 R8](#十编译与-r8)
- [十一、GitHub Actions 构建](#十一github-actions-构建)
- [十二、Git 分支、提交和 PR](#十二git-分支提交和-pr)
- [十三、测试清单](#十三测试清单)
- [十四、常见问题](#十四常见问题)
- [十五、可直接复制的提示词](#十五可直接复制的提示词)
- [十六、最常用命令速查](#十六最常用命令速查)

## 一、准备工作

### 1. 设备要求

手机 Termux 方案至少需要：

- Android 10 或更高版本。
- ARM64 设备。`DioNanos/codex-termux` 的 Termux 构建面向 Android ARM64。
- 足够的存储空间。源码、Gradle 缓存、Android SDK 和 APK 可能占用数 GB。
- 稳定网络。首次下载 npm 包、Gradle 依赖和 Android SDK 时尤其重要。
- 一个 GitHub 账号，以及公开仓库的 Fork 权限。

Hchat 本身是微信的 LSPosed/Xposed 功能扩展模块。开发和测试应只在自己拥有或明确获准测试的设备、账号和数据上进行，不要把他人的聊天记录、数据库、账号凭据或私有媒体交给 AI。

### 2. 先了解仓库结构

进入仓库后，优先认识这些目录：

```text
app/src/main/java/h/Hchat/hooks/api/       微信公共 API
app/src/main/java/h/Hchat/hooks/items/     独立功能实现
app/src/main/java/h/Hchat/hooks/core/      Hook 注册、调度和生命周期
app/src/main/java/h/Hchat/dexkit/          DexKit 定位和缓存
app/src/main/java/h/Hchat/event/           模块内部事件
app/src/main/java/h/Hchat/ui/              设置页和 UI
app/src/main/java/h/Hchat/utils/           反射、日志和通用工具
app/src/main/java/bsh/                     BeanShell 脚本引擎
app/src/main/jniLibs/                      ARM native 库
app/src/main/assets/                       脚本和内置说明
docs/                                      项目、API 和开发文档
.github/workflows/                         GitHub Actions 工作流
server/plugin-market/                      在线插件市场后端
```

每次开始任务前都要阅读：

```text
AGENTS.md
CONTRIBUTING.md
docs/FEATURE_FRAMEWORK.md
docs/WECHAT_APIS.md
docs/SCRIPT_PLUGIN_API.md
```

### 3. 公开仓库和内部信息的边界

公开仓库中可以保留构建工作流的变量名和 GitHub Secrets 名称，但不能提交以下内容：

- keystore、`.jks`、APK 和 Gradle 构建缓存；
- 服务器密码、SSH 私钥、API key、GitHub Token、Telegram Bot Token；
- 微信聊天数据库、联系人导出、账号 Cookie 和个人媒体；
- 设备上的完整日志，尤其是包含微信号、手机号、路径或服务器信息的日志；
- 只适用于维护者本机的 `local.properties` 或脚本。

密码不能因为仓库是私有或 Fork 就当成安全。Fork、Issue、PR、Actions 日志和 AI 对话都可能暴露信息。

## 二、Termux 安装环境

### 1. 安装 Termux

请使用可信来源安装 Termux，并保持 Termux、Android 系统和基础包更新。首次启动后执行：

```sh
pkg update && pkg upgrade -y
```

给 Termux 授予访问共享存储的权限：

```sh
termux-setup-storage
```

弹出系统权限请求时选择允许。成功后，Termux 中的：

```text
$HOME/storage/shared
```

通常对应 Android 的：

```text
/storage/emulated/0
```

例如微信 APK 在共享存储的路径，传给 DexClub 时要写绝对路径：

```text
/storage/emulated/0/Download/微信归档/weixin8049android2600_0x2800313d_arm64.apk
```

不要写成 `./weixin.apk`、`~/storage/shared/...` 或只写文件名，除非你已经确认工具可以解析该路径。教程中的逆向命令统一使用 `/storage/emulated/0/...` 这种绝对路径。

### 2. 安装基础工具

```sh
pkg install -y \
  git nodejs-lts openssh curl wget jq unzip zip ripgrep tmux python openjdk-21 gh
```

检查版本：

```sh
git --version
node --version
npm --version
java -version
gh --version
rg --version
```

Hchat 的源码 Java/Kotlin 目标版本是 Java 17，GitHub Actions 使用 JDK 21。Termux 中安装 JDK 21 通常同时满足本地工具和 Gradle 的需要；如果某个本地构建明确要求 JDK 17，应以 Gradle 报错和项目配置为准，不要在不同 JDK 之间反复切换而不记录版本。

### 3. 用 tmux 保持长任务

手机锁屏、切换应用或网络波动可能让普通终端会话中断。建议使用 `tmux`：

```sh
tmux new -s hchat
```

暂时离开但不结束任务：先按 `Ctrl+b`，再按 `d`。

重新进入：

```sh
tmux attach -t hchat
```

查看已有会话：

```sh
tmux ls
```

## 三、安装手机 Codex

### 1. 使用指定的 Termux 发行版

本教程按用户指定的 `DioNanos/codex-termux` 发行版编写。它提供 Termux/Android ARM64 的 npm 包。该仓库可能处于归档或停止维护状态，安装前应先查看它的 README、Release 和 npm 包实际版本；不要把某个旧版本号永久写死在自己的脚本里。

安装命令：

```sh
npm install -g @mmmbuto/codex-cli-termux@latest
```

检查安装：

```sh
which codex
codex --version
codex --help
```

如果 `which codex` 没有输出，先检查 npm 全局 bin 目录：

```sh
npm prefix -g
npm bin -g 2>/dev/null || true
echo "$PATH"
```

重新打开 Termux，或把 npm 全局 bin 目录加入 `PATH`。不要从陌生网站下载来路不明的二进制替换 npm 包。

### 2. 登录和检查

优先按当前安装版本的帮助信息执行。常见命令是：

```sh
codex login
codex login status
codex doctor
```

如果该版本使用带双横线的写法：

```sh
codex --login
```

登录时不要把 API key 直接写进 Git、脚本、截图或提示词。浏览器回调无法自动返回 Termux 时，按照命令行提示复制回调地址或在同一设备打开登录页面，不要把包含授权码的 URL 发到群里。

检查 Codex 是否能正常工作：

```text
请只读取当前目录，说明你看到的项目类型、入口文件和可能的构建命令。不要修改文件，不要执行 Gradle，不要联网下载依赖。
```

首次使用必须明确“只读调查”，不要一开始就授予全自动修改权限。

### 3. 启动参数和权限模式

进入项目根目录后，推荐使用工作区写入、命令执行需要确认的模式：

```sh
codex -C "$PWD" --sandbox workspace-write --ask-for-approval on-request
```

不同 Codex 版本的参数名称可能变化，先运行 `codex --help`。部分 Termux 版本仍支持等价的短参数：

```sh
codex -C "$PWD" -s workspace-write -a on-request
```

不要把 `--auto-edit` 或全自动执行当成默认方案。Xposed 模块会接触反射、native、数据库和构建签名，先审查 diff 和命令，再允许执行更稳妥。无论使用哪种模式，都要让 Codex 在任务中遵守：

```text
先读取 AGENTS.md 和 git status。保留我已有修改，不要 reset、checkout -- 或删除未提交文件。涉及微信内部结构先用 DexClub 逆向确认。完成后只做指定范围的修改，并报告验证结果。
```

## 四、获取 Hchat 源码

### 1. 直接克隆公开仓库

```sh
cd "$HOME"
git clone --branch alt-entry --single-branch \
  https://github.com/ljh520134/Hchat-alt-entry.git
cd Hchat-alt-entry
git status --short --branch
```

预期当前分支是 `alt-entry`。检查远端：

```sh
git remote -v
git branch -vv
```

### 2. 从自己的 Fork 开发

在 GitHub 页面 Fork `ljh520134/Hchat-alt-entry` 后，推荐这样设置远端：

```sh
git clone --branch alt-entry --single-branch \
  https://github.com/你的用户名/Hchat-alt-entry.git
cd Hchat-alt-entry
git remote rename origin upstream
git remote add origin \
  https://github.com/你的用户名/Hchat-alt-entry.git
git remote -v
```

其中：

- `upstream` 指公开仓库 `ljh520134/Hchat-alt-entry`；
- `origin` 指你自己的 Fork；
- 不要把私有维护仓库配置成教程里的上游。

如果仓库已经存在，只需要检查并修正远端，不要为了改 URL 删除本地工作目录：

```sh
git remote -v
git remote set-url upstream \
  https://github.com/ljh520134/Hchat-alt-entry.git
```

### 3. 开始任务前的固定检查

```sh
git status --short
git branch --show-current
git log -1 --oneline
```

如果 `git status` 有不是你本轮产生的修改，先阅读相关 diff，不能直接覆盖或清理。需要同步干净的上游分支时：

```sh
git fetch upstream alt-entry
git switch alt-entry
git pull --ff-only upstream alt-entry
```

有自己的未提交修改时，先保存到可恢复的 stash，并记录内容：

```sh
git stash push -u -m "开发前临时保存"
git pull --ff-only upstream alt-entry
git stash pop
```

出现冲突时逐个解决；不要用 `git reset --hard` 或 `git checkout -- .` 作为“快速修复”。

## 五、第一次使用 Codex

### 1. 先让 AI 只读调查

在仓库根目录启动 Codex：

```sh
cd "$HOME/Hchat-alt-entry"
codex -C "$PWD" -s workspace-write -a on-request
```

第一条消息不要直接要求“改好并构建”。推荐先发送：

```text
先不要修改文件，也不要构建。读取 AGENTS.md、CONTRIBUTING.md、相关功能文档、git status 和目标源码。
我的问题是：<具体描述>
环境是：微信 <版本和 versionCode>，Android <版本>，ABI <arm64-v8a/armeabi-v7a>，模块 <版本>，R8/无 R8，微信主体/分身。
请说明真实调用链、当前实现、需要用 DexClub 确认的类/方法/字段、拟修改文件、兼容风险和验证步骤。没有逆向证据时不要猜混淆名称。
```

这样做的目的是确认问题属于哪一层：设置 UI、模块 Hook、微信原生回调、数据库观察、DexKit 定位、R8 混淆，还是脚本插件运行时。层次判断错了，改得越多越容易引入新问题。

### 2. 再让 AI 修改

确认调查结果后，再发送：

```text
按刚才确认的范围动手。只修改与这个问题直接相关的文件，保留我已有修改。
先实现最小修复，不要顺手重构无关功能。涉及微信内部结构的入口必须来自 DexClub 或现有可靠缓存证据，并考虑支持的微信版本。
完成后执行 git diff --check、适合本改动的静态检查或单元测试，不要擅自构建 APK、提交或推送。
最后总结：改了什么、为什么、验证了什么、哪些版本没有验证、还剩什么风险。
```

### 3. 审查 AI 的改动

不要只看 AI 的口头总结，执行：

```sh
git status --short
git diff --check
git diff --stat
git diff -- app/src/main/java app/src/main/assets docs
```

重点检查：

- 是否误改了构建脚本、签名、依赖或其他功能；
- 是否把一个微信版本的混淆 descriptor 直接套给其他版本；
- 是否新增了全局 Hook、主线程数据库查询或无限重试；
- 是否吞掉了异常导致以后只能看到“没反应”；
- 是否把密码、Token、设备路径或个人数据写入源码；
- 是否忘记清理 RecyclerView 复用 View 的旧状态；
- 是否在 `ComposeView` 上调用传统 `addView`；
- 是否破坏 `arm64-v8a` 和 `armeabi-v7a` 的 native 双 ABI。

如果不理解某段改动，要求 Codex 按文件和行号解释，不要因为“能编译”就直接合并。

### 4. 让 AI 下载公开源码或构建产物

下载操作也要写清来源、分支、构建类型和保存目录。推荐这样要求：

```text
请从公开仓库 https://github.com/ljh520134/Hchat-alt-entry 获取 alt-entry 分支的无 R8 测试构建。
先确认 gh 登录状态和 workflow 运行编号，只下载名为 Hchat-alt-entry-test 的 Artifact 到仓库外的临时目录。
不要下载或读取任何私有仓库、签名密码、Token 或用户数据；下载后只报告文件名、大小和来源。
```

如果是下载正式包：

```text
请从公开仓库 ljh520134/Hchat-alt-entry 的 Release 下载 alt-entry 的正式 APK。
先确认 Release 标签和资产名称，保存到仓库外的 dist 临时目录，不要修改源码，不要提交 APK。
```

下载命令应由你确认后再执行。AI 不应擅自把 APK、日志、微信数据库或外部脚本写进源码仓库。

## 六、配置 DexClub MCP

DexClub 用于分析微信 APK 的 Manifest、资源、DEX、类、方法和调用关系。Hchat 的规则要求：不确定微信内部结构时先逆向；多版本任务要使用相同锚点横向比较。

### 1. 准备 DexClub

DexClub MCP 不是 Hchat 源码的一部分，需要使用可信渠道提供的 Android ARM64 版本。假设已解压到：

```text
$HOME/dexclub-mcp-android-arm64
```

如果拿到的是 zip 压缩包，可以这样解压到自己的 Home 目录：

```sh
mkdir -p "$HOME/dexclub-mcp-android-arm64"
unzip /path/to/dexclub-mcp-android-arm64.zip \
  -d "$HOME/dexclub-mcp-android-arm64"
```

实际压缩包可能自带一层目录。用 `find` 找到真正的 `bin/mcp` 后，再把后续命令中的路径改成对应目录：

```sh
find "$HOME/dexclub-mcp-android-arm64" -maxdepth 4 -type f -name mcp -print
```

确认可执行文件存在：

```sh
cd "$HOME/dexclub-mcp-android-arm64/bin"
ls -l ./mcp
chmod +x ./mcp
```

如果你拿到的是其他目录，替换后续命令中的路径即可。不要把 DexClub 二进制或其缓存文件提交到 Hchat 仓库。

### 2. 启动 MCP 服务

在单独的 Termux 会话中保持服务运行：

```sh
cd "$HOME/dexclub-mcp-android-arm64/bin"
DEXCLUB_MCP_PORT=8787 ./mcp
```

`8787` 是本教程的统一端口。项目目录中若存在旧的持久化脚本，先检查它实际使用的端口；不要同时启动两个服务，也不要把写死的 `8788` 当成默认端口。

如果需要让服务在手机锁屏后继续运行，可以放进独立的 `tmux`：

```sh
tmux new -s dexclub
cd "$HOME/dexclub-mcp-android-arm64/bin"
DEXCLUB_MCP_PORT=8787 ./mcp
```

另开一个 Termux 窗口配置 Codex：

```sh
codex mcp add dexclub --url http://127.0.0.1:8787/mcp
codex mcp list
```

如果某个 Codex 版本的 MCP 子命令参数不同，执行：

```sh
codex mcp --help
```

不要因为资源列表为空就认定服务不可用。DexClub 没有打开目标时，目标会话为空是正常的；应先确认 MCP 服务能连接，再打开 APK。

### 3. 让 Codex 使用 DexClub

把 APK 放到 Termux 可读取的位置，并确认路径：

```sh
ls -lh \
  /storage/emulated/0/Download/微信归档/weixin8049android2600_0x2800313d_arm64.apk
```

然后告诉 Codex：

```text
使用 DexClub MCP 打开这个绝对路径的 APK：
/storage/emulated/0/Download/微信归档/weixin8049android2600_0x2800313d_arm64.apk
先调用 open_target_session，再确认 session_id。不要猜类名，不要修改源码。
```

打开目标后，继续要求：

```text
先列出当前 DexClub target session，确认工作区和 ClassLoader。
围绕 <功能/日志字符串/资源名> 使用字符串、资源或 Manifest 锚点查找候选。
先 brief 输出候选，再 inspect_method；只有确认候选后才导出少量 Java 或 Smali。
```

## 七、用 DexClub 逆向微信

### 1. 正确的调查顺序

一个可靠的逆向任务通常按下面顺序进行：

1. 确认 APK、微信版本、versionCode、架构和是否为热更新包。
2. `open_target_session` 打开目标，记录 `session_id`。
3. 从用户日志、界面文字、资源名、异常类或协议字段中提取字符串锚点。
4. 使用 `find_classes_using_strings`、`find_methods_using_strings` 或资源搜索缩小候选。
5. 使用 `inspect_method` 查看字段、调用者、被调用者、字符串和注解。
6. 确认候选后再使用 `export_method_java` 或 `export_method_smali` 读取最小必要片段。
7. 将逆向证据和版本写入调查记录或 PR 描述，然后才修改 Hook。

不要一开始导出整包或整类。输出太大不但浪费上下文，也更容易把无关的同名方法误认为目标。

### 2. 多微信版本横向验证

当前项目规则建议尽量覆盖：

```text
8.0.49  versionCode 2600
8.0.58  versionCode 2841
8.0.66  versionCode 2980
8.0.68  versionCode 3020
8.0.72  versionCode 3100
8.0.74  versionCode 3120
8.0.76  versionCode 3140
```

本地归档路径示例：

```text
/storage/emulated/0/Download/微信归档/weixin8049android2600_0x2800313d_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8058android2841_0x28003a3f_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8066android2980_0x28004234_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8068android3020_0x28004434_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8072android3100_0x28004835_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8074android3120_0x28004a36_arm64.apk
/storage/emulated/0/Download/微信归档/weixin8076android3140_0x28004c30_arm64.apk
```

没有某个 APK 时必须在代码或 PR 中写明“未验证”，不能写“兼容所有版本”。不同版本的混淆类名、方法描述符、View 层级和网络回调都可能变化。

### 3. DexKit 缓存要求

如果改动涉及 DexKit 定位，必须检查缓存是否按运行时区分。缓存 key 至少要考虑：

- 微信版本和 `versionCode`；
- `clientVersion`；
- 热更新标识；
- APK 时间戳；
- 当前 ClassLoader 指纹。

缓存有效时不要每次启动重新跑 DexKit。微信升级、降级、热更新或 ClassLoader 变化时应清理旧 descriptor 并重新定位。定位失败不能标记为已安装；要保留有限重试和补装机会。

共享 `DexKitBridge` 必须通过统一串行调度器使用。不要在功能内部新增 `Thread.sleep`、裸线程抢跑或多套 `installWithRetry`。

## 八、修复 Bug 和新增功能

### 1. 把问题写成可复现案例

面对“没有效果”“偶尔闪退”“高版本不行”这类描述，先整理成下面的格式：

```text
仓库：ljh520134/Hchat-alt-entry
分支：fix/功能名称
模块版本：<例如 test.96>
微信版本：<例如 8.0.49 (2600)>，是否微信分身：<是/否>
Android：<版本和 SDK>，设备：<型号>，ABI：<arm64-v8a/armeabi-v7a>
构建类型：R8/无 R8
前置设置：<开关、名单、权限、登录状态>
复现步骤：1. ... 2. ... 3. ...
实际结果：...
期望结果：...
日志：完整异常堆栈或关键时间段日志
已尝试：<提交号、构建包、是否强停微信>
```

同一个功能要区分以下场景：

- 微信主体和微信分身；
- 冷启动、强停后启动和热切换设置；
- 主页、二级页、分组页、搜索结果和弹窗；
- 私聊、群聊、公众号、自己发送和别人发送；
- R8 与无 R8；
- 有缓存、无缓存、微信升级或降级后的首次启动。

### 2. 先定位归属层

可以用下面的顺序缩小范围：

1. 设置页开关是否真的写入配置，重启后值是否保留。
2. 功能是否注册、是否命中当前进程和当前 Activity。
3. DexKit 定位是否成功，缓存是否属于当前微信版本和 ClassLoader。
4. Hook 是否安装在真实可执行方法，而不是只命中了抽象父类或未执行分支。
5. 回调参数是否在该版本发生变化，消息方向、类型、ID 和账号是否有效。
6. 后台线程是否完成工作，结果是否回到主线程更新 UI。
7. UI 是否被复用、覆盖、遮罩或透明 View 截走触摸。
8. R8 是否改写了反射、脚本、JNI 或公共接口所依赖的名称和签名。

日志里第一处 `h.Hchat` 调用通常比堆栈最后的 Android 系统行更有价值。异常不能只记录“失败”，应同时保留功能名、阶段、微信版本、目标 descriptor 和原始 Throwable，并限制重复日志频率。

### 3. UI 与 Hook 的通用注意事项

Hchat 使用现有的设置 UI 和 Hook 生命周期。新增 UI 时：

- 设置入口统一走现有的 `SettingsUI.show(context)` 和 Miuix 设置体系；
- 功能选项放在对应分组的二级或三级页面，不要随意新建 Activity；
- 关闭功能时释放监听器、回调、Window、线程和缓存；
- RecyclerView 每次绑定都清除旧的注入状态，避免复用行显示上一次内容；
- ComposeView 只能设置 Compose 内容，不能对它调用传统的 `addView`；
- 覆盖内容的悬浮层必须处理测量高度、触摸穿透和 Activity 销毁；
- 弹窗或全屏遮罩切换时先移除旧层，再在下一帧创建新层，避免透明层吞掉点击；
- 需要早于 UI 构建的 Hook 才能使用早期入口，不能为了“更早”全局 Hook 共用抽象方法。

### 4. 数据库和消息观察

数据库监听只能把真正新增的记录当成新消息。微信可能用 `replace` 或 `upsert` 更新旧记录，媒体下载、已读状态和状态变化也可能触发表更新。消费消息前至少检查：

- 稳定的消息 ID 或服务器 ID；
- 有效创建时间；
- 发送方向和当前账号；
- 会话 ID、消息类型和消息内容是否与功能范围匹配；
- 是否已经被其他权威回调消费。

不要同时消费多个来源，再靠一个很短的时间窗口猜测去重。一个业务事件应选择一个权威来源，数据库监听只作为明确设计的兜底。SQL 查询必须使用目标版本确认存在的字段；SQL 失败不能悄悄变成空列表。

### 5. R8 失效时的正确排查

如果无 R8 包正常，而 R8 包没有回调或功能失效，按这个顺序：

1. 固定同一提交、同一微信版本、同一设备和同一测试步骤。
2. 保存无 R8 和 R8 两个最终 APK，不要只比较源码。
3. 对比 DEX 中的入口类、反射目标、方法描述符、脚本 API、JNI 导出符号和资源名。
4. 检查 `app/proguard-rules.pro` 与 `@Keep`、反射字符串、Xposed 入口、BeanShell 方法、序列化类和 native 方法的 keep 规则。
5. 对比运行时日志，确认失败发生在“定位、安装 Hook、回调、解析、分发”哪一阶段。
6. 只为证据明确的类、方法、字段或接口增加最小 keep 规则。
7. 重新构建并确认无 R8 仍然没有被意外改坏。

不要因为 R8 失败就给整个包加 `-keep class ** { *; }`，也不要先改数据库监听、线程或业务判断。宽泛异常捕获必须限频记录原始 Throwable，否则 R8 问题会被伪装成“没有消息”。

### 6. 新功能的最小实现边界

新增功能时先回答：

- 用户从哪个已有分组或页面进入；
- 配置默认值是什么，关闭时是否完全不运行；
- 运行在哪个进程、线程和生命周期；
- 依赖哪个公共 API、事件或微信原生对象；
- 版本差异如何定位和缓存；
- 失败时如何回退，是否会阻塞主线程或吞掉触摸；
- 是否需要同步更新脚本 API、设置文档和构建说明；
- 如何测试主体、分身、群聊、私聊、冷启动和 R8/无 R8。

先做一条最小可用路径，确认日志和测试通过，再增加批量操作、动画、兼容兜底等扩展。批量发送、转发、删除、数据库更新不能在主线程同步遍历大量目标，也不能把数百个目标一次交给微信 Activity。

## 九、开发脚本插件

### 1. 先读公开接口

脚本插件运行在微信的 `Hchat/脚本插件/` 目录。开发前阅读：

```text
docs/SCRIPT_PLUGIN_API.md
docs/SCRIPT_PLUGIN_API_QUICK.md
app/src/main/assets/script_plugin_agent_guide.md
```

如果接口文档没有列出某个能力，运行时和逆向工具也没有确认，就只能标为“未知，需实际验证”，不能凭其他模块的名字猜 API。

### 2. 插件目录和生命周期

插件要遵守现有 WA 风格的接口、参数顺序、类型和返回值。一个新插件至少要明确：

- `info.prop` 中的名称、版本、入口和权限说明；
- `main.java` 或项目约定的入口文件；
- 加载、卸载、启用和异常路径；
- 消息回调的来源、方向、类型、ID 和去重方式；
- 网络请求、文件写入和后台线程的取消逻辑；
- 需要的设置和默认关闭状态。

不要让插件默认自动启用高权限、高耗电或会批量发送消息的功能。网络和文件操作要处理超时、失败、取消和敏感数据脱敏。

### 3. 插件问题的调查提示词

```text
先只读检查脚本插件运行时和 docs/SCRIPT_PLUGIN_API.md。
我要修复插件“<名称>”的 <回调/发送/下载> 问题。
请确认回调的权威来源、消息类型、发送方向、消息 ID、去重位置和 R8 影响。
先不要修改；如果需要微信内部类或方法，使用 DexClub 给出证据。
确认后只改插件运行时和该插件需要的文档，不要改无关功能。
```

### 4. 插件发送消息的校验

脚本或模块主动发送的消息也可能触发消息观察回调。自动转发、自动回复、通知和计数功能都要区分：

- 入站消息和出站消息；
- 当前账号发送和其他账号发送；
- 原始消息和模块自己生成的消息；
- 同一个服务器 ID 的补偿回调和真正的新消息。

账号身份未就绪、目标身份不明确或消息已消费时，应停止自动化动作，而不是继续发送。每个自动化功能都应有明确的去重键和限频策略。

## 十、编译与 R8

### 1. 只做编译检查

如果只是检查 Kotlin/Java 是否能编译，不需要生成 APK：

```sh
cd "$HOME/Hchat-alt-entry"
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

本地默认不要执行 Gradle。只有明确需要验证源码或用户明确要求构建时才执行；低内存设备优先用 GitHub Actions。

### 2. 正式 R8 构建需要什么

`app/build.gradle.kts` 的 `release` 默认配置是：

```text
isMinifyEnabled = true
isShrinkResources = true
```

签名配置通过环境变量读取：

```text
HCAT_STORE_PASSWORD
HCAT_KEY_ALIAS
HCAT_KEY_PASSWORD
```

密钥文件默认是：

```text
app/keystore/。。.jks
```

它不应该出现在 Git 中。没有维护者同一签名证书时，可以编译检查或使用自己的测试签名；换证书的 APK 不保证能覆盖安装正式版本。

### 3. 本地 R8 构建

只有在已经准备好签名环境、存储空间和足够内存时才在本地构建：

```sh
cd "$HOME/Hchat-alt-entry"
export HCAT_VERSION_NAME="local.test"
export HCAT_VERSION_CODE="1"
export HCAT_APK_NAME="Hchat-alt-entry-local.apk"
export HCAT_STORE_PASSWORD='<仅在当前终端临时设置>'
export HCAT_KEY_ALIAS='<仅在当前终端临时设置>'
export HCAT_KEY_PASSWORD='<仅在当前终端临时设置>'
sh ./gradlew :app:assembleRelease --no-daemon --no-watch-fs \
  -x lintVitalAnalyzeRelease
```

生成的 APK 会由 `copyToDist` 复制到：

```text
dist/Hchat-alt-entry-local.apk
```

不要把密码写进命令历史、脚本或终端截图。构建后清理当前 shell 中的变量：

```sh
unset HCAT_STORE_PASSWORD HCAT_KEY_ALIAS HCAT_KEY_PASSWORD
```

### 4. 无 R8 测试构建

无 R8 不是一个永久的 Gradle 参数。当前公开仓库的 `android-test.yml` 会临时执行：

```sh
sed -i 's/isMinifyEnabled = true/isMinifyEnabled = false/' app/build.gradle.kts
sed -i 's/isShrinkResources = true/isShrinkResources = false/' app/build.gradle.kts
```

本地临时测试时不要把这两行永久提交到分支。更安全的做法是使用独立 worktree：

```sh
cd "$HOME/Hchat-alt-entry"
git worktree add ../Hchat-alt-entry-no-r8 alt-entry
cd ../Hchat-alt-entry-no-r8
sed -i 's/isMinifyEnabled = true/isMinifyEnabled = false/' app/build.gradle.kts
sed -i 's/isShrinkResources = true/isShrinkResources = false/' app/build.gradle.kts
sh ./gradlew :app:assembleRelease --no-daemon --no-watch-fs -x lintVitalRelease
```

测试结束后移除临时 worktree：

```sh
cd "$HOME/Hchat-alt-entry"
git worktree remove ../Hchat-alt-entry-no-r8
```

如果 worktree 中有你自己的文件，先检查状态再处理，不要强制删除。无 R8 包用于定位功能问题，不代表可以省略正式 R8 测试。

### 5. 2 GB 服务器为什么会很慢

2 GB 内存编译 Android 项目可能长时间交换到磁盘，表现为 Gradle 几乎不动、Java 进程被系统杀掉或 R8 长时间占用 CPU。当前 `gradle.properties` 设置了 `-Xmx2048m`，在只有 2 GB 物理内存的服务器上会和系统、JDK、Gradle、Android 工具争抢内存。

优先顺序：

1. 用 GitHub Actions 构建，不在 2 GB 服务器上跑 R8。
2. 如果必须本地构建，增加交换分区或升级内存，并确保磁盘空间足够。
3. 关闭并行和 daemon，减少同时运行的进程：

```sh
sh ./gradlew :app:assembleRelease --no-daemon --no-parallel --no-watch-fs \
  -x lintVitalAnalyzeRelease
```

4. 只做单任务编译，避免同时启动 Android Studio、DexClub、多个 Gradle 和 Codex。
5. 不要因为“打开优化编译后更慢”就关闭 R8。R8 本身会增加处理时间，但它是正式包的压缩和兼容验证环节；无 R8 只能作为诊断包。

查看内存和磁盘：

```sh
free -h
df -h
ps -ef | grep -E 'gradle|java|kotlinc' | grep -v grep
```

## 十一、GitHub Actions 构建

### 1. 登录 GitHub CLI

如果要在 Termux 中查看或触发 Actions，先登录：

```sh
gh auth login
gh auth status
```

选择 GitHub.com、HTTPS 和浏览器登录；如果没有浏览器，按 GitHub CLI 的设备码流程操作。不要把访问 Token 写进 shell 脚本、Issue、日志或 Codex 提示词。

### 2. 无 R8 测试包

公开仓库的 `.github/workflows/android-test.yml` 是手动触发的无 R8 测试构建。它只上传 Actions Artifact，不创建 Release，也不推送 Telegram：

```sh
gh workflow run android-test.yml \
  --repo ljh520134/Hchat-alt-entry \
  --ref alt-entry
```

查看最近运行：

```sh
gh run list \
  --repo ljh520134/Hchat-alt-entry \
  --workflow android-test.yml \
  --limit 5
```

取得运行编号后查看日志：

```sh
gh run watch <运行编号> \
  --repo ljh520134/Hchat-alt-entry
```

下载无 R8 Artifact：

```sh
gh run download <运行编号> \
  --repo ljh520134/Hchat-alt-entry \
  --name Hchat-alt-entry-test \
  --dir ./dist
```

如果只想查看而不等待：

```sh
gh run view <运行编号> \
  --repo ljh520134/Hchat-alt-entry \
  --log-failed
```

### 3. R8 单频道正式包

`.github/workflows/android-single.yml` 会构建当前分支的正式 R8 包，并创建 Release。只在代码已经审查、测试完成且确实需要发布时触发：

```sh
gh workflow run android-single.yml \
  --repo ljh520134/Hchat-alt-entry \
  --ref alt-entry
```

这个 workflow 需要仓库 Secrets 中存在签名信息。它会按当前分支生成单频道产物；`alt-entry` 的正式 APK 文件名是：

```text
Hchat-alt-entry-release-signed.apk
```

下载最近的 Release 可以使用仓库脚本：

```sh
sh scripts/download_release_apk.sh alt-entry
```

或直接按标签下载：

```sh
sh scripts/download_release_apk.sh v<版本号> alt-entry
```

APK 会放入本地 `dist/`，该目录不应提交 Git。

### 4. GitHub Secrets

当前工作流使用这些 Secrets：

```text
HCAT_KEYSTORE_BASE64   签名证书的 base64 内容
HCAT_STORE_PASSWORD    keystore 密码
HCAT_KEY_ALIAS         key alias
HCAT_KEY_PASSWORD      key 密码
TG_BOT_TOKEN            可选，Telegram Bot Token
TG_CHAT_ID              可选，Telegram 私聊或频道 ID
```

配置方式：仓库页面进入 **Settings -> Secrets and variables -> Actions -> New repository secret**，逐项填写。签名文件先在可信机器上转换为单行 base64，再粘贴到 `HCAT_KEYSTORE_BASE64`；不要把原始 `.jks` 提交到仓库。

如果只需要无 R8测试包，也仍然需要签名相关 Secrets，因为公开 workflow 会生成签名 APK。没有维护者证书时，不要向陌生人索要或复制签名密码，应改用自己的测试仓库和测试证书。

### 5. Actions 构建失败怎么查

先看失败的具体 step，不要只看最后一句 `Process completed with exit code 1`：

```sh
gh run view <运行编号> \
  --repo ljh520134/Hchat-alt-entry \
  --log-failed
```

常见归类：

- `Missing secret`：仓库 Secrets 未配置或名称拼写不一致；
- `Keystore was tampered with`：证书 base64 损坏或密码不匹配；
- `SDK location not found`：检查 workflow 的 Android SDK 配置；
- `Kotlin`、`Java` 或依赖错误：先固定提交，单独做编译检查；
- R8 后功能没有回调：保存产物，按 [R8 失效排查](#5-r8-失效时的正确排查) 对比 DEX 和日志；
- 超时或被杀：检查 runner 日志，不要在源码中盲目增加重试。

## 十二、Git 分支、提交和 PR

### 1. 创建功能分支

不要直接在 `alt-entry` 上开发。先同步上游，再创建自己的分支：

```sh
cd "$HOME/Hchat-alt-entry"
git fetch upstream alt-entry
git switch alt-entry
git pull --ff-only upstream alt-entry
git switch -c fix/功能名称
```

分支名建议使用：

```text
fix/xxx       修复问题
feature/xxx   新增功能
refactor/xxx  结构整理
docs/xxx      文档修改
build/xxx     构建或 CI 修改
```

一个分支尽量只解决一个主题。不要把朋友圈过滤、悬浮底栏、视频号下载和服务器迁移放在同一个 PR 中。

### 2. 提交前检查

```sh
git status --short
git diff --check
git diff --stat
git diff --cached --check
```

只添加本次改动：

```sh
git add app/src/main/java/h/Hchat/hooks/items/目标功能 \
  docs/相关文档.md
git status --short
git diff --cached --stat
```

不要使用 `git add .` 把 APK、日志、密钥、缓存或其他人的修改一起加入。提交前可再次扫描敏感内容：

```sh
rg -n -i --hidden --glob '!/.git/**' \
  '(password|passwd|token|secret|api[_-]?key|private[_ -]?key|\.jks)' .
```

出现误报时人工确认；出现真实凭据时立即从工作区移除，并按凭据类型撤销或轮换，不能只删掉那一行再提交。

### 3. 中文提交

提交信息和 PR 标题、说明使用中文，写清行为变化：

```sh
git commit -m "修复视频号消息回调在R8构建下失效"
```

提交后检查：

```sh
git show --stat --oneline HEAD
git show --check HEAD
```

### 4. 推送到自己的 Fork

```sh
git push -u origin fix/功能名称
```

确认推送的是自己的 Fork：

```sh
git remote -v
git branch -vv
```

不要把未经审查的提交直接推到公开仓库的保护分支。如果只是给自己测试，可以推到自己的分支并触发无 R8 Actions。

### 5. 创建 Pull Request

可以在 GitHub 网页创建，也可以使用 `gh`：

```sh
gh pr create \
  --repo ljh520134/Hchat-alt-entry \
  --base alt-entry \
  --head 你的用户名:fix/功能名称 \
  --title "修复某某功能" \
  --body-file /path/to/pr.md
```

PR 正文至少包含：

```text
## 问题
说明用户如何遇到问题。

## 修改
列出实际改动的模块和原因。

## 逆向依据
涉及微信内部结构时，写明 APK 版本、锚点、候选和确认结果。

## 测试
微信版本、Android、ABI、主体/分身、R8/无 R8、复现次数和结果。

## 未验证
明确没有 APK、没有设备或没有测试的版本。

## 风险
说明兼容性、性能、权限、数据库和回退风险。
```

### 6. 让 Codex 做 Review

有未提交修改时：

```sh
codex review --uncommitted
```

比较当前分支和基准分支：

```sh
codex review --base alt-entry
```

让 Codex 重点找：崩溃、竞态、主线程阻塞、版本兼容、R8 反射失效、消息重复消费、资源泄漏、触摸被遮挡、配置迁移和测试缺口。Review 结果要人工确认，不能把 AI 的“没有问题”当成运行时验证。

### 7. 同步后续上游改动

PR 期间上游有新提交时：

```sh
git fetch upstream alt-entry
git rebase upstream/alt-entry
git push --force-with-lease
```

只对自己的功能分支使用 `--force-with-lease`，不要强推 `alt-entry`。发生冲突先保存补丁，逐个解决后运行 `git diff --check` 和相关检查。

## 十三、测试清单

### 1. 源码和文档检查

```sh
git diff --check
git status --short
git diff --stat
```

如果改了服务端：

```sh
python3 -m unittest discover -s server/plugin-market/tests -v
```

如果改了 Kotlin/Java 且环境允许：

```sh
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

没有环境或依赖下载失败就明确写“未验证”，不要伪造通过结果。

### 2. 微信运行时测试

至少记录：

- 微信版本、versionCode 和是否热更新；
- Android 版本、设备型号和 ABI；
- 模块版本；
- R8 或无 R8；
- 微信主体或分身；
- 是否强停微信后冷启动；
- 是否切换过开关、页面、搜索、分组和弹窗；
- 测试次数、成功次数和复现步骤。

涉及微信内部结构的改动，尽量横向测试 `8.0.49`、`8.0.58`、`8.0.66`、`8.0.68`、`8.0.72`、`8.0.74`、`8.0.76`。缺少对应 APK 或设备时写明未验证版本。

### 3. 按功能类型测试

UI 功能：冷启动、热切换、页面进退、尺寸变化、深色模式、触摸、RecyclerView 复用、返回栈和 Activity 销毁。

消息功能：私聊、群聊、公众号、自己发送、别人发送、引用、撤回、媒体下载、重复回调、免打扰和数据库更新。

自动化功能：入站/出站方向、当前账号、重复消息、批量目标、延迟、取消、异常、网络断开和权限拒绝。

R8 功能：同一提交的无 R8/R8 包、同一微信版本、同一设置、同一操作顺序和同一日志采集方式。

## 十四、常见问题

### 1. Codex 命令不存在

检查 Node、npm 和全局安装路径：`node --version`、`npm --version`、`npm prefix -g`、`which codex`。重新打开 Termux 后仍然找不到时，执行 `codex --help` 对照当前发行版 README，确认 npm 全局 bin 在 `PATH` 中。不要同时安装多个来源的 Codex，再凭命令名猜实际运行的是哪个版本。

### 2. DexClub MCP 没有工具或目标

依次检查：

1. `./mcp` 是否可执行；
2. 服务是否仍在运行；
3. Codex 配置的地址是否是 `http://127.0.0.1:8787/mcp`；
4. `codex mcp list` 是否能看到 `dexclub`；
5. 是否已经调用 `open_target_session` 打开绝对路径 APK。

目标会话列表为空，只说明当前没有打开 APK，不代表 MCP 不可用。服务启动在一个 Termux 窗口，Codex 在另一个窗口时，两个窗口都必须保持在同一台设备上。

### 3. DexKit 命中但功能仍然无效

检查是否使用了错误微信版本的缓存、只 Hook 了旧列表、Hook 了抽象方法、缓存误标记为 installed、子进程重复创建 `DexKitBridge`，或微信已经热更新导致旧 descriptor 和 ClassLoader 失效。先记录定位、安装和运行三个阶段的日志，再决定是否清理缓存或重新定位。

### 4. 无 R8 正常，R8 没反应

不要立刻改业务逻辑。确认两个包来自同一个提交、同一个版本和同一配置，然后对比 Xposed 入口、反射类名/方法名/字段名、脚本 API、JNI 导出符号、序列化类和 keep 规则。只添加必要的 keep 规则，避免全包 keep 造成 APK 过大并掩盖真正问题。

### 5. 出现 `ViewTreeLifecycleOwner not found`

这通常表示 ComposeView 被挂到了没有正确 LifecycleOwner 的窗口或 DecorView 上。不要在微信原生窗口里随意创建独立 Compose 根 View，也不要把 ComposeView 当成传统 ViewGroup 调用 `addView`。优先复用已有 Compose 宿主；如果必须注入传统 View，挂到经过确认的普通父容器，并处理 Activity 生命周期。

### 6. 出现 `Cannot add views to ComposeView`

`ComposeView` 只支持 Compose 内容，将传统 View 加到它上面会抛出 `UnsupportedOperationException`。应使用 `setContent` 在 Compose 内部绘制，或找到合适的普通 `ViewGroup` 作为注入父容器，或复用页面已有 View。

### 7. 页面卡顿、一直加载或消息重复

常见原因包括主线程查询数据库、每次滚动都重新定位、监听旧记录更新、没有去重、重复安装 Hook、透明层挡住触摸、无限重试或每条消息都启动新线程。用日志确认调用频率和线程，再做缓存、限频、批处理和生命周期释放；不要只提高超时或增加 `sleep`。

### 8. 构建失败或下载不到 APK

先确认工作流是否跑在正确分支，使用 `gh run list --repo ljh520134/Hchat-alt-entry --limit 5` 查看运行记录。无 R8 测试包从 Artifact 下载，正式 R8 包从 Release 下载。Artifact 名称通常是 `Hchat-alt-entry-test`，正式单频道工作流的 Release APK 名称是 `Hchat-alt-entry-release-signed.apk`。没有成功运行编号、权限或 Secrets 时，脚本不会凭空产生 APK。

### 9. Git 不小心包含敏感信息

立即停止推送，检查 `git status --short`、`git diff --cached --stat` 和 `git diff --cached --name-only`。如果已经推到远端，删除文件并不等于凭据失效。立即撤销或轮换密码、Token、SSH key 和证书，并通知仓库维护者；历史清理应由熟悉 Git 的人执行，不能随意重写公共分支。

## 十五、可直接复制的提示词

### 1. 调查日志问题

```text
先不要修改文件和构建。读取 AGENTS.md、CONTRIBUTING.md、相关功能文档和 git status。
根据下面日志定位 Hchat 的第一处调用，并说明它属于注册、DexKit、Hook、回调、解析、分发还是 UI 层：
<粘贴日志>
请列出需要确认的微信内部类/方法/字段，使用 DexClub 对指定 APK 做证据调查。
输出调用链、候选证据、风险和验证计划；没有证据不要猜名称。
```

### 2. 修复功能 Bug

```text
按调查结果修复“<功能>”。
环境：微信 <版本/versionCode>，Android <版本/SDK>，ABI <ABI>，主体/分身 <哪一个>，R8/无 R8。
复现：<步骤>
实际：<结果>
期望：<结果>
约束：只改直接相关文件，保留已有修改；不使用全局 Hook，不在主线程做重查询，不新增无限重试。
先实现最小修复，补充必要日志和兼容分支。完成后运行 git diff --check 和相关测试，不要自动提交、推送或构建。
```

### 3. 新增功能

```text
为 Hchat 增加“<功能>”。先只读调查现有设置分组、公共 API、事件通道、生命周期和相近功能。
请先说明入口、默认值、关闭时的资源释放、进程/线程、版本差异、DexKit 依据、R8 风险、失败回退和测试矩阵。
确认方案后再修改。复用现有 UI、配置迁移、日志和调度器，不另造一套反射或重试框架。
同步更新必要的 API/功能文档，最后报告未验证版本。
```

### 4. 排查 R8

```text
无 R8 构建可以工作，R8 构建在同一微信版本上没有回调。
先不要改业务逻辑。检查两个最终 APK 是否来自同一提交，比较 DEX、反射类名/方法名/字段名、脚本公共接口、JNI 符号、序列化类和 proguard-rules.pro。
定位失败阶段后，只添加最小必要 keep 规则，并说明每条规则保护的证据。
保留原始 Throwable 的限频日志；完成后同时验证无 R8 和 R8。
```

### 5. Review 提示词

```text
请以代码审查方式检查当前修改，优先列出真实问题而不是总结。
重点检查：崩溃、竞态、主线程阻塞、重复回调、消息方向、数据库误判、Hook 重复安装、DexKit 缓存、R8 反射失效、View 复用、Compose 宿主、资源泄漏、触摸被遮挡、配置迁移和多版本兼容。
每个问题给出文件和行号、影响、复现条件和修复建议；没有问题时说明测试缺口。
```

## 十六、最常用命令速查

```sh
# 项目状态
git status --short --branch
git diff --check

# 启动 Codex
codex -C "$PWD" --sandbox workspace-write --ask-for-approval on-request

# 启动 DexClub
cd "$HOME/dexclub-mcp-android-arm64/bin"
DEXCLUB_MCP_PORT=8787 ./mcp

# 配置 MCP
codex mcp add dexclub --url http://127.0.0.1:8787/mcp
codex mcp list

# 编译检查
sh ./gradlew :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac

# 无 R8 Actions
gh workflow run android-test.yml --repo ljh520134/Hchat-alt-entry --ref alt-entry

# R8 单频道 Actions
gh workflow run android-single.yml --repo ljh520134/Hchat-alt-entry --ref alt-entry

# 下载发布包
sh scripts/download_release_apk.sh alt-entry
```

完成一个 PR 后，最好把“提交号、构建类型、微信版本、测试结果、未验证项和已知风险”一起写进 PR。这样下一个维护者可以复现和继续工作，而不是从一句“现在没问题了”重新猜起。
