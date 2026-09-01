# Hchat 社区贡献指南

本仓库是公开的 `alt-entry` 频道源码，代码涉及微信混淆、Xposed Hook、DexKit、脚本、数据库和 R8。开始前阅读：

- [AI Coding 教程](docs/AI_CODING_TUTORIAL.md)：Termux、Codex、DexClub、构建和 PR 操作；
- [AGENTS.md](AGENTS.md)：Codex 自动读取的项目硬规则；
- [功能框架](docs/FEATURE_FRAMEWORK.md)、[微信 API](docs/WECHAT_APIS.md)、[脚本 API](docs/SCRIPT_PLUGIN_API.md)：具体实现和接口。

## 1. 仓库和分支

公开仓库是 `ljh520134/Hchat-alt-entry`，默认开发分支是 `alt-entry`。建议 Fork 后配置：

```sh
git clone --branch alt-entry --single-branch \
  https://github.com/你的用户名/Hchat-alt-entry.git
cd Hchat-alt-entry
git remote rename origin upstream
git remote add origin \
  https://github.com/你的用户名/Hchat-alt-entry.git
```

`upstream` 指公开仓库，`origin` 指自己的 Fork。开始新任务前：

```sh
git status --short
git fetch upstream alt-entry
git switch alt-entry
git pull --ff-only upstream alt-entry
git switch -c fix/功能名称
```

有未提交改动时先保存或提交，不能使用 `git reset --hard`、`git checkout -- .` 覆盖已有工作。

## 2. 问题描述和 Codex

问题至少包含：微信版本和 versionCode、Android/ABI、模块版本、R8 或无 R8、主体或分身、复现步骤、实际结果、期望结果和完整日志。

```text
仓库：ljh520134/Hchat-alt-entry
分支：fix/xxx
环境：微信 <版本/versionCode>，Android <版本/SDK>，<ABI>，主体/分身，R8/无 R8
复现：1. ... 2. ... 3. ...
实际：...
期望：...
日志：...
```

让 Codex 先调查，再修改：

```text
先不要修改文件和构建。读取 AGENTS.md、相关 docs 和 git status。
根据复现步骤定位真实调用链；涉及微信内部类、方法、字段、数据库或 Intent 时，先使用 DexClub MCP 逆向确认，不要猜混淆名称。
输出拟修改文件、版本兼容风险和验证方案，确认后再动手。
```

修改后让 Codex 执行 `git diff --check`、相关测试，并说明未验证版本。详细提示词见教程。

## 3. 开发规则

- 只 Hook DexClub 或现有可靠证据确认的具体可执行方法，不要全局 Hook 抽象入口。
- 多版本任务尽量检查 `8.0.49`、`8.0.58`、`8.0.66`、`8.0.68`、`8.0.72`、`8.0.74`、`8.0.76`、`8.0.77`；缺少 APK 时明确写“未验证”。
- 微信 APK 路径由开发者自己提供，必须向 DexClub 传绝对路径；仓库文档不指定个人设备目录。
- DexKit 缓存至少区分微信版本、versionCode、clientVersion、热更新、APK 时间戳和 ClassLoader；共享 Bridge 必须串行使用。
- 定位失败不能标记为已安装；不要在功能内新增裸线程、`Thread.sleep` 或重复重试框架。
- 反射统一走 `h.Hchat.utils.KavaReflector`，模块错误日志统一走 `HLog.e(...)`。
- 同一业务事件选择一个权威来源；数据库兜底只能消费真正新增的消息。
- RecyclerView 每次绑定清理旧状态；ComposeView 不能使用传统 `addView`。
- 数据库查询、DexKit 定位和网络请求不要阻塞主线程；批量操作要限速、可取消。
- 无 R8 正常而 R8 失效时，先比较同一提交的两个最终 APK、DEX、反射名称、脚本 API、JNI 和 keep 规则。
- native 库必须同时覆盖 `arm64-v8a` 和 `armeabi-v7a`。
